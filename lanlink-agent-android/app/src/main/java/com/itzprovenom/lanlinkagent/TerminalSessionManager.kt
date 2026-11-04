package com.itzprovenom.lanlinkagent

import android.content.Context
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ChatGPT-operated Alpine sessions. PRoot's fake root identity applies only
 * inside the Alpine guest; the Android process retains the LANLink app UID.
 *
 * The current shell transport uses interactive pipes, not a PTY. Shell input,
 * stdout and stderr work, but terminal ioctl/job-control behavior is not
 * advertised as fully supported yet.
 */
internal object TerminalSessionManager {
    private const val MAX_SESSIONS = 3
    private const val MAX_BUFFER_CHARS = 65_536
    private const val MAX_READ_CHARS = 12_000
    private const val MAX_INPUT_CHARS = 16_384
    private const val MAX_COMMAND_TIMEOUT_MS = 10 * 60 * 1000L
    private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val sessionsLock = Any()

    @JvmStatic
    fun execute(context: Context, payload: JSONObject): JSONObject {
        cleanupExpired()
        return when (payload.optString("operation", "")) {
            "environment", "status" -> LinuxRuntimeManager.status(context)
            "bootstrap" -> LinuxRuntimeManager.bootstrap(context)
            "start" -> start(context)
            "exec" -> exec(payload)
            "write" -> write(payload)
            "read" -> read(payload)
            "cancel" -> cancel(payload)
            "close" -> close(payload)
            else -> throw IllegalArgumentException("Unsupported terminal operation.")
        }
    }

    fun shutdown() {
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.stop() }
    }

    private fun start(context: Context): JSONObject {
        if (!LinuxRuntimeManager.isReady(context)) {
            throw IllegalStateException("Alpine is not initialized. Call lan_terminal operation=bootstrap first.")
        }
        synchronized(sessionsLock) {
            cleanupExpired()
            if (sessions.size >= MAX_SESSIONS) {
                throw IllegalStateException("The TV already has the maximum of " + MAX_SESSIONS + " terminal sessions. Close an unused session first.")
            }
            val id = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "lanlink-linux/runtime/sessions/" + id)
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IllegalStateException("Could not create the private terminal session directory.")
            }
            val builder = ProcessBuilder(LinuxRuntimeManager.shellCommand(context))
            builder.directory(LinuxRuntimeManager.workspaceDirectory(context))
            builder.redirectErrorStream(false)
            val environment = builder.environment()
            environment.clear()
            environment.putAll(LinuxRuntimeManager.shellEnvironment(context))
            val process = try {
                builder.start()
            } catch (error: Exception) {
                deleteRecursively(directory)
                throw IllegalStateException("Could not start the Alpine shell through PRoot: " + (error.message ?: "process launch failed"))
            }
            val session = TerminalSession(id, process, directory)
            sessions[id] = session
            session.startOutputReaders()
            return JSONObject()
                .put("session_id", id)
                .put("state", "running")
                .put("shell", "/bin/sh")
                .put("distribution", "Alpine Linux")
                .put("alpine_version", "3.24.3")
                .put("architecture", LinuxRuntimeManager.status(context).optString("alpine_arch"))
                .put("platform", "Android-hosted PRoot userspace")
                .put("privilege", "Android app UID; simulated root only inside Alpine")
                .put("working_directory", "/workspace")
                .put("persistent_workspace", true)
                .put("max_sessions", MAX_SESSIONS)
                .put("command_timeout_max_ms", MAX_COMMAND_TIMEOUT_MS)
                .put("idle_timeout_seconds", IDLE_TIMEOUT_MS / 1000)
                .put("transport", "interactive pipes; no PTY")
        }
    }

    private fun exec(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        val command = payload.optString("command", "")
        if (command.isBlank() || command.length > MAX_INPUT_CHARS || command.indexOf('\u0000') >= 0) {
            throw IllegalArgumentException("Terminal command must contain 1 to 16,384 characters and no NUL bytes.")
        }
        val timeoutMs = payload.optLong("timeout_ms", 30_000L).coerceIn(500L, MAX_COMMAND_TIMEOUT_MS)
        return synchronized(session.commandLock) {
            if (!session.isAlive()) throw IllegalStateException("Terminal session has exited. Start a new session.")
            val marker = "__LANLINK_DONE_" + UUID.randomUUID().toString().replace("-", "") + "__"
            val startOut = session.stdout.cursor()
            val startErr = session.stderr.cursor()
            synchronized(session.inputLock) {
                session.writer.write(command)
                session.writer.newLine()
                session.writer.write("printf '\\n" + marker + ":%s\\n' \"\$?\"")
                session.writer.newLine()
                session.writer.flush()
                session.lastActivity = System.currentTimeMillis()
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var exitCode: Int? = null
            var markerSeen = false
            var outEnd = startOut
            while (System.currentTimeMillis() < deadline) {
                val snap = session.stdout.snapshot()
                val startIndex = (startOut - snap.start).coerceAtLeast(0L).toInt().coerceAtMost(snap.text.length)
                val markerIndex = snap.text.indexOf(marker, startIndex)
                if (markerIndex >= 0) {
                    val statusStart = markerIndex + marker.length + 1
                    val statusEnd = snap.text.indexOf('\n', statusStart).let { if (it < 0) snap.text.length else it }
                    exitCode = snap.text.substring(statusStart, statusEnd).trim().toIntOrNull()
                    outEnd = snap.start + statusEnd
                    if (outEnd < snap.end && snap.text.getOrNull(statusEnd) == '\n') outEnd++
                    markerSeen = true
                    break
                }
                if (!session.isAlive()) break
                try {
                    Thread.sleep(40L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    sessions.remove(session.id, session)
                    session.stop()
                    return@synchronized JSONObject()
                        .put("session_id", session.id)
                        .put("cancelled", true)
                        .put("completed", false)
                        .put("process_running", false)
                        .put("session_discarded", true)
                }
            }
            val timedOut = !markerSeen && session.isAlive() && System.currentTimeMillis() >= deadline
            if (timedOut) {
                // Kill the shell/PRoot process. PRoot --kill-on-exit tears down
                // traced guest processes; merely stopping the wait is unsafe.
                sessions.remove(session.id, session)
                session.stop()
            }
            if (markerSeen) {
                try { Thread.sleep(75L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            val out = session.stdout.read(startOut, MAX_READ_CHARS, outEnd)
            val err = session.stderr.read(startErr, MAX_READ_CHARS)
            JSONObject()
                .put("session_id", session.id)
                .put("stdout", out.text)
                .put("stderr", err.text)
                .put("output", out.text)
                .put("exit_code", exitCode ?: JSONObject.NULL)
                .put("completed", markerSeen)
                .put("timed_out", timedOut)
                .put("cancelled", false)
                .put("process_running", session.isAlive())
                .put("session_discarded", timedOut)
                .put("stdout_truncated", out.truncated)
                .put("stderr_truncated", err.truncated)
                .put("next_cursor", out.nextCursor)
                .put("stderr_cursor", err.nextCursor)
        }
    }

    private fun write(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        val data = payload.optString("data", "")
        if (data.length > MAX_INPUT_CHARS || data.indexOf('\u0000') >= 0) {
            throw IllegalArgumentException("Terminal input must be at most 16,384 characters and contain no NUL bytes.")
        }
        synchronized(session.inputLock) {
            if (!session.isAlive()) throw IllegalStateException("Terminal session has exited.")
            session.writer.write(data)
            if (payload.optBoolean("newline", false)) session.writer.newLine()
            session.writer.flush()
            session.lastActivity = System.currentTimeMillis()
        }
        return JSONObject()
            .put("session_id", session.id)
            .put("written_chars", data.length)
            .put("process_running", session.isAlive())
    }

    private fun read(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        val out = session.stdout.read(payload.optLong("cursor", 0L).coerceAtLeast(0L), MAX_READ_CHARS)
        val err = session.stderr.read(payload.optLong("stderr_cursor", 0L).coerceAtLeast(0L), MAX_READ_CHARS)
        session.lastActivity = System.currentTimeMillis()
        return JSONObject()
            .put("session_id", session.id)
            .put("stdout", out.text)
            .put("stderr", err.text)
            .put("output", out.text)
            .put("cursor", out.nextCursor)
            .put("stderr_cursor", err.nextCursor)
            .put("stdout_truncated", out.truncated)
            .put("stderr_truncated", err.truncated)
            .put("process_running", session.isAlive())
    }

    private fun cancel(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        sessions.remove(session.id, session)
        session.stop()
        return JSONObject()
            .put("session_id", session.id)
            .put("cancelled", true)
            .put("process_running", false)
            .put("session_discarded", true)
    }

    private fun close(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        sessions.remove(session.id, session)
        session.stop()
        return JSONObject().put("session_id", session.id).put("closed", true)
    }

    private fun requireSession(payload: JSONObject): TerminalSession {
        val id = payload.optString("session_id", "")
        if (!id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))) {
            throw IllegalArgumentException("A valid terminal session_id is required.")
        }
        return sessions[id] ?: throw IllegalStateException("Terminal session not found or expired. Start a new session.")
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        for ((id, session) in sessions.entries) {
            if (!session.isAlive() || now - session.lastActivity > IDLE_TIMEOUT_MS) {
                if (sessions.remove(id, session)) session.stop()
            }
        }
    }

    private class TerminalSession(
        val id: String,
        val process: Process,
        private val directory: File
    ) {
        val inputLock = Any()
        val commandLock = Any()
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        val stdout = OutputBuffer()
        val stderr = OutputBuffer()
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        @Volatile private var stopped = false

        fun startOutputReaders() {
            startReader(process.inputStream, stdout, "stdout")
            startReader(process.errorStream, stderr, "stderr")
        }

        private fun startReader(input: java.io.InputStream, output: OutputBuffer, label: String) {
            Thread({
                try {
                    InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                        val chars = CharArray(4096)
                        while (true) {
                            val count = reader.read(chars)
                            if (count < 0) break
                            output.append(String(chars, 0, count))
                        }
                    }
                } catch (_: Exception) {
                    // Stream closure is expected when the shell exits or is cancelled.
                }
            }, "lanlink-terminal-" + label + "-" + id.take(8)).apply {
                isDaemon = true
                start()
            }
        }

        fun isAlive(): Boolean = !stopped && process.isAlive

        fun stop() {
            if (stopped) return
            stopped = true
            try { writer.close() } catch (_: Exception) {}
            try {
                if (process.isAlive) process.destroy()
                if (!process.waitFor(750L, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(750L, TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {
                try { process.destroyForcibly() } catch (_: Exception) {}
            }
            // Never delete the Alpine rootfs or persistent /workspace here.
            deleteRecursively(directory)
        }
    }

    private data class BufferSnapshot(val text: String, val start: Long, val end: Long)
    private data class BufferRead(val text: String, val nextCursor: Long, val truncated: Boolean)

    private class OutputBuffer {
        private val lock = Any()
        private val buffer = StringBuilder()
        private var absoluteStart = 0L
        private var absoluteEnd = 0L

        fun cursor(): Long = synchronized(lock) { absoluteEnd }

        fun append(value: String) {
            synchronized(lock) {
                buffer.append(value)
                absoluteEnd += value.length
                if (buffer.length > MAX_BUFFER_CHARS) {
                    val remove = buffer.length - MAX_BUFFER_CHARS
                    buffer.delete(0, remove)
                    absoluteStart += remove
                }
            }
        }

        fun snapshot(): BufferSnapshot = synchronized(lock) {
            BufferSnapshot(buffer.toString(), absoluteStart, absoluteEnd)
        }

        fun read(cursor: Long, maximum: Int, endLimit: Long = Long.MAX_VALUE): BufferRead =
            synchronized(lock) {
                val truncated = cursor < absoluteStart
                val actual = cursor.coerceIn(absoluteStart, absoluteEnd)
                val startIndex = (actual - absoluteStart).toInt().coerceIn(0, buffer.length)
                val boundedEnd = endLimit.coerceAtMost(absoluteEnd)
                val endIndex = (boundedEnd - absoluteStart).toInt().coerceIn(startIndex, buffer.length)
                val readEnd = minOf(endIndex, startIndex + maximum)
                BufferRead(
                    text = buffer.substring(startIndex, readEnd),
                    nextCursor = absoluteStart + readEnd,
                    truncated = truncated || readEnd < endIndex
                )
            }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        try { file.delete() } catch (_: Exception) {}
    }
}

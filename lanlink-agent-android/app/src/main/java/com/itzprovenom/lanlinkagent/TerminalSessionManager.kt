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

/**
 * Persistent, non-root Android shell sessions for the authenticated LANLink
 * relay. A session is an interactive /system/bin/sh process running as this
 * application's UID, with its working directory inside private app storage.
 *
 * It is intentionally not a root shell and does not mount or emulate a Linux
 * distribution. Only utilities already present on the Android TV are available.
 */
internal object TerminalSessionManager {
    private const val MAX_SESSIONS = 3
    private const val MAX_BUFFER_CHARS = 65_536
    private const val MAX_READ_CHARS = 12_000
    private const val MAX_INPUT_CHARS = 16_384
    private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val sessionsLock = Any()

    @JvmStatic
    fun execute(context: Context, payload: JSONObject): JSONObject {
        cleanupExpired()
        return when (payload.optString("operation", "")) {
            "start" -> start(context)
            "exec" -> exec(payload)
            "write" -> write(payload)
            "read" -> read(payload)
            "close" -> close(payload)
            else -> throw IllegalArgumentException("Unsupported terminal operation.")
        }
    }

    private fun start(context: Context): JSONObject {
        synchronized(sessionsLock) {
            if (sessions.size >= MAX_SESSIONS) {
                throw IllegalStateException("The TV already has the maximum of $MAX_SESSIONS terminal sessions. Close an unused session first.")
            }
            val id = UUID.randomUUID().toString()
            val root = File(context.filesDir, "lanlink-terminal")
            val directory = File(root, id)
            val tempDirectory = File(directory, "tmp")
            if (!tempDirectory.mkdirs() && !tempDirectory.isDirectory) {
                throw IllegalStateException("Could not create the private terminal working directory.")
            }

            val builder = ProcessBuilder("/system/bin/sh", "-i")
            builder.directory(directory)
            builder.redirectErrorStream(true)
            val environment = builder.environment()
            environment.clear()
            environment["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
            environment["HOME"] = directory.absolutePath
            environment["TMPDIR"] = tempDirectory.absolutePath
            environment["LANG"] = "C.UTF-8"
            environment["TERM"] = "xterm-256color"

            val process = try {
                builder.start()
            } catch (error: Exception) {
                deleteRecursively(directory)
                throw IllegalStateException("Could not start Android's /system/bin/sh: ${error.message ?: "process launch failed"}")
            }

            val session = TerminalSession(id, process, directory)
            sessions[id] = session
            session.startOutputReader()
            return JSONObject()
                .put("session_id", id)
                .put("state", "running")
                .put("shell", "/system/bin/sh")
                .put("platform", "Android")
                .put("privilege", "LANLink app UID; not root")
                .put("working_directory", directory.absolutePath)
                .put("max_sessions", MAX_SESSIONS)
                .put("idle_timeout_seconds", IDLE_TIMEOUT_MS / 1000)
        }
    }

    private fun exec(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        val command = payload.optString("command", "")
        if (command.isBlank() || command.length > MAX_INPUT_CHARS || command.indexOf('\u0000') >= 0) {
            throw IllegalArgumentException("Terminal command must contain 1 to 16,384 characters and no NUL bytes.")
        }
        val timeoutMs = payload.optLong("timeout_ms", 30_000L).coerceIn(500L, 60_000L)
        if (!session.isAlive()) throw IllegalStateException("Terminal session has exited. Start a new session.")

        val marker = "__LANLINK_DONE_${UUID.randomUUID().toString().replace("-", "")}__"
        val startCursor: Long
        synchronized(session.outputLock) {
            startCursor = session.bufferEnd
        }
        synchronized(session.inputLock) {
            session.writer.write(command)
            session.writer.newLine()
            session.writer.write("printf '\\n${marker}:%s\\n' \"\\$?\"")
            session.writer.newLine()
            session.writer.flush()
            session.lastActivity = System.currentTimeMillis()
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var outputSnapshot = ""
        var markerIndex = -1
        var exitCode: Int? = null
        var endCursor = startCursor
        while (System.currentTimeMillis() < deadline) {
            synchronized(session.outputLock) {
                outputSnapshot = session.buffer.toString()
                val startIndex = (startCursor - session.bufferStart).coerceAtLeast(0L).toInt().coerceAtMost(outputSnapshot.length)
                markerIndex = outputSnapshot.indexOf(marker, startIndex)
                if (markerIndex >= 0) {
                    val statusStart = markerIndex + marker.length + 1
                    val statusEnd = outputSnapshot.indexOf('\n', statusStart).let { if (it < 0) outputSnapshot.length else it }
                    exitCode = outputSnapshot.substring(statusStart, statusEnd).trim().toIntOrNull()
                    endCursor = session.bufferStart + statusEnd
                    if (endCursor < session.bufferEnd && outputSnapshot.getOrNull(statusEnd) == '\n') endCursor++
                }
            }
            if (markerIndex >= 0 || !session.isAlive()) break
            Thread.sleep(50L)
        }

        synchronized(session.outputLock) {
            outputSnapshot = session.buffer.toString()
            val startIndex = (startCursor - session.bufferStart).coerceAtLeast(0L).toInt().coerceAtMost(outputSnapshot.length)
            if (markerIndex < 0) endCursor = session.bufferEnd
            val endIndex = (endCursor - session.bufferStart).coerceAtLeast(startIndex.toLong()).toInt().coerceAtMost(outputSnapshot.length)
            val cleaned = outputSnapshot.substring(startIndex, endIndex)
            return JSONObject()
                .put("session_id", session.id)
                .put("output", cleaned.take(MAX_READ_CHARS))
                .put("output_truncated", cleaned.length > MAX_READ_CHARS || startCursor < session.bufferStart)
                .put("exit_code", exitCode ?: JSONObject.NULL)
                .put("completed", markerIndex >= 0)
                .put("timed_out", markerIndex < 0 && session.isAlive())
                .put("process_running", session.isAlive())
                .put("next_cursor", endCursor)
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
        return JSONObject().put("session_id", session.id).put("written_chars", data.length).put("process_running", session.isAlive())
    }

    private fun read(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        val requestedCursor = payload.optLong("cursor", 0L).coerceAtLeast(0L)
        synchronized(session.outputLock) {
            val actualCursor = requestedCursor.coerceAtLeast(session.bufferStart)
            val start = (actualCursor - session.bufferStart).toInt().coerceAtMost(session.buffer.length)
            val end = (start + MAX_READ_CHARS).coerceAtMost(session.buffer.length)
            val output = session.buffer.substring(start, end)
            val nextCursor = session.bufferStart + end
            session.lastActivity = System.currentTimeMillis()
            return JSONObject()
                .put("session_id", session.id)
                .put("output", output)
                .put("cursor", nextCursor)
                .put("truncated", requestedCursor < session.bufferStart)
                .put("process_running", session.isAlive())
        }
    }

    private fun close(payload: JSONObject): JSONObject {
        val session = requireSession(payload)
        sessions.remove(session.id)
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
        val outputLock = Object()
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        val buffer = StringBuilder()
        var bufferStart = 0L
        var bufferEnd = 0L
        @Volatile var lastActivity: Long = System.currentTimeMillis()

        fun startOutputReader() {
            Thread({
                try {
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                        val chars = CharArray(4096)
                        while (true) {
                            val count = reader.read(chars)
                            if (count < 0) break
                            synchronized(outputLock) {
                                buffer.append(chars, 0, count)
                                bufferEnd += count
                                if (buffer.length > MAX_BUFFER_CHARS) {
                                    val remove = buffer.length - MAX_BUFFER_CHARS
                                    buffer.delete(0, remove)
                                    bufferStart += remove
                                }
                                outputLock.notifyAll()
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    synchronized(outputLock) { outputLock.notifyAll() }
                }
            }, "lanlink-terminal-output-${id.take(8)}").apply {
                isDaemon = true
                start()
            }
        }

        fun isAlive(): Boolean = try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

        fun stop() {
            try { writer.close() } catch (_: Exception) {}
            if (isAlive()) process.destroy()
            deleteRecursively(directory)
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        try { file.delete() } catch (_: Exception) {}
    }
}

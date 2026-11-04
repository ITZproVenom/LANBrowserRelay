package com.itzprovenom.lanlinkagent

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.Os
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Owns the persistent, unprivileged Alpine userspace used by LANLink.
 *
 * PRoot runs as the Android app UID. Its -0 option only simulates UID 0 inside
 * the guest rootfs; it never grants Android/root privileges.
 */
internal object LinuxRuntimeManager {
    private const val ALPINE_VERSION = "3.24.3"
    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 512L * 1024L * 1024L
    private const val MAX_ENTRIES = 30_000
    private const val PROOT_NAME = "libproot.so"
    private const val STATE_FILE = "runtime.json"
    private val bootstrapLock = Any()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    private data class AlpineTarget(
        val androidAbi: String,
        val alpineArch: String
    )

    fun hasBundledProot(context: Context): Boolean {
        val binary = prootExecutable(context)
        return binary.isFile && binary.canExecute()
    }

    fun canBootstrap(context: Context): Boolean = detectTarget() != null && hasBundledProot(context)

    fun isReady(context: Context): Boolean {
        if (!hasBundledProot(context)) return false
        val root = rootfs(context)
        val marker = File(root.parentFile, STATE_FILE)
        val release = File(root, "etc/alpine-release")
        if (!marker.isFile || !release.isFile) return false
        return try {
            JSONObject(marker.readText()).optString("alpine_version") == ALPINE_VERSION &&
                release.readText().trim() == ALPINE_VERSION
        } catch (_: Exception) {
            false
        }
    }

    fun status(context: Context): JSONObject {
        val target = detectTarget()
        val root = rootfs(context)
        val release = File(root, "etc/alpine-release")
        val ready = isReady(context)
        return JSONObject()
            .put("state", when {
                target == null -> "unsupported_architecture"
                !hasBundledProot(context) -> "proot_missing"
                ready -> "ready"
                root.exists() -> "rootfs_unverified"
                else -> "not_installed"
            })
            .put("ready", ready)
            .put("proot_available", hasBundledProot(context))
            .put("proot_path", prootExecutable(context).absolutePath)
            .put("android_release", Build.VERSION.RELEASE ?: "unknown")
            .put("android_api", Build.VERSION.SDK_INT)
            .put("kernel", System.getProperty("os.version") ?: "unknown")
            .put("process_64_bit", Process.is64Bit())
            .put("supported_abis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("alpine_version", if (release.isFile) release.readText().trim() else JSONObject.NULL)
            .put("alpine_arch", target?.alpineArch ?: JSONObject.NULL)
            .put("android_abi", target?.androidAbi ?: JSONObject.NULL)
            .put("rootfs_path", root.absolutePath)
            .put("workspace_path", workspaceDirectory(context).absolutePath)
            .put("free_bytes", context.filesDir.usableSpace)
            .put("rootfs_bytes", directorySize(root))
            .put("package_manager", if (ready && File(root, "sbin/apk").isFile) "apk" else "unavailable")
            .put("privilege", "Android app UID; PRoot fake-root only inside Alpine")
    }

    fun bootstrap(context: Context): JSONObject = synchronized(bootstrapLock) {
        if (isReady(context)) return@synchronized status(context)
        val target = detectTarget()
            ?: throw IOException("Unsupported Android ABI. Alpine runtime currently supports armeabi-v7a and arm64-v8a; legacy armeabi is not assumed compatible.")
        if (context.filesDir.usableSpace < 100L * 1024L * 1024L) {
            throw IOException("At least 100 MB of free app storage is required to bootstrap Alpine safely.")
        }
        val proot = prootExecutable(context)
        if (!proot.isFile || !proot.canExecute()) {
            throw IOException("The Android PRoot executable is missing for this device ABI. Rebuild the TV agent with the NDK PRoot assets before bootstrapping Alpine.")
        }

        val base = File(context.filesDir, "lanlink-linux")
        if (!base.exists() && !base.mkdirs()) throw IOException("Could not create the private Linux runtime directory.")
        val archive = File(base, "alpine-minirootfs.tar.gz.part")
        val staging = File(base, "rootfs.installing")
        val finalRoot = rootfs(context)
        val previous = File(base, "rootfs.previous")

        val baseUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/" +
            target.alpineArch + "/alpine-minirootfs-" + ALPINE_VERSION + "-" + target.alpineArch + ".tar.gz"
        val checksumText = downloadText("$baseUrl.sha256")
        val expected = checksumText.trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw IOException("Alpine checksum sidecar did not contain a SHA-256 digest.")

        archive.delete()
        downloadFile(baseUrl, archive)
        val actual = sha256(archive)
        if (!actual.equals(expected, ignoreCase = true)) {
            archive.delete()
            throw IOException("Alpine rootfs SHA-256 verification failed.")
        }

        deleteRecursively(staging)
        if (!staging.mkdirs()) throw IOException("Could not create the staging rootfs directory.")
        try {
            extractTarSafely(archive, staging)
            configureRootfs(context, staging)
            val release = File(staging, "etc/alpine-release")
            if (!release.isFile || release.readText().trim() != ALPINE_VERSION) {
                throw IOException("Downloaded rootfs is not the expected Alpine release.")
            }
            if (!File(staging, "sbin/apk").isFile) {
                throw IOException("Alpine rootfs is missing /sbin/apk.")
            }
            val smoke = runSmokeTest(context, staging)
            if (!smoke.first) {
                throw IOException("PRoot could not execute the Alpine smoke test: " + smoke.second)
            }

            deleteRecursively(previous)
            if (finalRoot.exists() && !finalRoot.renameTo(previous)) {
                throw IOException("Could not move the previous Alpine rootfs aside safely.")
            }
            if (!staging.renameTo(finalRoot)) {
                if (previous.exists()) previous.renameTo(finalRoot)
                throw IOException("Could not atomically activate the verified Alpine rootfs.")
            }
            writeState(base, target)
            deleteRecursively(previous)
        } catch (error: Exception) {
            deleteRecursively(staging)
            throw error
        } finally {
            archive.delete()
        }
        status(context)
    }

    fun shellCommand(context: Context): List<String> {
        if (!isReady(context)) throw IOException("Alpine is not ready. Call lan_terminal operation=bootstrap first.")
        refreshResolver(context)
        val root = rootfs(context)
        val workspace = workspaceDirectory(context)
        return arrayListOf(
            prootExecutable(context).absolutePath,
            "--kill-on-exit",
            "-0",
            "-r", root.absolutePath,
            "-w", "/workspace",
            "-b", workspace.absolutePath + ":/workspace",
            "-b", "/dev/null:/dev/null",
            "-b", "/dev/zero:/dev/zero",
            "-b", "/dev/random:/dev/random",
            "-b", "/dev/urandom:/dev/urandom",
            "/bin/sh",
            "-i"
        )
    }

    fun shellEnvironment(context: Context): Map<String, String> {
        val runtimeTemp = File(context.filesDir, "lanlink-linux/runtime/proot-tmp")
        if (!runtimeTemp.exists()) runtimeTemp.mkdirs()
        return mapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "PROOT_TMP_DIR" to runtimeTemp.absolutePath
        )
    }

    fun workspaceDirectory(context: Context): File =
        File(context.filesDir, "lanlink-linux/workspaces/default").apply {
            if (!exists() && !mkdirs()) throw IOException("Could not create the persistent Linux workspace.")
        }

    private fun rootfs(context: Context): File = File(context.filesDir, "lanlink-linux/rootfs")

    private fun prootExecutable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir ?: "", PROOT_NAME)

    private fun detectTarget(): AlpineTarget? {
        val is64 = Build.VERSION.SDK_INT >= 23 && Process.is64Bit()
        val preferred = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            is64 && preferred == "arm64-v8a" -> AlpineTarget("arm64-v8a", "aarch64")
            !is64 && Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> AlpineTarget("armeabi-v7a", "armv7")
            else -> null
        }
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder().url(url).get().header("User-Agent", "LANLink-TV-Agent/0.5.0").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed with HTTP " + response.code + ".")
            val body = response.body ?: throw IOException("Download returned an empty response.")
            if (body.contentLength() > 16_384L) throw IOException("Checksum response exceeded its size limit.")
            return body.string()
        }
    }

    private fun downloadFile(url: String, destination: File) {
        val request = Request.Builder().url(url).get().header("User-Agent", "LANLink-TV-Agent/0.5.0").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Alpine download failed with HTTP " + response.code + ".")
            val body = response.body ?: throw IOException("Alpine download returned an empty response.")
            if (body.contentLength() > MAX_ARCHIVE_BYTES) throw IOException("Alpine archive exceeds the configured download limit.")
            body.byteStream().use { input ->
                FileOutputStream(destination).use { raw ->
                    BufferedOutputStream(raw).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_ARCHIVE_BYTES) throw IOException("Alpine archive exceeded the configured download limit.")
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                        raw.fd.sync()
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractTarSafely(archive: File, destination: File) {
        val root = destination.canonicalFile
        var expanded = 0L
        var entries = 0
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            val buffer = ByteArray(32 * 1024)
            while (entry != null) {
                entries += 1
                if (entries > MAX_ENTRIES) throw IOException("Alpine archive contains too many entries.")
                val target = safeArchivePath(root, entry.name)
                when {
                    entry.isDirectory -> {
                        if (!target.exists() && !target.mkdirs()) throw IOException("Could not create rootfs directory.")
                        applyMode(target, entry.mode)
                    }
                    entry.isSymbolicLink -> {
                        // Alpine rootfs archives legitimately contain absolute
                        // guest links such as /bin -> /usr/bin. Rewrite them as
                        // equivalent relative links so the Android host never
                        // follows a link outside the private extraction root.
                        val entryParts = archivePathParts(entry.name)
                        val targetParts = normalizeGuestLink(entryParts.dropLast(1), entry.linkName)
                        val relativeLink = relativeGuestPath(entryParts.dropLast(1), targetParts)
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create link parent.") }
                        if (target.exists()) throw IOException("Alpine archive contains a duplicate path.")
                        Os.symlink(relativeLink, target.absolutePath)
                        val canonical = target.canonicalFile
                        if (!canonical.path.startsWith(root.path + File.separator) && canonical.path != root.path) {
                            throw IOException("Alpine symbolic link escaped the rootfs.")
                        }
                    }
                    entry.isLink -> {
                        val linkTarget = safeArchivePath(root, entry.linkName.removePrefix("./"))
                        if (!linkTarget.exists()) throw IOException("Alpine archive contains a forward or missing hard link.")
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create hard-link parent.") }
                        Os.link(linkTarget.absolutePath, target.absolutePath)
                    }
                    entry.isFile -> {
                        if (entry.size < 0 || expanded + entry.size > MAX_EXPANDED_BYTES) {
                            throw IOException("Alpine archive exceeds the expanded-size limit.")
                        }
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create file parent.") }
                        FileOutputStream(target).use { output ->
                            var remaining = entry.size
                            while (remaining > 0) {
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw IOException("Alpine archive ended inside a file.")
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                            output.fd.sync()
                        }
                        expanded += entry.size
                        applyMode(target, entry.mode)
                    }
                    else -> throw IOException("Alpine archive contains an unsupported special file: " + entry.name)
                }
                entry = tar.nextTarEntry
            }
        }
    }

    private fun archivePathParts(raw: String): List<String> {
        val name = raw.replace('\\', '/').removePrefix("./").trimEnd('/')
        if (name.startsWith("/") || name.indexOf('\\u0000') >= 0) {
            throw IOException("Alpine archive contains an absolute or invalid entry path.")
        }
        val parts = ArrayList<String>()
        for (part in name.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> throw IOException("Alpine archive contains a path traversal entry.")
                else -> parts.add(part)
            }
        }
        return parts
    }

    private fun normalizeGuestLink(parent: List<String>, link: String): List<String> {
        if (link.isBlank() || link.indexOf('\\u0000') >= 0) {
            throw IOException("Alpine archive contains an invalid symbolic link.")
        }
        val result = if (link.startsWith("/")) ArrayList<String>() else ArrayList(parent)
        for (part in link.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (result.isEmpty()) throw IOException("Alpine symbolic link escapes the guest root.")
                    result.removeAt(result.lastIndex)
                }
                else -> result.add(part)
            }
        }
        return result
    }

    private fun relativeGuestPath(from: List<String>, to: List<String>): String {
        var common = 0
        while (common < from.size && common < to.size && from[common] == to[common]) common++
        val result = ArrayList<String>()
        repeat(from.size - common) { result.add("..") }
        result.addAll(to.drop(common))
        return if (result.isEmpty()) "." else result.joinToString("/")
    }

    private fun safeArchivePath(root: File, raw: String): File {
        val parts = archivePathParts(raw)
        val candidate = File(root, parts.joinToString("/")).canonicalFile
        val prefix = root.path + File.separator
        if (candidate.path != root.path && !candidate.path.startsWith(prefix)) {
            throw IOException("Alpine archive entry escaped the rootfs.")
        }
        return candidate
    }

    private fun applyMode(file: File, mode: Int) {
        val safeMode = mode and 0x1ff
        try {
            Os.chmod(file.absolutePath, safeMode)
        } catch (_: Exception) {
            file.setReadable(safeMode and 0x124 != 0, false)
            file.setWritable(safeMode and 0x092 != 0, false)
            file.setExecutable(safeMode and 0x049 != 0, false)
        }
    }

    private fun configureRootfs(context: Context, root: File) {
        listOf("root", "workspace", "tmp", "dev", "proc", "sys", "run").forEach { name ->
            val dir = File(root, name)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create /" + name + " in Alpine.")
        }
        val repositories = File(root, "etc/apk/repositories")
        repositories.parentFile?.mkdirs()
        repositories.delete()
        repositories.writeText(
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/community\n"
        )
        writeResolver(context, root)
    }

    private fun refreshResolver(context: Context) {
        writeResolver(context, rootfs(context))
    }

    private fun writeResolver(context: Context, root: File) {
        val nameservers = try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val properties = if (network != null) manager.getLinkProperties(network) else null
            properties?.dnsServers?.map { it.hostAddress?.substringBefore("%") }?.filter { !it.isNullOrBlank() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val servers = if (nameservers.isEmpty()) listOf("1.1.1.1", "8.8.8.8") else nameservers
        val file = File(root, "etc/resolv.conf")
        file.parentFile?.mkdirs()
        file.delete()
        file.writeText(servers.joinToString("\n") { "nameserver " + it } + "\n")
    }

    private fun runSmokeTest(context: Context, root: File): Pair<Boolean, String> {
        val command = arrayListOf(
            prootExecutable(context).absolutePath,
            "--kill-on-exit",
            "-0",
            "-r", root.absolutePath,
            "-w", "/root",
            "-b", "/dev/null:/dev/null",
            "-b", "/dev/zero:/dev/zero",
            "-b", "/dev/random:/dev/random",
            "-b", "/dev/urandom:/dev/urandom",
            "/bin/sh", "-c",
            "test -x /sbin/apk && test -f /etc/alpine-release && /sbin/apk --version"
        )
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> if (output.length < 4096) output.append(line).append('\n') }
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(15_000L)
            if (reader.isAlive) reader.interrupt()
            val waiter = Thread { try { process.waitFor() } catch (_: Exception) {} }
            waiter.isDaemon = true
            waiter.start()
            waiter.join(15_000L)
            if (waiter.isAlive) {
                process.destroy()
                return false to "PRoot smoke test timed out."
            }
            (process.exitValue() == 0) to output.toString().trim()
        } catch (error: Exception) {
            false to (error.message ?: "PRoot launch failed.")
        }
    }

    private fun writeState(base: File, target: AlpineTarget) {
        val state = JSONObject()
            .put("alpine_version", ALPINE_VERSION)
            .put("alpine_arch", target.alpineArch)
            .put("android_abi", target.androidAbi)
            .put("verified_at", System.currentTimeMillis())
        File(base, STATE_FILE).writeText(state.toString())
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}

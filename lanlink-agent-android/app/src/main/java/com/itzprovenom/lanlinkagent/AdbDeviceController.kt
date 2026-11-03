package com.itzprovenom.lanlinkagent

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB host-side control for devices that have explicitly enabled and authorized
 * ADB on the same private LAN. No target-side authorization is bypassed.
 */
internal object AdbDeviceController {
    private const val MAX_OUTPUT_CHARS = 12000

    @JvmStatic
    fun execute(context: Context, payload: JSONObject): JSONObject =
        runBlocking(Dispatchers.IO) {
            executeSuspend(context.applicationContext, payload)
        }

    private suspend fun executeSuspend(context: Context, payload: JSONObject): JSONObject {
        if (Build.VERSION.SDK_INT < 24) {
            throw IllegalStateException("ADB controller requires Android 7.0 (API 24) or newer on the LANLink bridge.")
        }

        val host = payload.optString("ip", "")
        if (!isPrivateIpv4(host)) throw IllegalArgumentException("ADB target must be a private IPv4 address.")
        val transport = payload.optString("transport", "tcp")
        if (transport != "tcp" && transport != "tls") {
            throw IllegalArgumentException("ADB transport must be tcp or tls.")
        }

        val keyProvider = SecureAdbKeyProvider(context)

        if (payload.optString("action", "") == "pair") {
            if (transport != "tls") throw IllegalArgumentException("Wireless ADB pairing requires TLS transport.")
            val pairingPort = payload.optInt("pairing_port", 0)
            val pairingCode = payload.optString("pairing_code", "")
            if (pairingPort !in 1..65535 || !pairingCode.matches(Regex("[0-9]{6}"))) {
                throw IllegalArgumentException("Supply the pairing port and current six-digit Wireless debugging code shown by the target.")
            }
            val pairResult = AdbClient.pairTls(
                host = host,
                port = pairingPort,
                pairingCode = pairingCode,
                keyProvider = keyProvider,
            )
            if (pairResult.isFailure) {
                throw IOException("ADB wireless pairing failed: ${pairResult.exceptionOrNull()?.message ?: "target rejected pairing"}")
            }
            return JSONObject()
                .put("paired", true)
                .put("transport", "tls")
                .put("target", host)
                .put("pairing_port", pairingPort)
                .put("message", "Wireless ADB pairing succeeded. Connect using the target's Wireless debugging connection port.")
        }

        val port = payload.optInt("port", if (transport == "tcp") 5555 else 0)
        if (port !in 1..65535) {
            throw IllegalArgumentException("Supply the current ADB connection port. Classic TCP defaults to 5555; Wireless debugging uses its displayed connection port.")
        }
        val action = payload.optString("action", "")
        val shellCommand = commandFor(action, payload)
            ?: throw IllegalArgumentException("Unsupported ADB action.")

        val client = if (transport == "tls") {
            AdbClient.connectTls(host = host, port = port, keyProvider = keyProvider)
        } else {
            AdbClient.connectTcp(host = host, port = port, keyProvider = keyProvider, handshakeTimeoutMs = 12000)
        }

        return client.use { connected ->
            val shell = connected.shell(shellCommand)
            val stdout = shell.stdoutText.take(MAX_OUTPUT_CHARS)
            val stderr = shell.stderrText.take(MAX_OUTPUT_CHARS)
            JSONObject()
                .put("target", host)
                .put("port", port)
                .put("transport", transport)
                .put("device_mode", connected.deviceMode.name)
                .put("action", action)
                .put("success", shell.isSuccess)
                .put("exit_code", shell.exitCode)
                .put("stdout", stdout)
                .put("stderr", stderr)
        }
    }

    private fun commandFor(action: String, payload: JSONObject): String? {
        val keyEvent = when (action) {
            "home" -> 3
            "back" -> 4
            "power" -> 26
            "volume_up" -> 24
            "volume_down" -> 25
            "mute" -> 164
            "play_pause" -> 85
            "next" -> 87
            "previous" -> 88
            "dpad_up" -> 19
            "dpad_down" -> 20
            "dpad_left" -> 21
            "dpad_right" -> 22
            "select" -> 23
            else -> null
        }
        if (keyEvent != null) return "input keyevent $keyEvent"

        return when (action) {
            "info" -> "printf 'manufacturer='; getprop ro.product.manufacturer; printf 'model='; getprop ro.product.model; printf 'android='; getprop ro.build.version.release; printf 'sdk='; getprop ro.build.version.sdk; printf 'fingerprint='; getprop ro.build.fingerprint"
            "apps" -> "cmd package list packages -3"
            "launch_app" -> {
                val packageName = payload.optString("package", "")
                if (!validPackage(packageName)) throw IllegalArgumentException("A valid Android package name is required.")
                "monkey -p $packageName 1"
            }
            "stop_app" -> {
                val packageName = payload.optString("package", "")
                if (!validPackage(packageName)) throw IllegalArgumentException("A valid Android package name is required.")
                "am force-stop $packageName"
            }
            "reboot" -> "reboot"
            else -> null
        }
    }

    private fun validPackage(value: String): Boolean =
        value.length <= 255 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))

    private fun isPrivateIpv4(value: String): Boolean {
        val p = value.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /**
     * ADB's long-lived RSA identity is encrypted at rest with SecureStore's
     * Android Keystore AES-GCM key and reused for both TCP AUTH and TLS pairing.
     */
    private class SecureAdbKeyProvider(context: Context) : AdbKeyProvider {
        private val delegate: AdbKeyProvider = AdbKeyProvider.from(loadOrCreateKeyPair(context), "LANLink-TV-Agent")

        override suspend fun getKeyPair(): KeyPair = delegate.getKeyPair()

        override suspend fun getAdbPublicKeyBytes(): ByteArray? = delegate.getAdbPublicKeyBytes()

        private fun loadOrCreateKeyPair(context: Context): KeyPair {
            val privateBytes = SecureStore.readAdbPrivateKey(context)
            val publicBytes = SecureStore.readAdbPublicKey(context)
            if (privateBytes.isNotEmpty() && publicBytes.isNotEmpty()) {
                try {
                    val factory = KeyFactory.getInstance("RSA")
                    val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privateBytes, Base64.NO_WRAP)))
                    val publicKey = factory.generatePublic(X509EncodedKeySpec(Base64.decode(publicBytes, Base64.NO_WRAP)))
                    return KeyPair(publicKey, privateKey)
                } catch (_: Exception) {
                    // A corrupt/incompatible key is replaced with a new identity.
                }
            }
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val generated = generator.generateKeyPair()
            SecureStore.writeAdbKeyPair(context, generated.private.encoded, generated.public.encoded)
            return generated
        }
    }
}

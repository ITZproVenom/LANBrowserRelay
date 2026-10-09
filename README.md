# 📺 LAN Browser Relay

**Browse on your phone. Let your Android TV fetch and stream over Ethernet.** 🌐⚡

LAN Browser Relay is a lightweight Android TV app with a phone-friendly web UI. It routes proxied pages and downloads through the TV, streams downloads to the requesting browser, and keeps **no permanent copy of downloaded file contents on the TV**. No companion app is required. 🙌

## ✨ Highlights

- 🔌 Prefers an active wired Ethernet IPv4 address; refreshes when interfaces or DHCP addresses change.
- 🧭 Serves the trusted UI/control plane on port `8080` and proxied page content on a separate origin/port (`8081`).
- 🛡️ Gives each browser session an isolated, in-memory capability and CSRF token; transfer status and cancellation are scoped to the owning session.
- 📥 Streams downloads through bounded buffers, with a hard **100,000,000-byte** limit and timeouts.
- 🚫 No login, pairing, or companion app. Other LAN users can open their own isolated session; see [Security & privacy](#-security--privacy).

## 🚀 Build and run

### Requirements

- JDK 17 ☕
- Android SDK: `platforms;android-34` and `build-tools;34.0.0` 📦
- Gradle 8.2

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleDebug
gradle test
gradle lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` 🧪

### 📲 Use it

1. Connect the Android TV to your router (Ethernet recommended). 🔌
2. Install and open the app; it displays the TV's LAN URL, for example `http://192.168.1.50:8080`.
3. On a phone on the same LAN, open that URL in Safari or Chrome. 📱
4. Enter a URL or search. Use **Download** to relay the current URL; the transfer panel shows progress and offers cancellation. ⬇️

## 📥 Download guarantees

- The absolute maximum is **100,000,000 bytes (decimal 100 MB) per file**.
- The limit is checked against bytes actually streamed, including chunked or unknown-length responses.
- Early EOF, upstream errors, client disconnects, timeouts, and over-limit payloads are reported as failed/cancelled—not successful truncated files.
- Downloads use bounded streaming buffers and are not written to TV storage. 🧹
- Global and per-browser concurrency limits prevent a few sessions from occupying every transfer slot.

## 🛡️ Security & privacy

### Browser isolation and control plane

- The UI and proxy listener bind to the selected LAN IPv4 on separate TCP ports. Proxied pages never share the UI/control origin.
- The viewer iframe is sandboxed without `allow-same-origin`; the proxied origin also uses a restrictive CSP. Its small `postMessage` bridge accepts only validated normal-navigation messages—never API, cancellation, or download commands. 🔒
- A random per-browser session cookie is `HttpOnly` and `SameSite=Strict`. Sensitive API requests require a matching synchronizer CSRF token, expected `Host`/`Origin`, and appropriate methods. Sessions expire and rotate on service restart; transfer lists and cancellation are owner-scoped.
- CORS is not used as authorization. The control API is not exposed on the content listener. 🚧

### Upstream requests

- Only `http` and `https` URLs are accepted; URL credentials are rejected.
- Loopback, private, link-local, multicast, CGNAT, documentation, and other reserved destinations are blocked before connection and in the OkHttp DNS hook to reduce SSRF and DNS-rebinding risks.
- Redirects are followed manually and each target is revalidated; redirect chains are bounded.
- URL-derived filenames are sanitized, upstream MIME types are normalized, and HTML is escaped in the UI.

### ⚠️ LAN limitations

The relay uses plain HTTP on the local network; it does not provide TLS. A hostile peer able to monitor the LAN traffic could potentially observe or steal an active browser session. Use a trusted/private network. There is deliberately no login or pairing: a reachable LAN user can open the UI and create their **own** session, but cannot use that session to inspect or cancel another session's transfers. The service also permits public-Internet fetches through the TV subject to the URL policy, byte cap, deadlines, and concurrency limits.

The HTML proxy is not a full browser engine. Some websites may not work because of CSP, script, embedded-frame, or cross-origin restrictions. The TV preview and browser UI are not a substitute for device-level testing. 🧪

## 🔐 Persistent release signing

Production release builds require one persistent JKS key. Create/retain the key and configure these **GitHub repository Actions secrets**:

- `LBR_RELEASE_KEYSTORE_BASE64` — base64-encoded JKS (`base64 < release.jks | tr -d '\n'`)
- `LBR_RELEASE_KEYSTORE_PASSWORD` — keystore password
- `LBR_RELEASE_KEY_ALIAS` — signing-key alias
- `LBR_RELEASE_KEY_PASSWORD` — key password

GitHub path: **Settings → Secrets and variables → Actions → New repository secret**. Keep secure backups of the JKS and passwords; never commit them. The release workflow validates the key/alias before building and fails if any secret is missing or invalid. 🔑

Local release builds use the same key through `LBR_KEYSTORE_PATH`, `LBR_KEYSTORE_PASSWORD`, `LBR_KEY_ALIAS`, and `LBR_KEY_PASSWORD`:

```bash
gradle -PreleaseVersion=1.2.3 assembleRelease
```

Release tags must be stable `vMAJOR.MINOR.PATCH` (no leading zeroes or prerelease suffix). Android `versionCode` is derived as `MAJOR × 1,000,000 + MINOR × 1,000 + PATCH`; `MINOR` and `PATCH` must be at most 999. This encoding is strictly increasing for supported SemVer versions. 📈

> **Upgrade note:** Earlier CI generated a fresh release key per run. If an installed APK was signed by one of those unretained keys, Android will reject an in-place upgrade to the persistent-key build; back up user data and uninstall that old APK before reinstalling. Future releases must keep using the same production JKS.

## 🧪 Tests and CI

`app/src/test/` includes coverage for:

- DNS/SSRF URL policy and filename sanitization.
- The 100 MB byte budget, unknown-length streaming, truncation, overflow, disconnects, and errors.
- Transfer ownership, cancellation races, concurrency, and byte accounting.
- Per-session CSRF capability, origin/host checks, expiry/rate limits, and deterministic LAN IPv4 selection.
- HTML rewrite/link-bridge behavior. The browser-security integration test exercises hostile HTML against the actual UI assets in a real browser.

`.github/workflows/android.yml` runs browser security checks, unit tests, lint, debug APK integrity/signature verification, and version metadata checks on PRs and pushes. `.github/workflows/release.yml` validates the tag, requires the persistent signing secrets, builds the release APK, verifies signature/integrity/version metadata, and only then publishes a GitHub Release. ✅

## 📦 Install on Android TV

```bash
adb connect <tv-ip>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 📄 License

MIT

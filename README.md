# LAN Browser Relay

Android TV app that hosts a small web server on your LAN. Open its address on
your phone (Safari/Chrome), browse the web and download files **through the TV's
Ethernet connection**. Files are streamed and are **never stored permanently on
the TV**, and there is **no login, pairing, or companion app**.

## Setup

Requirements to build (CI does this automatically):

- JDK 17
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- Gradle 8.2

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleDebug
# Debug APK: app/build/outputs/apk/debug/app-debug.apk
gradle test           # unit tests
gradle lintDebug      # Android lint
```

To build a signed release APK, supply a JKS keystore (optional):

```bash
LBR_KEYSTORE_PATH=release.jks \
LBR_KEYSTORE_PASSWORD=... \
LBR_KEY_ALIAS=... \
LBR_KEY_PASSWORD=... \
gradle assembleRelease
# Release APK: app/build/outputs/apk/release/app-release.apk
```

Without `LBR_KEYSTORE_*` the release APK is signed with the Android debug key so
it is installable but clearly not production-signed.

## Usage

1. Connect the Android TV to your router (Ethernet preferred).
2. Install and open the app. The LAN service starts automatically and shows the
   server URL (e.g. `http://192.168.1.50:8080`).
3. On a phone on the same network, open that URL in Safari or Chrome.
4. Use the address bar to search Google or enter a URL. Downloadable links open
   in the transfer manager on the phone, with live progress, speed, and a Cancel
   button. The TV screen shows the address, logs, transfer speed, and a read-only
   preview.

### Download size limit

- The absolute maximum is **100,000,000 bytes (decimal 100 MB) per file**.
- The limit is enforced against **bytes actually streamed**, not just the
  declared `Content-Length`. Downloads larger than the cap are aborted and
  reported as failed — never silently truncated into a "success".
- Payloads are streamed through bounded buffers; nothing is buffered whole and
  nothing is saved to the TV disk.

### Security

- Only `http`/`https` destinations are accepted.
- Private, loopback, link-local, multicast, CGNAT, and reserved IP ranges are
  blocked **before** and **during** connection (OkHttp DNS hook) to prevent SSRF
  and DNS rebinding.
- Every redirect is re-validated before being followed; redirect loops are
  cut off after 5 hops.
- The HTML proxy is not a full browser engine. Sites that require advanced
  JavaScript, service workers, strict CSP, or cross-origin APIs may not render
  correctly. Use the download action for direct file URLs.

## Tests & robustness

`app/src/test/` covers:

- DNS / SSRF policy (`UrlValidator`), including IPv4, IPv6, and IPv4-mapped
  addresses.
- Download byte-budget policy (`DownloadPolicy`).
- The streaming relay (`BoundedRelayInputStream`): exact-limit completion,
  overflow aborts (never a silent truncation), chunked/unknown-length streams,
  truncated upstreams, mid-stream read errors, and client disconnects.
- Download manager state: concurrency cap, duplicate IDs, cancellation, and
  byte accounting.

## Continuous integration

- `.github/workflows/android.yml` — on every push/PR to `main`: compiles,
  runs unit tests, runs lint, packages the APK, verifies it with `apksigner`,
  and uploads it as a build artifact.
- `.github/workflows/release.yml` — on every `v*` tag: builds a **signed**
  release APK, verifies the signature, computes the SHA-256 checksum, and
  attaches the APK to a GitHub Release.

## Install on Android TV

```bash
adb connect <tv-ip>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT
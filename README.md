# LAN Browser Relay

**Android TV LAN-hosted browser and Ethernet download relay for mobile browsers.**

The Android TV runs a local web server. Open its LAN address (e.g. `http://192.168.1.50:8080`) in Safari or Chrome on an iPhone. Browse the web through a hosted interface; when you download a file, the TV fetches it over Ethernet and streams it directly to the phone with bounded RAM buffers. The TV never permanently stores downloaded file contents.

## Features

- Local HTTP server on Android TV (NanoHTTPD)
- Pairing code authentication + session tokens
- Mobile-first browser UI (address bar, Google search, back/forward, home)
- Restricted HTML gateway with link rewriting
- Streaming download relay with hard **100 MB** (decimal) limit by default
- SSRF protections (blocks private/loopback/link-local destinations)
- Foreground service with notification
- Configurable port and download limit
- Unit tests for URL validation, sessions, and download limits
- GitHub Actions CI for test + APK build

## Requirements

- Android TV or Android device with API 24+ (Android 7.0)
- Ethernet (or any network) on the TV for upstream fetches
- iPhone/iPad or other device on the same LAN
- Safari or Chrome (no app install required on the phone)

## Quick start

1. Build or download the debug APK.
2. Install on the Android TV (sideload via ADB, USB, or file manager).
3. Open **LAN Browser Relay** on the TV and tap **Start Service**.
4. Note the LAN address and the 6-character pairing code.
5. On the iPhone, open Safari/Chrome and go to `http://<TV-IP>:8080`.
6. Enter the pairing code.
7. Search or navigate; use download links or `window.startDownload(url, filename)` for explicit downloads.

### Install via ADB

```bash
adb connect <tv-ip>
adb install -r app-debug.apk
```

## Architecture (short)

```
iPhone Safari  --LAN HTTP-->  TV NanoHTTPD
                                  |
                    +-------------+-------------+
                    |             |             |
               Static UI     HTML Gateway   Download Relay
                    |             |             |
                    |        OkHttp fetch   OkHttp stream
                    |             |             |
                    +-------> Internet (Ethernet) <------+
```

- **No disk writes** of download payloads.
- Streaming uses 64 KB buffers; concurrent downloads limited.
- Content-Length is a hint; actual bytes are counted and capped.

## Security notes

- Intended for a **trusted home LAN**.
- Unencrypted HTTP – do not expose to the public internet.
- Pairing code + session tokens required for browse/download APIs.
- Upstream destinations are validated (scheme, host, resolved IPs).
- Private, loopback, link-local, multicast addresses are blocked.
- Redirect targets are re-validated.
- Filenames are sanitized for `Content-Disposition`.

See [docs/SECURITY.md](docs/SECURITY.md) for the threat model.

## Limitations

- Not a full transparent proxy. Sites with strict CSP, CORS, complex JS apps, WebSockets, DRM, or login flows may break.
- Google search and simple static/download sites are primary targets.
- iframe sandboxing limits some interactions; downloads work best when initiated via the `/api/download` endpoint.
- Mixed-content and secure-context rules of the phone browser still apply.
- If a site cannot load through the gateway, open it in the normal browser instead.

## Building

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test
```

Requires JDK 17 and Android SDK (compileSdk 34).

## Configuration

| Setting              | Default     | Notes                          |
|----------------------|-------------|--------------------------------|
| Port                 | 8080        | Configurable in prefs          |
| Max download         | 100_000_000 | Decimal MB; hard per-transfer  |
| Concurrent downloads | 3           |                                |
| Buffer size          | 64 KB       | Streaming only                 |

## License

MIT (see LICENSE).

## Disclaimer

This project is for educational and personal LAN use. You are responsible for complying with the terms of use of any remote services and with applicable law. Do not use it to bypass access controls or to proxy traffic for unauthorized purposes.

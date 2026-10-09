# LAN Browser Relay

Android TV hosts a local web server. Open its LAN address on your phone (Safari/Chrome). Browse and download through the TV’s Ethernet connection. Downloads are streamed with a 100 MB limit and never stored on the TV.

**No login / no pairing.** Open the app → service starts automatically → open the shown URL on your phone.

## TV app

- Starts the LAN server automatically
- Clears caches on every startup
- Shows only: LAN address, logs, download speed, and a small read-only browser preview
- All real browsing and downloads happen in the phone’s browser

## Phone

1. Open the URL shown on the TV (e.g. `http://192.168.1.50:8080`)
2. Search Google or enter any URL
3. Downloads stream through the TV and appear as normal browser downloads

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 + Android SDK (compileSdk 34).

## Install on Android TV

```bash
adb connect <tv-ip>
adb install -r app-debug.apk
```

Open the app — it starts serving immediately.

## Limits

- Default max download size: 100 MB (decimal)
- Streaming only (no permanent file storage on TV)
- Basic SSRF protection (private/loopback destinations blocked for upstream fetches)
- Not every website works through the gateway (CSP, complex apps, DRM, etc.)

## License

MIT

# LANBrowserRelay

Install the APK on Android TV and open it. The service starts automatically. Open the LAN URL shown on the TV in Safari or Chrome on the same network.

No login, pairing, companion app, or PC is required. Downloads are streamed through bounded memory, capped at 100,000,000 bytes, and never written to TV storage. Redirects are revalidated, and transfer progress and cancellation are exposed.

This is an HTTP gateway, not a full browser engine. DRM, WebSockets, service workers, strict CSP, and complex cross-origin JavaScript may not work. Download speed depends on the network and remote host.

GitHub Actions builds, tests, lints, verifies, and uploads the debug APK on pull requests.

# LAN Browser Relay

An Android TV hosts a no-login browser gateway on the home LAN. Open the TV's LAN URL in Safari or Chrome on a phone connected to the same network.

## Use it
1. Install the debug APK on Android TV and open it.
2. Wait for the LAN URL to appear.
3. Open that URL on your phone.

Downloads are streamed through bounded RAM buffers, never staged in files on the TV. Each download is limited to 100,000,000 bytes (100 MB decimal), including chunked responses whose size is unknown at the start. The gateway blocks local and reserved destination addresses, and validates redirect destinations.

No login, pairing, account, or companion app is required. Keep this open service on a trusted LAN and do not expose the port to the public internet.

The hosted gateway supports ordinary websites, but sites that depend on advanced browser APIs, service workers, third-party cookie policies, or complex cross-origin behavior may not work fully in a proxied page.

# LAN Browser Relay

Clean Android TV LAN browser and Ethernet download relay.

## Use
1. Connect the Android TV to the router by Ethernet.
2. Install and open the TV app. The service starts automatically.
3. Connect the phone to the same LAN and open the TV URL shown on screen, usually http://TV-IP:8080.

The phone UI is hosted by the TV. The TV fetches pages and streams files to the phone.

## Requirements
- No login, pairing, companion app, or PC.
- Hard 100,000,000-byte (decimal 100 MB) download cap.
- Bounded memory streaming, with no intentional staging of downloaded file contents on TV storage.
- Cancels upstream work on cancellation or disconnect.
- Validates redirects and blocks local/reserved targets.
- This HTML proxy is not a full browser engine. Sites requiring advanced JavaScript, service workers, strict CSP or cross-origin APIs may not render correctly.

GitHub Actions builds the APK, runs tests and lint, checks APK integrity and uploads an artifact.

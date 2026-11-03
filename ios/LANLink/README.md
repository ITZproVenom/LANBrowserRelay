# LANLink iOS Controller

The iOS app is the full remote controller for the LANLink Android TV agent. It does not scan the LAN itself or poll the ChatGPT relay. The Android app on the TV is the single network execution engine.

## Automatic connection

1. Install LANLink on the iPhone and allow Local Network access when iOS asks.
2. Install LANLink TV Agent on the Android TV and open it once. The screen is passive; it starts the background service automatically.
3. Keep both devices on the same private Wi-Fi/Ethernet network.
4. Open LANLink on the iPhone. It scans the iPhone's private Wi-Fi subnet in bounded batches, probes port `43821` for the LANLink-specific status response, automatically enrolls its Keychain-generated controller key on first contact and connects. No Bonjour allowlist, IP, port, token, QR or manual pairing entry is required.

The Android service starts again after TV reboot. The iPhone reconnects automatically whenever LANLink is open and returns to the foreground.

## Architecture

- **Android TV agent:** foreground service, Bonjour advertisement, local network discovery, device operations and ChatGPT relay polling.
- **iOS controller:** bounded same-subnet TCP discovery, automatic first-use enrollment, dashboard, device list/details, Android-backed scans, relay start/pause, activity, probe, constrained HTTP GET and Wake-on-LAN.
- **ChatGPT:** uses the existing LANLink MCP relay; queued commands are executed by the Android TV agent.

## Security and recovery

The iPhone creates a random 256-bit controller key and stores it in iOS Keychain. The Android TV stores the enrolled key encrypted with Android Keystore. The TV accepts only the first controller on its private subnet; pairing credentials are never published in Bonjour. Use this only on a trusted home LAN. Clearing LANLink TV Agent app data on the TV resets enrollment and lets the iPhone pair again.

## Platform limits

- Requires iOS 17 or newer.
- Android TV is the only LAN scanning/execution engine; iOS does not perform duplicate scans.
- The Android app has only a passive status screen, not navigable settings or pairing controls.
- The Android foreground service is subject to Android vendor battery-management and OS policies.
- If an Android agent relay credential was already saved, updates preserve it. A fresh TV install without a relay credential can still be controlled locally by iPhone, but ChatGPT relay commands remain unavailable until the TV agent receives its relay identity.
- Scans are bounded to at most 256 addresses. Devices that do not respond to probed ports and do not appear in Android's ARP cache may remain undiscovered.
- Do not forward the Android controller API port (43821) to the public internet.

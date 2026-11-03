# LANLink iOS Controller

The iOS app is the full remote controller for the LANLink Android agent. It does not scan the LAN itself or poll the ChatGPT relay. The Android app on the TV is the single network execution engine.

## Architecture

- **Android TV agent:** runs the foreground service, polls the LANLink MCP relay, discovers devices, probes services, performs constrained local HTTP requests and Wake-on-LAN.
- **iOS controller:** connects directly to the Android TV over the same private LAN using an authenticated local HTTP API. It controls the relay, starts scans, displays devices, sends supported commands and reads activity.
- **ChatGPT:** uses the existing LANLink MCP relay; its queued commands are executed by the Android TV agent.

## Requirements

- Xcode 16 or newer
- iOS 17 or newer
- Android TV running LANLink TV Agent
- iPhone and Android TV on the same private Wi-Fi/Ethernet LAN

## Pairing

1. Install and open LANLink TV Agent on the Android TV.
2. Enter the Android agent's own relay token and tap **Start Android agent** once.
3. The TV displays its private IP address, control port (43821), and a separate controller pairing key.
4. In the iPhone app, open **Settings** and enter those three values.
5. Tap **Save & connect**.

The pairing key is stored in iOS Keychain. The Android app stores its copy encrypted with Android Keystore AES-GCM.

## Controller features

- Connect/disconnect from the paired Android agent.
- Start or pause the Android agent's ChatGPT relay polling without shutting down the local control API.
- Trigger a full Android-side LAN discovery scan.
- Browse the Android-discovered device inventory, including IP, ARP MAC address when available, and open TCP services.
- Probe devices, send supported local HTTP GET requests, and send Wake-on-LAN to hosts whose MAC is known.
- View Android-side activity and relay status.

## Local API security

- Android binds the controller API to TCP port 43821.
- Every endpoint requires the high-entropy controller pairing key in an HTTP Bearer header.
- Requests are accepted only from private IPv4 clients on the Android agent's current LAN subnet.
- Request headers and JSON body sizes are bounded; redirects are not followed by the agent.
- The Android agent remains the only component that executes LAN operations and communicates with the ChatGPT relay.

The controller API uses HTTP on the private LAN. Use it only on a trusted network and do not forward its port to the internet.

## Platform limits

- The Android foreground service provides persistent agent operation subject to Android's foreground-service and battery-management policies.
- The iOS app is a controller only; it has no GPS or silent-audio keep-alive requirement.
- Scanning is bounded to at most 256 addresses. Devices that do not respond to probed ports and do not appear in Android's ARP cache can remain undiscovered.
- The Android app does not execute arbitrary shell commands or bypass device pairing/passwords.

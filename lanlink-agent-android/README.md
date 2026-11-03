# LANLink Android TV Agent

The Android TV app is the LANLink network engine. The iPhone app is its full remote controller.

## Supported device target

- Android 6.0+ (min API 23), including Android TV 9 / API 28 and ARMv7.
- No native ABI-specific libraries; one APK supports ARMv7 and ARM64.
- Runs as an Android foreground service with an ongoing notification.
- Polls the LANLink MCP relay and performs the LAN operations requested by ChatGPT or the iPhone controller.

## Setup

1. Install LANLink TV Agent on the Android TV.
2. Open it and enter the Android agent's private `agent_` relay token.
3. Tap **Save relay token**, then **Start Android agent**.
4. The TV displays its local IP address, controller port (`43821`) and a separate 256-bit controller pairing key.
5. In the iPhone LANLink app, open Settings and enter that IP, port and pairing key. Tap **Save & connect**.

The controller pairing key is generated on the TV, stored encrypted with Android Keystore AES-GCM, and is separate from the relay token. It is visible/selectable in the TV app so it can be copied into the iPhone controller.

## iPhone controller API

The Android foreground service serves an authenticated HTTP API on TCP port 43821, reachable only from private IPv4 clients on the same subnet:

- `GET /v1/status` - agent, relay and network status
- `GET /v1/devices` - most recent Android-side device inventory
- `GET /v1/activity` - recent agent activity
- `POST /v1/scan` - run Android-side LAN discovery
- `POST /v1/bridge/start` - start ChatGPT relay polling
- `POST /v1/bridge/stop` - pause ChatGPT relay polling but keep the controller API running
- `POST /v1/command` - execute a supported `probe`, constrained `http`, or `wol` action

Every request must carry `Authorization: Bearer <controller-pairing-key>`. The listener rejects non-private and off-subnet clients, caps request headers/body size, and never follows redirects. Do not expose port 43821 to the public internet; use only on a trusted LAN.

## Capabilities

- Discovers local IPv4 hosts in a bounded /24-or-smaller scan, incorporating the Android ARP cache and common TCP service probes.
- Displays IP, MAC address (when available in the ARP table), hostname where available, and open services.
- Supports same-subnet probing, constrained HTTP requests, and Wake-on-LAN.
- Stores the relay and controller secrets encrypted with Android Keystore AES-GCM.
- Does not execute arbitrary shell commands, does not bypass device passwords/pairing, and does not currently implement ADB client control.

## Background operation

The foreground service keeps both the local controller API and the ChatGPT relay agent running while Android permits the foreground service. On Android 13+ the notification permission is requested; allow it so the ongoing service notification is visible. Battery optimization or vendor power management may still affect uptime.

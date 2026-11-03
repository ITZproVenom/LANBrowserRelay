# LANLink Android TV Agent

The Android TV app is a headless LANLink engine. It is deliberately not a navigable settings app: the only TV screen is a passive running/status screen. All controls live in the iPhone app.

## Automatic start and discovery

- Open LANLink once after installation to start its foreground service. It also starts automatically after TV reboot and after app updates.
- The service advertises `_lanlink._tcp` as `LANLink-TV` using Android Network Service Discovery.
- The iPhone app scans its private IPv4 subnet and probes the LANLink control API on port 43821, so discovery also works inside LiveContainer without Bonjour.
- No TV IP entry, controller token display, QR code, PIN or manual iPhone pairing form is required.
- On first contact, the iPhone generates a 256-bit controller key in Keychain. The TV accepts the first controller on its private subnet and stores the key encrypted using Android Keystore. Later connections require the saved key.
- Only one iPhone controller is enrolled per TV. To reset controller enrollment, clear LANLink TV Agent app data on the TV and relaunch it.

## ChatGPT relay

The Android service preserves a previously saved relay agent credential when upgrading from the older setup build. If no relay credential exists, local iPhone control still works. In the iPhone app, enter the TV's one-time activation code under Settings. The iPhone exchanges it for a per-TV relay identity and installs that identity through the authenticated local control API. The TV encrypts it with Android Keystore and starts polling automatically. The long-lived relay token is never shown on the TV or stored on the iPhone.

## iPhone controller API

The Android foreground service serves an authenticated HTTP API on TCP port 43821, reachable only from private IPv4 clients on the same subnet:

- `POST /v1/pair` - one-time first-controller enrollment, accepted only while the TV has no enrolled controller
- `GET /v1/status` - agent, relay and network status
- `GET /v1/devices` - most recent Android-side device inventory
- `GET /v1/activity` - recent agent activity
- `GET /v1/logs` - persistent diagnostic log ring buffer (last 500 entries)
- `POST /v1/scan` - run Android-side LAN discovery
- `POST /v1/relay/credential` - install a newly enrolled relay identity (controller Bearer token required)
- `POST /v1/bridge/start` - start ChatGPT relay polling
- `POST /v1/bridge/stop` - pause ChatGPT relay polling but keep the controller API running
- `POST /v1/command` - execute a supported `probe`, constrained `http`, or `wol` action

Every route except the one-time pairing endpoint requires the controller key in an HTTP Bearer header. Requests from outside the TV's private subnet are rejected; headers and JSON bodies are size-bounded; redirects are not followed. Do not expose TCP 43821 to the public internet.

## Requirements and capabilities

- Android 6.0+ (min API 23), including Android TV 9 / API 28 and ARMv7.
- One APK supports ARMv7 and ARM64 (no native ABI-specific libraries).
- Foreground service with ongoing notification; boot receiver restarts it after TV reboot.
- Discovers local IPv4 hosts using common TCP service probes and complete neighbor-table entries only. Incomplete/zero-MAC ARP records are ignored. Every scan records its subnet, probe budget, completed/timed-out hosts, TCP-confirmed count, ARP-only count, before/after ARP counts, per-host evidence and duration.
- Displays IP, MAC address when available, hostname where available, and open services in the iPhone app.
- Supports same-subnet probing, constrained local HTTP requests, and Wake-on-LAN.
- Does not execute arbitrary shell commands or bypass device passwords/pairing; ADB client control is not implemented.

## Security note

First pairing is automatic trust-on-first-use: the first LAN client to claim an unpaired TV becomes its controller. Use this on a trusted home network, not a guest/shared Wi-Fi. Pairing secrets are never advertised through Bonjour.

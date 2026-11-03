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

## ChatGPT ADB control

The ChatGPT relay exposes `lan_adb_command` for Android devices that have ADB enabled on the same private subnet.

- Classic ADB: use `transport=tcp`; the bridge defaults to port 5555. The target must already be listening for TCP ADB and must authorize the LANLink host key.
- Android 11+ Wireless debugging: first run `action=pair`, `transport=tls` with the current six-digit pairing code and pairing port shown by the target. Then run normal actions with `transport=tls` and the separate connection port shown on the Wireless debugging screen.
- The host key is persistent across agent restarts and encrypted by Android Keystore. Pairing codes are transient and are redacted from the relay command record after execution.
- ADB is restricted to the bridge's own private subnet. Reboot is available only as an explicit ADB action.

The bridge can only control devices whose ADB service is enabled and authorized. It does not silently enable debugging or bypass the target's authorization prompt.

## ChatGPT terminal and arbitrary ADB shell

The Android agent now exposes `lan_terminal` through the Supabase MCP relay:

- `operation=start` creates a persistent `/system/bin/sh -i` session on the Android TV.
- `operation=exec` runs a command in that session and returns bounded output, exit status, completion/timeout state and a cursor.
- `operation=write` sends interactive stdin; `operation=read` retrieves later output by cursor; `operation=close` destroys the session.
- The agent permits at most three concurrent sessions, retains at most 64 KiB of output per session, limits one input/command to 16 KiB, caps a command wait at 60 seconds, and expires idle sessions after ten minutes.
- Commands execute as the LANLink Android app UID, not root. The working directory is a private per-session app directory. Available commands are the Android TV's existing system utilities; this is not an Alpine Linux container and does not bundle Python, Node.js, apt or a general Linux package manager.
- Use `lan_command_status` with the returned command ID to retrieve the result of each queued terminal operation. Sessions are held in agent memory and end when the service process restarts.

`lan_adb_command` also accepts `action=shell` and a `command` string up to 16 KiB. This runs an arbitrary command through the target device's authorized ADB shell, with a 60-second execution deadline and bounded stdout/stderr. It does not bypass Android's ADB authorization or target permissions. Shell commands can alter the target device, so use explicit target IPs and inspect the command before sending it.

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
- `POST /v1/command` - execute a supported `probe`, constrained `http`, `wol`, authenticated `adb`, or `terminal` operation

Every route except the one-time pairing endpoint requires the controller key in an HTTP Bearer header. Requests from outside the TV's private subnet are rejected; headers and JSON bodies are size-bounded; redirects are not followed. Do not expose TCP 43821 to the public internet.

## Requirements and capabilities

- Android 6.0+ (min API 23), including Android TV 9 / API 28 and ARMv7.
- One APK supports ARMv7 and ARM64 (no native ABI-specific libraries).
- Foreground service with ongoing notification; boot receiver restarts it after TV reboot.
- Discovers local IPv4 hosts using common TCP service probes and complete neighbor-table entries only. Incomplete/zero-MAC ARP records are ignored. Every scan records its subnet, probe budget, completed/timed-out hosts, TCP-confirmed count, ARP-only count, before/after ARP counts, per-host evidence and duration.
- Displays IP, MAC address when available, hostname where available, and open services in the iPhone app.
- Supports same-subnet probing, constrained local HTTP requests, and Wake-on-LAN.
- ADB host control supports classic authorized TCP ADB (default port 5555) and Android 11+ Wireless debugging over TLS, including one-time six-digit pairing.
- The bridge creates one persistent 2048-bit ADB identity and encrypts the private/public key material at rest using the existing Android Keystore AES-GCM storage.
- ADB supports both convenience actions and arbitrary commands through the target's explicitly authorized shell; shell commands are length-bounded and time-limited.
- Wireless debugging pairing codes are accepted for one command and scrubbed from the Supabase command payload after the target responds. The target must explicitly enable Wireless debugging and authorize/pair the LANLink bridge.
- Terminal commands and stdin are scrubbed from the Supabase command payload after execution. Terminal output is returned as the command result; do not print credentials or other secrets into the terminal output.

## Security note

First pairing is automatic trust-on-first-use: the first LAN client to claim an unpaired TV becomes its controller. Use this on a trusted home network, not a guest/shared Wi-Fi. Pairing secrets are never advertised through Bonjour.

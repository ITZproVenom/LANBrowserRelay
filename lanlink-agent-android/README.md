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

## ChatGPT-operated Alpine Linux terminal

The TV agent hosts a persistent Alpine Linux userspace in its private application storage. ChatGPT operates it through the authenticated Supabase MCP relay; the human does not need to open or type into a terminal.

### Runtime

- The agent bundles an Android-native PRoot executable for `armeabi-v7a` and `arm64-v8a`. It is built from a pinned Android-aware PRoot source revision using the Android NDK.
- The first `lan_terminal` call should use `operation=environment` to inspect the actual Android release, API level, supported ABIs, PRoot presence, Alpine state and storage.
- Use `operation=bootstrap` to download the pinned Alpine 3.24.3 minirootfs for the detected architecture, verify its SHA-256 sidecar, safely extract it into a staging directory, run a PRoot/APK smoke test, and only then activate it.
- Rootfs location: `files/lanlink-linux/rootfs` under the Android app's private files directory.
- Persistent workspace: `files/lanlink-linux/workspaces/default`, exposed inside Alpine as `/workspace`.
- The rootfs and workspace survive terminal sessions, service restarts and normal APK upgrades. Closing a terminal session does not delete installed packages or workspace files.
- PRoot's `-0` simulates UID 0 only inside Alpine. The Android process continues to run as the LANLink application UID. This is not Android root and PRoot is not a kernel security boundary.
- The bootstrap capability is reported separately from execution. `terminal-exec` and `linux-package-management` are advertised only after a rootfs has been initialized and verified.
- The initial native build covers ARMv7 and ARM64. Legacy `armeabi` is not assumed to be ARMv7; if the TV reports only legacy `armeabi`, bootstrap returns an explicit unsupported-ABI error rather than installing a mismatched executable.
- Actual execution on a particular TV must still be verified on that hardware. A green cross-build does not prove that the TV kernel permits PRoot tracing.

### Package installation

The shell is a real Alpine `/bin/sh` with Alpine's `apk` package manager. ChatGPT can install utilities as needed, for example:

~~~sh
apk update
apk add python3 py3-pip
apk add nodejs npm
apk add go
apk add rust cargo
apk add ffmpeg
apk add git curl wget jq
~~~

Packages are installed into the persistent rootfs. They are not bundled into the APK and are not preinstalled unless the bootstrap image contains them. Package availability depends on Alpine repositories and the selected architecture. Rust, Go and FFmpeg can require substantial storage and installation time.

### MCP operations

`lan_terminal` supports:

- `environment` / `status`: report Android ABI, PRoot presence, Alpine version/readiness and storage.
- `bootstrap`: install and verify the persistent Alpine rootfs.
- `start`: create a new persistent interactive shell session.
- `exec`: run a shell command or script and return bounded stdout, stderr, exit code, timeout and cursor information.
- `write`: send interactive stdin.
- `read`: retrieve stdout and stderr independently using their cursors.
- `cancel`: terminate and discard the session.
- `close`: close a session normally.

Terminal commands are queued asynchronously so a long bootstrap or package installation does not block agent polling and heartbeats. Use `lan_command_status` with the returned command ID to retrieve the result. Bootstrap jobs have a longer queue expiry; command execution is capped at ten minutes. The agent supports at most three sessions, 16 KiB inputs, 64 KiB retained output per stream, 12 KiB per read, and ten minutes idle lifetime.

The current terminal transport is interactive stdin/stdout/stderr pipes, not a pseudo-terminal. Shell commands and package installations work through this interface, but programs which require a controlling TTY, terminal ioctls or full job-control semantics are not yet guaranteed to work.

Terminal sessions live in agent memory and end when the app process/service shuts down; the Alpine rootfs and workspace persist. Commands execute as the Android application UID, with fake-root only inside the Linux guest.

### ADB

`lan_adb_command` retains authorized ADB TCP and Wireless Debugging TLS pairing/connection. Its `action=shell` operation runs arbitrary commands through the target device's authorized ADB shell. ADB target authorization and Android permission boundaries are still enforced; LANLink does not bypass them.

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
- The TV-agent APK packages Android-native PRoot executables for ARMv7 and ARM64. Legacy `armeabi` is not yet supported and must be reported explicitly.
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

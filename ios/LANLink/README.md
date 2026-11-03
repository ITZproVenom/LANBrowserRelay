# LANLink for iOS

LANLink turns an iPhone on the local Wi-Fi network into the LAN agent for the LANLink MCP relay. It includes a native SwiftUI interface for bridge status, local-network scanning, command activity, and relay settings.

## Requirements

- Xcode 16 or newer
- iOS 17 or newer
- iPhone connected to a private Wi-Fi network
- An enabled LANLink agent token for this iPhone

## Build

Open `LANLink.xcodeproj`, select the `LANLink` scheme, choose an iPhone simulator or device, and build.

The GitHub Actions workflow builds the iOS target without signing and packages an unsigned device IPA for inspection or later signing. An unsigned IPA is not installable until it is signed with a valid Apple development/distribution certificate and provisioning profile.

## Pairing

1. Obtain an agent token for the iOS agent from the LANLink project owner.
2. Install and open LANLink.
3. In Settings, keep the default relay URL or enter your relay URL, then paste the agent token.
4. Tap **Save settings**, return to Overview, and tap **Start bridge**.
5. Keep LANLink open while using the ChatGPT LANLink tools.

The token is stored in the iOS Keychain. It is never written to the repository.

## Current capabilities

- Poll the existing LANLink relay using the agent heartbeat, poll, and report endpoints.
- Handle queued discovery, common TCP port probes, constrained local HTTP requests, and Wake-on-LAN commands.
- Discover private IPv4 hosts in a bounded scan window of 256 addresses or fewer using expanded TCP service probes and Bonjour browsing for HTTP/HTTPS, Google Cast, AirPlay, printers, SSH, SMB, HomeKit and other common LAN services.
- Keep a local device list and command activity feed.
- Use Keychain storage for the agent token.
- Optional StikDebug-style background keep-alive controls: silent AVAudioEngine playback and low-accuracy Core Location updates while the bridge is active.
- Optional LocalDevVPN URL hand-off for users who need its loopback developer tunnel.

## Platform limits and safety

- The background keep-alive is best-effort. While the bridge is active, LANLink can run a silent zero-sample AVAudioEngine playback session (`audio` background mode) and/or low-accuracy Core Location updates (`location` background mode). This follows the two controls exposed in StikDebug. iOS may still suspend or terminate the app; force-quitting stops the session. Audio/location modes increase battery use, and location permission is required for the location option.
- LocalDevVPN is a separate loopback Network Extension used by StikDebug for on-device developer services. It is not the keep-alive mechanism and is optional for LANLink's normal local-Wi-Fi operations. The Settings button invokes LocalDevVPN's `localdevvpn://enable` hand-off when it is installed.
- iOS does not expose ARP-table access to normal apps, so discovery reports IP addresses and responding services rather than MAC addresses. Devices that do not advertise Bonjour and block all probed TCP ports can remain undetected; the router's DHCP/client table remains the authoritative inventory.
- Scanning and HTTP requests are limited to the current private IPv4 subnet. The app does not execute arbitrary shell commands or ADB commands.
- The local network permission prompt is required before LAN discovery can work.

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
- Discover private IPv4 hosts in a bounded scan window of 256 addresses or fewer.
- Keep a local device list and command activity feed.
- Use Keychain storage for the agent token.

## Platform limits and safety

- Without location-assisted mode, iOS may suspend relay polling after the app enters the background. With the optional location background mode enabled, LANLink requests real Core Location updates and enables `allowsBackgroundLocationUpdates`; this can keep the process active while iOS delivers location updates, but is not guaranteed after force-quit, termination, denied permission, or system resource pressure. This mode consumes additional battery and shows the iOS location indicator.
- iOS does not expose ARP-table access to normal apps, so discovery reports IP addresses and responding services rather than MAC addresses.
- Scanning and HTTP requests are limited to the current private IPv4 subnet. The app does not execute arbitrary shell commands or ADB commands.
- The local network permission prompt is required before LAN discovery can work.

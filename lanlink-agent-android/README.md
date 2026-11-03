# LANLink TV Agent

Native Android TV companion for the LANLink ChatGPT plugin.

- Android 6.0+ (min API 23); built/tested for Android TV 9 / API 28 and ARMv7.
- No native ABI-specific libraries are used, so one APK supports ARMv7 and ARM64.
- Runs a foreground service and polls the LANLink relay over HTTPS.
- Discovers hosts on one local /24-or-smaller subnet, checks common TCP services, sends bounded HTTP requests, and emits Wake-on-LAN packets.
- Stores the bridge token encrypted with Android Keystore AES-GCM.
- Does not execute arbitrary shell commands and does not bypass device pairing/passwords.

## Pairing

Install the APK, open LANLink TV Agent, paste the private `agent_` token supplied for this bridge, press **Save token**, then **Start LANLink bridge**. Keep the TV connected to the same LAN as the devices to manage.

## Notes

The device is a network bridge, not a universal remote. A target must expose a compatible HTTP API, Wake-on-LAN support, or another explicitly supported protocol. The current Android build does not implement ADB client control.

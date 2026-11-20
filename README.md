# WiFi Guardian — Redmi Note 8 Pro

Native Android Kotlin source project (Android 8+). **Source code only: not a built or tested APK.**

## Implemented
- Wi-Fi IPv4 and gateway detection
- Manual device discovery on /24 IPv4 networks using TCP connect checks (limited visibility)
- Concurrent probing, progress and device classifications
- Comparison with previous scan, scan history (last 30), shareable CSV text
- Copy device IP, open router's management page
- No root, no packet interception, no deauthentication, no bypass of router authentication

## Not implemented
- Forced disconnection or router blocking: requires a supported router-specific, authenticated management integration
- Per-device traffic capture: requires router monitoring telemetry or other authorized network instrumentation
- Background monitoring, device hostnames, MAC addresses, full subnet discovery, and an installable APK

## Build
Open this folder in Android Studio with a compatible Android SDK/JDK and let Gradle sync. Build > Build APK(s), then install the resulting debug APK on your own Android phone. Android Studio may download dependencies. The source has not been compiled in this environment.

## Limitations
Wi-Fi client isolation, firewalls, VPNs, and Android restrictions may prevent device discovery. TCP scanning cannot enumerate all connected devices. Scanning a network does not establish permission to manage its clients. Only scan networks you are authorized to test.

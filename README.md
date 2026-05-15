# BASTION mobile

> A defensive **sensor** for your phone. Not a shield, not magic. Honest receipts.

This is the mobile companion to [BASTION](https://github.com/1800bobrossdotcom-byte/bastion) (Windows). Two surfaces:

1. **Network sensor** — a system-level VPN that DNS-sinkholes hostnames present on the merged
   URLhaus + OpenPhish + MalwareBazaar feed. Every blocked lookup is recorded in a tamper-evident
   audit log on-device. **Nothing leaves the phone.**
2. **Desktop pair** *(planned)* — connect to your Windows BASTION agent over Tailscale and mirror
   its audit log + receive push when something is quarantined.

## Honesty disclaimer

- We do **not** "block spyware" or "detect Pegasus". Anything claiming that on a phone is lying.
- Android sandboxing means BASTION cannot watch other apps' files or memory. We watch the wire.
- iOS sandboxing is even tighter. Same approach: DNS / network only.
- The blocklist is public, hosted from this repo's `gh-pages` branch. Verify it yourself.

## Layout

```
android/        Kotlin + Jetpack Compose. Min SDK 26 (Android 8). Target SDK 35.
ios/            Swift + SwiftUI + NetworkExtension. Min iOS 16. xcodegen project.
blocklist/      Merger script + GitHub Action that rebuilds blocklist.txt every 12h.
store/play/     Play Store listing, screenshots template, data-safety form answers.
store/appstore/ App Store listing, privacy nutrition label answers.
LEGAL/          Privacy policy + terms (single source, both stores reference).
```

## Build

### Android

Requires JDK 21 + Android Studio (Android SDK 35, build-tools 35).

```powershell
cd android
# First time: let Studio generate gradle wrapper, OR:
gradle wrapper --gradle-version 8.10
./gradlew assembleRelease
```

APK lands at `android/app/build/outputs/apk/release/app-release-unsigned.apk`.
Sign with your Play Store upload key before submitting.

### iOS

Requires macOS + Xcode 16 + xcodegen (`brew install xcodegen`).

```bash
cd ios
xcodegen generate
open Bastion.xcodeproj
```

## Identity

- Android `applicationId`: `cam.bastion.mobile`
- iOS `bundleIdentifier`: `cam.bastion.mobile` (host) + `cam.bastion.mobile.tunnel` (extension)
- Domain: shipping under [lovebeing.shop](https://lovebeing.shop/apps/bastion) umbrella, GitHub release for sideload APK.

## License

All rights reserved (matches main BASTION repo).

# SoundCtrl

JBL headphones, earbuds and speakers app in the VKNXWorks Ctrl series, replacing JBL Headphones and JBL Portable. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings and the device list. Bluetooth LE GATT directly.

## Layout

- `proto/`: pure Kotlin, unit-tested (`app/src/test`). `AaFrame.kt` (the `AA cmd len payload` protocol, speaker device-info tokens), `P4.kt` (protocol 4: `00 DD` frames, TLVs, reassembly, OTA notices), `HeadphoneP4.kt` (noise control, auto-off, voice prompts, gestures; speaker P4 settings), `SpeakerDfu.kt` (speaker update protocol: CRC, start payload, device-paced session), `Race.kt` (Airoha version read), `Catalog.kt` + generated `ProductRows.kt` (product id → chip, control protocol, update family, update source), `UpdateInfo.kt` (Harman file-server URLs, JSON/XML answers, MD5, host allow-list).
- `ble/GattLink.kt`: one GATT connection; finds the Harman pipe (`…0001` notify, `…0002` write) or Airoha's RACE pipe; MTU 512; writes wait for the callback.
- `device/DeviceSession.kt`: detection, refresh and settings per control protocol; `device/Devices.kt`: remembered devices, scanning, the one open session.
- `update/`: `FileServer` (the only network code), `Updater` (check, download, file, rules, start/abort), `Transfers` (speaker AA and protocol-4 transfers), `UpdateService` (connectedDevice foreground service during a transfer only).
- `ui/device`: `DevicesScreen`, `DeviceScreen`, `UpdateScreen`.
- `PROTOCOL.md`: everything known about the protocols, verified vs inferred. Keep it in step with the code.

## Hard rules

- **Network:** only `storage.harman.com` and `appstorage.onecloud.harman.com`, HTTPS, only after a tap on Check for update / Download, sending only the product id (`UpdateServer.isAllowed`, `FileServer`). No other host, ever. CI fails on network-state, Wi-Fi or location permissions.
- **No location permission**; BLUETOOTH_SCAN keeps `neverForLocation`.
- **Never send guessed bytes.** A setting whose bytes aren't established from the JBL apps is shown as "Needs confirming on your device" and disabled. Firmware writing only for families marked `writeSupported`.
- **Firmware rules:** MD5 must match (server's or the user's), version must be newer, battery at 50 % or more, speaker idle and not grouped, explicit confirmation that says it's untested.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting. No JBL/Harman account, cloud, PersoniFi or product pictures.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`). Settings open only from the profile picture.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "SoundCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.

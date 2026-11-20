# SoundCtrl

JBL headphones, earbuds and speakers app in the VKNXWorks Ctrl series, replacing JBL Headphones and JBL Portable. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings; the device list is a sealed file. Bluetooth LE GATT directly.

## Layout

- `proto/`: pure Kotlin, unit-tested (`app/src/test`). `AaFrame.kt` (the `AA cmd len payload` protocol, speaker device-info tokens), `P4.kt` (protocol 4: `00 DD` frames, TLVs, reassembly, OTA notices), `HeadphoneP4.kt` (noise control, auto-off, voice prompts, gestures; speaker P4 settings), `SpeakerDfu.kt` (speaker update protocol: CRC, start payload, device-paced session), `Race.kt` (Airoha version read), `Catalog.kt` + generated `ProductRows.kt` (product id → chip, control protocol, update family, update source), `UpdateInfo.kt` (Harman file-server URLs, JSON/XML answers, MD5, host allow-list).
- `ble/GattLink.kt`: one GATT connection; finds the Harman pipe (`…0001` notify, `…0002` write) or Airoha's RACE pipe; MTU 512; writes wait for the callback.
- `device/DeviceSession.kt`: detection, refresh and settings per control protocol; `device/Devices.kt`: remembered devices (`filesDir/devices.json`, encrypted at rest, the only reader and writer), scanning, the one open session.
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

## Encryption at rest

- `devices.json` (the remembered devices) is sealed with AES-256-GCM (`data/crypto/SealedFile`, marker `SOUNDCTRL-REST1`) under the data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `soundctrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication). It is the only sealed file. Settings (DataStore `soundctrl`) and the app lock vault are not sealed. Firmware images are never written to disk.
- Versions up to 0.1.3 kept the list in the DataStore key `devices`. `Devices.ensureLoaded` copies it into the file, reads it back and only then removes the key. Keep that path.
- Readers accept both sealed and ordinary files (`SealedFile.isSealed`). Without a data key the list is an ordinary file, sealed later through `SealedFile.sealInPlace`.
- Never delete or overwrite a list that can't be opened, never replace an existing `data.key`, and never create a Keystore key on the decrypt path (`KeystoreAes.existing`). An unreadable list shows as empty and is renamed to `devices.json.unreadable` only when a new list has to be written (an unusable `data.key` becomes `data.key.unreadable`). Only "Forgot PIN?" (`Devices.forgetAll`) deletes them.
- All file and key work is on `Dispatchers.IO` under one mutex; `Devices.loaded` tells the UI when the list has been read.
- `SealedFileTest` covers the format, the migration step and the key rules (`./gradlew :app:testDebugUnitTest`; CI does not run unit tests).

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "SoundCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.

# Changelog

What changed in each SoundCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/SoundCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.2.1 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It is the first release built with the new build tools (Android Gradle Plugin 9, stricter code shrinking), so update over your installed version and check that your data and settings are still there. It has also never been tried with a real JBL device.

### Changed

- **Newer build tools; nothing changes in how SoundCtrl looks or works.** SoundCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.13.2 → 9.5.1, Gradle 8.14.3 → 9.9.2 and Compose BOM 2026.06.01 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). Kotlin stays at 2.4.21 (with its Compose and serialization compiler plugins) and the other libraries were already on the versions the newest Ctrl apps use, so none of them changed. No library was added, and the app is still Google-free (CI checks it).
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions SoundCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4. SoundCtrl's own keep rules are unchanged.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

### Security

- **The signing key is no longer available to the build.** Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Builds are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. Nothing in the app itself changed for this.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache.** Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause, reproduced on DateCtrl: with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the code scan start from a clean Gradle home, and the plugin portal is no longer a plugin source at all (`settings.gradle.kts`): everything the build needs is on Maven Central and Google's Maven. Dependency verification itself is unchanged and as strict as before.
- The new build-and-sign path has run for ordinary builds only. SoundCtrl's release job (publishing) has not run with it yet; the next real release is its first use here.

## v0.2.0 (2026-11-20)

**This release encrypts some of what the app stores on the phone. An older version cannot read it afterwards, so export or back up anything you need before you update (details below).** Built and checked by CI only: nothing in this release had been run on a phone when it was published. It has also never been tried with a real JBL device.

### Privacy

- **The list of your devices is now encrypted on the phone.** The devices SoundCtrl remembers (name, Bluetooth address, model, last firmware and battery levels) used to sit as readable text in the settings file. They now live in their own file, `devices.json` in SoundCtrl's private storage, sealed with AES-256-GCM under a random 256-bit data key. The data key is stored next to it in `data.key`, itself encrypted by a key in the phone's secure hardware (Android Keystore, StrongBox when the phone has it) that can't be exported and needs no fingerprint or PIN. This protects the list in a copy of SoundCtrl's files taken off the phone; it does not protect against someone who can run code as SoundCtrl on the unlocked phone, and it is independent of the app lock.
- **Your existing list is moved, not rebuilt.** The first time this version starts, the list is copied from the settings file into the new file, read back from storage, decrypted and compared; only when it matches is the old readable copy removed from the settings file. If anything in that fails, the old copy stays and SoundCtrl tries again at the next start. If the phone has no usable secure hardware key, the list is kept as an ordinary file instead and is sealed once a key is available.
- **If the encrypted list can't be opened** (for example the secure hardware key is gone), SoundCtrl shows an empty device list, as on a new install, and leaves the file alone. When you connect a device again, the unreadable file is renamed to `devices.json.unreadable` (not deleted) and a new list is started. Nothing about a device is lost: the list only holds what the device itself reports, so **Look for devices** and connecting bring it back.
- **Not encrypted, on purpose:** the settings (open the last device, app lock timing, hide in screenshots and recents) are plain preferences with nothing about your devices in them. The app lock's own storage was already protected and is unchanged. Firmware images are never stored: a downloaded or picked image is held in memory only for the transfer.
- **No going back to an older version with the list intact:** SoundCtrl 0.1.3 and older look for the list in the settings file and will show no remembered devices. Connecting a device again adds it back.
- Nothing about Bluetooth, the device protocols or firmware updates was changed.

### Changed

- The device list is read on a background thread when SoundCtrl starts. The first screen waits for it before it decides there are no remembered devices, so it no longer starts a scan or shows "No devices" for a moment while the list is still loading.
- Saving the list is skipped when nothing in it changed.

## v0.1.3 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It has also never been tried with a real JBL device.

### Performance

- **A downloaded firmware file is no longer held in memory twice.** When Harman's server says how big the file is, SoundCtrl now reads it into a buffer of exactly that size and uses that buffer as it is, instead of copying it once more when the download ends (up to 64 MB less memory at that moment). The downloaded bytes, the MD5 check and the transfer are unchanged.
- **The update notification is refreshed only when the percentage changes.** During a transfer the device asks for data many times a second, and the notification was posted again for every request. It now changes once per whole percent, which is all it ever showed.
- **Scanning redraws less.** While looking for devices, an advert that only differs in signal strength (which SoundCtrl doesn't show) no longer rebuilds and re-sorts the list of nearby devices.

## v0.1.2 (2026-11-13)

A security and maintenance release. Nothing changes in how you use SoundCtrl.

### Security

- **Stricter handling of what a headphone or speaker sends.** Packets that don't belong together (a piece of another reply, or one whose first part went missing) are now thrown away instead of being glued into one message, so a garbled Bluetooth connection can't make SoundCtrl misread a setting or an update notice.
- The build tool download is now checked against its published checksum.

### Performance

- Updated Jetpack Compose, AndroidX and Kotlin libraries to their current versions.
- Release builds drop debug logging.
## v0.1.1 (2026-11-12)

### Fixed

- **The Devices screen no longer sits under the status bar**, so the profile picture (and Settings behind it) can be tapped again.

## v0.1.0 (2026-11-12)

The first release of SoundCtrl: one private app for JBL headphones, earbuds and speakers, in place of JBL Headphones and JBL Portable. Not tested on a real JBL device yet; reports are welcome.

### New

- **Your devices in one list**, with model, firmware and last battery level, and **Look for devices** to find JBL devices nearby or already paired.
- **Battery** of the speaker, or of each earbud and the case where the device reports them, with a charging mark.
- **Headphones and earbuds (2024-2025 models):** noise control (off, noise cancelling, Ambient Aware, TalkThru), touch gestures, auto power-off and voice prompts.
- **Older headphones:** battery, firmware and noise cancelling on/off.
- **Speakers:** rename, feedback tones, PartyBoost, Auracast on/off, stereo channel, Find, and Indoor/Outdoor sound on the BoomBox.
- **Firmware update:** Check for update asks Harman's public file server for your model (only the product id is sent); the download must match its MD5. You can also install your own file with its MD5 and version. Only newer firmware, on a device charged to 50 % or more and idle, with progress, Stop and continuing where it stopped. Supported for speakers and 2024-2025 headphones; other models get the version check only. Firmware writing has not been tested on a real device yet.
- **Needs confirming on your device:** settings whose exact commands couldn't be established for a model are shown switched off; SoundCtrl never sends guessed commands.
- **Privacy:** no account, no JBL cloud, no analytics; online only when you tap Check for update; no location permission; app lock, Hide in screenshots and Hide in recent apps.

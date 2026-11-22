# Changelog

What changed in each SoundCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/SoundCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.6.1 (2026-11-22)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. Tour Pro 3 controls, the order of connection attempts and auto reconnect are unchanged.

### Fixed

- **Cancelling a connection no longer leaves the earbuds' control channel open.** If you tapped Disconnect on the "Reconnecting" screen, opened another device or used "Forgot PIN?" while SoundCtrl was still connecting to earbuds over classic Bluetooth (Tour Pro 3 and other Airoha earbuds), the connection that was being set up could still complete in the background and then stay open with nobody reading it. The earbuds kept that channel busy, so the next connection (from SoundCtrl or the JBL app) could fail until Android cleaned it up. SoundCtrl now closes a connection that is still being set up when the device session ends.

### Security

- **"Forgot PIN?" now asks for your phone's screen lock first.** Before, anyone holding your unlocked phone could tap "Forgot PIN?" on SoundCtrl's lock screen and reset it. Now the reset first asks for your phone's own PIN, pattern, password or fingerprint. If you cancel or it fails, nothing is reset. If your phone has no screen lock at all, the reset works as before.

### Project

- CI now runs the unit tests on every build (`:app:testDebugUnitTest`), with a new check that both clean-up guards stay in place.

## v0.6.0 (2026-11-22)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

**Not tried on a Tour Pro 3 yet:** every new setting below comes from reading the JBL Headphones app (5.38.2) for this model. Please test them one by one (see the test list in PROTOCOL.md §7.4) and report what the earbuds do.

### New (JBL Tour Pro 3 only)

These appear only for the Tour Pro 3 (product id 20f2, and 20fd for its case) with the AB158x chip SoundCtrl reads from the earbuds. Other Airoha models (Live Buds 3, Tune 770NC, …) don't get them, because the JBL app may use other commands or tables for those. Each one is read from the earbuds when you open the device or tap **Refresh**, so the device page shows the earbuds' current setting; a change that the earbuds don't accept shows the reason line (no answer, refused with status, or unexpected answer), the same way as the other Airoha settings since 0.5.1.

- **Touch controls per earbud:** for each earbud you pick one of the four controls the JBL app offers for the Tour Pro 3: **Ambient sound** (tap: noise cancelling/Ambient Aware, double tap: TalkThru, hold: voice assistant), **Volume** (tap: louder, double tap: softer, hold: voice assistant), **Playback** (tap: play/pause, double tap: next, triple tap: previous, hold: voice assistant) or **None**. If the earbuds hold something else, the row says **Custom**. SoundCtrl writes the controls the way the JBL app does: both earbuds' key maps on the earbud your phone talks to, then the same on the other earbud through it. That's why this needs both earbuds connected and read first; otherwise the row asks you to put both in and tap Refresh.
- **Voice prompts:** Off, Tones only or Spoken. "Off" switches the prompts off; "Tones only" and "Spoken" set the prompt type. Note: the JBL app never sends an explicit "prompts on" command, it only sets the type, so after choosing Tones or Spoken SoundCtrl reads the on/off state back and shows what the earbuds report. "Spoken" uses the first language stored on the earbuds; changing the prompt language (which downloads a language file in the JBL app) isn't offered.
- **Adaptive noise cancelling** on/off.
- **Spatial sound:** Off, Fixed or Head tracking.
- **Hi-Res audio (LDAC)** on/off.
- **Voice Aware** (hear your own voice during calls) on/off, with Low, Medium or High.
- **Smart Talk:** Off, or on with 5, 15 or 20 seconds.
- All new texts in English, Dutch, French and German.

### Not included (still "Needs confirming" or not offered)

- **EQ presets and custom EQ** stay switched off: the JBL app sends EQ to these earbuds as a full band curve through Airoha's PEQ functions, calculated from its own EQ model, and that wasn't pinned down byte for byte.
- **Noise-cancelling and Ambient Aware levels**, auto compensation, ear-tip fit test and "check my best fit" (they need sound measurements), smart audio and video / low-latency mode (it uses a per-model bitrate table and makes the earbuds reconnect), the smart case's own settings (screen, wallpapers, notifications, Auracast from the case), Silent Now, left/right balance, volume limiter, PersoniFi, rename and multipoint: not in this version. The Tour Pro 3 page in the JBL app has no rename or multipoint setting.
- **Firmware writing** stays check-only for Airoha earbuds.

## v0.5.2 (2026-11-22)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Fixed

- **Tour Pro 3: Ambient Aware and TalkThru were swapped.** On a real Tour Pro 3, choosing Ambient Aware in SoundCtrl 0.5.1 turned on TalkThru on the earbuds, and choosing TalkThru turned on Ambient Aware. The numbers SoundCtrl sends for these two modes came from reading the JBL app's code, and that reading turned out to be the wrong way round for this model. For the Tour Pro 3 SoundCtrl now sends them the other way round, and reads the current mode back the same way, so the selected mode on the device page matches what you hear (also when the earbuds report a change themselves, for example after you use the touch controls). Other Airoha models (Live Buds 3, Tune 770NC, …) keep the old order, because it hasn't been checked on them yet; please report it if those are swapped too.

### New

- **SoundCtrl reconnects by itself.** Until now, if the connection dropped (earbuds back in the case and out again, Bluetooth switched off and on, phone out of range), the device stayed disconnected until you opened it again, and "Reconnect to the last device" tried only once when SoundCtrl started. Now:
  - While a device page is open and the connection drops, the page shows **Reconnecting…** instead of an error, and SoundCtrl tries again after 2, 5 and 10 seconds and then every 30 seconds. As soon as your phone itself connects to the device again (Android reports it), SoundCtrl connects at once instead of waiting for the next try. A **Disconnect** button on that page stops it.
  - If the device can't be reached when you open it (for example the earbuds are still in the case), the "Couldn't connect" page now says SoundCtrl will connect by itself, and it does so as soon as your phone connects to the device. **Try again** still tries at once. This also covers **Reconnect to the last device** when SoundCtrl starts.
  - Each reconnection starts a completely new connection and reads everything again (for Airoha earbuds: the chip, the status updates and all settings), the same as when you open the device. It works for every kind of device: earbuds and headphones over classic Bluetooth, and speakers and headphones over Bluetooth Low Energy.
  - It only happens while SoundCtrl is open on screen. There is no background service, no new permission (the existing "Nearby devices" permission is enough, still no location), and SoundCtrl stops listening for Bluetooth connections when you leave the app. Coming back to SoundCtrl tries again at once.
  - Not while a firmware update is being sent: then SoundCtrl waits until the update is over before reconnecting. **Disconnect** (and Forget) still ends the connection for good.
  - Translated into Dutch, French and German.

## v0.5.1 (2026-11-22)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Fixed

- **Airoha earbuds (Tour Pro 3): settings weren't accepted.** On a real Tour Pro 3, SoundCtrl 0.5.0 showed battery and firmware correctly, but the current noise-control mode stayed empty and every change (noise control, auto power-off, auto play/pause, find my buds) ended in "The device didn't accept that". The exact cause isn't known yet. This version makes three changes that follow the official JBL app more closely:
  - Right after connecting it now turns on the earbuds' status updates, the first thing the JBL app does once it is connected and the one step SoundCtrl 0.5.0 left out.
  - It waits up to 4 seconds (was 3) for the earbuds to answer a change, and 3 seconds (was 2.5) when reading.
  - For noise control, a status update from the earbuds showing the mode you picked now also counts as done.
- **The error message now says why.** When an Airoha setting fails, the message under "The device didn't accept that" tells which case it was: it couldn't be sent, there was no answer (and which other messages came in meanwhile), the earbuds refused it (with their status code), or the earbuds answered in a way SoundCtrl didn't expect (with the first bytes of that answer). The command is named by its number, for example "RACE 0x0E06" for noise control. Please send a screenshot of that message if it still happens. Translated into Dutch, French and German.
- Still not tested on a real device by the developer; this release is meant to find out what the Tour Pro 3 answers.

## v0.5.0 (2026-11-22)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### New

- **Real control of the JBL Tour Pro 3 and other Airoha earbuds and headphones.** Until now SoundCtrl could only read the firmware version of these models and every setting showed "Needs confirming on your device". It now talks to them the way the official JBL Headphones app does: over classic Bluetooth, through Airoha's own serial (RFCOMM) channel, instead of only Bluetooth Low Energy. When you open a device whose model is an Airoha model, or a paired JBL device whose model isn't known yet, SoundCtrl first tries that channel and falls back to Bluetooth Low Energy if the device doesn't offer it. No new permission is needed: the existing "Nearby devices" permission covers it, and there is still no location permission.
- **The chip inside decides what you get.** Right after connecting, SoundCtrl reads the chip name and model from the device, as the JBL app does, and only shows controls whose commands were established for that chip:
  - **Battery:** left and right earbud (with a charging mark) and the charging case, on Airoha models except those with an AB1562 chip and the Quantum 910 family.
  - **Noise control:** off, noise cancelling, Ambient Aware and TalkThru, on AB1568 and AB158x chips (the Tour Pro 3's family; SoundCtrl reads which chip your model has when it connects).
  - **Auto power-off:** 30 minutes, 1 hour, 2 hours or never, the same choices the JBL app offers for earbuds (AB1568 and AB158x chips).
  - **Auto play/pause:** pauses when you take an earbud out and plays again when you put it back (AB1568 and AB158x chips).
  - **Find my buds:** two switches to make the left or right earbud play a loud sound, and to stop it. Take the earbuds out of your ears first. If only one earbud is connected, only that one is offered.
- Touch gestures, voice prompts, EQ, spatial sound, Hi-Res/LDAC, low latency, adaptive noise cancelling levels and the smart case settings are not established yet for these models and stay "Needs confirming on your device". Firmware updates for Airoha models are still "check only".
- **Not tested on a real device yet.** The commands come from reading the JBL app's code. A Bluetooth log from a real Tour Pro 3 confirmed that the JBL app uses this classic Bluetooth channel and that the earbuds answer in the formats SoundCtrl expects, but SoundCtrl itself hasn't been run against the earbuds. Please report what works and what doesn't.

## v0.4.2 (2026-11-22)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Removed

- **Protocol log.** The Troubleshooting group in Settings is gone, with its "Protocol log" switch and the "Open protocol log" screen (the list of Bluetooth messages, Copy, Share and Clear log). SoundCtrl no longer keeps any record of the messages it sends to or receives from your devices, not even in memory. If you had the switch on, the saved setting is dropped the next time a setting changes; a settings file exported by an earlier version that still contains it imports without an error and the entry is ignored. Nothing else changes: connecting, device controls, firmware updates, the app lock and the settings file work as before.
- **Leftover log lines stripped from release builds.** The release build now also removes warning, error and other system-log calls from the code it ships (SoundCtrl itself had none), so nothing ends up in the phone's system log.

## v0.4.1 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Fixed

- **Footer badges translated.** The "Google-free · Telemetry-free · arm64" line at the bottom of the You page was still in English in Dutch, French and German; it now reads "Google-vrij · Zonder telemetrie", "Sans Google · Sans télémétrie" and "Google-frei · Ohne Telemetrie", like the other Ctrl apps.

## v0.4.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### New

- **SoundCtrl in French and German, following the phone's language.** With your phone set to French, SoundCtrl is now in French (formal "vous", as Android itself uses); set to German, it is in German (informal "du", as modern Android apps use). Every screen, message and the app lock are translated. Product and feature names (JBL, PartyBoost, Auracast, Ambient Aware, TalkThru, PersoniFi), units and technical terms (MD5, MTU, EQ) are not, and like the Dutch version the brand line, badges, licence name and source link stay in English. Android's per-app language setting (Settings > Apps > SoundCtrl > Language) now offers English, Dutch, French and German, so you can also pick one for SoundCtrl alone.

## v0.3.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### New

- **Protocol log you can share by hand.** Settings has a new Troubleshooting section with a **Protocol log** switch (off by default). While it's on, every Bluetooth message SoundCtrl sends to your device and gets back is kept, as hex with the time and direction, in memory only: nothing is written to the phone's storage and the log is gone when SoundCtrl closes, when you clear it or when you turn the switch off. **Open protocol log** shows the newest first, and **Share** or **Copy** hands the text (with SoundCtrl's version, the Android version and the device's model, product id and firmware at the top) to an app you choose. SoundCtrl never sends it anywhere itself. Long messages (firmware data) are cut after 48 bytes with their full length noted, and the newest 1,500 messages are kept.
- **Firmware downloads resume.** When the connection to Harman's file server drops during a firmware download, SoundCtrl now tries again by itself up to three times (after 2, 4 and 6 seconds), each time asking the server only for the part that is still missing (an HTTP `Range` request with `If-Range`, so a file that changed on the server in the meantime is fetched whole rather than glued onto the old part). If it still can't finish, the update page says where it stopped and offers **Resume download**. If the server doesn't support partial downloads, it simply starts over. The part already received stays in memory only (firmware is still never written to disk), so closing SoundCtrl means starting again, and the MD5 check on the finished file is unchanged.
- **Export and import settings.** Settings has a new Settings file section: **Export settings** saves SoundCtrl's settings (reconnect to the last device, lock timing, hide in screenshots and recent apps, protocol log) as a small JSON file wherever you choose; **Import settings** reads one back, shows exactly which settings will change (old → new) and asks before replacing them. The file never holds your devices, Bluetooth addresses or anything of the app lock. An import can turn Hide in screenshots and Hide in recent apps on but never off, and can make the lock ask sooner but never later.

- **SoundCtrl in Dutch.** With your phone set to Dutch, SoundCtrl is now in Dutch (written for Flanders, informal "je"): every screen, message, notification and the app lock. It follows the phone's language, so there's no language setting in the app; otherwise it stays in English. Model names, units, product ids and technical terms (MD5, MTU, PartyBoost, Auracast) are not translated. The reasons a firmware transfer stops ("the speaker stopped answering" and so on) used to be English text inside the code; they are now translated like everything else.

### Not built

- **Equaliser:** still off. Its commands for your models haven't been confirmed on a real device (see PROTOCOL.md: the EQ structure is known, but presets are sent as a full band curve from per-model tables, plus a calibration value whose formula wasn't pinned). SoundCtrl doesn't send guessed bytes, so the EQ rows keep showing "Needs confirming on your device".

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

# SoundCtrl

**One private app for your JBL headphones, earbuds and speakers, in place of JBL Headphones and JBL Portable.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/SoundCtrl?label=download)](https://github.com/VoidedKN0X/SoundCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

SoundCtrl finds your JBL devices over Bluetooth, shows their battery and firmware, and changes their settings: noise cancelling, Ambient Aware and TalkThru, touch gestures, auto power-off, voice prompts and feedback tones, PartyBoost and Auracast. It can check Harman's public file server for new firmware and install it on supported models. No account, no JBL cloud, no analytics, and it goes online only when you tap **Check for update**.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

> **Status: early.** SoundCtrl was built from how the official JBL apps talk to the devices, but it has **not been tested on a real JBL device yet** (Tested on: none yet). Firmware writing in particular is untested. Please report what works and what doesn't on your model.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/SoundCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## Features

- **Your devices in one list:** headphones, earbuds and speakers, remembered on this phone with their model, firmware and last battery level. **Look for devices** finds JBL devices nearby.
- **Battery:** the speaker's level, or left and right earbud and the case where the device reports them, with a charging mark.
- **Headphones and earbuds (2024-2025 models, "protocol 4"):** noise control (off, noise cancelling, Ambient Aware, TalkThru), touch gestures for each tap and swipe the device reports, auto power-off and voice prompts.
- **Older headphones:** battery, firmware and noise cancelling on/off.
- **Speakers:** rename, feedback tones, PartyBoost, Auracast on/off, stereo channel (left, right or both), Find (plays a sound), and Indoor/Outdoor sound on the BoomBox.
- **Firmware update:** **Check for update** asks Harman's public file server for your model; the download is checked against its MD5. You can also install a file you have, if you give its MD5 and version. Only newer firmware is installed, on a charged (50 % or more) and idle device, with progress, Stop and continuing where it stopped. A download that breaks off is retried by itself and can be resumed: only the missing part is fetched again.
- **Protocol log:** turn it on in Settings to keep the Bluetooth messages SoundCtrl sends and receives (in memory only), then share or copy them by hand, for example for a bug report.
- **Settings file:** export SoundCtrl's settings to a file and import them again (no devices, no addresses, nothing of the app lock).
- **Honest about gaps:** anything whose exact commands couldn't be established for your model shows as **Needs confirming on your device** and is switched off. SoundCtrl never sends guessed commands.
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on).
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, high contrast, one violet accent, Space Grotesk and JetBrains Mono.

Not part of SoundCtrl: PersoniFi, JBL or Harman accounts and cloud features, product pictures, Spotify and light-show features, and per-app EQ.

## Which devices

SoundCtrl knows every model in the JBL apps' own lists (about 160). What it can do depends on how the device is controlled:

| Devices | Settings | Firmware update |
| --- | --- | --- |
| Speakers on the older protocol (Flip 5/6, Charge 5/6, Pulse 4/5, Xtreme 3/4, BoomBox 2/3, GO 4, Clip 5, …) | Rename, feedback tones, PartyBoost or Auracast, channel, Find | Check and install (untested) |
| Newer speakers (GO 5, Xtreme 5, Grip, BoomBox 4, Pulse 6, Clip 6, Charge/Flip Essential 3 SE) | Rename, feedback tones, Auracast | Check and install (untested) |
| Older speakers (Charge 3/4, Flip 4, Pulse 3, Xtreme 2, BoomBox 1, Flip 3, Xtreme) | As above where the protocol matches | Check only: "Update from this app isn't available for this model yet" |
| 2024-2025 headphones and earbuds (Tour One M3/M4, Tour Pro 4, Live 680NC/780NC, Live Buds/Beam/Flex 4, Tune 530BT, Tune/Wave/Vibe 3, Endurance, …) | Noise control, gestures, auto-off, voice prompts | Check and install (untested) |
| Other BES, Realtek and Airoha headphones and earbuds | Battery, firmware; ANC on/off on some | Check only: not available yet |
| Everest / Reflect Aware (Avnera) | Not available | Not available |

[PROTOCOL.md](PROTOCOL.md) has the details, model by model and feature by feature, and marks what was verified in the JBL apps' code and what is inferred.

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years), with Bluetooth Low Energy.
- Works on any Android ROM as a normal app: stock Android, LineageOS, GrapheneOS, CalyxOS, /e/OS and the phone makers' own systems. It doesn't need root.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google.

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/SoundCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `SoundCtrl-v0.1.0.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings and device list are kept.

**Automatic updates:** on your phone, tap the Obtainium button above. Install [Obtainium](https://github.com/ImranR98/Obtainium) first; the button adds SoundCtrl and installs every new release for you. If the button doesn't open the app, add `https://github.com/VoidedKN0X/SoundCtrl` in Obtainium by hand.

## Getting started

- **Allow nearby devices** when asked, and turn Bluetooth on.
- **Look for devices:** switch your JBL device on. Headphones already paired with the phone show up straight away. Tap one to connect.
- **A device:** battery and firmware at the top, then its settings. **Refresh** reads everything again, **Disconnect** frees it for another phone.
- **Firmware update** (on the device page): **Check for update**, then **Download**, then **Install**. Read the warning first: firmware writing has not been tested on a real device yet.
- **Settings** (profile picture): reconnect to the last device when SoundCtrl opens, forget devices, privacy, the protocol log and the settings file.

Close the JBL app on other phones first: a device usually talks to one app at a time.

## Firmware updates

- **Check for update** sends one anonymous HTTPS request to Harman's public file server (`storage.harman.com` or `appstorage.onecloud.harman.com`). The address contains only your model's product id (for example `…/ota/2101/ota_config.json`). Nothing else about you, your phone or the device is sent.
- The image is downloaded from the same server and must match the MD5 the server gives. If the connection drops, SoundCtrl tries again by itself up to three times, each time asking only for the part that's still missing (an HTTP range request, with `If-Range` so a file that changed on the server is fetched whole). After that, **Resume download** carries on from where it stopped. The part already received is kept in memory only, never on disk, so closing SoundCtrl starts the download over. A file you load yourself must match the MD5 you type in, and you give its version, so SoundCtrl can refuse anything that isn't newer.
- Before sending, SoundCtrl checks: the version is newer than the device's, every battery is at 50 % or more, and (speakers) nothing is playing and the speaker isn't in a PartyBoost, Auracast or stereo group.
- The transfer runs with a notification and can be stopped. The device decides what to ask for next, so after a stop or a dropped connection the next attempt continues where it stopped. The device checks the image (CRC or signature) before it switches to it.
- **Not tested on a real device yet.** The confirm dialog says so too. If in doubt, use the JBL app for updates until someone has confirmed your model.
- Some chip families (Airoha, the BES update library, Harman's older update manager, Avnera) aren't supported for writing: SoundCtrl shows "Update from this app isn't available for this model yet" and only checks the version.

## Is it safe to install?

- **Open source.** All of SoundCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks the app's permissions and that it has no Google libraries. The signing key is kept away from the build: one job builds the APK without any secrets, and a separate job that runs no code from this repo signs it and checks the signature against the fingerprint below.
- **Always signed with the same key.** Android refuses to install an update signed with a different key over SoundCtrl. The SHA-256 fingerprint of SoundCtrl's signing certificate is:

  ```
  C2:16:33:34:A0:D0:CB:B4:EA:DA:35:AF:5B:FD:0D:6B:BB:16:DB:C1:2D:6B:FD:21:75:C3:54:A9:25:75:08:33
  ```

  Advanced users can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).

**Check a download.** Every release is built and published by GitHub Actions, never on a personal computer:

- The release notes end with the APK's **SHA-256** checksum. Compare it with `sha256sum SoundCtrl-vX.Y.Z.apk` (or `Get-FileHash` on Windows).
- Each release APK has a signed **build provenance attestation**. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify SoundCtrl-vX.Y.Z.apk -R VoidedKN0X/SoundCtrl` proves it was built by this repo's workflow from the tagged commit.
- The signing-certificate fingerprint above stays the same for every release.

**Permissions.** SoundCtrl has **no location permission**.

| Permission | What SoundCtrl uses it for |
| --- | --- |
| Nearby devices (Bluetooth scan, flagged "never for location") | Finding JBL devices. Android can't use it to work out where you are. |
| Nearby devices (Bluetooth connect) | Talking to your JBL devices. |
| Internet | Only **Check for update** and the firmware download, from Harman's public file server, when you tap them. Every other address is refused in the code. |
| Foreground service (connected device) | Keeping a firmware update going, with its notification. Only during an update. |
| Notifications | The update progress notification. |
| Use biometric hardware | Optional fingerprint unlock for the app lock. |
| Hide other apps' floating windows over the PIN screen | While the lock or PIN screen is showing, other apps' floating windows are hidden over it. |
| App-internal broadcasts (`com.vknxworks.soundctrl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | Added by AndroidX so that only SoundCtrl itself can send to its own internal receivers. |

See [Privacy](#privacy) for what happens to your data.

## App lock

Turn the app lock on under **Settings > Privacy and security > App lock**. It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours.
- **The PIN is never stored.** It's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to forget SoundCtrl's device list, reset its settings and turn the lock off. Nothing on your devices changes.
- **A firmware update keeps going** when the app locks.

## Privacy

**Telemetry-free.** SoundCtrl has no analytics, crash reporting, usage stats or tracking of any kind, and no account.

- **Online only on request.** The only network traffic is **Check for update** and the firmware download, both when you tap them, both to Harman's public file server over HTTPS. The request contains only the model's product id. In code, SoundCtrl refuses every host except `storage.harman.com` and `appstorage.onecloud.harman.com`, allows HTTPS only, trusts only the phone's system certificates and follows redirects only within those two hosts. CI checks that the APK has no other network or location permission.
- **Bluetooth only for your devices.** Scanning looks only for JBL adverts (Harman's Bluetooth company ids and service data) and paired devices whose name contains "JBL". The scan permission is flagged "never for location".
- **Stays on the phone:** the list of your devices (name, Bluetooth address, model, last firmware and battery) and the settings live in SoundCtrl's private storage. The device list is also encrypted there (AES-256-GCM, with a key protected by the phone's secure hardware); the settings are not. Android backups don't include them, and SoundCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- **No JBL cloud:** no sign-in, no PersoniFi, no product pictures, no app configuration downloaded from Harman.
- **Protocol log:** off by default. When on, the messages to and from your device are kept in memory while SoundCtrl runs and are gone when it closes. SoundCtrl never sends the log anywhere; Share and Copy hand it to an app you pick. It can contain your device's name and serial number.
- **Settings file:** holds only SoundCtrl's settings. Importing one can make SoundCtrl stricter (hide switches on, a shorter lock time) but never less private, and it never touches the app lock.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## How SoundCtrl is made

SoundCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

How the devices are controlled was worked out from the official JBL apps, for compatibility with devices you own; SoundCtrl contains no code or assets from them. [PROTOCOL.md](PROTOCOL.md) documents it. JBL and Harman are trademarks of their owners; SoundCtrl is not made or endorsed by them.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 or newer and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/SoundCtrl.git
   cd SoundCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`. `./gradlew testDebugUnitTest` runs the protocol tests.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

**Dependency verification.** Every library and build plugin download is checked against the sha256 checksums in `gradle/verification-metadata.xml`, and the build fails on any mismatch. When you change a dependency, a plugin or the Gradle version, run the **Dependency verification metadata** workflow (Actions tab, *Run workflow*), check the diff of the file it prints and commit it in the same push.

## Support the project

SoundCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). Please report bugs and ideas with the [bug report and feature request forms](https://github.com/VoidedKN0X/SoundCtrl/issues/new/choose); reports of what works on your model are especially welcome. Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CallCtrl](https://github.com/VoidedKN0X/CallCtrl) | Phone app: dialer, call history and in-call screen |
| [CameraCtrl](https://github.com/VoidedKN0X/CameraCtrl) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [GalleryCtrl](https://github.com/VoidedKN0X/GalleryCtrl) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| [MailCtrl](https://github.com/VoidedKN0X/MailCtrl) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| [RecorderCtrl](https://github.com/VoidedKN0X/RecorderCtrl) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [ShutterCtrl](https://github.com/VoidedKN0X/ShutterCtrl) | Remote control and photo transfer for Canon cameras |
| **SoundCtrl** (this app) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| [TapoCtrl](https://github.com/VoidedKN0X/TapoCtrl) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

SoundCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).

# Changelog

What changed in each SoundCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/SoundCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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

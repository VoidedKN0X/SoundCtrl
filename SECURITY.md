# Security policy

Thank you for helping keep SoundCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/SoundCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of SoundCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

SoundCtrl has no server and no account. Its only network traffic is the firmware check and download from Harman's public file server, when you tap them. So these count:

- Any network connection to a host other than `storage.harman.com` and `appstorage.onecloud.harman.com`, over anything but HTTPS, or without a tap on Check for update or Download
- Anything sent in that request beyond the model's product id
- Firmware being sent to a device without its MD5 matching, without your confirmation, or when it isn't newer
- Another app being able to start a firmware update or send commands to your devices through SoundCtrl
- Your device list or Bluetooth addresses leaving the phone
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN
- Anything that lets someone run code or read files they shouldn't

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
C2:16:33:34:A0:D0:CB:B4:EA:DA:35:AF:5B:FD:0D:6B:BB:16:DB:C1:2D:6B:FD:21:75:C3:54:A9:25:75:08:33
```

The key never meets the build: the job that runs Gradle (and with it every plugin and library) has no secrets and produces an unsigned APK. A separate job that checks out nothing and runs no build code signs that APK, then refuses to pass it on unless it is signed by exactly this certificate and its contents are unchanged.

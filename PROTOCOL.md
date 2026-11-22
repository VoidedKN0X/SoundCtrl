# SoundCtrl protocol notes

How JBL headphones, earbuds and speakers are controlled and updated, as far as it could be established from the two official apps, for interoperability with your own devices. Apart from the transport facts in §7.1, nothing here was captured from a real device: **SoundCtrl has not been tested on a real JBL device yet.**

Sources (reverse engineered with REA / JADX, class and method names only, no code copied):

- **JBL Portable** (`com.harman.ble.jbllink`, 6.9.33): the speaker app. Readable package `com.harman.sdk`.
- **JBL Headphones** (`jbl.stc.com`): the headphone app. Packages `com.harman.protocol4`, `com.harman.bluetooth`, `com.harman.cmdctrl`, `com.airoha.*`, plus obfuscated classes (`gn.*`, `ym.*`, `in.*`, `qi.*`).
- The product lists both apps ship (`assets/product_list_config.json`, `assets/product_list.json`, `assets/AppConfig.json`). `app/.../proto/ProductRows.kt` is generated from them (product id, name, chip, control protocol, update family, update source).

Markers: **[V]** verified in the app's code; **[I]** inferred (SoundCtrl's own choice or a reading of code that isn't fully unambiguous). All multi-byte values are little-endian unless the text says big-endian (BE).

## 1. Transport and discovery

| Item | Value | |
| --- | --- | --- |
| Harman GATT service | `65786365-6c70-6f69-6e74-2e636f6d0000` | [V] speaker `AppConfig` (bleRxTxUUID), headphone `com.harman.bluetooth.constantsimp.Constants` |
| Notify (device → phone) | `…2e636f6d0001` | [V] same |
| Write (phone → device) | `…2e636f6d0002`, write with response | [V] `GattControllerImpl.doWriteCharacteristic` |
| Other services carrying the same two characteristics | a per-product BR/EDR GATT service (`GattControllerImpl.findBrEdrUUidByPid`), or GAP `1801` | [V]; SoundCtrl simply searches every service for the two characteristics [I] |
| MTU | the apps request 512 | [V] `GattControllerImpl` (requestMtu 512), headphone `BleDevice.requestMtu` |
| Airoha RACE service | `5052494d-2dab-0341-6972-6f6861424c45`, TX `43484152-2dab-3241-…`, RX `43484152-2dab-3141-…` | [V] `com.airoha.liblinker.constant.UuidTable` |
| SPP (classic RFCOMM), speakers | `00001101-…` | [V] `AppConfig.sppUUID`; SoundCtrl doesn't use it for speakers [I] |
| Airoha SPP (classic RFCOMM) | `00000000-0000-0000-0099-AABBCCDDEEFF`, secure socket found through SDP | [V] `com.airoha.liblinker.constant.UuidTable.AIROHA_SPP_UUID`, `SppController.createRfcomm`; used by SoundCtrl for Airoha devices, see §7 |
| Advertising | manufacturer data company `0x0057` (speakers) or `0x0ECB` (headphones), or service data `FDDF` (protocol 4), `DFFD`, `FC69` | [V] `DefaultScanFilter`, `SpeakerBleBroadCastParser.parseBleScanRecordData`, headphone `qi.n`/`qi.b` |
| Product id in the advert | first two bytes, little-endian (e.g. `4f 20` = `204f`, JBL Flip 6) | [V] `SpeakerBleBroadCastParser.parseAdvPacketFormat2/6` |

One notification = one frame [V] (`GattControllerImpl…onCharacteristicChanged`).

## 2. The "AA" protocol (speakers before protocol 4, older headphones)

Frame: `AA cmd len payload`, one-byte length [V] (`BaseCommand.data`). Replies longer than 127 bytes, and commands `0x94`, `0x96`, `0x99`, use a two-byte big-endian length [V] (`BaseCommandForLongBytes`, `PacketFormat.LONG_BYTES_COMMAND`). No checksum [V]. A setting is acknowledged with `AA 00 02 <cmd> <status>`, status 0 = OK [V] (e.g. `SetEQModeCommand.onReceive`).

### 2.1 Speakers (`com.harman.sdk.command.*`, ids in `PacketFormat`)

| Function | Bytes | |
| --- | --- | --- |
| Device info | `AA 11` → one or more `AA 12 [index] (token value)*` | [V] `GetDevInfoCommand`, `CommandProcessor.processVimicroDeviceInfoRes` |
| Tokens | `C1` name (len + UTF-8), `C2` serial (len + text), `42` product id (2 bytes), `43` model/colour id, `44` battery (bit 7 charging, bits 0-6 %), `45` linked count, `46` channel, `47` audio source, `48` MAC (6), `4C` Auracast support (1), `4D` second speaker's firmware (4), `4F`/`51` stereo link (1), `50` DAB firmware (4), `53` mic (1) | [V] same method; token sizes read from its substring lengths |
| Battery only | `AA 13 [index 44]` → `AA 12` | [V] `ReqBatteryCommand` |
| Firmware version | `AA 41` → `AA 42 bytes`; each byte's two hex digits read as a decimal number (`01 02 03` = 1.2.3) | [V] `ReqVerCommand.formatVersion` |
| Rename | `AA 15 [index C1 len UTF-8]`, at most 16 characters | [V] `SetDeviceNameCommand` |
| Feedback tones | read `AA 65` → `AA 66 [0/1]`; set `AA 67 [0/1]` | [V] `ReqFeedbackToneCommand`, `SetFeedbackToneCommand` |
| Sound mode (BoomBox) | read `AA 61` → `AA 62 [m]`; set `AA 63 [m]`, 0 indoor, 1 outdoor | [V] `ReqEQModeCommand`, `SetEQModeCommand`, `ConnectPlusDashboardActivity.logSettingSoundMode` |
| PartyBoost / Auracast link | `AA 6A [0 off, 1 PartyBoost/Connect+, 2 Auracast]` → `AA 6B [role]` (0 normal, 1 master, 2 slave, 101/102 TWS) | [V] `SetLinkModeCommand`, `DeviceRole.getRole` |
| Stereo channel | `AA 15 [index 46 ch]`, 0 both, 1 left, 2 right | [V] `SetChannelCommand` |
| Find (identify) | `AA 31 [index]` | [V] `IdentificationCommand` |
| Preset/custom EQ (newer speakers) | `AA 6E` (simple EQ), `AA 97` (advanced EQ) | **not implemented**: the band/category tables per model come from the app's config files [I] |
| Auracast stereo groups | `AA A0` TLV `01 action, 02 type, 03 role, 04 channel, 05 group id, 06 name, 07 partner, 08 = 20` | [V] layout from `SetAuraCastGroupCommand`, but the values come from the group set-up flow; **not implemented** |

### 2.2 Older headphones (headphone app, `CommandHeader`, `CmdGen`, `HandleParse`)

| Function | Bytes | |
| --- | --- | --- |
| Device info | `AA 11` → `AA 12` fixed layout: name at frame bytes 3-18, product id bytes 20,19, model 21, battery 22 (bit 7 charging), firmware 29.30.31 | [V] `HandleParse.parseDeviceInfo` (from the research pass) |
| ANC on/off | `AA 31 [bool]` | [V] `CmdGen` |
| Ambient Aware mode | `AA 32 [mode]` | mode values not established: **disabled** |
| EQ preset / custom | `AA 40 [index]`, `AA 41 …` | index meaning per model not established: **disabled** |
| Auto-off | `AA 33 [(on<<7) | minutes]` or a V2 form `[on][u16]` per model | which form per model not established: **disabled** |
| Gestures | `AA 71 [enable type action]` | per-model ids not established: **disabled** |

## 3. Protocol 4 (newer speakers and headphones)

Frame [V] (speaker `AssembleCmd.generateCmdList`, headphone `AssembleCmd4.generateCmdList`):

```
00 DD | cmdId (LE16) | packetCount | packetIndex | payloadLen (LE16) | payload (max 490 per packet)
```

`01 DD` (identifier 0xDD01) forwards to the second earbud/speaker [V]. Commands [V] (`HeaderCommandID`): `0001` GET, `0002` SET, `0003` notification to app, `0004` notification to device, `0101` START_OTA, `0102` SEND_OTA, `0103` STOP_OTA, `0104` OTA notification. GET payload: feature ids (LE16 each). SET payload: `featureId (LE16) len (LE16) value`, several in one SET allowed. Replies: packet 0 carries a status (LE16, 0 = OK) at bytes 8-9; the payload is TLVs `id len value`; payloadLen must equal frame length - 8 [V] (`CommandProcessor.processP4Command`, `DeviceInfoList4Cmd.parse`).

### 3.1 Features used by SoundCtrl

| Feature | Id | Value | |
| --- | --- | --- | --- |
| Product id | `0004` | 2 bytes | [V] (byte order not pinned; SoundCtrl tries both against its table [I]) |
| Device name | `0006` | UTF-8 | [V] |
| Firmware | `000C` | 3 bytes, one number each | [V] (the headphone app prints them as hex digits, the speaker app as decimal; SoundCtrl shows decimal [I]) |
| Battery left / main | `000D` | bit 7 charging, bits 0-6 % | [V] |
| Battery right | `000E` | same | [V] |
| Case battery / firmware | `1F03` / `1F01` | same / 3 bytes | [V] |
| Auto power-off | `0012` | LE16: bit 15 on, bits 0-14 seconds; the app sends minutes × 60; `7FFF` shown as 30 min | [V] `StatusLevelSize2Feature`, `General4Control.setAutoPowerOffStatus` |
| ANC / Ambient Aware / TalkThru | `0018` / `0019` / `001A` | byte: bit 7 active, bits 0-6 level | [V] `StatusLevelSize1Feature` |
| Ambient sound control (2025 products) | `0049` | `FF` on, `7F` off | [V] `TuneFlex3Control.setAncSetting` and siblings |
| Voice prompts / speaker feedback tone | `0C40` | bit 7 spoken prompts (0 = tones only), bits 0-6 volume; speakers `80`/`00` | [V] `General4Control.setVoicePromptStatus`, `PortableControl.setFeedBackToneStatus` |
| Gestures | `0F00`-`0F0D` | one action byte (`GestureFunType`: 0 none, 1 vol+, 2 vol-, 3 Ambient, 4 TalkThru, 5 next, 6 previous, 7 ANC, 8 play/pause, 9 ANC/Ambient/off, 11 ANC/Ambient, 12 ANC/off, 13 Ambient/off, 15 TalkThru/off, …) | [V] `GestureFeature`, `HmProtocol4BaseControl.getFeature4ByGestureId`, `General4Control.setGestureStatus` |
| Speaker Auracast mode | `2004` | 0/1 | [V] `PortableControl.changeAuraCastMode` |
| EQ | `0E01` query, `0E02` EQ struct, `0E7F` Airoha PEQ | struct known, but presets are applied by sending the full band curve from the app's EQ model and, for BES chips, a calibration value whose formula wasn't pinned: **disabled** | [V]/[I] `EQInfoFeature`, `General4Control.setEQSettings2` |
| Rename (headphones) | `0006` | the headphone app never sets it | not supported [I] |

Noise control bytes [V] (`General4Control.setAncSetting`): ANC on `18 00 01 00 FF`, Ambient on `19 00 01 00 FF`, TalkThru on `1A 00 01 00 FF`, all off `18 00 01 00 7F`. 2025 products send `49 00 01 00 FF|7F` plus the mode feature with `0x80 | level` in the same SET. SoundCtrl picks the 2025 form for the product ids whose control class is TuneFlex3/TuneBeam3/WaveFlex3/LiveTws4/Live680NC, plus Tour Pro 4 and Tour One M4 [V for the list of classes, I for Tour Pro 4 / One M4 whose control class is unnamed].

## 4. Firmware check and download (Harman's public file server)

One anonymous HTTPS GET, no headers beyond a generic user agent, no cookies or tokens; the only variable part is the product id (or a per-model path) [V] (speaker `OTATaskImpl.requestRemoteConfig` with Volley, no headers; headphone `ym.c`, OkHttp, no headers).

| Devices | Config URL | Answer | |
| --- | --- | --- | --- |
| Speakers, older (`otaUrl` in the product list) | `https://storage.harman.com/release/portable/<path>` e.g. `flip6/flip6_ota.json` | `{version, md5, binUrl, targetPID, whatsNew…}`; image at `binUrl` (relative to the same host, or absolute) | [V] `BuildConfig.REMOTE_HOST`, `ProductConfig.ProductItem.getOtaUrl`, `RemoteOTAConfig`, `DownloadFirmwareFileUtils.downloadFile` |
| Speakers, newer (`dynamicOTAUrl`) | `https://appstorage.onecloud.harman.com/jbl_portable/ota/<pid>/ota_config.json` | `{pid, firmware: {version, md5, bin}}`; image at `…/ota/<pid>/<bin>` | [V] `BuildConfig.REMOTE_HOST_FOR_NEW_OTA`, `ProductConfig.OTA_URL_AFTER_PID` |
| Headphones (`otaStorageServerType` "amazon") | `https://appstorage.onecloud.harman.com/jbl_headphones/ota/<pid>/ota_config.json` | `{pid, name, firmware{version, md5, bin, only_support_greater_than, …}, case, …}`; image next to it | [V] `OTAUtil.getConfigUrl`, `ym.b`, `in.h` |
| Headphones, other new ones | `https://storage.harman.com/MyJBLHeadphones/ota/release/<pid>/ota_config.json` | same | [V] |
| Headphones, older (`releaseUrl.ota`) | per-model XML, e.g. `https://storage.harman.com/MyJBLHeadphones/BES/TOURONE/TOURONE_Upgrade_Index.xml` | `<Release name version md5>url</Release>`, "boot" entries skipped | [V] `in.b` |

Verification [V]: MD5 of the downloaded file compared, case-insensitive, with `md5`; on mismatch the file is deleted (`DownloadFirmwareFileUtils.downloadFile`, `in.h.e`). Neither app checks a signature; the devices check the image (CRC or SHA-256, see §5).

SoundCtrl: only `storage.harman.com` and `appstorage.onecloud.harman.com` over HTTPS on port 443, redirects followed only within them, at most 64 MB, system CAs only (`UpdateServer.isAllowed`, `FileServer`, `network_security_config.xml`) [I: SoundCtrl's choice].

## 5. Writing firmware

### 5.1 Speakers on the AA protocol (Vimicro/Actions chips): **implemented**

[V] `UpgradeFirmwareFileUtils` (reqDfuStart, requestDFUInfo, sendNextPacket checked against smali, computePacketAndSendDFUData, setDfuDataForVimicroChip, triggerUpgrade), `ReqDfuStartCommand.initPayload`, `DfuCRC`.

1. `AA 4A` → `AA 4B ver`: the version of an image the speaker already partly holds.
2. `AA 43` start, 12 bytes: CRC low 16 bits (BE), CRC high 16 bits (BE), image length (BE32), first three numbers of the new version, then `01` if the held image has the same version (continue) or `02` (new image).
   CRC: table CRC-32, MSB first, polynomial `0xDB710641`, init 0, no final XOR, update `crc = (crc << 8) ^ T[crc >> 24] ^ byte`, computed over the image followed by `FF FF FF FF` (`DfuCRC.fileCrc`, Vimicro/QCC branch). SoundCtrl's unit test checks its table against the app's.
3. The speaker paces everything with `AA 45 [status …]`:
   - `01` or `01 offset(BE32) x`: start, or continue from that offset (an offset past the end means 0).
   - `02 idx`: packet received; a new index means "next packet", the same index again means "send it again"; index `FF` is ignored; 50 repeats abort.
   - `03`: whole image received. `00`: error.
4. Data: `AA 44 [packetIndex low byte] data`, data = MTU - 7 bytes in the app. Because the length field is one byte, SoundCtrl caps data at 250 bytes [I]; identical to the app for MTU up to 257.
5. Apply: `AA 4D` → `AA 4C [code]`, 0 = accepted; the speaker checks the CRC, installs and restarts [V for the bytes, I for what the speaker does internally].
6. Cancel: `AA 47 01` [V] `NotifyDfuCancelCommand`.

Resumable: yes, by the speaker's offset [V]. Timeouts: the app's command timeout for OTA is 6 s (`AppConfig.json`); SoundCtrl resends the last packet after 6 s of silence, at most 5 times [I].

Older CSR/QCC speakers (Charge 3/4, Flip 4, Pulse 3, Xtreme 2, BoomBox) use `ReqDfuStartConnectPlusCommand` and a different CRC byte order; TWS-era speakers (Flip 3, Xtreme) use `ReqDfuStartTwsCommand`. Those weren't examined: **not available**.

### 5.2 Protocol-4 speakers and headphones: **implemented**

[V] speaker `PortableControl.startOTA/sendOTAData/stopOTA`, `AssembleCmd.updateOTA/sendOTAData`, `CommandProcessor.processP4Command`; headphone `ji.d` (OtaCtrl).

1. Start: speakers `00 DD 01 01 01 00 04 00 size(LE32)`; headphones `00 DD 01 01 01 00 00 00`.
2. The device sends `0104` notifications: status (LE16), progress (LE16, 10000 = 100 %), offset (LE32), length to read (LE32).
3. The phone answers with `00 DD 02 01 01 00 len offset(LE32) data`: speakers one packet per notification (240 bytes over BLE, 500 over BR/EDR), headphones as many packets of MTU - 17 bytes as asked, at most 20.
4. End: speakers when progress is 10000 with offset and length 0; headphones when length is 0. An offset falling back to 0 after progress means an error (speaker app).
5. Stop: `00 DD 03 01 01 00 00 00`.

Resumable: yes, the device asks by offset [V]. The device verifies the image itself (the headphone app knows a "signature verification failed" status) and switches after the transfer [I]. SoundCtrl keeps speaker packets within MTU - 15 [I].

### 5.3 Headphone chip families

The product list's `platform` and `otaConfig.protocolType` decide [V] (`ControlFactory`, `AppUtils.isAirohaDevice`).

| Family | Product ids in the list (regional variants counted separately) | Version read | Writing | In SoundCtrl |
| --- | --- | --- | --- | --- |
| Protocol 4 (BES and Realtek chips, 2024-25 models) | 36, e.g. Tour One M3/M4, Tour Pro 4, Live 680NC, Live Buds 4, Tune 530BT, Tune/Wave/Vibe 3 | feature `000C` [V] | §5.2 [V app side] | **write implemented** |
| Harman HmOtaMgr (`AA F0`) | 9, e.g. Tune 520BT/720BT, Wave Flex 2, Tune Beam 2 | AA device info [V] | start TLVs (type, CRC-32, size, version, breakpoint), data by offset, `04` apply: the TLV lengths and which CRC-32 variant weren't pinned [I] | read only: "isn't available yet" |
| BES OTA SDK (BesOtaService, UserLang, BreakPoint, Stereo, RoleSwitch) | 63, e.g. Tour Pro 2, Live 770NC, Tune Buds | `8E "BEST"` → `8F` [V] | opcodes `80/81/82/83/85/86/88/8C/8D/92/93` known, but the two code paths disagree on the image size/CRC for `80`, plus an optional AES layer [V/I] | read only: "isn't available yet" |
| Airoha FOTA (AB155x/1562/1568/158x) | 24, e.g. Live Free 2, Tune 770NC, Quantum 910, Live Buds 3 | RACE `05 5A 03 00 07 1C [role]` → `05 5D … [status role len ASCII]` [V] `MmiStageGetFwVersion` | RACE erase/program/commit sequence with partition query, 4 KB erase, 256-byte pages with CRC-8, SHA-256 compare per block, commit `1C02`, device checks integrity (`1C01`) and switches after reboot [V in Java], plus TWS relay and AB1562 NV commands in a resource not decompiled | read only in 0.1: "isn't available yet" (too large to implement safely without a device) |
| Avnera (Everest/Reflect Aware, legacy) | 6 | not established | flash command ids known, framing obfuscated | not available |
| No update protocol set in the list | 18, e.g. Tune 680NC/780NC, Wave Buds/Beam/Flex, Quantum 360 | per control protocol | unknown | read only: isn't available yet |
| Realtek (bbpro SDK) | (Realtek models use protocol 4 or HmOtaMgr above) | — | no Realtek DFU code in the app | — |

## 6. What SoundCtrl implements, per feature

| Feature | Speakers AA | Speakers P4 | Headphones P4 | Headphones AA | Airoha |
| --- | --- | --- | --- | --- | --- |
| Scan, connect, remember | yes [V] | yes [V] | yes [V] | yes [V] | yes (RACE pipe) [V] |
| Model, firmware | yes [V] | yes [V] | yes [V] | yes [V] | firmware [V] |
| Battery (main / L / R / case) | yes [V] | yes [V] | yes [V] | yes [V] | L / R / case with charging [V] (not AB1562, not Quantum 910) |
| EQ presets, custom EQ | Indoor/Outdoor (BoomBox) [V]; rest needs confirming | needs confirming | needs confirming | needs confirming | needs confirming |
| ANC / Ambient / TalkThru | — | — | yes [V] | ANC only [V] | off / ANC / Ambient Aware / TalkThru on AB1568 and AB158x chips [V]; levels and Adaptive ANC need confirming |
| Gestures | — | — | yes, for the gestures the device reports [V] | needs confirming | needs confirming |
| Auto-off | — | — | yes [V] (unit I) | needs confirming | 30 / 60 / 120 min or never, AB1568 and AB158x [V] |
| Voice prompts / feedback tones | yes [V] | yes [V] | yes [V] | needs confirming | needs confirming |
| Rename | yes [V] | yes [V] | needs confirming | needs confirming | needs confirming |
| In-ear auto play/pause | — | — | — | — | AB1568 and AB158x [V] |
| Find my buds (ring left / right) | — | — | — | — | yes [V] (not AB1562, not Quantum 910) |
| Transport | BLE | BLE | BLE | BLE | classic RFCOMM first (Tour Pro 3: confirmed in use by the JBL app), BLE RACE as fallback |
| PartyBoost | yes [V] | — | — | — | — |
| Auracast on/off | yes [V] | yes [V] | — | — | — |
| Stereo channel | yes [V] | — | — | — | — |
| Auracast stereo groups | needs confirming | needs confirming | — | — | — |
| Check for update | yes [V] | yes [V] | yes [V] | yes [V] | yes [V] |
| Write firmware | yes [V] (untested) | yes [V] (untested) | yes [V] (untested) | not available | not available |

Out of scope: PersoniFi, JBL/Harman accounts and cloud, product pictures, per-app EQ.

## 7. Airoha headphones and earbuds (control)

Source: `com.harman.cmdctrl.imp.control.u3.lea.TourPro3LeControl` → `LeaAhBaseControl` → `gi.a` (`AhDevice`) → `com.airoha.adapter.harman.AirohaHmSDK` / `AirohaDeviceConnector` / `AirohaDeviceStatusControl` → Airoha SDK (`com.airoha.sdk.AB155xDeviceControl`, `AB1568DeviceControl`, `AB158xDeviceControl`) → stages in `com.airoha.libmmi*`, `com.airoha.libcommon`, `com.airoha.adapter.harman.stage`.

### 7.1 Picking the controller and the transport

- The product list (`product_list.json`) gives the Tour Pro 3 (`20f2`, case `20fd`) `platform` "Airoha", `cmdCtrlList` `TourPro3LeControl` and `connectType` "BT" [V]. Its control talks only through `AhDevice` (`getAhDevice()`) [V].
- `AhDevice.w` connects with `DeviceProtocol.PROTOCOL_SPP` unless the device was opened as LE Audio (`PROTOCOL_LEA`, then GATT) [V]. `AirohaConnector.connect` builds an `SppLinkParam`, whose UUID is `AIROHA_SPP_UUID` `00000000-0000-0000-0099-AABBCCDDEEFF`; `SppController.createRfcomm` calls `createRfcommSocketToServiceRecord` (secure, found through SDP) [V]. The BLE GATT RACE service (§1) is the same protocol on the LE side.
- **Confirmed on a real Tour Pro 3** (from the owner's Bluetooth stack log while using the JBL app; no command bytes from the phone were in it): the JBL app opened one secure RFCOMM client socket, found by SDP for `00000000-0000-0000-0099-aabbccddeeff`, and every reply came back on it. The other RFCOMM connection in that log (UUID `df21fe2c-…`) belonged to a different app (Android's Fast Pair), not to the JBL app. No RACE GATT traffic. Replies of the shapes listed below (agent side, NV reads, noise-control set and status pushes, common-config, charge-info acks) were seen on that socket and match the layouts here.
- The stream is cut into frames as `H4Transport.parseRace` does: start byte `05` (or `15`), length LE16 at [2], frame = length + 4, 6 to 2000 bytes [V] (`RaceAssembler`).
- After the link is up the SDK reads the chip name (NV key `1002`) and the model name (NV key `1003` on AB158x, `F500` on AB155x/AB1568) [V] (`AirohaConnector`, `CommonStageReadChipName`, `CommonStageGetModelName`). The chip name picks the family: contains AB1562/AB1563 → AB1562; AB1565/AB1568 dual chip → AB1568; starts with AB158/AB157/MT283 → AB158x; AB1565/AB1568 or starts with MT → AB1568; otherwise AB155x [V]. AB1562 devices use `AirohaAB1562DeviceStatusControl` and the Quantum 910 family (`2089`, `209e`, `20a3`, `2149`) `AirohaQ910DeviceStatusControl`; everything else uses `AirohaDeviceStatusControl`, which branches on AB1568/AB158x for noise control, auto-off and play/pause [V]. SoundCtrl offers the features below only where the family matches.

### 7.2 Frames

`05 type len(LE16) raceId(LE16) payload`, len = 2 + payload [V] (`RacePacket.getRaw`). Types: `5A` command wanting a reply, `5B` reply (status in [6] unless noted), `5C` command without reply, `5D` indication (a later answer or a push) [V]. Commands that are for one earbud carry a role byte (0 = the earbud the phone talks to, "agent"; 1 = the other one, "partner"); the agent relays them [V]. The SDK can also wrap a whole frame in `RELAY_PASS_TO_DST` (`0D01`, payload `dstType dstId frame`; reply's inner frame from [8]) [V] (`RaceCmdRelayPass`, `RelayRespExtracter`); none of the features SoundCtrl uses need it.

| Function | Command | Reply | |
| --- | --- | --- | --- |
| Status pushes on (sent once after connecting) | `5A 0006 [01]` | `5B 0006 [status]` | [V] `AirohaDeviceConnector` (`setDevicePushStatusOnOff(true)`), `MmiStage_SetDevicePushStatusOnOff` |
| Agent is right earbud? | `5A 0CD4` | `5B 0CD4 [status][1 = right]` | [V] `MmiStageCheckAgentChannel` |
| Partner connected? | `5A 0D00` | `5B 0D00 (type id)*` from [6], no status; partner present when a pair has type 5 | [V] `MmiStageGetAvaDst` |
| Earbud battery | `5A 0CD6 [role]` | `5D 0CD6 [status][role][percent]` | [V] `MmiStageTwsGetBattery` |
| Earbud charging | `5A 0009 [role]` | `5B` ack, then `5D 0009 [status][role][1 = charging]` (9 bytes) | [V] `MmiStage_GetChargeInfo`, `notifyChargeInfo` |
| Case battery | `5A 000B` | `5D 000B [status][percent]` (8 bytes), ≤ 0 = not reported | [V] `MmiStage_GetBoxBattery`, `notifyBoxBattery` |
| Firmware | `5A 1C07 [role]` | `5D 1C07 [status][role][n][ASCII]` | [V] `MmiStageGetFwVersion` |
| NV read (chip / model name) | `5A 0A00 [key LE16][max LE16]` (max 1000 / 100) | `5B 0A00 [len LE16][data]`, no status; model name = data bytes 20-39 | [V] `CommonStage.genReadNvKeyPacket`, `CommonStageGetModelName` |
| Noise control status | `5A 0901 [05 00]` | `5B 0901 [05 00][status][filter][gain LE16]`; the device also pushes the same as `5D` | [V] `MmiStageGetAncStatus` |
| Noise control on | `5A 0E06 [00 0A filter mode]` | `5B 0E06 [status][0A]…` | [V] `MmiStageAncOn` |
| Noise control off | `5A 0E06 [00 0B]` | `5B 0E06 [status][0B]…` | [V] `MmiStageAncOff` |
| Auto power-off read | `5A 2C83 [01 00]` | `5B 2C83 [status][01 00][on LE16][seconds LE16]` | [V] `MmiStage_GetAutoPowerOffStatus` |
| Auto power-off set | `5A 2C82 [01 00][on LE16][seconds LE16]` | `5B 2C82 [status][01 00]` | [V] `MmiStage_SetAutoPowerOffStatus` |
| In-ear auto play/pause read | `5A 2C83 [02 00]` | `5B 2C83 [status][02 00][on]` | [V] `MmiStageGetInearEnableStatus` |
| In-ear auto play/pause set | `5A 2C82 [02 00][on]` | `5B 2C82 [status][02 00]` | [V] `MmiStageSetInearEnableStatus` |
| Find my buds | `5A 2C01 [blink][ring][target]` | `5B 2C01 [status]` | [V] `MmiStageFindMe` |

Values [V]:

- **Filters on AB1568/AB158x** (`SDKAncSettings.AB1568_ANC_INDEX`): 0 off, 1-4 noise cancelling, 9-11 pass-through. The Harman layer maps ANC → 1 (2 in "sports" mode), Ambient Aware → pass-through 1 = 9, TalkThru → pass-through 2 = 10 (`AirohaDeviceStatusControl.convertToAirohaAncIndex`, `SDKAncSettings.queryAirohaFilterAndSetSetting`), with mode 0 for ANC and 4 for the pass-through filters and no gain sent (`execFlow` case SET_ANC_SETTINGS, `disableGainSetting`). So: ANC `00 0A 01 00`, Ambient Aware `00 0A 09 04`, TalkThru `00 0A 0A 04`, off `00 0B`. AB155x uses filters 5-7 for pass-through and isn't offered. Levels, Adaptive ANC (`0E26`) and the sports filter aren't offered [I: SoundCtrl's choice].
- **Battery**: the agent/partner levels become left/right by the agent's side, as the app does for in-ear status and find-me (`mIsAgentRight`) [V for the app's mapping]. Without the side SoundCtrl shows the agent's level as one battery [I].
- **Auto power-off**: the app sends minutes × 60 and offers 30, 60, 120 minutes or off for earbuds (`fq.a`, 15 minutes and up to 4 hours only for some headphones); off keeps the last time with "on" 0 (`fq.g.O0`).
- **Find my buds**: the Tour Pro 3 control only rings or silences (`TourPro3LeControl.setFindMyBuds`); behaviour = ring (lighting unset) gives blink 0, ring 1; silence gives 0, 0. Left (Harman channel 2) → target 0, right (channel 1) → target 1 (`AB155xDeviceControl.doSetFindMyBuds`). Without the partner the app rings only the agent's side.

### 7.3 Field report (SoundCtrl 0.5.0 on a real Tour Pro 3)

- Worked: RFCOMM connection, chip and model name, agent side, battery (both earbuds, charging, case), firmware.
- Failed: the noise-control status read (`0901`) got no matching reply, and every setting (`0E06`, `2C82`, `2C01`) was reported as not accepted. Which of "no answer", "refused (status)" or "other answer" isn't known yet.
- What was checked: the sizes of the JBL app's own writes in the owner's stack log (10 bytes for Ambient Aware and ANC, 8 for off) match SoundCtrl's frames, its header byte is `05` (`RacePacket.getRawMmiMode`), and the earbuds answered the JBL app within about 80 ms with `5B 0E06 [00][0A|0B]…`, followed about 0.5 s later by a `5D 0901` status push. The one difference found in the connection sequence: right after connecting the JBL app turns the device's status pushes on (`0006 [01]`), which SoundCtrl 0.5.0 didn't send [V that the app sends it; I whether the earbuds need it].
- Since 0.5.1 SoundCtrl sends that command too, waits up to 4 s for a setting's answer, also counts the `5B`/`5D` `0901` status showing the requested mode as done for noise control [I], and the "didn't accept" message names the reason (no answer / refused with status / unexpected answer with its first bytes / frames seen meanwhile) so the next test can tell which case it is.

### 7.4 Not implemented for Airoha (needs confirming)

- **Gestures**: per-chip stages (`MmiStage_Get/SetGesture_1568_v3`, `…_1568`, `MmiStage_GetHeadsetGesture`) that read and write key-map NV keys and reload them on both earbuds; the per-model action tables weren't pinned.
- **Voice prompts**: on these models the setting is a voice-prompt language (`SetVpLanguageIndex`, 255 = tones) plus an off switch (`SetVpOnoff 0`); how "on" is restored after "off" isn't clear from the code.
- **EQ** (presets / custom): Airoha PEQ (`AirohaEQControl`, `RACE_HOSTAUDIO_PEQ…`) plus Harman EQ relayed in `RACE_SEND_HM_CMD` (`0093`); not pinned.
- **Spatial sound, Hi-Res/LDAC, low latency (smart audio/video), Adaptive ANC levels, smart case settings, rename**: separate stages or Harman commands relayed in `0093`; not examined in enough detail.
- **Firmware writing**: unchanged, see §5.3.


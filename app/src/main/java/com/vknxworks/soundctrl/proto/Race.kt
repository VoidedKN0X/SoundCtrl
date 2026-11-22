package com.vknxworks.soundctrl.proto

/**
 * Airoha "RACE" frames, as the JBL headphone app sends them to Airoha-chip JBL earbuds and headphones
 * (Tour Pro 3, Live Buds 3, Tune 770NC, …) through Airoha's SDK (`com.airoha.libbase.RaceCommand.packet.RacePacket`,
 * `com.airoha.libmmi*`, `com.airoha.adapter.harman`). See PROTOCOL.md §7.
 *
 * Frame: `05 type len(LE16) raceId(LE16) payload`, len = 2 + payload length. Types: 5A command that wants a
 * reply, 5B reply, 5C command without reply, 5D indication (an answer that comes later, or a push).
 */
object Race {
    const val CMD_NEEDS_RESPONSE = 0x5A
    const val RESPONSE = 0x5B
    const val CMD_NO_RESPONSE = 0x5C
    const val INDICATION = 0x5D

    /** Which earbud a command is for: the one the phone talks to (agent) or the other one (partner). */
    const val AGENT = 0
    const val PARTNER = 1

    // RACE ids (com.airoha.libbase.RaceCommand.constant.RaceId)
    const val GET_CHARGING_INFO = 0x0009
    const val GET_BOX_BATTERY = 0x000B
    const val HOSTAUDIO_MMI_GET = 0x0901
    const val NVKEY_READ = 0x0A00
    const val IS_AGENT_RIGHT = 0x0CD4
    const val TWS_GET_BATTERY = 0x0CD6
    const val RELAY_GET_AVA_DST = 0x0D00
    const val ANC_ON_OFF = 0x0E06
    const val GET_FW_VERSION = 0x1C07
    const val FIND_ME = 0x2C01
    const val SET_MMI_COMMON_CONFIG = 0x2C82
    const val GET_MMI_COMMON_CONFIG = 0x2C83

    /** NV keys (`CommonStageReadChipName`, `CommonStageGetModelName`). */
    const val NV_CHIP_NAME = 0x1002
    const val NV_MODEL_NAME_158X = 0x1003
    const val NV_MODEL_NAME_OLDER = 0xF500

    /** MMI common-config modules (`MmiStage_Get/SetAutoPowerOffStatus`, `MmiStageGet/SetInearEnableStatus`). */
    const val MODULE_AUTO_POWER_OFF = 1
    const val MODULE_IN_EAR_PLAY_PAUSE = 2

    /** Host-audio module of the noise-control status (`MmiStageGetAncStatus`). */
    const val MODULE_ANC_STATUS = 5

    /** Largest frame the app's own reader accepts (`H4Transport.parseRace`). */
    const val MAX_FRAME = 2000

    fun build(type: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0x05, type.toByte()) + le16(payload.size + 2) + le16(id) + payload

    private fun cmd(id: Int, vararg payload: Int) = build(CMD_NEEDS_RESPONSE, id, ByteArray(payload.size) { payload[it].toByte() })

    /** A whole frame, split up. null when it isn't one (wrong start byte, length that doesn't fit). */
    fun parse(b: ByteArray): RaceFrame? {
        if (b.size < 6 || (b.u8(0) != 0x05 && b.u8(0) != 0x15)) return null
        if (b.u16le(2) + 4 != b.size) return null
        return RaceFrame(b.u8(1), b.u16le(4), b.copyOfRange(6, b.size))
    }

    // ---------- firmware ----------

    fun getFwVersion(role: Int) = cmd(GET_FW_VERSION, role)

    /** The version reply (type 5D, id 1C07): status [6] (0 = OK), role [7], length [8], ASCII from [9]. */
    fun parseFwVersion(b: ByteArray): Pair<Int, String>? {
        if (b.size < 9 || b.u8(0) != 0x05 || b.u8(1) != INDICATION || b.u16le(4) != GET_FW_VERSION) return null
        if (b.u8(6) != 0) return null
        val n = b.u8(8)
        if (n == 0 || 9 + n > b.size) return null
        return b.u8(7) to String(b.copyOfRange(9, 9 + n), Charsets.US_ASCII).trim { it <= ' ' }
    }

    // ---------- which chip, which model ----------

    /** Reads an NV key, up to [max] bytes (`CommonStage.genReadNvKeyPacket`, `CommonStageGetModelName`). */
    fun readNvKey(key: Int, max: Int) = build(CMD_NEEDS_RESPONSE, NVKEY_READ, le16(key) + le16(max))

    /** The chip name (`CommonStageReadChipName`: NV key 0x1002, at most 1000 bytes). */
    fun getChipName() = readNvKey(NV_CHIP_NAME, 1000)

    /** The model name; which NV key depends on the chip (`AirohaCommonMgr.getModelName`), at most 100 bytes. */
    fun getModelName(family: AirohaFamily) =
        readNvKey(if (family == AirohaFamily.AB158X) NV_MODEL_NAME_158X else NV_MODEL_NAME_OLDER, 100)

    /** NV read reply (5B 0A00): length LE16 at [6], data from [8]. No status byte. */
    fun parseNvData(b: ByteArray): ByteArray? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != NVKEY_READ || f.payload.size < 2) return null
        val n = f.payload.u16le(0)
        if (2 + n > f.payload.size) return null
        return f.payload.copyOfRange(2, 2 + n)
    }

    fun parseChipName(b: ByteArray): String? =
        parseNvData(b)?.let { d -> String(d, Charsets.US_ASCII).trim { it <= ' ' } }?.takeIf { it.isNotEmpty() }

    /** The model name is the second 20-byte text in the reply (frame bytes 28-47), the product id on JBL models. */
    fun parseModelName(b: ByteArray): String? {
        if (b.size < 48) return null
        parseNvData(b) ?: return null
        return String(b.copyOfRange(28, 48), Charsets.US_ASCII).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
    }

    // ---------- the two earbuds ----------

    /** Is the earbud the phone talks to the right one? (`MmiStageCheckAgentChannel`) */
    fun getAgentIsRight() = cmd(IS_AGENT_RIGHT)

    /** 5B 0CD4: status [6], 1 = right [7]. */
    fun parseAgentIsRight(b: ByteArray): Boolean? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != IS_AGENT_RIGHT || f.payload.size < 2 || f.payload.u8(0) != 0) return null
        return f.payload.u8(1) == 1
    }

    /** Which relay destinations exist (`MmiStageGetAvaDst`); the partner is there when one has type 5. */
    fun getAvailableDestinations() = cmd(RELAY_GET_AVA_DST)

    /** 5B 0D00: (type, id) pairs from [6], no status byte. */
    fun parsePartnerExists(b: ByteArray): Boolean? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != RELAY_GET_AVA_DST) return null
        var i = 0
        while (i + 1 < f.payload.size) {
            if (f.payload.u8(i) == 5) return true
            i += 2
        }
        return false
    }

    // ---------- battery ----------

    /** One earbud's battery (`MmiStageTwsGetBattery`). */
    fun getBattery(role: Int) = cmd(TWS_GET_BATTERY, role)

    /** 5D 0CD6: status [6], role [7], percent [8]. Returns (role, percent). */
    fun parseBattery(b: ByteArray): Pair<Int, Int>? {
        val f = parse(b) ?: return null
        if (f.type != INDICATION || f.id != TWS_GET_BATTERY || f.payload.size < 3 || f.payload.u8(0) != 0) return null
        val level = f.payload.u8(2)
        if (level > 100) return null
        return f.payload.u8(1) to level
    }

    /** Is one earbud charging (`MmiStage_GetChargeInfo`)? The device first acks with 5B, then answers with 5D. */
    fun getChargeInfo(role: Int) = cmd(GET_CHARGING_INFO, role)

    /** 5D 0009 (exactly 9 bytes): status [6], role [7], 1 = charging [8]. Returns (role, charging). */
    fun parseChargeInfo(b: ByteArray): Pair<Int, Boolean>? {
        val f = parse(b) ?: return null
        if (f.type != INDICATION || f.id != GET_CHARGING_INFO || b.size != 9 || f.payload.u8(0) != 0) return null
        return f.payload.u8(1) to (f.payload.u8(2) == 1)
    }

    /** The charging case's battery (`MmiStage_GetBoxBattery`). */
    fun getCaseBattery() = cmd(GET_BOX_BATTERY)

    /** 5D 000B (exactly 8 bytes): status [6], percent [7]; 0 or less means not reported. */
    fun parseCaseBattery(b: ByteArray): Int? {
        val f = parse(b) ?: return null
        if (f.type != INDICATION || f.id != GET_BOX_BATTERY || b.size != 8 || f.payload.u8(0) != 0) return null
        val level = f.payload[1].toInt()
        return if (level <= 0 || level > 100) null else level
    }

    // ---------- noise control (AB1568 / AB158x chips) ----------

    /** Airoha filter numbers on AB1568/AB158x (`SDKAncSettings.AB1568_ANC_INDEX`). */
    const val FILTER_OFF = 0
    const val FILTER_ANC = 1
    const val FILTER_AMBIENT = 9
    const val FILTER_TALKTHRU = 10

    /**
     * Sets noise control (`MmiStageAncOn` / `MmiStageAncOff`, id 0E06). On: `00 0A filter mode`, mode 0 for ANC,
     * 4 for the pass-through filters (Ambient Aware 9, TalkThru 10). Off: `00 0B`.
     */
    fun setNoise(mode: NoiseMode): ByteArray = when (mode) {
        NoiseMode.OFF -> cmd(ANC_ON_OFF, 0x00, 0x0B)
        NoiseMode.ANC -> cmd(ANC_ON_OFF, 0x00, 0x0A, FILTER_ANC, 0)
        NoiseMode.AMBIENT -> cmd(ANC_ON_OFF, 0x00, 0x0A, FILTER_AMBIENT, 4)
        NoiseMode.TALKTHRU -> cmd(ANC_ON_OFF, 0x00, 0x0A, FILTER_TALKTHRU, 4)
    }

    /** 5B 0E06: status [6], then 0A (turned on) or 0B (turned off) [7]. True when the device did what [mode] asked. */
    fun parseSetNoise(b: ByteArray, mode: NoiseMode): Boolean? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != ANC_ON_OFF || f.payload.size < 2) return null
        val want = if (mode == NoiseMode.OFF) 0x0B else 0x0A
        if (f.payload.u8(1) != want) return null
        return f.payload.u8(0) == 0
    }

    /** Reads the noise-control state (`MmiStageGetAncStatus`): host-audio module 5. */
    fun getNoise() = build(CMD_NEEDS_RESPONSE, HOSTAUDIO_MMI_GET, le16(MODULE_ANC_STATUS))

    /**
     * 5B (or a 5D push) 0901: module LE16 [6], status [8], filter [9], gain LE16 [10] (optional).
     * Filter 0 off, 1-4 noise cancelling, 9 Ambient Aware, 10 TalkThru; other filters aren't mapped (null).
     */
    fun parseNoise(b: ByteArray): NoiseMode? {
        val f = parse(b) ?: return null
        if ((f.type != RESPONSE && f.type != INDICATION) || f.id != HOSTAUDIO_MMI_GET || f.payload.size < 4) return null
        if (f.payload.u16le(0) != MODULE_ANC_STATUS || f.payload.u8(2) != 0) return null
        return when (f.payload.u8(3)) {
            FILTER_OFF -> NoiseMode.OFF
            1, 2, 3, 4 -> NoiseMode.ANC
            FILTER_AMBIENT -> NoiseMode.AMBIENT
            FILTER_TALKTHRU -> NoiseMode.TALKTHRU
            else -> null
        }
    }

    // ---------- MMI common config: auto power-off, in-ear auto play/pause (AB1568 / AB158x chips) ----------

    fun getCommonConfig(module: Int) = build(CMD_NEEDS_RESPONSE, GET_MMI_COMMON_CONFIG, le16(module))

    /** Auto power-off (`MmiStage_SetAutoPowerOffStatus`): module 1, on LE16, seconds LE16. The app sends minutes x 60. */
    fun setAutoOff(on: Boolean, minutes: Int): ByteArray =
        build(CMD_NEEDS_RESPONSE, SET_MMI_COMMON_CONFIG, le16(MODULE_AUTO_POWER_OFF) + le16(if (on) 1 else 0) + le16(minutes * 60))

    /** 5B 2C83 for module 1 (at least 13 bytes): status [6], module [7], on LE16 [9], seconds LE16 [11]. Returns (on, minutes). */
    fun parseAutoOff(b: ByteArray): Pair<Boolean, Int>? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != GET_MMI_COMMON_CONFIG || b.size < 13) return null
        if (f.payload.u8(0) != 0 || f.payload.u16le(1) != MODULE_AUTO_POWER_OFF) return null
        return (f.payload.u16le(3) != 0) to f.payload.u16le(5) / 60
    }

    /** In-ear detection pauses and resumes playback (`MmiStageSetInearEnableStatus`): module 2, one byte. */
    fun setInEarPlayPause(on: Boolean): ByteArray =
        build(CMD_NEEDS_RESPONSE, SET_MMI_COMMON_CONFIG, le16(MODULE_IN_EAR_PLAY_PAUSE) + byteArrayOf(if (on) 1 else 0))

    /** 5B 2C83 for module 2 (at least 10 bytes): status [6], module [7], on [9]. */
    fun parseInEarPlayPause(b: ByteArray): Boolean? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != GET_MMI_COMMON_CONFIG || b.size < 10) return null
        if (f.payload.u8(0) != 0 || f.payload.u16le(1) != MODULE_IN_EAR_PLAY_PAUSE) return null
        return f.payload.u8(3) != 0
    }

    /** 5B 2C82: status [6], module LE16 [7]. True when the device accepted the change for [module]. */
    fun parseSetCommonConfig(b: ByteArray, module: Int): Boolean? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != SET_MMI_COMMON_CONFIG || f.payload.size < 3 || f.payload.u16le(1) != module) return null
        return f.payload.u8(0) == 0
    }

    // ---------- find my buds ----------

    const val FIND_LEFT = 0
    const val FIND_RIGHT = 1

    /**
     * Rings one earbud or stops it (`MmiStageFindMe`): `blink ring target`. The headphone app never blinks
     * (lighting stays unset), rings with 1, stops with 0; target 0 for the left earbud, 1 for the right
     * (`AB155xDeviceControl.doSetFindMyBuds`).
     */
    fun findMe(target: Int, ring: Boolean) = cmd(FIND_ME, 0, if (ring) 1 else 0, target)

    /** 5B 2C01: status [6]. */
    fun parseFindMe(b: ByteArray): Boolean? {
        val f = parse(b) ?: return null
        if (f.type != RESPONSE || f.id != FIND_ME || f.payload.isEmpty()) return null
        return f.payload.u8(0) == 0
    }
}

/** One RACE frame: [type] 5A-5D, [id] the RACE id, [payload] everything after the id. */
class RaceFrame(val type: Int, val id: Int, val payload: ByteArray)

/**
 * Which Airoha chip family the device has, from its chip name (`AirohaConnector.mGetChipNameListener`).
 * The JBL app picks commands per family; SoundCtrl only offers what it established for each.
 */
enum class AirohaFamily {
    AB155X, AB1562, AB1568, AB158X;

    /** Noise control, auto power-off and in-ear play/pause as SoundCtrl sends them: AB1568 and AB158x only. */
    val hasMmiSettings: Boolean get() = this == AB1568 || this == AB158X

    /** Battery, case battery and Find my buds go through the same calls on every family except AB1562. */
    val hasBasics: Boolean get() = this != AB1562

    companion object {
        fun fromChipName(name: String): AirohaFamily {
            val n = name.uppercase()
            return when {
                n.contains("AB1562E") || n.contains("AB1563") || n.contains("AB1562") -> AB1562
                n.contains("AB1565_DUAL_CHIP") || n.contains("AB1568_DUAL_CHIP") -> AB1568
                n.startsWith("AB158") || n.startsWith("AB157") || n.startsWith("MT283") -> AB158X
                n.contains("AB1565") || n.contains("AB1568") || n.startsWith("MT") -> AB1568
                else -> AB155X
            }
        }

        /** Quantum 910 headsets get their own control class in the JBL app (`AirohaHmSDK.getAirohaDeviceControl`). */
        val OWN_CONTROL_PIDS = setOf("2089", "209e", "20a3", "2149")
    }
}

/**
 * Cuts a byte stream (classic Bluetooth RFCOMM, or BLE notifications that split or join frames) into RACE
 * frames, as the app's `H4Transport.parseRace` does: start byte 05 or 15, length LE16 at [2], whole frame =
 * length + 4, at least 6 and at most [Race.MAX_FRAME] bytes. Anything else is skipped one byte at a time.
 */
class RaceAssembler {
    private var buf = ByteArray(0)

    fun accept(bytes: ByteArray): List<ByteArray> {
        buf += bytes
        val out = mutableListOf<ByteArray>()
        while (buf.isNotEmpty()) {
            val start = buf.u8(0)
            if (start != 0x05 && start != 0x15) { buf = buf.copyOfRange(1, buf.size); continue }
            if (buf.size < 4) break
            val len = buf.u16le(2)
            val total = len + 4
            if (len == 0 || total < 6 || total > Race.MAX_FRAME) { buf = buf.copyOfRange(1, buf.size); continue }
            if (buf.size < total) break
            out += buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
        }
        // Never hold more than one largest frame of unfinished data.
        if (buf.size > Race.MAX_FRAME) buf = ByteArray(0)
        return out
    }

    fun reset() { buf = ByteArray(0) }
}

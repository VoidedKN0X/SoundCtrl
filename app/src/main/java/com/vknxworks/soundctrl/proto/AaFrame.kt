package com.vknxworks.soundctrl.proto

/**
 * Harman's "AA" frame, used by JBL speakers before protocol 4 and by older JBL headphones:
 * `AA cmd len payload`. Long replies use a two-byte big-endian length. See PROTOCOL.md §2.
 */
object Aa {
    const val ID: Byte = 0xAA.toByte()

    // Command ids (speaker app: PacketFormat; headphone app: CommandHeader)
    const val DEV_ACK = 0x00
    const val REQ_DEV_INFO = 0x11
    const val RET_DEV_INFO = 0x12
    const val REQ_DEV_INFO_TOKEN = 0x13
    const val SET_DEV_INFO = 0x15
    const val IDENTIFY = 0x31
    const val REQ_VER = 0x41
    const val RET_VER = 0x42
    const val REQ_DFU_START = 0x43
    const val SET_DFU_DATA = 0x44
    const val RET_DFU_STATUS = 0x45
    const val NOTIFY_DFU_CANCEL = 0x47
    const val REQ_DFU_VERSION = 0x4A
    const val RET_DFU_VERSION = 0x4B
    const val RET_TRIGGER_UPDATE = 0x4C
    const val REQ_TRIGGER_UPDATE = 0x4D
    const val REQ_EQ_MODE = 0x61
    const val RET_EQ_MODE = 0x62
    const val SET_EQ_MODE = 0x63
    const val NOTIFY_EQ_MODE = 0x64
    const val REQ_FEEDBACK = 0x65
    const val RET_FEEDBACK = 0x66
    const val SET_FEEDBACK = 0x67
    const val SET_LINK_MODE = 0x6A
    const val RET_LINK_MODE = 0x6B

    // Headphone (protocol 3) ids, from the headphone app's CommandHeader / CmdGen
    const val HP_REQ_BATTERY = 0x25
    const val HP_SET_ANC = 0x31
    const val HP_SET_AMBIENT = 0x32
    const val HP_SET_EQ_PRESET = 0x40

    // Device-info tokens
    const val T_DEVICE_NAME = 0xC1
    const val T_SERIAL = 0xC2
    const val T_PRODUCT_ID = 0x42
    const val T_MODEL_ID = 0x43
    const val T_BATTERY = 0x44
    const val T_LINKED_COUNT = 0x45
    const val T_ACTIVE_CHANNEL = 0x46
    const val T_AUDIO_SOURCE = 0x47
    const val T_MAC = 0x48
    const val T_AURACAST = 0x4C
    const val T_SECONDARY_FW = 0x4D
    const val T_LLS = 0x4F
    const val T_DAB_FW = 0x50
    const val T_LLS_AIR_TOUCH = 0x51
    const val T_MIC = 0x53

    private val LONG_REPLIES = setOf(0x94, 0x96, 0x99)

    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 255) { "payload too long for a one-byte length" }
        return byteArrayOf(ID, cmd.toByte(), payload.size.toByte()) + payload
    }

    /** One notification = one frame. Returns null for anything that isn't an AA frame. */
    fun parse(b: ByteArray): AaFrame? {
        if (b.size < 3 || b[0] != ID) return null
        val cmd = b.u8(1)
        return if (b.size > 127 + 3 || cmd in LONG_REPLIES) {
            if (b.size < 4) return null
            val len = (b.u8(2) shl 8) or b.u8(3)
            AaFrame(cmd, b.copyOfRange(4, minOf(b.size, 4 + len)))
        } else {
            val len = b.u8(2)
            AaFrame(cmd, b.copyOfRange(3, minOf(b.size, 3 + len)))
        }
    }

    // ---------- Speaker commands (speaker app, com.harman.sdk.command.*) ----------
    fun reqDeviceInfo() = build(REQ_DEV_INFO)
    fun reqBattery(index: Int) = build(REQ_DEV_INFO_TOKEN, byteArrayOf(index.coerceAtLeast(0).toByte(), T_BATTERY.toByte()))
    fun reqVersion() = build(REQ_VER)
    fun reqFeedback() = build(REQ_FEEDBACK)
    fun setFeedback(on: Boolean) = build(SET_FEEDBACK, byteArrayOf(if (on) 1 else 0))
    fun reqEqMode() = build(REQ_EQ_MODE)
    fun setEqMode(mode: Int) = build(SET_EQ_MODE, byteArrayOf(mode.toByte()))
    fun identify(index: Int) = build(IDENTIFY, byteArrayOf(index.toByte()))
    fun setLinkMode(mode: Int) = build(SET_LINK_MODE, byteArrayOf(mode.toByte()))

    /** Rename: `AA 15 [index C1 len utf8]`; the app cuts the name to 16 characters. */
    fun setName(index: Int, name: String): ByteArray {
        val bytes = name.take(16).toByteArray(Charsets.UTF_8)
        return build(SET_DEV_INFO, byteArrayOf(index.toByte(), T_DEVICE_NAME.toByte(), bytes.size.toByte()) + bytes)
    }

    /** Stereo channel: `AA 15 [index 46 ch]`, ch 0 = both, 1 = left, 2 = right. */
    fun setChannel(index: Int, channel: Int) = build(SET_DEV_INFO, byteArrayOf(index.toByte(), T_ACTIVE_CHANNEL.toByte(), channel.toByte()))

    /** True when [f] is the speaker's "OK" for [cmd] (`AA 00 [cmd 00]`). */
    fun isAckOk(f: AaFrame, cmd: Int) = f.cmd == DEV_ACK && f.payload.size >= 2 && f.payload.u8(0) == cmd && f.payload.u8(1) == 0

    /**
     * A firmware version as speakers send it (`AA 42`): every byte's two hex digits read as a decimal
     * number, so 01 02 0A... is refused by the JBL app; here a byte with a-f digits falls back to its value.
     */
    fun formatVersion(bytes: ByteArray): String = bytes.joinToString(".") { b ->
        val h = "%02x".format(b.toInt() and 0xFF)
        (if (h.all { it.isDigit() }) h.toInt() else b.toInt() and 0xFF).toString()
    }

    // ---------- Headphone protocol-3 commands (headphone app, CmdGen) ----------
    fun hpReqBattery() = build(HP_REQ_BATTERY, byteArrayOf(0x01, 0x00))
    fun hpSetAnc(on: Boolean) = build(HP_SET_ANC, byteArrayOf(if (on) 1 else 0))
    fun hpSetEqPreset(index: Int) = build(HP_SET_EQ_PRESET, byteArrayOf(index.toByte()))
}

class AaFrame(val cmd: Int, val payload: ByteArray)

/** What a speaker's device-info reply (`AA 12`) says. Fields the speaker didn't send stay null. */
data class SpeakerInfo(
    val index: Int,
    val name: String? = null,
    val productId: String? = null,
    val modelId: Int? = null,
    val battery: Battery? = null,
    val channel: Int? = null,
    val linkedCount: Int? = null,
    val audioSource: Int? = null,
    val mac: String? = null,
    val serial: String? = null,
    val secondaryFirmware: String? = null,
)

object SpeakerInfoParser {
    /**
     * Payload of `AA 12`: `[deviceIndex] (token value)*`. Token sizes come from the speaker app's
     * CommandProcessor.processVimicroDeviceInfoRes; an unknown token ends parsing (the app stops too).
     */
    fun parse(p: ByteArray): SpeakerInfo? {
        if (p.isEmpty()) return null
        var info = SpeakerInfo(index = p.u8(0))
        var i = 1
        while (i < p.size) {
            val t = p.u8(i)
            i++
            fun fixed(n: Int): ByteArray? = if (i + n <= p.size) p.copyOfRange(i, i + n).also { i += n } else null
            fun counted(): ByteArray? {
                if (i >= p.size) return null
                val n = p.u8(i)
                i++
                return fixed(n)
            }
            info = when (t) {
                Aa.T_MAC -> fixed(6)?.let { m -> info.copy(mac = m.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }) } ?: return info
                Aa.T_AURACAST, Aa.T_LLS, Aa.T_LLS_AIR_TOUCH, Aa.T_MIC -> { fixed(1) ?: return info; info }
                Aa.T_SECONDARY_FW -> fixed(4)?.let { info.copy(secondaryFirmware = Aa.formatVersion(it)) } ?: return info
                Aa.T_DAB_FW -> { fixed(4) ?: return info; info }
                Aa.T_SERIAL -> counted()?.let { info.copy(serial = String(it, Charsets.UTF_8)) } ?: return info
                Aa.T_AUDIO_SOURCE -> fixed(1)?.let { info.copy(audioSource = it.u8(0)) } ?: return info
                Aa.T_ACTIVE_CHANNEL -> fixed(1)?.let { info.copy(channel = it.u8(0)) } ?: return info
                Aa.T_LINKED_COUNT -> fixed(1)?.let { info.copy(linkedCount = it.u8(0)) } ?: return info
                Aa.T_BATTERY -> fixed(1)?.let { info.copy(battery = Battery.fromByte(it.u8(0))) } ?: return info
                Aa.T_MODEL_ID -> fixed(1)?.let { info.copy(modelId = it.u8(0)) } ?: return info
                // The product id is sent as two bytes, shown by the app as their hex digits in order.
                Aa.T_PRODUCT_ID -> fixed(2)?.let { info.copy(productId = it.hex()) } ?: return info
                Aa.T_DEVICE_NAME -> counted()?.let { info.copy(name = String(it, Charsets.UTF_8)) } ?: return info
                else -> return info
            }
        }
        return info
    }
}

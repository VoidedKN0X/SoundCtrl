package com.vknxworks.soundctrl.proto

/**
 * Harman "protocol 4", used by newer JBL speakers and headphones (speaker app: AssembleCmd,
 * CommandProcessor.processP4Command; headphone app: AssembleCmd4, DeviceInfoList4Cmd).
 * Frame: `00 DD | cmdId LE16 | packetCount | packetIndex | payloadLen LE16 | payload`. See PROTOCOL.md §3.
 */
object P4 {
    const val GET = 0x0001
    const val SET = 0x0002
    const val NOTIFY_APP = 0x0003
    const val START_OTA = 0x0101
    const val SEND_OTA = 0x0102
    const val STOP_OTA = 0x0103
    const val OTA_NOTIFICATION = 0x0104

    // Feature ids (speaker app DeviceFeature; headphone app protocol4_commands.json / DeviceInfoFeature5)
    const val F_PRODUCT_ID = 0x0004
    const val F_COLOR_ID = 0x0005
    const val F_DEVICE_NAME = 0x0006
    const val F_SERIAL = 0x0008
    const val F_MAC = 0x000A
    const val F_FIRMWARE = 0x000C
    const val F_BATTERY_LEFT = 0x000D
    const val F_BATTERY_RIGHT = 0x000E
    const val F_AUTO_OFF = 0x0012
    const val F_ANC = 0x0018
    const val F_AMBIENT = 0x0019
    const val F_TALKTHRU = 0x001A
    const val F_FEEDBACK_TONE = 0x0C40
    const val F_EQ_INFO = 0x0E02
    const val F_CASE_FIRMWARE = 0x1F01
    const val F_CASE_BATTERY = 0x1F03

    private const val PAYLOAD_PER_PACKET = 490

    private fun header(cmd: Int, count: Int, index: Int, len: Int) =
        byteArrayOf(0x00, 0xDD.toByte()) + le16(cmd) + byteArrayOf(count.toByte(), index.toByte()) + le16(len)

    /** Splits a payload into packets of at most 490 bytes, as the apps do. */
    fun frames(cmd: Int, payload: ByteArray): List<ByteArray> {
        val count = maxOf(1, (payload.size + PAYLOAD_PER_PACKET - 1) / PAYLOAD_PER_PACKET)
        return (0 until count).map { i ->
            val part = payload.copyOfRange(i * PAYLOAD_PER_PACKET, minOf(payload.size, (i + 1) * PAYLOAD_PER_PACKET))
            header(cmd, count, i, part.size) + part
        }
    }

    fun get(vararg features: Int): List<ByteArray> = frames(GET, features.fold(ByteArray(0)) { acc, f -> acc + le16(f) })

    fun set(feature: Int, value: ByteArray): List<ByteArray> = frames(SET, le16(feature) + le16(value.size) + value)

    /** Speaker start: `00 DD 01 01 01 00 04 00 size32` (speaker app, AssembleCmd.updateOTA). */
    fun speakerStartOta(size: Int) = header(START_OTA, 1, 0, 4) + le32(size.toLong())

    /** Headphone start: no payload (headphone app, OtaCtrl). */
    fun headphoneStartOta() = header(START_OTA, 1, 0, 0)

    fun stopOta() = header(STOP_OTA, 1, 0, 0)

    /** `00 DD 02 01 01 00 len offset32 data`. */
    fun otaData(offset: Long, data: ByteArray) = header(SEND_OTA, 1, 0, data.size + 4) + le32(offset) + data

    data class Tlv(val id: Int, val value: ByteArray)

    /** Parses `id LE16, len LE16, value` entries. */
    fun parseTlvs(b: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 0
        while (i + 4 <= b.size) {
            val id = b.u16le(i)
            val len = b.u16le(i + 2)
            if (i + 4 + len > b.size) break
            out += Tlv(id, b.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return out
    }

    /** Firmware version: one number per byte ("1.2.3"); the headphone app prints them as hex digits. */
    fun versionString(v: ByteArray, hexDigits: Boolean): String =
        v.joinToString(".") { if (hexDigits) "%x".format(it.toInt() and 0xFF) else (it.toInt() and 0xFF).toString() }
}

/** One parsed protocol-4 message: after reassembly. */
class P4Message(val cmd: Int, val status: Int?, val payload: ByteArray)

/**
 * Collects the packets of one message (each notification is one packet). Packet 0 of a reply to the
 * phone carries a status code before the payload.
 */
class P4Assembler {
    private val parts = sortedMapOf<Int, ByteArray>()
    private var status: Int? = null
    private var expected = -1
    private var expectedCmd = -1

    fun accept(b: ByteArray): P4Message? {
        if (b.size < 8 || b[0] != 0x00.toByte() || b[1] != 0xDD.toByte()) return null
        val cmd = b.u16le(2)
        val count = b.u8(4)
        val index = b.u8(5)
        val len = b.u16le(6)
        if (len != b.size - 8) return null
        if (cmd == P4.OTA_NOTIFICATION) return P4Message(cmd, null, b.copyOfRange(8, b.size))
        // A packet that can't belong to the message (index past the count) is dropped.
        if (count == 0 || index >= count) return null
        if (index == 0) {
            parts.clear()
            status = null
            expected = count
            expectedCmd = cmd
        } else if (count != expected || cmd != expectedCmd) {
            // A packet of another message, or one whose first packet was lost: start over.
            parts.clear()
            expected = -1
            return null
        }
        val body = if (index == 0 && b.size >= 10) {
            status = b.u16le(8)
            b.copyOfRange(10, b.size)
        } else {
            b.copyOfRange(8, b.size)
        }
        parts[index] = body
        if (parts.size < count) return null
        val all = parts.values.fold(ByteArray(0)) { acc, p -> acc + p }
        parts.clear()
        expected = -1
        return P4Message(cmd, status, all)
    }
}

/**
 * The device's OTA notification body (from byte 8 of the frame): status LE16, progress LE16
 * (10000 = 100.00 %), offset LE32, length to read LE32.
 */
data class P4OtaNotice(val status: Int, val progress: Int, val offset: Long, val length: Long) {
    /** The speaker app's end condition: progress 10000 with offset and length 0. */
    val speakerFinished: Boolean get() = progress == 10000 && offset == 0L && length == 0L

    /** The headphone app's end condition: nothing more to read. */
    val headphoneFinished: Boolean get() = length == 0L

    companion object {
        fun parse(body: ByteArray): P4OtaNotice? =
            if (body.size < 12) null else P4OtaNotice(body.u16le(0), body.u16le(2), body.u32le(4), body.u32le(8))
    }
}

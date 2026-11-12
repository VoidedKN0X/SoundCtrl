package com.vknxworks.soundctrl.proto

/**
 * The speaker update protocol of JBL speakers on the "AA" protocol (Vimicro/Actions chips), as the
 * speaker app's UpgradeFirmwareFileUtils, ReqDfuStartCommand, SetDfuDataCommand and DfuCRC do it.
 * Pure state machine: feed it the speaker's replies, send what it returns. See PROTOCOL.md §5.1.
 */
object DfuCrc {
    private const val POLY = 0xDB710641L
    private val table = LongArray(256) { i ->
        var c = i.toLong() shl 24
        repeat(8) { c = if (c and 0x80000000L != 0L) ((c shl 1) xor POLY) and 0xFFFFFFFFL else (c shl 1) and 0xFFFFFFFFL }
        c
    }

    fun tableValue(i: Int): Long = table[i]

    private fun update(crc: Long, b: Int): Long = (((crc shl 8) xor table[((crc shr 24) and 0xFF).toInt()]) xor b.toLong()) and 0xFFFFFFFFL

    /** The image checksum the speaker checks: the CRC over the file followed by four 0xFF bytes. */
    fun fileCrc(image: ByteArray): Long {
        var c = 0L
        for (b in image) c = update(c, b.toInt() and 0xFF)
        repeat(4) { c = update(c, 0xFF) }
        return c
    }
}

object SpeakerDfuCommands {
    fun reqDfuVersion() = Aa.build(Aa.REQ_DFU_VERSION)

    /**
     * `AA 43` start: CRC low half (big-endian), CRC high half, image length (big-endian 32-bit),
     * the first three numbers of the new version, then 1 when the speaker's half-done image is this
     * same version (continue) or 2 for a new image.
     */
    fun startPayload(crc: Long, length: Int, newVersion: String, resumeSameVersion: Boolean): ByteArray {
        val c = be32(crc)
        val parts = newVersion.split('.')
        require(parts.size >= 3) { "version needs three parts" }
        val v = ByteArray(3) { parts[it].trim().toInt().toByte() }
        return byteArrayOf(c[2], c[3], c[0], c[1]) + be32(length.toLong()) + v + byteArrayOf(if (resumeSameVersion) 1 else 2)
    }

    fun start(crc: Long, length: Int, newVersion: String, resumeSameVersion: Boolean) =
        Aa.build(Aa.REQ_DFU_START, startPayload(crc, length, newVersion, resumeSameVersion))

    /** `AA 44 [packet index (low byte)] data`. */
    fun data(packetIndex: Int, chunk: ByteArray) = Aa.build(Aa.SET_DFU_DATA, byteArrayOf(packetIndex.toByte()) + chunk)

    fun cancel() = Aa.build(Aa.NOTIFY_DFU_CANCEL, byteArrayOf(1))
    fun applyNewFirmware() = Aa.build(Aa.REQ_TRIGGER_UPDATE)

    /** `AA 4B` reply: the version of the image the speaker has (partly) received, e.g. 01 02 03 -> "1.2.3". */
    fun parseDfuVersion(payload: ByteArray): String = Aa.formatVersion(payload)

    /**
     * Data per packet. The app uses MTU - 7 over BLE. The length field is one byte, so SoundCtrl also
     * caps it at 250 (inferred: identical to the app whenever the MTU is 257 or less).
     */
    fun chunkSize(mtu: Int): Int = (mtu - 7).coerceIn(16, 250)
}

/** What the speaker asks for next. */
sealed interface DfuAction {
    data class Send(val frame: ByteArray, val offset: Long, val total: Long) : DfuAction
    data object Ignore : DfuAction
    data object TransferDone : DfuAction
    data class Failed(val reason: String) : DfuAction
}

/**
 * Drives one transfer. The speaker paces it: after `AA 43` it answers `AA 45 01 [offset32 x]`
 * (start, or continue at offset); each `AA 45 02 idx` acknowledges a packet; `AA 45 03` = all
 * received; `AA 45 00` = error. A repeated index means "send that packet again".
 */
class SpeakerDfuSession(private val image: ByteArray, private val chunk: Int) {
    var startOffset = 0L
        private set
    private var packageIndex = 0
    private var previousRemote = -1
    private var previousSuccess = 0
    private var failures = 0
    var lastSent: DfuAction.Send? = null
        private set

    private fun packet(index: Int): DfuAction {
        val from = startOffset + index.toLong() * chunk
        if (from >= image.size) return DfuAction.Ignore
        val to = minOf(image.size.toLong(), from + chunk)
        val send = DfuAction.Send(SpeakerDfuCommands.data(index, image.copyOfRange(from.toInt(), to.toInt())), to, image.size.toLong())
        lastSent = send
        return send
    }

    fun onStatus(payload: ByteArray): DfuAction {
        if (payload.isEmpty()) return DfuAction.Ignore
        return when (payload.u8(0)) {
            1 -> {
                startOffset = if (payload.size == 6) payload.u32be(1).let { if (it > image.size) 0L else it } else 0L
                packageIndex = 0
                previousRemote = -1
                failures = 0
                packet(0)
            }
            2 -> {
                // The app ignores a missing index and the index 0xFF.
                if (payload.size != 2 || payload.u8(1) == 0xFF) return DfuAction.Ignore
                val remote = payload[1].toInt()
                if (remote != previousRemote) {
                    failures = 0
                    packageIndex++
                    previousSuccess = packageIndex
                    previousRemote = remote
                } else {
                    packageIndex = previousSuccess
                    failures++
                }
                if (failures >= MAX_REPEATS) DfuAction.Failed("speaker asked for the same packet $MAX_REPEATS times") else packet(packageIndex)
            }
            3 -> DfuAction.TransferDone
            0 -> DfuAction.Failed("speaker reported an error")
            else -> DfuAction.Ignore
        }
    }

    companion object {
        const val MAX_REPEATS = 50
    }
}

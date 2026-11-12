package com.vknxworks.soundctrl.proto

/**
 * Airoha "RACE" frames, used read-only by SoundCtrl to ask Airoha-chip JBL headphones for their
 * firmware version (headphone app: com.airoha.libbase RacePacket, libmmi MmiStageGetFwVersion).
 * Frame: `05 type len LE16 raceId LE16 payload`, len = 2 + payload length.
 */
object Race {
    const val CMD_NEEDS_RESPONSE = 0x5A
    const val RESPONSE = 0x5B
    const val INDICATION = 0x5D
    const val GET_FW_VERSION = 0x1C07
    const val AGENT = 0
    const val PARTNER = 1

    fun build(type: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0x05, type.toByte()) + le16(payload.size + 2) + le16(id) + payload

    fun getFwVersion(role: Int) = build(CMD_NEEDS_RESPONSE, GET_FW_VERSION, byteArrayOf(role.toByte()))

    /** The version reply (type 5D, id 1C07): status [6] (0 = OK), role [7], length [8], ASCII from [9]. */
    fun parseFwVersion(b: ByteArray): Pair<Int, String>? {
        if (b.size < 9 || b.u8(0) != 0x05 || b.u8(1) != INDICATION || b.u16le(4) != GET_FW_VERSION) return null
        if (b.u8(6) != 0) return null
        val n = b.u8(8)
        if (n == 0 || 9 + n > b.size) return null
        return b.u8(7) to String(b.copyOfRange(9, 9 + n), Charsets.US_ASCII).trim { it <= ' ' }
    }
}

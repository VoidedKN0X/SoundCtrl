package com.vknxworks.soundctrl.proto

/** Small byte helpers shared by the protocol code. Pure Kotlin, unit-tested. */
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun hexToBytes(s: String): ByteArray {
    val clean = s.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u32le(i: Int): Long = (u8(i).toLong()) or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
internal fun ByteArray.u32be(i: Int): Long = (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())
internal fun le32(v: Long): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
internal fun be32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

/** A battery byte as JBL devices report it: bit 7 = charging, bits 0-6 = percent. */
data class Battery(val percent: Int, val charging: Boolean) {
    companion object {
        fun fromByte(b: Int): Battery = Battery((b and 0x7F).coerceAtMost(100), (b and 0x80) != 0)
    }
}

/**
 * Compares dotted versions numerically ("1.10.0" > "1.9.3"). Missing parts count as 0.
 * Returns null when either side has a part that isn't a number, so callers refuse rather than guess.
 */
fun compareVersions(a: String, b: String): Int? {
    val pa = a.trim().split('.').map { it.trim().toIntOrNull() ?: return null }
    val pb = b.trim().split('.').map { it.trim().toIntOrNull() ?: return null }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

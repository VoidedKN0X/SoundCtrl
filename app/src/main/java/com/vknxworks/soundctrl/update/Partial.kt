package com.vknxworks.soundctrl.update

import java.io.IOException

/**
 * A firmware download so far, in memory only (firmware never goes to disk). [count] bytes have arrived. Only a
 * download whose size the server gave ([total] > 0) can be resumed, because then the buffer is the right size
 * and the rest can be checked against it.
 */
class PartialImage private constructor(val url: String, val total: Long, val validator: String?, private var buf: ByteArray) {
    var count: Int = 0
        private set

    val resumable: Boolean get() = total > 0

    /** Adds [n] bytes of [src]; false when that would go past [max] bytes or past the size the server gave. */
    fun append(src: ByteArray, n: Int, max: Int): Boolean {
        val needed = count + n
        if (needed > max || (total > 0 && needed > total)) return false
        if (needed > buf.size) buf = buf.copyOf(maxOf(needed, minOf(max, buf.size * 2)))
        System.arraycopy(src, 0, buf, count, n)
        count = needed
        return true
    }

    /** The image as received; the same array when it filled the buffer exactly, so it isn't held twice. */
    fun bytes(): ByteArray = if (count == buf.size) buf else buf.copyOf(count)

    companion object {
        fun start(url: String, total: Long, validator: String?): PartialImage =
            PartialImage(url, total, validator, ByteArray(if (total > 0) total.toInt() else 64 * 1024))
    }
}

/** The connection broke during a download. [partial] is what arrived, when it can be resumed; null to start over. */
class DownloadInterrupted(val partial: PartialImage?, cause: Throwable) : IOException(cause.message, cause)

/** `Content-Range: bytes first-last/total` (RFC 9110, 14.4). Null for anything else, including an unknown total. */
data class ContentRange(val first: Long, val last: Long, val total: Long) {
    companion object {
        private val PATTERN = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+)\s*$""", RegexOption.IGNORE_CASE)

        fun parse(header: String?): ContentRange? {
            val m = PATTERN.matchEntire(header ?: return null) ?: return null
            val first = m.groupValues[1].toLongOrNull() ?: return null
            val last = m.groupValues[2].toLongOrNull() ?: return null
            val total = m.groupValues[3].toLongOrNull() ?: return null
            if (first > last || last >= total) return null
            return ContentRange(first, last, total)
        }
    }
}

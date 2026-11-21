package com.vknxworks.soundctrl.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces a resumed firmware download relies on. */
class ResumeTest {
    @Test fun contentRangeIsRead() {
        assertEquals(ContentRange(100, 199, 200), ContentRange.parse("bytes 100-199/200"))
        assertEquals(ContentRange(0, 0, 1), ContentRange.parse("BYTES 0-0/1"))
    }

    @Test fun unusableContentRangesAreRefused() {
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse("bytes 100-199/*"))
        assertNull(ContentRange.parse("bytes */200"))
        assertNull(ContentRange.parse("bytes 200-100/300"))
        assertNull(ContentRange.parse("bytes 100-200/200"))
    }

    @Test fun partialNeverGrowsPastTheGivenSize() {
        val p = PartialImage.start("u", 10, null)
        val six = ByteArray(6) { it.toByte() }
        assertTrue(p.append(six, 6, 100))
        assertFalse(p.append(six, 6, 100))
        assertTrue(p.append(six, 4, 100))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 0, 1, 2, 3), p.bytes())
        assertTrue(p.resumable)
    }

    @Test fun unknownSizeGrowsButIsNotResumable() {
        val p = PartialImage.start("u", -1, null)
        val chunk = ByteArray(40_000)
        assertTrue(p.append(chunk, chunk.size, 1_000_000))
        assertTrue(p.append(chunk, chunk.size, 1_000_000))
        assertEquals(80_000, p.bytes().size)
        assertFalse(p.resumable)
        assertFalse(p.append(chunk, chunk.size, 100_000))
    }
}

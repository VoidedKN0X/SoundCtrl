package com.vknxworks.soundctrl.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Device packets are untrusted: broken or mixed-up ones are dropped, never glued into a message. */
class HardeningTest {
    private fun h(s: String) = hexToBytes(s)

    @Test fun packetIndexPastTheCountIsDropped() {
        val a = P4Assembler()
        assertNull(a.accept(h("00dd01000105" + "0200" + "0000")))
        assertNull(a.accept(h("00dd01000000" + "0200" + "0000")))
    }

    @Test fun packetsOfAnotherMessageDontMix() {
        val a = P4Assembler()
        // First packet of a two-packet GET reply...
        assertNull(a.accept(h("00dd01000200" + "0600" + "0000" + "06000500")))
        // ...then the second packet of a SET reply: dropped, and the half message is thrown away.
        assertNull(a.accept(h("00dd02000201" + "0500" + "4a424c2021")))
        assertNull(a.accept(h("00dd01000201" + "0500" + "4a424c2021")))
        // A whole message afterwards still works.
        val m = a.accept(h("00dd01000100" + "0600" + "0000" + "0c000000"))!!
        assertEquals(P4.GET, m.cmd)
        assertEquals(0, m.status)
    }

    @Test fun lengthsLargerThanTheDataAreClamped() {
        assertEquals(emptyList<P4.Tlv>(), P4.parseTlvs(h("0c00ff00" + "01")))
        assertNull(Race.parseFwVersion(h("055d0000071c" + "00" + "00" + "ff" + "3132")))
        val f = Aa.parse(h("aa4205" + "0102"))!!
        assertEquals(2, f.payload.size)
        assertNull(P4OtaNotice.parse(ByteArray(11)))
        // A token whose length runs past the end stops the parse with what was read so far.
        val info = SpeakerInfoParser.parse(h("00" + "4402" + "c1" + "10" + "41"))!!
        assertEquals(2, info.battery?.percent)
        assertNull(info.name)
    }
}

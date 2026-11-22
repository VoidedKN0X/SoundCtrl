package com.vknxworks.soundctrl.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tour Pro 3 settings (PROTOCOL.md §7.4), byte for byte against the JBL app's stages. */
class RaceTourPro3Test {
    private fun h(s: String) = hexToBytes(s)

    @Test fun gatedToTourPro3() {
        assertTrue(RaceTourPro3.isTourPro3("20F2"))
        assertTrue(RaceTourPro3.isTourPro3("20fd"))
        assertFalse(RaceTourPro3.isTourPro3("2089"))
        assertFalse(RaceTourPro3.isTourPro3(null))
    }

    @Test fun commandsAreByteExact() {
        assertEquals("055a040001090100", RaceTourPro3.getVpOnOff().hex())
        assertEquals("055a05000009010000", RaceTourPro3.setVpOff().hex())
        assertEquals("055a0400832c0900", RaceTourPro3.getVpLanguage().hex())
        assertEquals("055a0500822c0900ff", RaceTourPro3.setVpLanguage(RaceTourPro3.VP_TONES).hex())
        assertEquals("055a0500822c090000", RaceTourPro3.setVpLanguage(RaceTourPro3.VP_SPOKEN).hex())
        assertEquals("055a0400832c0700", RaceTourPro3.getSidetoneState().hex())
        assertEquals("055a0400832c0600", RaceTourPro3.getSidetoneLevel().hex())
        assertEquals("055a0500822c070001", RaceTourPro3.setSidetoneState(true).hex())
        assertEquals("055a0600822c06000200", RaceTourPro3.setSidetoneLevel(2).hex())
        assertEquals("055a0400170e0302", RaceTourPro3.getAdaptiveAnc().hex())
        assertEquals("055a0400170e0301", RaceTourPro3.setAdaptiveAnc(true).hex())
        assertEquals("055a0400170e0300", RaceTourPro3.setAdaptiveAnc(false).hex())
        assertEquals("055a02006000", RaceTourPro3.getSmartTalk().hex())
        assertEquals("055a040061000114", RaceTourPro3.setSmartTalk(true, 20).hex())
        assertEquals("055a040061000005", RaceTourPro3.setSmartTalk(false, 5).hex())
        assertEquals("055a02007500", RaceTourPro3.getHiRes().hex())
        assertEquals("055a0300760001", RaceTourPro3.setHiRes(true).hex())
        assertEquals("055a0300760000", RaceTourPro3.setHiRes(false).hex())
    }

    @Test fun spatialHarmanFrames() {
        assertEquals("aa06030001002e", RaceTourPro3.hmGet(0x2E).hex())
        assertEquals("055a09009300aa06030001002e", RaceTourPro3.getSpatial().hex())
        assertEquals("055a0c009300aa06060000002e020102", RaceTourPro3.setSpatial(RaceTourPro3.SPATIAL_HEAD_TRACKING).hex())
        assertEquals("055a0c009300aa06060000002e020101", RaceTourPro3.setSpatial(RaceTourPro3.SPATIAL_FIXED).hex())
        assertEquals("055a0c009300aa06060000002e020000", RaceTourPro3.setSpatial(RaceTourPro3.SPATIAL_OFF).hex())
        // 5B 0093 [status][AA 06 len 02 (002E 02 on type)]
        assertEquals(RaceTourPro3.SPATIAL_HEAD_TRACKING, RaceTourPro3.parseSpatial(h("055b0d00930000aa06060002002e020102")))
        assertEquals(RaceTourPro3.SPATIAL_OFF, RaceTourPro3.parseSpatial(h("055b0d00930000aa06060002002e020002")))
        assertEquals(RaceTourPro3.SPATIAL_FIXED, RaceTourPro3.parseSpatial(h("055d0c009300aa06060002002e020101")))
        assertNull(RaceTourPro3.parseSpatial(h("055b0300930000"))) // a bare ack
        assertNull(RaceTourPro3.parseSpatial(h("055b0d00930001aa06060002002e020102"))) // refused
    }

    @Test fun replies() {
        assertEquals(true, RaceTourPro3.parseVpOnOff(h("055b0600010901000001")))
        assertEquals(false, RaceTourPro3.parseVpOnOff(h("055b0600010901000000")))
        assertNull(RaceTourPro3.parseVpOnOff(h("055b0600010905000001"))) // the noise-control module
        assertEquals(true, RaceTourPro3.parseSetVpOff(h("055b05000009010000")))
        assertEquals(0xFF, RaceTourPro3.parseVpLanguage(h("055b0600832c000900ff")))
        assertEquals(true, RaceTourPro3.parseSidetoneState(h("055b0600832c00070001")))
        assertEquals(3, RaceTourPro3.parseSidetoneLevel(h("055b0700832c0006000300")))
        assertNull(RaceTourPro3.parseSidetoneLevel(h("055b0600832c00060003"))) // too short
        assertEquals(true, RaceTourPro3.parseAdaptiveAnc(h("055b0500170e000301")))
        assertEquals(false, RaceTourPro3.parseAdaptiveAnc(h("055b0500170e000300")))
        assertEquals(true, RaceTourPro3.parseSetAdaptiveAnc(h("055b0500170e000301")))
        assertEquals(false, RaceTourPro3.parseSetAdaptiveAnc(h("055b0500170e010300")))
        assertEquals(true to 15, RaceTourPro3.parseSmartTalk(h("055b0500600000010f")))
        assertEquals(true, RaceTourPro3.parseHiRes(h("055b040075000001")))
        assertNull(RaceTourPro3.parseHiRes(h("055b0300750000"))) // under 8 bytes
        assertEquals(true, RaceTourPro3.parseStatus(h("055b0300610000"), RaceTourPro3.SMART_TALK_SET))
        assertEquals(false, RaceTourPro3.parseStatus(h("055b0300610003"), RaceTourPro3.SMART_TALK_SET))
        assertNull(RaceTourPro3.parseStatus(h("055b0300760000"), RaceTourPro3.HI_RES_SET, minSize = 8))
    }

    @Test fun relay() {
        // 5B 0D00: (type, id) pairs; the partner is type 5
        assertEquals(5 to 0x02, RaceTourPro3.parsePartnerDst(h("055b0600000d03010502")))
        assertNull(RaceTourPro3.parsePartnerDst(h("055b0400000d0301")))
        val inner = RaceGestures.read()
        assertEquals("055a0e00010d0502" + inner.hex(), RaceTourPro3.relay(5 to 2, inner).hex())
        // 5D 0D01 [type id] inner(5B 0A00 len data)
        val reply = h("055d0e00010d0502" + "055b0600000a02000102")
        assertArrayEquals(h("055b0600000a02000102"), RaceTourPro3.unwrapRelay(reply))
        assertEquals(0, RaceTourPro3.relayAckStatus(h("055b0300010d00")))
        assertNull(RaceTourPro3.unwrapRelay(h("055b0300010d00")))
    }

    @Test fun gestureCommands() {
        assertEquals("055a0600000ad6f26400", RaceGestures.read().hex())
        assertEquals("055a0400090ad6f2", RaceGestures.reload().hex())
        assertEquals("01" + "7e" + "0a00" + "00000000", RaceGestures.entry(RaceGestures.SHORT_CLICK, 0x7E, RaceGestures.VOICE_UP).hex())
        // Play/pause is two entries; the voice assistant three (with the interrupt on short click and long press 1)
        assertEquals("027e530000000000" + "027e550000000000", RaceGestures.entries(3, 8, 0x7E).hex())
        assertEquals(
            "047e200000000000" + "017e230000000000" + "047e230000000000",
            RaceGestures.entries(49, RaceGestures.A_VOICE_ASSISTANT, 0x7E).hex(),
        )
        assertEquals("017e26f000000000", RaceGestures.entries(2, RaceGestures.A_ANC_AMBIENT, 0x7E).hex())
        assertEquals(0, RaceGestures.entries(1, 0, 0x7E).size)
        // An earbud with nothing set gets one empty entry
        assertEquals("007e000000000000", RaceGestures.keyMap(emptyMap(), right = true, keyId = 0x7E).hex())
        val w = RaceGestures.write(RaceGestures.NV_PEER_KEY_MAP, RaceGestures.entry(0, 0x7E, 0))
        assertEquals("055a0c00010aeff2007e000000000000", w.hex())
    }

    @Test fun gestureKeyMapRoundTrip() {
        // Left: volume preset; right: ambient preset
        val g = mapOf(1 to 1, 3 to 2, 7 to 0, 49 to 161, 5 to 0, 2 to 11, 4 to 4, 8 to 0, 50 to 161, 6 to 0)
        val left = RaceGestures.keyMap(g, right = false, keyId = 0x7E)
        val right = RaceGestures.keyMap(g, right = true, keyId = 0x7E)
        assertEquals(
            "017e0a0000000000" + "027e0b0000000000" + "047e200000000000" + "017e230000000000" + "047e230000000000",
            left.hex(),
        )
        val l = RaceGestures.parse(left, right = false)
        assertEquals(mapOf(1 to 1, 3 to 2, 7 to 0, 49 to 161, 5 to 0), l.gestures)
        assertEquals(0x7E, l.keyId)
        val r = RaceGestures.parse(right, right = true)
        assertEquals(mapOf(2 to 11, 4 to 4, 8 to 0, 50 to 161, 6 to 0), r.gestures)
        assertEquals(RaceGestures.Preset.VOLUME, RaceGestures.presetOf(l.gestures, right = false))
        assertEquals(RaceGestures.Preset.AMBIENT, RaceGestures.presetOf(r.gestures, right = true))
        // An ambient cycle other than ANC/Ambient on the tap still counts as the ambient-sound control
        assertEquals(RaceGestures.Preset.AMBIENT, RaceGestures.presetOf(r.gestures + (2 to 9), right = true))
        assertNull(RaceGestures.presetOf(l.gestures + (3 to 5), right = false))
        // Empty key map: everything "nothing", key id unknown
        val empty = RaceGestures.parse(ByteArray(0), right = false)
        assertEquals(RaceGestures.Preset.NONE, RaceGestures.presetOf(empty.gestures, right = false))
        assertNull(empty.keyId)
    }

    @Test fun gestureIds() {
        assertEquals(50, RaceGestures.gestureId(RaceGestures.LONG_PRESS_1, right = true))
        assertEquals(5, RaceGestures.gestureId(RaceGestures.LONG_PRESS_2, right = false))
        assertEquals(RaceGestures.TRIPLE_CLICK, RaceGestures.keyType(8))
        assertNull(RaceGestures.keyType(9))
    }
}

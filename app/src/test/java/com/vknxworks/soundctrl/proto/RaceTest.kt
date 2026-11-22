package com.vknxworks.soundctrl.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Airoha RACE commands and replies (PROTOCOL.md §7), byte for byte. */
class RaceTest {
    private fun h(s: String) = hexToBytes(s)

    @Test fun commandsAreByteExact() {
        assertEquals("055a0300d60c00", Race.getBattery(Race.AGENT).hex())
        assertEquals("055a0300d60c01", Race.getBattery(Race.PARTNER).hex())
        assertEquals("055a0300090001", Race.getChargeInfo(Race.PARTNER).hex())
        assertEquals("055a02000b00", Race.getCaseBattery().hex())
        assertEquals("055a0200d40c", Race.getAgentIsRight().hex())
        assertEquals("055a0200000d", Race.getAvailableDestinations().hex())
        assertEquals("055a0600000a0210e803", Race.getChipName().hex())
        assertEquals("055a0600000a03106400", Race.getModelName(AirohaFamily.AB158X).hex())
        assertEquals("055a0600000a00f56400", Race.getModelName(AirohaFamily.AB1568).hex())
        assertEquals("055a040001090500", Race.getNoise().hex())
        assertEquals("055a0400060e000b", Race.setNoise(NoiseMode.OFF).hex())
        assertEquals("055a0600060e000a0100", Race.setNoise(NoiseMode.ANC).hex())
        assertEquals("055a0600060e000a0904", Race.setNoise(NoiseMode.AMBIENT).hex())
        assertEquals("055a0600060e000a0a04", Race.setNoise(NoiseMode.TALKTHRU).hex())
        assertEquals("055a0400832c0100", Race.getCommonConfig(Race.MODULE_AUTO_POWER_OFF).hex())
        assertEquals("055a0800822c01000100100e", Race.setAutoOff(true, 60).hex())
        assertEquals("055a0800822c010000000807", Race.setAutoOff(false, 30).hex())
        assertEquals("055a0500822c020001", Race.setInEarPlayPause(true).hex())
        assertEquals("055a0500822c020000", Race.setInEarPlayPause(false).hex())
        assertEquals("055a0500012c000100", Race.findMe(Race.FIND_LEFT, true).hex())
        assertEquals("055a0500012c000001", Race.findMe(Race.FIND_RIGHT, false).hex())
    }

    @Test fun batteryReplies() {
        assertEquals(1 to 87, Race.parseBattery(h("055d0500d60c000157")))
        assertNull(Race.parseBattery(h("055d0500d60c010157"))) // status not 0
        assertNull(Race.parseBattery(h("055b0500d60c000157"))) // a 5B ack, not the answer
        assertNull(Race.parseBattery(h("055d0500d60c0001c8"))) // 200 %: not a level
        assertEquals(0 to true, Race.parseChargeInfo(h("055d0500090000" + "0001")))
        assertNull(Race.parseChargeInfo(h("055b030009" + "0000"))) // the first ack
        assertEquals(64, Race.parseCaseBattery(h("055d04000b000040")))
        assertNull(Race.parseCaseBattery(h("055d04000b0000ff"))) // -1: not reported
        assertEquals(true, Race.parseAgentIsRight(h("055b0400d40c0001")))
        assertEquals(false, Race.parseAgentIsRight(h("055b0400d40c0000")))
        assertEquals(true, Race.parsePartnerExists(h("055b0600000d" + "0600" + "0501")))
        assertEquals(false, Race.parsePartnerExists(h("055b0400000d" + "0600")))
    }

    @Test fun noiseReplies() {
        assertEquals(NoiseMode.ANC, Race.parseNoise(h("055b0800" + "0109" + "0500" + "00" + "01" + "6400")))
        assertEquals(NoiseMode.AMBIENT, Race.parseNoise(h("055b0600" + "0109" + "0500" + "00" + "09")))
        // A push (5D) carries the same layout.
        assertEquals(NoiseMode.TALKTHRU, Race.parseNoise(h("055d0800" + "0109" + "0500" + "00" + "0a" + "0700")))
        assertEquals(NoiseMode.OFF, Race.parseNoise(h("055d0800" + "0109" + "0500" + "00" + "00" + "0000")))
        assertNull(Race.parseNoise(h("055b0800" + "0109" + "0700" + "00" + "01" + "6400"))) // another module
        assertNull(Race.parseNoise(h("055b0800" + "0109" + "0500" + "00" + "0b" + "6400"))) // filter not mapped
        assertEquals(true, Race.parseSetNoise(h("055b0800060e000a09040000"), NoiseMode.AMBIENT))
        assertEquals(true, Race.parseSetNoise(h("055b0800060e000b0b000000"), NoiseMode.OFF))
        assertEquals(false, Race.parseSetNoise(h("055b0400060e010a"), NoiseMode.ANC))
        assertNull(Race.parseSetNoise(h("055b0400060e000b"), NoiseMode.ANC)) // the reply to a different request
    }

    @Test fun passThroughFiltersPerModel() {
        // Other Airoha models (unconfirmed): the JBL app's code reading, Ambient Aware 9, TalkThru 10.
        assertEquals(9 to 10, Race.passThroughFilters(null))
        assertEquals(9 to 10, Race.passThroughFilters("2089"))
        assertEquals("055a0600060e000a0904", Race.setNoise(NoiseMode.AMBIENT, "2089").hex())
        assertEquals("055a0600060e000a0a04", Race.setNoise(NoiseMode.TALKTHRU, "2089").hex())
        // Tour Pro 3 (earbuds 20f2, case 20fd): confirmed on a real device the other way round.
        assertEquals(10 to 9, Race.passThroughFilters("20f2"))
        assertEquals(10 to 9, Race.passThroughFilters("20F2"))
        assertEquals(10 to 9, Race.passThroughFilters("20fd"))
        assertEquals("055a0600060e000a0a04", Race.setNoise(NoiseMode.AMBIENT, "20f2").hex())
        assertEquals("055a0600060e000a0904", Race.setNoise(NoiseMode.TALKTHRU, "20f2").hex())
        // ANC and off don't change.
        assertEquals("055a0600060e000a0100", Race.setNoise(NoiseMode.ANC, "20f2").hex())
        assertEquals("055a0400060e000b", Race.setNoise(NoiseMode.OFF, "20f2").hex())
        // Status (reply or push) read back the same way.
        val filter9 = h("055d0800" + "0109" + "0500" + "00" + "09" + "0000")
        val filter10 = h("055b0800" + "0109" + "0500" + "00" + "0a" + "0000")
        assertEquals(NoiseMode.AMBIENT, Race.parseNoise(filter9, "2089"))
        assertEquals(NoiseMode.TALKTHRU, Race.parseNoise(filter10, "2089"))
        assertEquals(NoiseMode.TALKTHRU, Race.parseNoise(filter9, "20f2"))
        assertEquals(NoiseMode.AMBIENT, Race.parseNoise(filter10, "20f2"))
        assertEquals(NoiseMode.ANC, Race.parseNoise(h("055b0800" + "0109" + "0500" + "00" + "01" + "6400"), "20f2"))
        assertEquals(NoiseMode.OFF, Race.parseNoise(h("055d0800" + "0109" + "0500" + "00" + "00" + "0000"), "20f2"))
    }

    @Test fun commonConfigReplies() {
        assertEquals(true to 60, Race.parseAutoOff(h("055b0900832c" + "00" + "0100" + "0100" + "100e")))
        assertEquals(false to 30, Race.parseAutoOff(h("055b0900832c" + "00" + "0100" + "0000" + "0807")))
        assertNull(Race.parseAutoOff(h("055b0900832c" + "00" + "0200" + "0100" + "100e")))
        assertEquals(true, Race.parseInEarPlayPause(h("055b0600832c" + "00" + "0200" + "01")))
        assertNull(Race.parseInEarPlayPause(h("055b0600832c" + "00" + "0900" + "00")))
        assertEquals(true, Race.parseSetCommonConfig(h("055b0500822c000200"), Race.MODULE_IN_EAR_PLAY_PAUSE))
        assertNull(Race.parseSetCommonConfig(h("055b0500822c000200"), Race.MODULE_AUTO_POWER_OFF))
        assertEquals(false, Race.parseSetCommonConfig(h("055b0500822c010100"), Race.MODULE_AUTO_POWER_OFF))
        assertEquals(true, Race.parseFindMe(h("055b0300012c00")))
    }

    @Test fun pushStatusAndStatusByte() {
        assertEquals("055a0300060001", Race.setDevicePushStatus(true).hex())
        assertEquals(0x03, Race.statusOf(h("055b0300060e03")))
        assertEquals(0x02, Race.statusOf(h("055b0600" + "0109" + "0500" + "02" + "01")))
        assertNull(Race.statusOf(h("0102")))
    }

    @Test fun chipAndModel() {
        val chip = h("055b0c00000a" + "0800") + "AB1585AM".toByteArray()
        assertEquals("AB1585AM", Race.parseChipName(chip))
        assertEquals(AirohaFamily.AB158X, AirohaFamily.fromChipName("AB1585AM"))
        assertEquals(AirohaFamily.AB1568, AirohaFamily.fromChipName("ab1568_dual_chip"))
        assertEquals(AirohaFamily.AB1562, AirohaFamily.fromChipName("AB1562E"))
        assertEquals(AirohaFamily.AB155X, AirohaFamily.fromChipName("AB1552"))
        assertTrue(AirohaFamily.AB158X.hasMmiSettings)
        assertFalse(AirohaFamily.AB155X.hasMmiSettings)
        assertFalse(AirohaFamily.AB1562.hasBasics)
        // Model name: 40 bytes of data, the second 20 hold the product id.
        val data = ByteArray(20) + "20F2".toByteArray() + ByteArray(16)
        val model = h("055b2c00000a" + "2800") + data
        assertEquals("20F2", Race.parseModelName(model))
        assertNull(Race.parseModelName(h("055b0600000a" + "0200" + "0000")))
    }

    @Test fun framesAreCutFromAStream() {
        val a = RaceAssembler()
        // Two frames in one read, the second split over two reads, with noise in front.
        val one = "055b0400d40c0000"
        val two = "055d0500d60c000157"
        assertEquals(listOf(one), a.accept(h("ff00" + one + two.substring(0, 6))).map { it.hex() })
        assertEquals(listOf(two), a.accept(h(two.substring(6))).map { it.hex() })
        // A length of 0 is skipped as noise.
        assertEquals(listOf(one), a.accept(h("05000000" + one)).map { it.hex() })
        // Start byte 15 is accepted, as in the app's reader.
        assertEquals(1, a.accept(h("155b0400d40c0000")).size)
        assertNull(Race.parse(h("055b0500d40c0000"))) // length doesn't fit
    }
}

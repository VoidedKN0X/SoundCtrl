package com.vknxworks.soundctrl.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun h(s: String) = hexToBytes(s)

    // ---------- AA frames ----------

    @Test fun aaBuildAndParse() {
        assertEquals("aa1100", Aa.reqDeviceInfo().hex())
        assertEquals("aa630101", Aa.setEqMode(1).hex())
        assertEquals("aa670100", Aa.setFeedback(false).hex())
        assertEquals("aa6a0102", Aa.setLinkMode(2).hex())
        assertEquals("aa150300" + "4602", Aa.setChannel(0, 2).hex())
        val f = Aa.parse(h("aa4203010203"))!!
        assertEquals(0x42, f.cmd)
        assertEquals("010203", f.payload.hex())
        assertNull(Aa.parse(h("0102")))
    }

    @Test fun aaRenameCutsTo16() {
        val f = Aa.setName(0, "Party speaker in the garden")
        assertEquals(0xAA, f.u8(0))
        assertEquals(0x15, f.u8(1))
        assertEquals(19, f.u8(2))
        assertEquals("00c110", f.copyOfRange(3, 6).hex())
        assertEquals("Party speaker in", String(f.copyOfRange(6, f.size)))
    }

    @Test fun aaAck() {
        assertTrue(Aa.isAckOk(Aa.parse(h("aa00026300"))!!, 0x63))
        assertFalse(Aa.isAckOk(Aa.parse(h("aa00026301"))!!, 0x63))
    }

    @Test fun versionFormat() {
        assertEquals("1.2.3", Aa.formatVersion(h("010203")))
        assertEquals("10.0.12", Aa.formatVersion(h("100012")))
    }

    @Test fun speakerInfoTokens() {
        // index 0, name "Flip", pid 20 4f, model 01, battery 0x80|57 (charging 87 %), channel 0, MAC.
        val p = h("00" + "c104" + "466c6970" + "42204f" + "4301" + "44d7" + "4600" + "48aabbccddeeff")
        val i = SpeakerInfoParser.parse(p)!!
        assertEquals(0, i.index)
        assertEquals("Flip", i.name)
        assertEquals("204f", i.productId)
        assertEquals(1, i.modelId)
        assertEquals(Battery(87, true), i.battery)
        assertEquals(0, i.channel)
        assertEquals("AA:BB:CC:DD:EE:FF", i.mac)
    }

    @Test fun speakerInfoStopsAtUnknownToken() {
        val i = SpeakerInfoParser.parse(h("00" + "4450" + "99" + "4301"))!!
        assertEquals(Battery(80, false), i.battery)
        assertNull(i.modelId)
    }

    // ---------- speaker update ----------

    @Test fun dfuCrcTableMatchesTheApp() {
        // Values from the speaker app's DfuCRC.revCrcTable (as unsigned 32-bit).
        assertEquals(0L, DfuCrc.tableValue(0))
        assertEquals(0xDB710641L, DfuCrc.tableValue(1))
        assertEquals(0x6D930AC3L, DfuCrc.tableValue(2))
        assertEquals(0xB6E20C82L, DfuCrc.tableValue(3))
        assertEquals(1701301141L, DfuCrc.tableValue(128))
        assertEquals(258633945L, DfuCrc.tableValue(255))
        assertEquals(1660516890L, DfuCrc.tableValue(253))
        assertEquals((-736397672L) and 0xFFFFFFFFL, DfuCrc.tableValue(254))
    }

    @Test fun dfuCrcIsStable() {
        val a = DfuCrc.fileCrc(ByteArray(1000) { it.toByte() })
        assertEquals(a, DfuCrc.fileCrc(ByteArray(1000) { it.toByte() }))
        assertTrue(a != DfuCrc.fileCrc(ByteArray(1000) { (it + 1).toByte() }))
        assertTrue(a in 0..0xFFFFFFFFL)
    }

    @Test fun dfuStartPayload() {
        val p = SpeakerDfuCommands.startPayload(0x11223344L, 0x00012345, "1.2.3", resumeSameVersion = false)
        assertEquals("33441122" + "00012345" + "010203" + "02", p.hex())
        assertEquals("aa430c", SpeakerDfuCommands.start(0x11223344L, 0x12345, "1.2.3.4", true).copyOfRange(0, 3).hex())
        assertEquals(1, SpeakerDfuCommands.start(0, 1, "1.2.3", true).last().toInt())
    }

    @Test fun dfuChunkFitsOneByteLength() {
        assertEquals(240, SpeakerDfuCommands.chunkSize(247))
        assertEquals(250, SpeakerDfuCommands.chunkSize(512))
        assertEquals(16, SpeakerDfuCommands.chunkSize(23))
    }

    @Test fun dfuSessionFollowsTheSpeaker() {
        val image = ByteArray(1000) { it.toByte() }
        val s = SpeakerDfuSession(image, 100)
        val first = s.onStatus(h("01")) as DfuAction.Send
        assertEquals("aa4465" + "00", first.frame.copyOfRange(0, 4).hex())
        assertEquals(100L, first.offset)
        val second = s.onStatus(h("0200")) as DfuAction.Send
        assertEquals(1, second.frame.u8(3))
        assertEquals(image[100], second.frame[4])
        // Same index again: send packet 1 again.
        val again = s.onStatus(h("0200")) as DfuAction.Send
        assertArrayEquals(second.frame, again.frame)
        val third = s.onStatus(h("0201")) as DfuAction.Send
        assertEquals(2, third.frame.u8(3))
        assertEquals(DfuAction.Ignore, s.onStatus(h("02ff")))
        assertEquals(DfuAction.TransferDone, s.onStatus(h("03")))
        assertTrue(s.onStatus(h("00")) is DfuAction.Failed)
    }

    @Test fun dfuSessionResumesAtOffset() {
        val image = ByteArray(1000) { it.toByte() }
        val s = SpeakerDfuSession(image, 100)
        val first = s.onStatus(h("01" + "00000190" + "00")) as DfuAction.Send
        assertEquals(400L, s.startOffset)
        assertEquals(image[400], first.frame[4])
        assertEquals(500L, first.offset)
        // An offset beyond the image restarts from 0, as the app does.
        val t = SpeakerDfuSession(image, 100)
        t.onStatus(h("01" + "00100000" + "00"))
        assertEquals(0L, t.startOffset)
    }

    @Test fun dfuSessionGivesUpAfterRepeats() {
        val s = SpeakerDfuSession(ByteArray(10_000), 100)
        s.onStatus(h("01"))
        s.onStatus(h("0200"))
        var last: DfuAction = DfuAction.Ignore
        repeat(SpeakerDfuSession.MAX_REPEATS) { last = s.onStatus(h("0200")) }
        assertTrue(last is DfuAction.Failed)
    }

    // ---------- protocol 4 ----------

    @Test fun p4Frames() {
        assertEquals("00dd010001000200" + "0c00", P4.get(P4.F_FIRMWARE).single().hex())
        assertEquals("00dd0200010005001800" + "01007f", HeadphoneP4.setNoise(NoiseMode.OFF, asc = false).single().hex())
        assertEquals("00dd020001000500" + "180001" + "00ff", HeadphoneP4.setNoise(NoiseMode.ANC, asc = false).single().hex())
        assertEquals("00dd020001000a00" + "49000100ff" + "1900010085", HeadphoneP4.setNoise(NoiseMode.AMBIENT, asc = true, level = 5).single().hex())
        assertEquals("00dd010101000400" + "34120000", P4.speakerStartOta(0x1234).hex())
        assertEquals("00dd010101000000", P4.headphoneStartOta().hex())
        assertEquals("00dd030101000000", P4.stopOta().hex())
        assertEquals("00dd020101000600" + "10000000" + "abcd", P4.otaData(16, h("abcd")).hex())
    }

    @Test fun p4LongPayloadSplits() {
        val frames = P4.frames(P4.SET, ByteArray(1000))
        assertEquals(3, frames.size)
        assertEquals(3, frames[0].u8(4))
        assertEquals(2, frames[2].u8(5))
        assertEquals(490, frames[0].u16le(6))
        assertEquals(20, frames[2].u16le(6))
    }

    @Test fun p4SettingsPayloads() {
        assertEquals("00dd0200010006001200020008" + "87", HeadphoneP4.setAutoOff(true, 30).single().hex())
        assertEquals(true to 30, HeadphoneP4.parseAutoOff(h("0887")))
        assertEquals(true to 30, HeadphoneP4.parseAutoOff(h("ffff")))
        assertEquals("00dd02000100050040" + "0c01008a", HeadphoneP4.setVoicePrompts(true, 10).single().hex())
        assertEquals("00dd0200010005000c0f010008", HeadphoneP4.setGesture(0x0F0C, 8).single().hex())
        assertEquals("00dd020001000500400c010080", SpeakerP4.setFeedback(true).single().hex())
    }

    @Test fun p4AssemblesReplies() {
        val a = P4Assembler()
        // GET reply, one packet, status 0, TLV firmware 1.2.3 and battery 0x85.
        val m = a.accept(h("00dd01000100" + "0e00" + "0000" + "0c000300010203" + "0d000100" + "85"))!!
        assertEquals(P4.GET, m.cmd)
        assertEquals(0, m.status)
        val t = P4.parseTlvs(m.payload).associate { it.id to it.value }
        assertEquals("1.2.3", P4.versionString(t[P4.F_FIRMWARE]!!, false))
        assertEquals(Battery(5, true), Battery.fromByte(t[P4.F_BATTERY_LEFT]!![0].toInt() and 0xFF))
        // Two packets.
        assertNull(a.accept(h("00dd01000200" + "0600" + "0000" + "06000500")))
        val m2 = a.accept(h("00dd01000201" + "0500" + "4a424c2021"))!!
        assertEquals("JBL !", String(P4.parseTlvs(m2.payload).single().value))
        // Wrong length is dropped.
        assertNull(a.accept(h("00dd010001000900" + "0000")))
    }

    @Test fun p4OtaNotice() {
        val a = P4Assembler()
        val m = a.accept(h("00dd04010100" + "0c00" + "0000" + "e803" + "00100000" + "f0000000"))!!
        val n = P4OtaNotice.parse(m.payload)!!
        assertEquals(1000, n.progress)
        assertEquals(4096L, n.offset)
        assertEquals(240L, n.length)
        assertFalse(n.headphoneFinished)
        assertTrue(P4OtaNotice(0, 10000, 0, 0).speakerFinished)
    }

    // ---------- Airoha, adverts, catalog ----------

    @Test fun raceVersion() {
        assertEquals("055a0300071c00", Race.getFwVersion(Race.AGENT).hex())
        val reply = h("055d" + "0900" + "071c" + "00" + "00" + "05" + "312e322e33")
        assertEquals(0 to "1.2.3", Race.parseFwVersion(reply))
        assertNull(Race.parseFwVersion(h("055d0900071c0100053132")))
    }

    @Test fun advertPid() {
        assertEquals(Advert("204f", 3), AdvertParser.parse(h("4f2003")))
        assertNull(AdvertParser.parse(h("01")))
    }

    @Test fun catalog() {
        assertTrue(Catalog.products.size > 100)
        val flip6 = Catalog.find("204F")!!
        assertEquals(Kind.SPEAKER, flip6.kind)
        assertEquals(UpdateFamily.SPEAKER_AA, flip6.update)
        assertTrue(flip6.update.writeSupported)
        val tourOneM3 = Catalog.find("2101")!!
        assertEquals(Control.HP_P4, tourOneM3.control)
        assertEquals(UpdateFamily.HP_P4, tourOneM3.update)
        assertFalse(Catalog.find("20f1")!!.update.writeSupported) // Airoha
        assertFalse(Catalog.find("1001")!!.update.writeSupported) // Avnera
        assertTrue(Catalog.find("21b6")!!.has("asc"))
    }

    // ---------- update check, MD5 ----------

    @Test fun updateUrls() {
        assertEquals("https://storage.harman.com/release/portable/flip6/flip6_ota.json", UpdateServer.configUrl(Catalog.find("204f")!!))
        assertEquals("https://appstorage.onecloud.harman.com/jbl_portable/ota/20e3/ota_config.json", UpdateServer.configUrl(Catalog.find("20e3")!!))
        assertEquals("https://appstorage.onecloud.harman.com/jbl_headphones/ota/2101/ota_config.json", UpdateServer.configUrl(Catalog.find("2101")!!))
        assertTrue(UpdateServer.isAllowed("https://storage.harman.com/x"))
        assertFalse(UpdateServer.isAllowed("http://storage.harman.com/x"))
        assertFalse(UpdateServer.isAllowed("https://storage.harman.com.evil.example/x"))
        assertFalse(UpdateServer.isAllowed("https://user@storage.harman.com/x"))
        assertFalse(UpdateServer.isAllowed("https://storage.harman.com:8443/x"))
    }

    @Test fun parseNewJson() {
        val p = Catalog.find("2101")!!
        val o = UpdateServer.parseJson(p, """{"pid":"2101","firmware":{"version":"1.5.0","md5":"0123456789abcdef0123456789abcdef","bin":"fw.bin"}}""")!!
        assertEquals("1.5.0", o.version)
        assertEquals("https://appstorage.onecloud.harman.com/jbl_headphones/ota/2101/fw.bin", o.url)
        assertNull(UpdateServer.parseJson(p, """{"pid":"9999","firmware":{"version":"1","md5":"x","bin":"y"}}"""))
    }

    @Test fun parseLegacyJson() {
        val p = Catalog.find("204f")!!
        val o = UpdateServer.parseJson(p, "﻿{\"version\":\"2.0.4\",\"md5\":\"abc\",\"binUrl\":\"flip6/flip6.bin\"}")!!
        assertEquals("https://storage.harman.com/release/portable/flip6/flip6.bin", o.url)
    }

    @Test fun parseXml() {
        val xml = """<Releases><Release name="boot" version="9.9.9" md5="aa">https://storage.harman.com/boot.bin</Release>
            <Release name="app" version="1.2.3" md5="bb">https://storage.harman.com/app.bin</Release></Releases>"""
        val o = UpdateServer.parseXml(xml)!!
        assertEquals("1.2.3", o.version)
        assertEquals("https://storage.harman.com/app.bin", o.url)
    }

    @Test fun offerNewer() {
        assertEquals(true, Offer("1.10.0", "", "", null).isNewerThan("1.9.9"))
        assertEquals(false, Offer("1.2.3", "", "", null).isNewerThan("1.2.3"))
        assertEquals(false, Offer("2.0.0", "", "", "1.5.0").isNewerThan("1.0.0"))
        assertNull(Offer("1.a", "", "", null).isNewerThan("1.0"))
    }

    @Test fun versions() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertEquals(1, compareVersions("1.10", "1.9"))
        assertNull(compareVersions("x", "1"))
    }

    @Test fun md5() {
        val abc = "abc".toByteArray()
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex(abc))
        assertTrue(Md5.matches(abc, "900150983CD24FB0D6963F7D28E17F72"))
        assertFalse(Md5.matches(abc, "900150983cd24fb0d6963f7d28e17f73"))
        assertFalse(Md5.matches(abc, "nope"))
    }

    @Test fun headphoneAaInfo() {
        val f = ByteArray(32)
        f[0] = 0xAA.toByte(); f[1] = 0x12; f[2] = 29
        "Tune".toByteArray().copyInto(f, 3)
        f[19] = 0x4b; f[20] = 0x20; f[21] = 2; f[22] = 0x46; f[29] = 1; f[30] = 4; f[31] = 7
        val i = HpAaInfo.parse(f)!!
        assertEquals("Tune", i.name)
        assertEquals("204b", i.pid)
        assertEquals(Battery(70, false), i.battery)
        assertEquals("1.4.7", i.firmware)
        assertNotNull(i)
    }
}

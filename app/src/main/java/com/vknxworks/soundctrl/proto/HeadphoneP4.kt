package com.vknxworks.soundctrl.proto

/**
 * Headphone settings over protocol 4, as the headphone app's General4Control and the newer
 * per-product controls send them. See PROTOCOL.md §3.3.
 */
enum class NoiseMode { OFF, ANC, AMBIENT, TALKTHRU }

object HeadphoneP4 {
    /** Ambient-sound-control switch used by the 2025 products (Tune/Wave/Vibe 3, Live 4, Live 680NC, Tour M4). */
    const val F_AMBIENT_SOUND_CONTROL = 0x0049

    /** One SET with several features in one payload. */
    fun setMany(vararg entries: Pair<Int, ByteArray>): List<ByteArray> =
        P4.frames(P4.SET, entries.fold(ByteArray(0)) { acc, (id, v) -> acc + le16(id) + le16(v.size) + v })

    /**
     * Noise control. Older protocol-4 products: one feature with 0xFF (on) or ANC 0x7F (all off).
     * Newer ones ([asc]): 0x0049 on/off plus the mode feature with 0x80 | level.
     */
    fun setNoise(mode: NoiseMode, asc: Boolean, level: Int = 0x7F): List<ByteArray> {
        val feature = when (mode) {
            NoiseMode.ANC, NoiseMode.OFF -> P4.F_ANC
            NoiseMode.AMBIENT -> P4.F_AMBIENT
            NoiseMode.TALKTHRU -> P4.F_TALKTHRU
        }
        if (!asc) {
            val v = if (mode == NoiseMode.OFF) 0x7F else 0xFF
            return P4.set(feature, byteArrayOf(v.toByte()))
        }
        val on = mode != NoiseMode.OFF
        return setMany(
            F_AMBIENT_SOUND_CONTROL to byteArrayOf((if (on) 0xFF else 0x7F).toByte()),
            feature to byteArrayOf((0x80 or (level and 0x7F)).toByte()),
        )
    }

    /** Reads which mode is active from the three status bytes (bit 7 = active). */
    fun noiseFrom(anc: Int?, ambient: Int?, talkThru: Int?, ascOn: Int?): NoiseMode? {
        if (anc == null && ambient == null && talkThru == null) return null
        if (ascOn != null && ascOn and 0x80 == 0) return NoiseMode.OFF
        return when {
            anc != null && anc and 0x80 != 0 -> NoiseMode.ANC
            ambient != null && ambient and 0x80 != 0 -> NoiseMode.AMBIENT
            talkThru != null && talkThru and 0x80 != 0 -> NoiseMode.TALKTHRU
            else -> NoiseMode.OFF
        }
    }

    /** Auto power-off: u16 LE, bit 15 = on, bits 0-14 = seconds (the app sends minutes x 60). */
    fun setAutoOff(on: Boolean, minutes: Int): List<ByteArray> {
        val v = (if (on) 0x8000 else 0) or ((minutes * 60) and 0x7FFF)
        return P4.set(P4.F_AUTO_OFF, le16(v))
    }

    /** (on, minutes); 0x7FFF is shown by the app as 30 minutes. */
    fun parseAutoOff(v: ByteArray): Pair<Boolean, Int>? {
        if (v.size < 2) return null
        val raw = v.u16le(0)
        val secs = raw and 0x7FFF
        return (raw and 0x8000 != 0) to (if (secs == 0x7FFF) 30 else secs / 60)
    }

    /** Voice prompts: bit 7 = spoken prompts (off = tones only), bits 0-6 = volume (kept as read). */
    fun setVoicePrompts(on: Boolean, volume: Int): List<ByteArray> =
        P4.set(P4.F_FEEDBACK_TONE, byteArrayOf(((if (on) 0x80 else 0) or (volume and 0x7F)).toByte()))

    /** Gestures: feature 0x0F00-0x0F0D, one action byte each. */
    fun setGesture(feature: Int, action: Int): List<ByteArray> = P4.set(feature, byteArrayOf(action.toByte()))

    val GESTURE_FEATURES: List<Int> = (0x0F00..0x0F0D).toList()

    /** Action ids the headphone app uses (GestureFunType); SoundCtrl offers the everyday ones. */
    val GESTURE_ACTIONS: List<Int> = listOf(0, 8, 5, 6, 1, 2, 7, 3, 4, 9, 11, 12, 13, 15)
}

/** Speaker settings over protocol 4 (speaker app, PortableControl). */
object SpeakerP4 {
    const val F_AURACAST_MODE = 0x2004

    /** Feedback tone: 0x80 = on, 0 = off. */
    fun setFeedback(on: Boolean) = P4.set(P4.F_FEEDBACK_TONE, byteArrayOf(if (on) 0x80.toByte() else 0))
    fun setName(name: String) = P4.set(P4.F_DEVICE_NAME, name.toByteArray(Charsets.UTF_8))
    fun setAuracast(on: Boolean) = P4.set(F_AURACAST_MODE, byteArrayOf(if (on) 1 else 0))
    fun deviceInfo() = P4.get(P4.F_BATTERY_LEFT, P4.F_FIRMWARE, P4.F_SERIAL, P4.F_DEVICE_NAME, P4.F_PRODUCT_ID)
}

/** Older JBL headphones' device-info reply `AA 12` (headphone app, HandleParse.parseDeviceInfo). Frame indices. */
data class HpAaInfo(val name: String, val pid: String, val modelId: Int, val battery: Battery, val firmware: String) {
    companion object {
        fun parse(frame: ByteArray): HpAaInfo? {
            if (frame.size < 32 || frame.u8(0) != 0xAA || frame.u8(1) != Aa.RET_DEV_INFO) return null
            val nameBytes = frame.copyOfRange(3, 19).takeWhile { it != 0.toByte() }.toByteArray()
            return HpAaInfo(
                name = String(nameBytes, Charsets.UTF_8).trim(),
                pid = "%02x%02x".format(frame.u8(20), frame.u8(19)),
                modelId = frame.u8(21),
                battery = Battery.fromByte(frame.u8(22)),
                firmware = "${frame.u8(29)}.${frame.u8(30)}.${frame.u8(31)}",
            )
        }
    }
}

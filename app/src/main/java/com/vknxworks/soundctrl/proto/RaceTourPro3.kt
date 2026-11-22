package com.vknxworks.soundctrl.proto

/**
 * Tour Pro 3 settings beyond the basics in [Race], as the JBL headphone app sends them for this model
 * (`TourPro3LeControl` → `AhDevice` → `com.airoha.adapter.harman.AirohaDeviceStatusControl` → Airoha stages).
 * Only offered for the Tour Pro 3 (`20f2`, case `20fd`) on an AB158x chip ([isTourPro3]); other Airoha models may
 * use other stages or not have these features. PROTOCOL.md §7.4.
 */
object RaceTourPro3 {
    /** The Tour Pro 3's product ids (earbuds and the smart case's id the earbuds may report). */
    val PIDS = setOf("20f2", "20fd")

    fun isTourPro3(pid: String?): Boolean = pid != null && pid.lowercase() in PIDS

    // RACE ids (com.airoha.libbase.RaceCommand.constant.RaceId)
    const val HOSTAUDIO_MMI_SET = 0x0900
    const val SMART_TALK_GET = 0x0060
    const val SMART_TALK_SET = 0x0061
    const val HI_RES_GET = 0x0075
    const val HI_RES_SET = 0x0076
    const val SEND_HM_CMD = 0x0093
    const val NVKEY_WRITE = 0x0A01
    const val RELOAD_NVKEY = 0x0A09
    const val RELAY_PASS_TO_DST = 0x0D01
    const val ANC_GAIN_CONTROL = 0x0E17

    /** Host-audio module of the voice-prompt on/off switch (`MmiStage_GetVpOnOff`, `MmiStage_SetVpOnoff`). */
    const val MODULE_VP_ON_OFF = 1
    /** MMI common-config modules (`MmiStageGet/SetSidetoneLevel`, `…SidetoneState`, `MmiStage(Get)SetVpLanguageIndex`). */
    const val MODULE_SIDETONE_LEVEL = 6
    const val MODULE_SIDETONE_STATE = 7
    const val MODULE_VP_LANGUAGE = 9

    /** Voice-prompt language index 255 = tones only (`MmiStage_SetVpLanguageIndex`, `notifySetLanguageIndexStatus`). */
    const val VP_TONES = 0xFF
    /** What the app sends for "voice" when no new language file is installed: index 0. */
    const val VP_SPOKEN = 0

    // ---------- relay to the other earbud ----------

    /**
     * The other earbud's relay destination: the first (type, id) pair with type 5 in the `5B 0D00` reply
     * (`MmiStageGetAvaDst`, `AirohaMmiMgr1568`). null when there is none.
     */
    fun parsePartnerDst(b: ByteArray): Pair<Int, Int>? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != Race.RELAY_GET_AVA_DST) return null
        var i = 0
        while (i + 1 < f.payload.size) {
            if (f.payload.u8(i) == 5) return 5 to f.payload.u8(i + 1)
            i += 2
        }
        return null
    }

    /** Wraps a whole frame for the other earbud (`RaceCmdRelayPass`): `5A 0D01 [dstType dstId] frame`. */
    fun relay(dst: Pair<Int, Int>, frame: ByteArray): ByteArray =
        Race.build(Race.CMD_NEEDS_RESPONSE, RELAY_PASS_TO_DST, byteArrayOf(dst.first.toByte(), dst.second.toByte()) + frame)

    /** The other earbud's answer inside a `5D 0D01` (from frame byte 8, `RelayRespExtracter`); null when it isn't one. */
    fun unwrapRelay(b: ByteArray): ByteArray? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.INDICATION || f.id != RELAY_PASS_TO_DST || b.size < 8 + 6) return null
        val inner = b.copyOfRange(8, b.size)
        return if (Race.parse(inner) != null) inner else null
    }

    /** The `5B 0D01` ack of a relayed command: its status (0 = passed on), or null when it isn't one. */
    fun relayAckStatus(b: ByteArray): Int? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != RELAY_PASS_TO_DST || f.payload.isEmpty()) return null
        return f.payload.u8(0)
    }

    // ---------- voice prompts ----------

    /** Voice prompts on/off (`MmiStage_GetVpOnOff`): `5A 0901 [01 00]`. */
    fun getVpOnOff() = Race.build(Race.CMD_NEEDS_RESPONSE, Race.HOSTAUDIO_MMI_GET, le16(MODULE_VP_ON_OFF))

    /** `5B 0901 [01 00][status][on]`. */
    fun parseVpOnOff(b: ByteArray): Boolean? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != Race.HOSTAUDIO_MMI_GET || f.payload.size < 4) return null
        if (f.payload.u16le(0) != MODULE_VP_ON_OFF || f.payload.u8(2) != 0) return null
        return f.payload.u8(3) != 0
    }

    /** Turns voice prompts off, as the app does for "off" (`MmiStage_SetVpOnoff` with 0): `5A 0900 [01 00 00]`. */
    fun setVpOff() = Race.build(Race.CMD_NEEDS_RESPONSE, HOSTAUDIO_MMI_SET, le16(MODULE_VP_ON_OFF) + byteArrayOf(0))

    /** `5B 0900 [01 00][status]`: true when accepted. */
    fun parseSetVpOff(b: ByteArray): Boolean? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != HOSTAUDIO_MMI_SET || f.payload.size < 3 || f.payload.u16le(0) != MODULE_VP_ON_OFF) return null
        return f.payload.u8(2) == 0
    }

    /** The voice-prompt language index (`MmiStageGetVpLangIndex`): `5A 2C83 [09 00]`. */
    fun getVpLanguage() = Race.getCommonConfig(MODULE_VP_LANGUAGE)

    /** `5B 2C83 [status][09 00][index]`; 255 = tones. */
    fun parseVpLanguage(b: ByteArray): Int? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != Race.GET_MMI_COMMON_CONFIG || f.payload.size < 4) return null
        if (f.payload.u8(0) != 0 || f.payload.u16le(1) != MODULE_VP_LANGUAGE) return null
        return f.payload.u8(3)
    }

    /**
     * Tones or spoken prompts (`MmiStage_SetVpLanguageIndex`): `5A 2C82 [09 00 index]`, 255 for tones, 0 for voice.
     * This is also the only thing the app sends to turn prompts on again after "off"; it never sends `0900 [01 00 01]`.
     */
    fun setVpLanguage(index: Int) =
        Race.build(Race.CMD_NEEDS_RESPONSE, Race.SET_MMI_COMMON_CONFIG, le16(MODULE_VP_LANGUAGE) + byteArrayOf(index.toByte()))

    // ---------- Voice Aware (sidetone during calls) ----------

    /** `5A 2C83 [07 00]` (`MmiStageGetSidetoneState`). */
    fun getSidetoneState() = Race.getCommonConfig(MODULE_SIDETONE_STATE)

    /** `5B 2C83 [status][07 00][on]`. */
    fun parseSidetoneState(b: ByteArray): Boolean? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != Race.GET_MMI_COMMON_CONFIG || f.payload.size < 4) return null
        if (f.payload.u8(0) != 0 || f.payload.u16le(1) != MODULE_SIDETONE_STATE) return null
        return f.payload.u8(3) != 0
    }

    /** `5A 2C83 [06 00]` (`MmiStageGetSidetoneLevel`). */
    fun getSidetoneLevel() = Race.getCommonConfig(MODULE_SIDETONE_LEVEL)

    /** `5B 2C83 [status][06 00][level LE16]`. */
    fun parseSidetoneLevel(b: ByteArray): Int? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != Race.GET_MMI_COMMON_CONFIG || f.payload.size < 5) return null
        if (f.payload.u8(0) != 0 || f.payload.u16le(1) != MODULE_SIDETONE_LEVEL) return null
        return f.payload.u16le(3)
    }

    /** `5A 2C82 [07 00 on]` (`MmiStageSetSidetoneState`). */
    fun setSidetoneState(on: Boolean) =
        Race.build(Race.CMD_NEEDS_RESPONSE, Race.SET_MMI_COMMON_CONFIG, le16(MODULE_SIDETONE_STATE) + byteArrayOf(if (on) 1 else 0))

    /** `5A 2C82 [06 00 level LE16]` (`MmiStageSetSidetoneLevel`). The app's slider sends 1, 2 or 3. */
    fun setSidetoneLevel(level: Int) =
        Race.build(Race.CMD_NEEDS_RESPONSE, Race.SET_MMI_COMMON_CONFIG, le16(MODULE_SIDETONE_LEVEL) + le16(level))

    // ---------- adaptive noise cancelling ----------

    /** Adaptive ANC ("environment detection", `AncStageGetEnvironmentDetectionStatus`): `5A 0E17 [03 02]`. */
    fun getAdaptiveAnc() = Race.build(Race.CMD_NEEDS_RESPONSE, ANC_GAIN_CONTROL, byteArrayOf(3, 2))

    /** `5B 0E17 [status][03][on]`. */
    fun parseAdaptiveAnc(b: ByteArray): Boolean? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != ANC_GAIN_CONTROL || f.payload.size < 3 || f.payload.u8(1) != 3) return null
        if (f.payload.u8(0) != 0) return null
        return f.payload.u8(2) == 1
    }

    /** `5A 0E17 [03 on]` (`AncStageSetEnvironmentDetectionStatus`). */
    fun setAdaptiveAnc(on: Boolean) = Race.build(Race.CMD_NEEDS_RESPONSE, ANC_GAIN_CONTROL, byteArrayOf(3, if (on) 1 else 0))

    /** `5B 0E17 [status][03]…` (at least 9 bytes): true when accepted. */
    fun parseSetAdaptiveAnc(b: ByteArray): Boolean? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != ANC_GAIN_CONTROL || b.size < 9 || f.payload.u8(1) != 3) return null
        return f.payload.u8(0) == 0
    }

    // ---------- Smart Talk (speak-to-chat) ----------

    /** The app's timeouts in seconds; it never sends less than 5. */
    val SMART_TALK_TIMEOUTS = listOf(5, 15, 20)

    /** `5A 0060` (`MmiStage_SmartTalkStatus_Get`). */
    fun getSmartTalk() = Race.build(Race.CMD_NEEDS_RESPONSE, SMART_TALK_GET)

    /** `5B 0060 [status][on][timeout]`. Returns (on, timeout seconds). */
    fun parseSmartTalk(b: ByteArray): Pair<Boolean, Int>? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != SMART_TALK_GET || f.payload.size < 3 || f.payload.u8(0) != 0) return null
        return (f.payload.u8(1) != 0) to f.payload.u8(2)
    }

    /** `5A 0061 [on timeout]` (`MmiStage_SmartTalkStatus_Set`). */
    fun setSmartTalk(on: Boolean, timeout: Int) =
        Race.build(Race.CMD_NEEDS_RESPONSE, SMART_TALK_SET, byteArrayOf(if (on) 1 else 0, timeout.toByte()))

    // ---------- Hi-Res audio (LDAC) ----------

    /** `5A 0075` (`MmiStage_GetHiResAudioType`). */
    fun getHiRes() = Race.build(Race.CMD_NEEDS_RESPONSE, HI_RES_GET)

    /** `5B 0075 [status][type]` (at least 8 bytes); the app's switch shows type 1 as on. */
    fun parseHiRes(b: ByteArray): Boolean? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != HI_RES_GET || b.size < 8 || f.payload.u8(0) != 0) return null
        return f.payload.u8(1) == 1
    }

    /** `5A 0076 [type]`, 1 on, 0 off (`MmiStage_SetHiResAudioType`, `HiResAudioCard`). */
    fun setHiRes(on: Boolean) = Race.build(Race.CMD_NEEDS_RESPONSE, HI_RES_SET, byteArrayOf(if (on) 1 else 0))

    // ---------- simple "5B id [status]" answers ----------

    /** A `5B` reply to [id] at least [minSize] bytes long: true when its status ([6]) is 0. */
    fun parseStatus(b: ByteArray, id: Int, minSize: Int = 7): Boolean? {
        val f = Race.parse(b) ?: return null
        if (f.type != Race.RESPONSE || f.id != id || b.size < minSize || f.payload.isEmpty()) return null
        return f.payload.u8(0) == 0
    }

    // ---------- spatial sound (Harman command relayed in RACE 0093) ----------

    /** Harman device-info feature "spatial sound with head tracking" (`DeviceInfoFeature.SpatialSoundWithHeadTrancking`). */
    const val HM_FEATURE_SPATIAL_HEAD_TRACKING = 0x002E

    /** Spatial sound modes as the Tour Pro 3's card sends them: off (on 0), Fixed (on 1, type 1), Head tracking (on 1, type 2). */
    const val SPATIAL_OFF = 0
    const val SPATIAL_FIXED = 1
    const val SPATIAL_HEAD_TRACKING = 2

    /**
     * Reads the feature (`GenDeviceInfoCmd.toBytesReq` → `Command3.get(6, …)`): Harman `AA 06 03 00 01 00 2E`,
     * sent in `5A 0093` (`MmiStage_SendHmCmd`).
     */
    fun getSpatial(): ByteArray = Race.build(Race.CMD_NEEDS_RESPONSE, SEND_HM_CMD, hmGet(HM_FEATURE_SPATIAL_HEAD_TRACKING))

    /** Sets it (`GenDeviceInfoCmd.toBytesSet` → `Command3.set(6, …)`): `AA 06 06 00 00 00 2E 02 [on] [type]` in `5A 0093`. */
    fun setSpatial(mode: Int): ByteArray {
        val on = if (mode == SPATIAL_OFF) 0 else 1
        val type = if (mode == SPATIAL_OFF) 0 else mode
        return Race.build(Race.CMD_NEEDS_RESPONSE, SEND_HM_CMD, hmSet(HM_FEATURE_SPATIAL_HEAD_TRACKING, byteArrayOf(on.toByte(), type.toByte())))
    }

    /** Harman "Command3" GET: `AA cmd len(LE16) 01 (featureId BE16)*`; len counts from the type byte. */
    fun hmGet(feature: Int): ByteArray = byteArrayOf(0xAA.toByte(), 6) + le16(3) + byteArrayOf(1, (feature shr 8).toByte(), feature.toByte())

    /** Harman "Command3" SET: `AA cmd len(LE16) 00 (featureId BE16, len, value)*`. */
    fun hmSet(feature: Int, value: ByteArray): ByteArray {
        val body = byteArrayOf(0, (feature shr 8).toByte(), feature.toByte(), value.size.toByte()) + value
        return byteArrayOf(0xAA.toByte(), 6) + le16(body.size) + body
    }

    /**
     * The spatial mode from a `5B` or `5D 0093` that carries a Harman answer (`AA 06 len … (featureId BE16, len, value)*`,
     * `Command3.parse`): on 0 → off, else the type (1 Fixed, 2 Head tracking). The app hands the bytes after the
     * status byte on to its Harman parser; where exactly the answer sits in a 5D push wasn't pinned, so the Harman
     * frame is looked for at the first two positions after the id.
     */
    fun parseSpatial(b: ByteArray): Int? {
        val f = Race.parse(b) ?: return null
        if ((f.type != Race.RESPONSE && f.type != Race.INDICATION) || f.id != SEND_HM_CMD) return null
        for (start in 0..1) {
            val p = f.payload
            if (p.size < start + 5 || p.u8(start) != 0xAA || p.u8(start + 1) != 6) continue
            if (f.type == Race.RESPONSE && start == 1 && p.u8(0) != 0) return null
            val v = hmValue(p.copyOfRange(start, p.size), HM_FEATURE_SPATIAL_HEAD_TRACKING) ?: continue
            if (v.size < 2) return null
            return if (v[0].toInt() == 0) SPATIAL_OFF else v.u8(1)
        }
        return null
    }

    /** The value of [feature] in a Harman "Command3" answer `AA cmd len(LE16) type (fid BE16, len, value)*`. */
    fun hmValue(h: ByteArray, feature: Int): ByteArray? {
        if (h.size < 5 || h.u8(0) != 0xAA) return null
        val end = minOf(h.size, 4 + h.u16le(2))
        var i = 5
        while (i + 2 < end) {
            val id = (h.u8(i) shl 8) or h.u8(i + 1)
            val n = h.u8(i + 2)
            if (i + 3 + n > h.size) return null
            if (id == feature) return h.copyOfRange(i + 3, i + 3 + n)
            i += 3 + n
        }
        return null
    }
}

/**
 * Touch gestures on AB158x earbuds (`MmiStage_GetGesture_1568_v3`, `MmiStage_SetGesture_1568_v3`,
 * `MmiStage_SetPeerGesture_1568_v3`, `MmiStage_ReloadGestureNv_1568_v3`, `AirohaHmGestureInfo1568v3`), with the
 * Tour Pro 3's choices (`assets/config/dash4/jbl_tour_pro_3.json` "gestureConfig", `xp.j` id tables).
 *
 * Each earbud keeps its own key map in NV key `F2D6` and a copy of the other earbud's in `F2EF`. An entry is 8
 * bytes: `keyType keyId event(LE16) state(4)`. The app writes state 0 and the key id it last read (0x7E by default).
 * Harman gesture ids: left 1 tap, 3 double tap, 7 triple tap, 49 tap and hold (5 = second long press, kept as read);
 * right is the left id + 1.
 */
object RaceGestures {
    const val NV_KEY_MAP = 0xF2D6
    const val NV_PEER_KEY_MAP = 0xF2EF
    const val DEFAULT_KEY_ID = 0x7E

    // key types (AirohaHmGestureInfo1568v3)
    const val RELEASE = 0
    const val SHORT_CLICK = 1
    const val DOUBLE_CLICK = 2
    const val TRIPLE_CLICK = 3
    const val LONG_PRESS_1 = 4
    const val LONG_PRESS_2 = 5
    const val PRESS = 9

    // key events
    const val VOICE_UP = 0x000A
    const val VOICE_DN = 0x000B
    const val WAKE_UP_VOICE_ASSISTANT = 0x0020
    const val INTERRUPT_VOICE_ASSISTANT = 0x0023
    const val AVRCP_PLAY = 0x0053
    const val AVRCP_PAUSE = 0x0055
    const val AVRCP_PLAY_DISMISS_VA = 0x0057
    const val AVRCP_PAUSE_DISMISS_VA = 0x0059
    const val AVRCP_FORWARD = 0x005A
    const val AVRCP_BACKWARD = 0x005B
    const val PASSTHROUGH = 0x0090
    const val ANC = 0x0091
    const val GSOUND_PRESS = 0x0100
    const val GSOUND_RELEASE = 0x0101
    const val GSOUND_NOTIFY = 0x0102
    const val GSOUND_VOICE_QUERY = 0x0103
    const val GSOUND_CANCEL = 0x0104
    const val AMA_START = 0x011A
    const val AMA_STOP = 0x011B
    const val XIAOWEI_STOP_PLAY = 0x0122
    const val XIAOWEI_START = 0x0123
    const val XIAOWEI_STOP = 0x0124
    const val CUSTOM_ULL_SWITCH = 0xF006
    const val AMBIENT_AWARE = 0xF025
    const val SWITCH_AMBIENT_AWARE_AND_ANC = 0xF026
    const val SWITCH_ANC_AND_OFF = 0xF027
    const val SWITCH_AMBIENT_AWARE_AND_OFF = 0xF028
    const val SWITCH_ANC_AND_AMBIENT_AWARE_AND_OFF = 0xF029

    /** Harman action ids (`GestureInfo.actionId`, `xp.j`). */
    const val A_NONE = 0
    const val A_VOLUME_UP = 1
    const val A_VOLUME_DOWN = 2
    const val A_TALKTHRU = 4
    const val A_NEXT = 5
    const val A_PREVIOUS = 6
    const val A_PLAY_PAUSE = 8
    const val A_ANC_AMBIENT_OFF = 9
    const val A_ANC_AMBIENT = 11
    const val A_ANC_OFF = 12
    const val A_AMBIENT_OFF = 13
    const val A_VOICE_ASSISTANT = 161

    /** Harman gesture ids per earbud, in the order the app lists them (tap, double, triple, hold, second long press). */
    val LEFT_IDS = listOf(1, 3, 7, 49, 5)
    val RIGHT_IDS = listOf(2, 4, 8, 50, 6)

    /** The four controls the Tour Pro 3's gesture page offers per earbud: (tap, double tap, triple tap, tap and hold). */
    enum class Preset(val actions: List<Int>) {
        AMBIENT(listOf(A_ANC_AMBIENT, A_TALKTHRU, A_NONE, A_VOICE_ASSISTANT)),
        VOLUME(listOf(A_VOLUME_UP, A_VOLUME_DOWN, A_NONE, A_VOICE_ASSISTANT)),
        PLAYBACK(listOf(A_PLAY_PAUSE, A_NEXT, A_PREVIOUS, A_VOICE_ASSISTANT)),
        NONE(listOf(A_NONE, A_NONE, A_NONE, A_NONE)),
    }

    /** Ambient-sound cycles the app may put on a tap within the ambient-sound control (`singleTapNumber`). */
    val AMBIENT_TAP_CYCLES = setOf(A_ANC_AMBIENT_OFF, A_ANC_AMBIENT, A_ANC_OFF, A_AMBIENT_OFF)

    /** Which preset one earbud's gestures (Harman id → action) match; null = something else ("custom"). */
    fun presetOf(gestures: Map<Int, Int>, right: Boolean): Preset? {
        val ids = if (right) RIGHT_IDS else LEFT_IDS
        val now = ids.take(4).map { gestures[it] ?: return null }
        return Preset.entries.firstOrNull { p ->
            if (p == Preset.AMBIENT) now[0] in AMBIENT_TAP_CYCLES && now.drop(1) == p.actions.drop(1) else now == p.actions
        }
    }

    /** Reads one earbud's key map (`MmiStage_GetGesture_1568_v3`): NV key F2D6, at most 100 bytes. */
    fun read(): ByteArray = Race.readNvKey(NV_KEY_MAP, 100)

    /** The key type a Harman gesture id is stored under (`MmiStage_SetGesture_1568_v3.getKeyType`). */
    fun keyType(gestureId: Int): Int? = when (gestureId) {
        1, 2 -> SHORT_CLICK
        3, 4 -> DOUBLE_CLICK
        5, 6 -> LONG_PRESS_2
        7, 8 -> TRIPLE_CLICK
        49, 50 -> LONG_PRESS_1
        else -> null
    }

    /** The Harman gesture id of a key type on the left or right earbud (`MmiStage_GetGesture_1568_v3.getGestureId`). */
    fun gestureId(keyType: Int, right: Boolean): Int? = when (keyType) {
        SHORT_CLICK -> if (right) 2 else 1
        DOUBLE_CLICK -> if (right) 4 else 3
        LONG_PRESS_2 -> if (right) 6 else 5
        LONG_PRESS_1 -> if (right) 50 else 49
        TRIPLE_CLICK -> if (right) 8 else 7
        else -> null
    }

    /** One 8-byte key-map entry as the app writes it: state 0. */
    fun entry(keyType: Int, keyId: Int, event: Int): ByteArray =
        byteArrayOf(keyType.toByte(), keyId.toByte(), event.toByte(), (event shr 8).toByte(), 0, 0, 0, 0)

    /** The entries for one gesture and action (`MmiStage_SetGesture_1568_v3.getGeneralKeyStruct`); empty for "nothing". */
    fun entries(gestureId: Int, action: Int, keyId: Int): ByteArray {
        val t = keyType(gestureId) ?: return ByteArray(0)
        fun e(type: Int, event: Int) = entry(type, keyId, event)
        return when (action) {
            1 -> e(t, VOICE_UP)
            2 -> e(t, VOICE_DN)
            3 -> e(t, AMBIENT_AWARE)
            4 -> e(t, PASSTHROUGH)
            5 -> e(t, AVRCP_FORWARD)
            6 -> e(t, AVRCP_BACKWARD)
            7 -> e(t, ANC)
            8 -> e(t, AVRCP_PLAY) + e(t, AVRCP_PAUSE)
            9 -> e(t, SWITCH_ANC_AND_AMBIENT_AWARE_AND_OFF)
            10 -> e(t, AVRCP_PLAY_DISMISS_VA) + e(t, AVRCP_PAUSE_DISMISS_VA)
            11 -> e(t, SWITCH_AMBIENT_AWARE_AND_ANC)
            12 -> e(t, SWITCH_ANC_AND_OFF)
            13 -> e(t, SWITCH_AMBIENT_AWARE_AND_OFF)
            14 -> e(t, CUSTOM_ULL_SWITCH)
            161 -> e(t, WAKE_UP_VOICE_ASSISTANT) + e(SHORT_CLICK, INTERRUPT_VOICE_ASSISTANT) + e(LONG_PRESS_1, INTERRUPT_VOICE_ASSISTANT)
            162 -> e(t, GSOUND_CANCEL)
            163 -> e(t, GSOUND_NOTIFY)
            164 -> e(PRESS, GSOUND_PRESS) + e(t, GSOUND_VOICE_QUERY) + e(RELEASE, GSOUND_RELEASE)
            166 -> e(t, AMA_START) + e(RELEASE, AMA_STOP)
            168 -> e(t, XIAOWEI_START) + e(RELEASE, XIAOWEI_STOP) + e(SHORT_CLICK, XIAOWEI_STOP_PLAY)
            else -> ByteArray(0)
        }
    }

    /**
     * One earbud's whole key map: the entries of that earbud's gestures, in the order tap, double, triple, hold,
     * second long press; a single empty entry (`00 keyId 0000 00000000`) when there are none (`genKeyStruct`).
     */
    fun keyMap(gestures: Map<Int, Int>, right: Boolean, keyId: Int): ByteArray {
        var out = ByteArray(0)
        for (id in if (right) RIGHT_IDS else LEFT_IDS) {
            val action = gestures[id] ?: continue
            out += entries(id, action, keyId)
        }
        return if (out.isEmpty()) entry(0, keyId, 0) else out
    }

    /** Writes an NV key (`RACE_NVKEY_WRITEFULLKEY`): `5A 0A01 [key LE16] data`. */
    fun write(key: Int, data: ByteArray): ByteArray = Race.build(Race.CMD_NEEDS_RESPONSE, RaceTourPro3.NVKEY_WRITE, le16(key) + data)

    /** Makes the earbud load its key map again (`RACE_RELOAD_NVKEY_TO_RAM`): `5A 0A09 [D6 F2]`. */
    fun reload(): ByteArray = Race.build(Race.CMD_NEEDS_RESPONSE, RaceTourPro3.RELOAD_NVKEY, le16(NV_KEY_MAP))

    /** The parsed key map of one earbud: Harman gesture id → action id, and the key id of its last entry (null if empty). */
    class KeyMap(val gestures: Map<Int, Int>, val keyId: Int?)

    /**
     * Parses one earbud's key map (the data of a `5B 0A00`), as `MmiStage_GetGesture_1568_v3.parseKeyStruct` does:
     * for each of the five key types the matching action (later rules win), 0 when nothing matches. An empty key
     * map gives 0 for all five.
     */
    fun parse(data: ByteArray, right: Boolean): KeyMap {
        val entries = (0 until data.size / 8).map { i -> Triple(data.u8(i * 8), data.u8(i * 8 + 1), data.u16le(i * 8 + 2)) }
        fun has(type: Int, event: Int) = entries.any { it.first == type && it.third == event }
        val out = LinkedHashMap<Int, Int>()
        for (t in listOf(SHORT_CLICK, DOUBLE_CLICK, TRIPLE_CLICK, LONG_PRESS_1, LONG_PRESS_2)) {
            var a = A_NONE
            if (has(t, VOICE_UP)) a = 1
            if (has(t, VOICE_DN)) a = 2
            if (has(t, ANC)) a = 7
            if (has(t, AMBIENT_AWARE)) a = 3
            if (has(t, PASSTHROUGH)) a = 4
            if (has(t, AVRCP_FORWARD)) a = 5
            if (has(t, AVRCP_BACKWARD)) a = 6
            if (has(t, AVRCP_PLAY) && has(t, AVRCP_PAUSE)) a = 8
            if (has(t, AVRCP_PLAY_DISMISS_VA) && has(t, AVRCP_PAUSE_DISMISS_VA)) a = 10
            if (has(t, SWITCH_ANC_AND_AMBIENT_AWARE_AND_OFF)) a = 9
            if (has(t, SWITCH_AMBIENT_AWARE_AND_ANC)) a = 11
            if (has(t, SWITCH_ANC_AND_OFF)) a = 12
            if (has(t, SWITCH_AMBIENT_AWARE_AND_OFF)) a = 13
            if (has(t, CUSTOM_ULL_SWITCH)) a = 14
            if (has(t, WAKE_UP_VOICE_ASSISTANT) && has(SHORT_CLICK, INTERRUPT_VOICE_ASSISTANT) && has(LONG_PRESS_1, INTERRUPT_VOICE_ASSISTANT)) a = 161
            if (has(t, GSOUND_CANCEL)) a = 162
            if (has(t, GSOUND_NOTIFY)) a = 163
            if (has(PRESS, GSOUND_PRESS) && has(t, GSOUND_VOICE_QUERY) && has(RELEASE, GSOUND_RELEASE)) a = 164
            if (has(t, AMA_START) && has(RELEASE, AMA_STOP)) a = 166
            if (has(t, XIAOWEI_START) && has(RELEASE, XIAOWEI_STOP) && has(SHORT_CLICK, XIAOWEI_STOP_PLAY)) a = 168
            gestureId(t, right)?.let { out[it] = a }
        }
        return KeyMap(out, entries.lastOrNull()?.second)
    }
}

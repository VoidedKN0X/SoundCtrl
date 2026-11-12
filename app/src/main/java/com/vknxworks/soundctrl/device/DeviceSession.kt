package com.vknxworks.soundctrl.device

import android.content.Context
import com.vknxworks.soundctrl.ble.GattLink
import com.vknxworks.soundctrl.ble.Pipe
import com.vknxworks.soundctrl.proto.Aa
import com.vknxworks.soundctrl.proto.AaFrame
import com.vknxworks.soundctrl.proto.Battery
import com.vknxworks.soundctrl.proto.Catalog
import com.vknxworks.soundctrl.proto.Control
import com.vknxworks.soundctrl.proto.HeadphoneP4
import com.vknxworks.soundctrl.proto.HpAaInfo
import com.vknxworks.soundctrl.proto.NoiseMode
import com.vknxworks.soundctrl.proto.P4
import com.vknxworks.soundctrl.proto.P4Assembler
import com.vknxworks.soundctrl.proto.P4Message
import com.vknxworks.soundctrl.proto.Product
import com.vknxworks.soundctrl.proto.Race
import com.vknxworks.soundctrl.proto.SpeakerDfuCommands
import com.vknxworks.soundctrl.proto.SpeakerInfo
import com.vknxworks.soundctrl.proto.SpeakerInfoParser
import com.vknxworks.soundctrl.proto.SpeakerP4
import com.vknxworks.soundctrl.proto.u8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Everything SoundCtrl knows about the connected device. null = not reported (yet). */
data class DeviceState(
    val address: String,
    val connected: Boolean = false,
    val connecting: Boolean = true,
    val failed: Boolean = false,
    val name: String? = null,
    val pid: String? = null,
    val product: Product? = null,
    val control: Control = Control.NONE,
    val firmware: String? = null,
    val partnerFirmware: String? = null,
    val caseFirmware: String? = null,
    val battery: Battery? = null,
    val batteryLeft: Battery? = null,
    val batteryRight: Battery? = null,
    val batteryCase: Battery? = null,
    val noise: NoiseMode? = null,
    val noiseLevels: Map<NoiseMode, Int> = emptyMap(),
    val autoOff: Pair<Boolean, Int>? = null,
    val voicePrompts: Boolean? = null,
    val promptVolume: Int = 0x7F,
    val feedbackTone: Boolean? = null,
    val eqMode: Int? = null,
    val linkMode: Int? = null,
    val role: Int? = null,
    val channel: Int? = null,
    val linkedCount: Int? = null,
    val auracast: Boolean? = null,
    val gestures: Map<Int, Int> = emptyMap(),
    val deviceIndex: Int = 0,
    val mtu: Int = 23,
)

/** One connected JBL device. All protocol traffic for it goes through here. */
class DeviceSession(context: Context, val address: String, knownPid: String?, val le: Boolean) {
    val link = GattLink(context.applicationContext, address)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DeviceState(address, pid = knownPid, product = Catalog.find(knownPid)))
    val state: StateFlow<DeviceState> = _state

    private val _aa = MutableSharedFlow<AaFrame>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val aaFrames: SharedFlow<AaFrame> = _aa
    private val _p4 = MutableSharedFlow<P4Message>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val p4Messages: SharedFlow<P4Message> = _p4
    private var reader: Job? = null

    /** Blocks setting changes while an update runs. */
    @Volatile var updating = false

    suspend fun start(): Boolean {
        _state.update { it.copy(connecting = true, failed = false) }
        var ok = link.connect(le)
        if (!ok && le) ok = link.connect(false)
        if (!ok) {
            _state.update { it.copy(connecting = false, failed = true, connected = false) }
            return false
        }
        reader?.cancel()
        val assembler = P4Assembler()
        reader = scope.launch {
            link.incoming.collect { b ->
                if (b.isNotEmpty() && b[0] == Aa.ID) Aa.parse(b)?.let { _aa.tryEmit(it) }
                else assembler.accept(b)?.let { _p4.tryEmit(it) }
            }
        }
        scope.launch { link.connected.collect { c -> _state.update { it.copy(connected = c) } } }
        _state.update { it.copy(connected = true, connecting = false, mtu = link.mtu) }
        detectControl()
        refresh()
        return true
    }

    fun close() {
        link.close()
        scope.cancel()
    }

    // ---------- request helpers ----------

    /** Sends [frames] and waits for the first incoming item [pick] accepts. */
    suspend fun <T, R> request(source: Flow<T>, frames: List<ByteArray>, timeoutMs: Long = 2500, pick: (T) -> R?): R? = coroutineScope {
        val wait = async(start = CoroutineStart.UNDISPATCHED) { source.mapNotNull(pick).first() }
        for (f in frames) if (!link.write(f)) { wait.cancel(); return@coroutineScope null }
        val r = withTimeoutOrNull(timeoutMs) { wait.await() }
        wait.cancel()
        r
    }

    suspend fun send(frames: List<ByteArray>): Boolean {
        for (f in frames) if (!link.write(f)) return false
        return true
    }

    private suspend fun p4Get(vararg features: Int, timeoutMs: Long = 2000): Map<Int, ByteArray>? =
        request(p4Messages, P4.get(*features), timeoutMs) { m ->
            if (m.cmd == P4.GET && (m.status ?: 0) == 0) P4.parseTlvs(m.payload).associate { it.id to it.value } else null
        }

    /** SET and wait for the status reply. True = the device said OK. */
    suspend fun p4Set(frames: List<ByteArray>): Boolean =
        request(p4Messages, frames, 2500) { m -> if (m.cmd == P4.SET) (m.status ?: 0) == 0 else null } == true

    private suspend fun aaAck(frame: ByteArray, cmd: Int): Boolean =
        request(aaFrames, listOf(frame), 2500) { f -> if (f.cmd == Aa.DEV_ACK && f.payload.size >= 2 && f.payload.u8(0) == cmd) f.payload.u8(1) == 0 else null } == true

    // ---------- detection and refresh ----------

    private suspend fun detectControl() {
        if (link.pipe == Pipe.RACE) {
            _state.update { it.copy(control = Control.HP_AIROHA) }
            return
        }
        val known = _state.value.product
        if (known != null && known.control != Control.NONE) {
            _state.update { it.copy(control = known.control) }
            return
        }
        // Unknown product: ask over protocol 4 first, then the AA device info.
        val p4 = p4Get(P4.F_PRODUCT_ID, timeoutMs = 1500)?.get(P4.F_PRODUCT_ID)
        if (p4 != null && p4.size >= 2) {
            val pid = "%02x%02x".format(p4.u8(1), p4.u8(0))
            val prod = Catalog.find(pid) ?: Catalog.find("%02x%02x".format(p4.u8(0), p4.u8(1)))
            _state.update { it.copy(pid = prod?.pid ?: pid, product = prod, control = prod?.control ?: Control.HP_P4) }
            return
        }
        val info = speakerInfo()
        val prod = Catalog.find(info?.productId)
        _state.update { it.copy(pid = info?.productId ?: it.pid, product = prod ?: it.product, control = prod?.control ?: Control.SPEAKER_AA) }
    }

    /** Collects every `AA 12` piece the speaker sends for one `AA 11`. */
    private suspend fun speakerInfo(): SpeakerInfo? = coroutineScope {
        val parts = mutableListOf<SpeakerInfo>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            aaFrames.collect { f -> if (f.cmd == Aa.RET_DEV_INFO) SpeakerInfoParser.parse(f.payload)?.let { parts += it } }
        }
        if (!link.write(Aa.reqDeviceInfo())) { job.cancel(); return@coroutineScope null }
        delay(1800)
        job.cancel()
        parts.reduceOrNull { a, b ->
            a.copy(
                name = b.name ?: a.name, productId = b.productId ?: a.productId, modelId = b.modelId ?: a.modelId,
                battery = if (b.index == a.index) b.battery ?: a.battery else a.battery, channel = b.channel ?: a.channel,
                linkedCount = b.linkedCount ?: a.linkedCount, mac = b.mac ?: a.mac, serial = b.serial ?: a.serial,
            )
        }
    }

    suspend fun refresh() {
        when (_state.value.control) {
            Control.SPEAKER_AA -> refreshSpeakerAa()
            Control.SPEAKER_P4 -> refreshSpeakerP4()
            Control.HP_P4 -> refreshHeadphoneP4()
            Control.HP_AA -> refreshHeadphoneAa()
            Control.HP_AIROHA -> refreshAiroha()
            Control.NONE -> {}
        }
    }

    private suspend fun refreshSpeakerAa() {
        speakerInfo()?.let { i ->
            val prod = Catalog.find(i.productId) ?: _state.value.product
            _state.update {
                it.copy(name = i.name ?: it.name, pid = i.productId ?: it.pid, product = prod, battery = i.battery ?: it.battery,
                    channel = i.channel, linkedCount = i.linkedCount, deviceIndex = i.index)
            }
        }
        request(aaFrames, listOf(Aa.reqVersion())) { f -> if (f.cmd == Aa.RET_VER) Aa.formatVersion(f.payload) else null }
            ?.let { v -> _state.update { it.copy(firmware = v) } }
        if (_state.value.product?.has("feedback") == true) {
            request(aaFrames, listOf(Aa.reqFeedback())) { f -> if (f.cmd == Aa.RET_FEEDBACK && f.payload.isNotEmpty()) f.payload.u8(0) != 0 else null }
                ?.let { v -> _state.update { it.copy(feedbackTone = v) } }
        }
        if (_state.value.product?.has("eqindoor") == true) {
            request(aaFrames, listOf(Aa.reqEqMode())) { f -> if (f.cmd == Aa.RET_EQ_MODE && f.payload.isNotEmpty()) f.payload.u8(0) else null }
                ?.let { v -> _state.update { it.copy(eqMode = v) } }
        }
    }

    private suspend fun refreshSpeakerP4() {
        val m = request(p4Messages, SpeakerP4.deviceInfo(), 2500) { m ->
            if (m.cmd == P4.GET && (m.status ?: 0) == 0) P4.parseTlvs(m.payload).associate { it.id to it.value } else null
        } ?: return
        applyCommon(m, hexVersion = false)
        p4Get(P4.F_FEEDBACK_TONE)?.get(P4.F_FEEDBACK_TONE)?.firstOrNull()?.let { b -> _state.update { it.copy(feedbackTone = b.toInt() and 0x80 != 0) } }
        p4Get(SpeakerP4.F_AURACAST_MODE)?.get(SpeakerP4.F_AURACAST_MODE)?.firstOrNull()?.let { b -> _state.update { it.copy(auracast = b.toInt() != 0) } }
    }

    private fun applyCommon(m: Map<Int, ByteArray>, hexVersion: Boolean) {
        _state.update { s ->
            s.copy(
                name = m[P4.F_DEVICE_NAME]?.let { String(it, Charsets.UTF_8).trim() }?.takeIf { it.isNotEmpty() } ?: s.name,
                firmware = m[P4.F_FIRMWARE]?.let { P4.versionString(it, hexVersion) } ?: s.firmware,
                battery = m[P4.F_BATTERY_LEFT]?.firstOrNull()?.let { Battery.fromByte(it.toInt() and 0xFF) }?.takeIf { s.product?.isSpeaker == true } ?: s.battery,
                batteryLeft = m[P4.F_BATTERY_LEFT]?.firstOrNull()?.let { Battery.fromByte(it.toInt() and 0xFF) }?.takeIf { s.product?.isSpeaker != true } ?: s.batteryLeft,
                batteryRight = m[P4.F_BATTERY_RIGHT]?.firstOrNull()?.let { Battery.fromByte(it.toInt() and 0xFF) } ?: s.batteryRight,
                batteryCase = m[P4.F_CASE_BATTERY]?.firstOrNull()?.let { Battery.fromByte(it.toInt() and 0xFF) } ?: s.batteryCase,
                caseFirmware = m[P4.F_CASE_FIRMWARE]?.let { P4.versionString(it, hexVersion) } ?: s.caseFirmware,
            )
        }
    }

    private suspend fun refreshHeadphoneP4() {
        // One feature per request: a device that doesn't know a feature may refuse the whole request.
        val all = mutableMapOf<Int, ByteArray>()
        val wanted = listOf(P4.F_DEVICE_NAME, P4.F_FIRMWARE, P4.F_BATTERY_LEFT, P4.F_BATTERY_RIGHT, P4.F_CASE_BATTERY, P4.F_CASE_FIRMWARE,
            P4.F_ANC, P4.F_AMBIENT, P4.F_TALKTHRU, HeadphoneP4.F_AMBIENT_SOUND_CONTROL, P4.F_AUTO_OFF, P4.F_FEEDBACK_TONE) + HeadphoneP4.GESTURE_FEATURES
        for (f in wanted) p4Get(f, timeoutMs = 1200)?.let { all.putAll(it) }
        applyCommon(all, hexVersion = false)
        fun b(id: Int) = all[id]?.firstOrNull()?.let { it.toInt() and 0xFF }
        _state.update { s ->
            val levels = buildMap {
                b(P4.F_ANC)?.let { put(NoiseMode.ANC, it and 0x7F) }
                b(P4.F_AMBIENT)?.let { put(NoiseMode.AMBIENT, it and 0x7F) }
                b(P4.F_TALKTHRU)?.let { put(NoiseMode.TALKTHRU, it and 0x7F) }
            }
            s.copy(
                noise = HeadphoneP4.noiseFrom(b(P4.F_ANC), b(P4.F_AMBIENT), b(P4.F_TALKTHRU), b(HeadphoneP4.F_AMBIENT_SOUND_CONTROL)) ?: s.noise,
                noiseLevels = levels.ifEmpty { s.noiseLevels },
                autoOff = all[P4.F_AUTO_OFF]?.let { HeadphoneP4.parseAutoOff(it) } ?: s.autoOff,
                voicePrompts = b(P4.F_FEEDBACK_TONE)?.let { it and 0x80 != 0 } ?: s.voicePrompts,
                promptVolume = b(P4.F_FEEDBACK_TONE)?.let { it and 0x7F } ?: s.promptVolume,
                gestures = HeadphoneP4.GESTURE_FEATURES.mapNotNull { f -> b(f)?.let { f to it } }.toMap().ifEmpty { s.gestures },
            )
        }
    }

    private suspend fun refreshHeadphoneAa() {
        request(aaFrames, listOf(Aa.reqDeviceInfo())) { f ->
            if (f.cmd == Aa.RET_DEV_INFO) HpAaInfo.parse(byteArrayOf(Aa.ID, f.cmd.toByte(), f.payload.size.toByte()) + f.payload) else null
        }?.let { i ->
            _state.update { it.copy(name = i.name.ifBlank { null } ?: it.name, battery = i.battery, firmware = i.firmware) }
        }
    }

    private suspend fun refreshAiroha() {
        for (role in listOf(Race.AGENT, Race.PARTNER)) {
            request(link.incoming, listOf(Race.getFwVersion(role)), 2500) { b -> Race.parseFwVersion(b)?.takeIf { it.first == role }?.second }
                ?.let { v -> _state.update { if (role == Race.AGENT) it.copy(firmware = v) else it.copy(partnerFirmware = v) } }
        }
    }

    // ---------- settings ----------

    suspend fun setNoise(mode: NoiseMode): Boolean {
        val s = _state.value
        val ok = when (s.control) {
            Control.HP_P4 -> {
                val level = s.noiseLevels[mode] ?: 0x7F
                p4Set(HeadphoneP4.setNoise(mode, s.product?.has("asc") == true, level))
            }
            Control.HP_AA -> aaAck(Aa.hpSetAnc(mode == NoiseMode.ANC), Aa.HP_SET_ANC)
            else -> false
        }
        if (ok) _state.update { it.copy(noise = mode) }
        return ok
    }

    suspend fun setAutoOff(on: Boolean, minutes: Int): Boolean =
        p4Set(HeadphoneP4.setAutoOff(on, minutes)).also { if (it) _state.update { s -> s.copy(autoOff = on to minutes) } }

    suspend fun setVoicePrompts(on: Boolean): Boolean =
        p4Set(HeadphoneP4.setVoicePrompts(on, _state.value.promptVolume)).also { if (it) _state.update { s -> s.copy(voicePrompts = on) } }

    suspend fun setGesture(feature: Int, action: Int): Boolean =
        p4Set(HeadphoneP4.setGesture(feature, action)).also { if (it) _state.update { s -> s.copy(gestures = s.gestures + (feature to action)) } }

    suspend fun setFeedbackTone(on: Boolean): Boolean {
        val ok = when (_state.value.control) {
            Control.SPEAKER_AA -> aaAck(Aa.setFeedback(on), Aa.SET_FEEDBACK)
            Control.SPEAKER_P4 -> p4Set(SpeakerP4.setFeedback(on))
            else -> false
        }
        if (ok) _state.update { it.copy(feedbackTone = on) }
        return ok
    }

    suspend fun setEqMode(mode: Int): Boolean =
        aaAck(Aa.setEqMode(mode), Aa.SET_EQ_MODE).also { if (it) _state.update { s -> s.copy(eqMode = mode) } }

    suspend fun rename(name: String): Boolean {
        val ok = when (_state.value.control) {
            Control.SPEAKER_AA -> aaAck(Aa.setName(_state.value.deviceIndex, name), Aa.SET_DEV_INFO)
            Control.SPEAKER_P4 -> p4Set(SpeakerP4.setName(name))
            else -> false
        }
        if (ok) _state.update { it.copy(name = name.take(16)) }
        return ok
    }

    /** PartyBoost / Connect+ link (0 off, 1 on) or Auracast (2). Reply `AA 6B [role]`. */
    suspend fun setLinkMode(mode: Int): Boolean {
        val role = request(aaFrames, listOf(Aa.setLinkMode(mode))) { f -> if (f.cmd == Aa.RET_LINK_MODE && f.payload.size == 1) f.payload.u8(0) else null }
        if (role != null) _state.update { it.copy(linkMode = mode, role = role) }
        return role != null
    }

    suspend fun setAuracastP4(on: Boolean): Boolean =
        p4Set(SpeakerP4.setAuracast(on)).also { if (it) _state.update { s -> s.copy(auracast = on) } }

    /** Stereo channel 0 = both, 1 = left, 2 = right. */
    suspend fun setChannel(channel: Int): Boolean =
        aaAck(Aa.setChannel(_state.value.deviceIndex, channel), Aa.SET_DEV_INFO).also { if (it) _state.update { s -> s.copy(channel = channel) } }

    suspend fun identify(): Boolean = link.write(Aa.identify(_state.value.deviceIndex))

    /** `AA 4A` -> `AA 4B`: the version of an image the speaker already holds (part of). */
    suspend fun speakerDfuVersion(): String? =
        request(aaFrames, listOf(SpeakerDfuCommands.reqDfuVersion())) { f -> if (f.cmd == Aa.RET_DFU_VERSION) SpeakerDfuCommands.parseDfuVersion(f.payload) else null }
}

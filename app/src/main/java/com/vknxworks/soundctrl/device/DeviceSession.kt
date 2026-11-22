package com.vknxworks.soundctrl.device

import android.content.Context
import com.vknxworks.soundctrl.ble.DeviceLink
import com.vknxworks.soundctrl.ble.GattLink
import com.vknxworks.soundctrl.ble.Pipe
import com.vknxworks.soundctrl.ble.SppLink
import com.vknxworks.soundctrl.proto.Aa
import com.vknxworks.soundctrl.proto.AaFrame
import com.vknxworks.soundctrl.proto.AirohaFamily
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
import com.vknxworks.soundctrl.proto.RaceAssembler
import com.vknxworks.soundctrl.proto.RaceGestures
import com.vknxworks.soundctrl.proto.RaceTourPro3
import com.vknxworks.soundctrl.proto.SpeakerDfuCommands
import com.vknxworks.soundctrl.proto.SpeakerInfo
import com.vknxworks.soundctrl.proto.SpeakerInfoParser
import com.vknxworks.soundctrl.proto.SpeakerP4
import com.vknxworks.soundctrl.proto.hex
import com.vknxworks.soundctrl.proto.u8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
    /** The first connection failed; SoundCtrl waits for the phone to connect to the device (or a tap on Try again). */
    val failed: Boolean = false,
    /** The device was connected and the link dropped (not by the user): SoundCtrl is connecting again. */
    val reconnecting: Boolean = false,
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
    /** Airoha devices: the chip family read from the device (null = not read, or not an Airoha device). */
    val airoha: AirohaFamily? = null,
    /** Airoha devices: connected over classic Bluetooth (RFCOMM) rather than BLE. */
    val classic: Boolean = false,
    /** Airoha earbuds: is the second earbud connected to the first, and is the first one the right one? */
    val partnerPresent: Boolean? = null,
    val agentRight: Boolean? = null,
    /** Airoha: in-ear detection pauses and resumes playback. */
    val inEarPlayPause: Boolean? = null,
    /** Airoha: which earbud SoundCtrl asked to ring. */
    val ringingLeft: Boolean = false,
    val ringingRight: Boolean = false,
    /** Airoha earbuds: the relay destination of the second earbud (from `0D00`), for commands meant for it. */
    val partnerDst: Pair<Int, Int>? = null,
    // ----- Tour Pro 3 only (tourPro3) -----
    /** Voice prompts switched on (`0901 [01 00]`) and the prompt language index (255 = tones). */
    val vpOn: Boolean? = null,
    val vpLanguage: Int? = null,
    /** Voice Aware (hear yourself during calls) and its level (the app uses 1-3). */
    val voiceAware: Boolean? = null,
    val voiceAwareLevel: Int? = null,
    val adaptiveAnc: Boolean? = null,
    /** Smart Talk on and its timeout in seconds. */
    val smartTalk: Pair<Boolean, Int>? = null,
    val hiRes: Boolean? = null,
    /** Spatial sound: [RaceTourPro3.SPATIAL_OFF], `SPATIAL_FIXED` or `SPATIAL_HEAD_TRACKING`. */
    val spatial: Int? = null,
    /** Touch gestures of both earbuds (Harman gesture id → action id), the key id to write, and whether both were read. */
    val airohaGestures: Map<Int, Int> = emptyMap(),
    val gestureKeyId: Int = RaceGestures.DEFAULT_KEY_ID,
    val gesturesComplete: Boolean = false,
) {
    /** Battery, case battery and Find my buds are established for this Airoha device. */
    val airohaBasics: Boolean get() = control == Control.HP_AIROHA && airoha?.hasBasics == true && (pid ?: "") !in AirohaFamily.OWN_CONTROL_PIDS
    /** Noise control, auto power-off and auto play/pause are established for this Airoha device. */
    val airohaSettings: Boolean get() = airohaBasics && airoha?.hasMmiSettings == true
    /** The Tour Pro 3's own settings (gestures, voice prompts, Voice Aware, adaptive ANC, Smart Talk, Hi-Res, spatial sound). */
    val tourPro3: Boolean get() = airohaSettings && airoha == AirohaFamily.AB158X && RaceTourPro3.isTourPro3(pid)
}

/**
 * Why the last Airoha setting failed, for the message shown to the user: nothing was sent, no answer came,
 * the device refused it (with its status byte), or an answer with the right id but a layout SoundCtrl didn't
 * expect came (its first bytes in [hex]). [seen] lists other frames that came in while waiting ("5D 0901").
 */
data class RaceFailure(val kind: Kind, val id: Int, val status: Int = 0, val hex: String = "", val seen: List<String> = emptyList()) {
    enum class Kind { NOT_SENT, NO_ANSWER, REFUSED, UNEXPECTED }
}

/**
 * One connected JBL device. All protocol traffic for it goes through here.
 *
 * The session keeps itself connected until [close]: when the first connection fails it waits for [wake] (the
 * phone reports the device connected, the app comes back to the foreground, or Try again); when an open link
 * drops it connects again after 2 s, 5 s, 10 s and then every 30 s while the app is in the foreground
 * ([foreground]), and at once on [wake]. Never while a firmware update runs ([updating]). Every attempt builds a
 * new link (sockets and GATT connections can't be reused) and runs the whole identify/refresh sequence again.
 * [onReady] is called after each successful connection and refresh.
 */
class DeviceSession(
    context: Context,
    val address: String,
    knownPid: String?,
    val le: Boolean,
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
    private val onReady: (DeviceState) -> Unit = {},
) {
    private val appContext = context.applicationContext
    /** BLE GATT, or classic RFCOMM for Airoha devices (picked in [connectOnce]). A new one for every connection. */
    @Volatile var link: DeviceLink = GattLink(appContext, address)
        private set
    /** A failure in one of the session's jobs must never close the app. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private val _state = MutableStateFlow(DeviceState(address, pid = knownPid, product = Catalog.find(knownPid)))
    val state: StateFlow<DeviceState> = _state

    private val _aa = MutableSharedFlow<AaFrame>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val aaFrames: SharedFlow<AaFrame> = _aa
    private val _p4 = MutableSharedFlow<P4Message>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val p4Messages: SharedFlow<P4Message> = _p4
    private val _race = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Whole RACE frames from an Airoha device. */
    val raceFrames: SharedFlow<ByteArray> = _race
    private var reader: Job? = null
    private var runner: Job? = null
    @Volatile private var closed = false
    /** "Try now": the phone connected to the device, the app came back, or the user tapped Try again. */
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Why the last Airoha setting failed (null after a success). Read by the device screen for its message. */
    @Volatile var lastRaceFailure: RaceFailure? = null
        private set

    fun clearRaceFailure() { lastRaceFailure = null }

    /** Blocks setting changes while an update runs. Also holds off reconnecting until the update is over. */
    @Volatile var updating = false

    /** Starts connecting; the session then keeps the device connected until [close]. */
    fun start() {
        if (runner != null || closed) return
        runner = scope.launch { keepConnected() }
    }

    /** Try to connect now, if the device isn't connected (no effect while it is). */
    fun wake() {
        if (!closed && !_state.value.connected) wakeUp.trySend(Unit)
    }

    fun close() {
        closed = true
        scope.cancel()
        link.close()
    }

    private suspend fun keepConnected() {
        var everConnected = false
        var attempt = 0
        while (!closed) {
            if (!everConnected) _state.update { it.copy(connecting = true, failed = false) }
            val ok = try {
                connectOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A Bluetooth call that threw (permission taken away, adapter gone): counts as a failed attempt.
                link.close()
                false
            }
            if (ok) {
                everConnected = true
                attempt = 0
                // Wait until the link drops; a drop while connecting and refreshing is seen here at once.
                val l = link
                l.connected.first { !it }
                if (closed) return
                reader?.cancel()
                l.close()
                _state.update { it.copy(connected = false, reconnecting = true, ringingLeft = false, ringingRight = false) }
                // Wake-ups that came while connected (the phone's own profile connections) are stale now.
                while (wakeUp.tryReceive().isSuccess) { /* drain */ }
            } else if (!everConnected) {
                _state.update { it.copy(connecting = false, failed = true, connected = false) }
            }
            waitForNextTry(timer = everConnected, attempt)
            attempt++
        }
    }

    /**
     * Waits before the next attempt: until [wake], or (after a drop, [timer]) 2 s, 5 s, 10 s and then 30 s. Only
     * in the foreground, and never while a firmware update runs; coming back to the foreground tries at once.
     */
    private suspend fun waitForNextTry(timer: Boolean, attempt: Int) {
        while (!closed) {
            if (updating) {
                delay(1_000)
                continue
            }
            if (!foreground.value) {
                foreground.first { it }
                while (wakeUp.tryReceive().isSuccess) { /* drain */ }
                return
            }
            if (timer) withTimeoutOrNull(RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_EVERY_MS }) { wakeUp.receive() }
            else wakeUp.receive()
            if (updating || !foreground.value) continue
            return
        }
    }

    /** One connection attempt with a fresh link, then the identify/init sequence and a refresh. */
    private suspend fun connectOnce(): Boolean {
        var ok = false
        // Airoha earbuds (Tour Pro 3 and others) are controlled over classic Bluetooth by the JBL app: try Airoha's
        // RFCOMM service first for them, and for paired devices whose model isn't known yet. BLE GATT otherwise.
        val known = _state.value.product
        // A device that was reached over RFCOMM before is reached the same way again (no GATT fallback then).
        val wasClassic = _state.value.classic
        if (known?.control == Control.HP_AIROHA || (known == null && !le) || wasClassic) {
            val spp = SppLink(appContext, address)
            if (spp.connect()) {
                link = spp
                ok = true
            }
        }
        val classic = ok
        if (!ok && !wasClassic) {
            val gatt = GattLink(appContext, address)
            link = gatt
            ok = gatt.connect(le)
            if (!ok && le) ok = gatt.connect(false)
        }
        if (closed) {
            link.close()
            return false
        }
        if (!ok) return false
        reader?.cancel()
        val assembler = P4Assembler()
        val raceAssembler = RaceAssembler()
        val l = link
        reader = scope.launch {
            l.incoming.collect { b ->
                if (l.pipe == Pipe.RACE) raceAssembler.accept(b).forEach { _race.tryEmit(it) }
                else if (b.isNotEmpty() && b[0] == Aa.ID) Aa.parse(b)?.let { _aa.tryEmit(it) }
                else assembler.accept(b)?.let { _p4.tryEmit(it) }
            }
        }
        while (wakeUp.tryReceive().isSuccess) { /* drain */ }
        _state.update {
            it.copy(connected = true, connecting = false, failed = false, reconnecting = false, classic = classic, mtu = l.mtu,
                ringingLeft = false, ringingRight = false)
        }
        detectControl()
        refresh()
        if (!l.connected.value) return true // dropped during the refresh: keepConnected sees it and tries again
        onReady(_state.value)
        return true
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
            identifyAiroha()
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

    private suspend fun <R> race(frame: ByteArray, timeoutMs: Long = 3000, pick: (ByteArray) -> R?): R? =
        request(raceFrames, listOf(frame), timeoutMs, pick)

    /**
     * Sends one Airoha setting and waits for the answer. [accept] returns true (done), false (the device refused:
     * right id, status not 0) or null (not the answer). Records why it failed in [lastRaceFailure].
     */
    private suspend fun raceSet(frame: ByteArray, id: Int, timeoutMs: Long = 4000, accept: (ByteArray) -> Boolean?): Boolean = coroutineScope {
        lastRaceFailure = null
        var sameId: ByteArray? = null
        val seen = mutableListOf<String>()
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            raceFrames.mapNotNull { b ->
                val f = Race.parse(b)
                if (f == null || (f.type != Race.RESPONSE && f.type != Race.INDICATION)) {
                    null
                } else {
                    val a = accept(b)
                    if (a == null) {
                        if (f.id == id && sameId == null) sameId = b
                        if (seen.size < 4) seen += "%02X %04X".format(f.type, f.id)
                        null
                    } else {
                        a to b
                    }
                }
            }.first()
        }
        if (!link.write(frame)) {
            wait.cancel()
            lastRaceFailure = RaceFailure(RaceFailure.Kind.NOT_SENT, id)
            return@coroutineScope false
        }
        val r = withTimeoutOrNull(timeoutMs) { wait.await() }
        wait.cancel()
        when {
            r == null -> {
                val same = sameId
                lastRaceFailure = if (same != null) {
                    RaceFailure(RaceFailure.Kind.UNEXPECTED, id, hex = same.copyOfRange(0, minOf(same.size, 16)).hex())
                } else {
                    RaceFailure(RaceFailure.Kind.NO_ANSWER, id, seen = seen.toList())
                }
                false
            }
            r.first -> true
            else -> {
                // A relayed command refused by the other earbud: its own status is in the inner frame.
                val status = RaceTourPro3.unwrapRelay(r.second)?.let { Race.statusOf(it) } ?: Race.statusOf(r.second)
                lastRaceFailure = RaceFailure(RaceFailure.Kind.REFUSED, id, status = status ?: -1)
                false
            }
        }
    }

    /**
     * Reads the chip name and, when the model isn't known yet, the model name (the product id on JBL models),
     * as the Airoha SDK does right after connecting (`AirohaConnector`).
     */
    private suspend fun identifyAiroha() {
        val chip = race(Race.getChipName()) { Race.parseChipName(it) } ?: return
        val family = AirohaFamily.fromChipName(chip)
        _state.update { it.copy(airoha = family) }
        // As the JBL app does once the link is up: turn on the device's status pushes. Its answer isn't needed.
        if (family != AirohaFamily.AB1562) race(Race.setDevicePushStatus(true)) { b -> Race.parse(b)?.takeIf { it.type == Race.RESPONSE && it.id == Race.SET_DEVICE_PUSH_STATUS } }
        if (_state.value.product == null && family != AirohaFamily.AB1562) {
            val model = race(Race.getModelName(family)) { Race.parseModelName(it) }?.lowercase()
            val prod = Catalog.find(model)
            if (prod != null) _state.update { it.copy(pid = prod.pid, product = prod) }
        }
    }

    private suspend fun refreshAiroha() {
        if (_state.value.airoha == null) identifyAiroha()
        val basics = _state.value.airohaBasics
        var partner = true
        if (basics) {
            val right = race(Race.getAgentIsRight()) { Race.parseAgentIsRight(it) }
            val dstReply = race(Race.getAvailableDestinations()) { b -> b.takeIf { Race.parsePartnerExists(it) != null } }
            partner = dstReply?.let { Race.parsePartnerExists(it) } ?: true
            val dst = dstReply?.let { RaceTourPro3.parsePartnerDst(it) }
            _state.update { it.copy(agentRight = right, partnerPresent = partner, partnerDst = dst) }
        }
        for (role in if (partner) listOf(Race.AGENT, Race.PARTNER) else listOf(Race.AGENT)) {
            race(Race.getFwVersion(role)) { b -> Race.parseFwVersion(b)?.takeIf { it.first == role }?.second }
                ?.let { v -> _state.update { if (role == Race.AGENT) it.copy(firmware = v) else it.copy(partnerFirmware = v) } }
        }
        if (basics) refreshAirohaBattery(partner)
        if (_state.value.airohaSettings) {
            race(Race.getNoise()) { Race.parseNoise(it, _state.value.pid) }?.let { m -> _state.update { it.copy(noise = m) } }
            race(Race.getCommonConfig(Race.MODULE_AUTO_POWER_OFF)) { Race.parseAutoOff(it) }?.let { v -> _state.update { it.copy(autoOff = v) } }
            race(Race.getCommonConfig(Race.MODULE_IN_EAR_PLAY_PAUSE)) { Race.parseInEarPlayPause(it) }?.let { v -> _state.update { it.copy(inEarPlayPause = v) } }
        }
        if (_state.value.tourPro3) refreshTourPro3()
    }

    /** The Tour Pro 3's own settings, each read as the JBL app reads it (PROTOCOL.md §7.4). */
    private suspend fun refreshTourPro3() {
        val t = 2500L
        race(RaceTourPro3.getVpOnOff(), t) { RaceTourPro3.parseVpOnOff(it) }?.let { v -> _state.update { it.copy(vpOn = v) } }
        race(RaceTourPro3.getVpLanguage(), t) { RaceTourPro3.parseVpLanguage(it) }?.let { v -> _state.update { it.copy(vpLanguage = v) } }
        race(RaceTourPro3.getSidetoneState(), t) { RaceTourPro3.parseSidetoneState(it) }?.let { v -> _state.update { it.copy(voiceAware = v) } }
        race(RaceTourPro3.getSidetoneLevel(), t) { RaceTourPro3.parseSidetoneLevel(it) }?.let { v -> _state.update { it.copy(voiceAwareLevel = v) } }
        race(RaceTourPro3.getAdaptiveAnc(), t) { RaceTourPro3.parseAdaptiveAnc(it) }?.let { v -> _state.update { it.copy(adaptiveAnc = v) } }
        race(RaceTourPro3.getSmartTalk(), t) { RaceTourPro3.parseSmartTalk(it) }?.let { v -> _state.update { it.copy(smartTalk = v) } }
        race(RaceTourPro3.getHiRes(), t) { RaceTourPro3.parseHiRes(it) }?.let { v -> _state.update { it.copy(hiRes = v) } }
        race(RaceTourPro3.getSpatial(), t) { RaceTourPro3.parseSpatial(it) }?.let { v -> _state.update { it.copy(spatial = v) } }
        refreshGestures()
    }

    /**
     * Both earbuds' key maps: the one SoundCtrl talks to directly, the other through the relay, as the app does
     * (`MmiStage_GetGesture_1568_v3`, plain and relayed). Without the agent's side or the second earbud they can't be
     * changed, because the app always writes both.
     */
    private suspend fun refreshGestures() {
        val s = _state.value
        val right = s.agentRight ?: return
        val agent = race(RaceGestures.read(), 2500) { Race.parseNvData(it) }?.let { RaceGestures.parse(it, right) } ?: return
        val dst = s.partnerDst
        val other = if (s.partnerPresent == true && dst != null) {
            race(RaceTourPro3.relay(dst, RaceGestures.read()), 3000) { b -> RaceTourPro3.unwrapRelay(b)?.let { Race.parseNvData(it) } }
                ?.let { RaceGestures.parse(it, !right) }
        } else {
            null
        }
        // The app keeps the key id of the last entry it parsed (the second earbud's, read last).
        val keyId = other?.keyId ?: agent.keyId ?: RaceGestures.DEFAULT_KEY_ID
        _state.update { it.copy(airohaGestures = agent.gestures + (other?.gestures ?: emptyMap()), gestureKeyId = keyId, gesturesComplete = other != null) }
    }

    /** Each earbud's battery and charging state, then the case. Agent/partner become left/right by the agent's side. */
    private suspend fun refreshAirohaBattery(partner: Boolean) {
        val roles = if (partner) listOf(Race.AGENT, Race.PARTNER) else listOf(Race.AGENT)
        val levels = mutableMapOf<Int, Battery>()
        for (role in roles) {
            val level = race(Race.getBattery(role)) { b -> Race.parseBattery(b)?.takeIf { it.first == role }?.second } ?: continue
            val charging = race(Race.getChargeInfo(role)) { b -> Race.parseChargeInfo(b)?.takeIf { it.first == role }?.second } ?: false
            levels[role] = Battery(level, charging)
        }
        val case = race(Race.getCaseBattery()) { Race.parseCaseBattery(it) }
        _state.update { s ->
            val right = s.agentRight
            if (right == null) {
                // Without the agent's side the earbuds can't be told apart: show the one SoundCtrl talks to as the battery.
                s.copy(battery = levels[Race.AGENT] ?: s.battery, batteryCase = case?.let { Battery(it, false) } ?: s.batteryCase)
            } else {
                val agent = levels[Race.AGENT]
                val other = levels[Race.PARTNER]
                s.copy(
                    batteryLeft = (if (right) other else agent) ?: s.batteryLeft,
                    batteryRight = (if (right) agent else other) ?: s.batteryRight,
                    batteryCase = case?.let { Battery(it, false) } ?: s.batteryCase,
                )
            }
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
            // The reply to 0E06, or the device's status push (5B/5D 0901) showing the new mode, counts as done.
            Control.HP_AIROHA -> s.airohaSettings && raceSet(Race.setNoise(mode, s.pid), Race.ANC_ON_OFF) { b ->
                Race.parseSetNoise(b, mode) ?: Race.parseNoise(b, s.pid)?.takeIf { it == mode }?.let { true }
            }
            else -> false
        }
        if (ok) _state.update { it.copy(noise = mode) }
        return ok
    }

    suspend fun setAutoOff(on: Boolean, minutes: Int): Boolean {
        val ok = when (_state.value.control) {
            Control.HP_P4 -> p4Set(HeadphoneP4.setAutoOff(on, minutes))
            Control.HP_AIROHA -> _state.value.airohaSettings &&
                raceSet(Race.setAutoOff(on, minutes), Race.SET_MMI_COMMON_CONFIG) { Race.parseSetCommonConfig(it, Race.MODULE_AUTO_POWER_OFF) }
            else -> false
        }
        if (ok) _state.update { s -> s.copy(autoOff = on to minutes) }
        return ok
    }

    /** Airoha: pause when an earbud comes out, play when it goes back in. */
    suspend fun setInEarPlayPause(on: Boolean): Boolean {
        val ok = _state.value.airohaSettings &&
            raceSet(Race.setInEarPlayPause(on), Race.SET_MMI_COMMON_CONFIG) { Race.parseSetCommonConfig(it, Race.MODULE_IN_EAR_PLAY_PAUSE) }
        if (ok) _state.update { it.copy(inEarPlayPause = on) }
        return ok
    }

    /** Airoha: ring the left ([Race.FIND_LEFT]) or right earbud, or stop it. */
    suspend fun findBud(target: Int, ring: Boolean): Boolean {
        val ok = _state.value.airohaBasics && raceSet(Race.findMe(target, ring), Race.FIND_ME) { Race.parseFindMe(it) }
        if (ok) _state.update { if (target == Race.FIND_LEFT) it.copy(ringingLeft = ring) else it.copy(ringingRight = ring) }
        return ok
    }

    // ---------- Tour Pro 3 ----------

    /** Relayed command for the second earbud: its `5B 0D01` ack must be 0, then the inner answer decides. */
    private fun relayed(innerId: Int): (ByteArray) -> Boolean? = { b ->
        val ack = RaceTourPro3.relayAckStatus(b)
        if (ack != null) {
            if (ack != 0) false else null
        } else {
            RaceTourPro3.unwrapRelay(b)?.let { RaceTourPro3.parseStatus(it, innerId) }
        }
    }

    /**
     * Gives one earbud a preset (tap, double tap, triple tap, tap and hold), the way the app writes gestures on
     * AB158x earbuds: both key maps on the earbud the phone talks to, reload, then the same through the relay on
     * the other earbud. Needs both earbuds' key maps read first ([DeviceState.gesturesComplete]).
     */
    suspend fun setGesturePreset(right: Boolean, preset: RaceGestures.Preset): Boolean {
        val s = _state.value
        val agentRight = s.agentRight
        val dst = s.partnerDst
        if (!s.tourPro3 || !s.gesturesComplete || agentRight == null || dst == null) return false
        val ids = if (right) RaceGestures.RIGHT_IDS else RaceGestures.LEFT_IDS
        val updated = s.airohaGestures.toMutableMap()
        ids.take(4).forEachIndexed { i, id -> updated[id] = preset.actions[i] }
        val agentMap = RaceGestures.keyMap(updated, agentRight, s.gestureKeyId)
        val partnerMap = RaceGestures.keyMap(updated, !agentRight, s.gestureKeyId)
        val write = RaceTourPro3.NVKEY_WRITE
        val reload = RaceTourPro3.RELOAD_NVKEY
        val relay = RaceTourPro3.RELAY_PASS_TO_DST
        val ok = raceSet(RaceGestures.write(RaceGestures.NV_KEY_MAP, agentMap), write) { RaceTourPro3.parseStatus(it, write) } &&
            raceSet(RaceGestures.write(RaceGestures.NV_PEER_KEY_MAP, partnerMap), write) { RaceTourPro3.parseStatus(it, write) } &&
            raceSet(RaceGestures.reload(), reload) { RaceTourPro3.parseStatus(it, reload) } &&
            raceSet(RaceTourPro3.relay(dst, RaceGestures.write(RaceGestures.NV_KEY_MAP, partnerMap)), relay, accept = relayed(write)) &&
            raceSet(RaceTourPro3.relay(dst, RaceGestures.write(RaceGestures.NV_PEER_KEY_MAP, agentMap)), relay, accept = relayed(write)) &&
            raceSet(RaceTourPro3.relay(dst, RaceGestures.reload()), relay, accept = relayed(reload))
        if (ok) _state.update { it.copy(airohaGestures = updated) }
        return ok
    }

    /** Voice prompts: 0 off, 1 tones, 2 spoken. Turning them on again is the language index alone, as in the app. */
    suspend fun setVoicePromptMode(mode: Int): Boolean {
        if (!_state.value.tourPro3) return false
        val ok = if (mode == 0) {
            raceSet(RaceTourPro3.setVpOff(), RaceTourPro3.HOSTAUDIO_MMI_SET) { RaceTourPro3.parseSetVpOff(it) }
        } else {
            val index = if (mode == 1) RaceTourPro3.VP_TONES else RaceTourPro3.VP_SPOKEN
            raceSet(RaceTourPro3.setVpLanguage(index), Race.SET_MMI_COMMON_CONFIG) { Race.parseSetCommonConfig(it, RaceTourPro3.MODULE_VP_LANGUAGE) }
        }
        if (ok) {
            if (mode == 0) _state.update { it.copy(vpOn = false) } else _state.update { it.copy(vpLanguage = if (mode == 1) RaceTourPro3.VP_TONES else RaceTourPro3.VP_SPOKEN) }
            // Show what the earbuds report now (whether the language index alone turned prompts on again).
            race(RaceTourPro3.getVpOnOff(), 2500) { RaceTourPro3.parseVpOnOff(it) }?.let { v -> _state.update { it.copy(vpOn = v) } }
        }
        return ok
    }

    /** Voice Aware on/off and level: the app always sends the state, then the level (`setVoiceAwareStatus`). */
    suspend fun setVoiceAware(on: Boolean, level: Int): Boolean {
        if (!_state.value.tourPro3) return false
        val m = Race.SET_MMI_COMMON_CONFIG
        val ok = raceSet(RaceTourPro3.setSidetoneState(on), m) { Race.parseSetCommonConfig(it, RaceTourPro3.MODULE_SIDETONE_STATE) } &&
            raceSet(RaceTourPro3.setSidetoneLevel(level), m) { Race.parseSetCommonConfig(it, RaceTourPro3.MODULE_SIDETONE_LEVEL) }
        if (ok) _state.update { it.copy(voiceAware = on, voiceAwareLevel = level) }
        return ok
    }

    suspend fun setAdaptiveAnc(on: Boolean): Boolean {
        val ok = _state.value.tourPro3 &&
            raceSet(RaceTourPro3.setAdaptiveAnc(on), RaceTourPro3.ANC_GAIN_CONTROL) { RaceTourPro3.parseSetAdaptiveAnc(it) }
        if (ok) _state.update { it.copy(adaptiveAnc = on) }
        return ok
    }

    suspend fun setSmartTalk(on: Boolean, timeout: Int): Boolean {
        val id = RaceTourPro3.SMART_TALK_SET
        val ok = _state.value.tourPro3 && raceSet(RaceTourPro3.setSmartTalk(on, timeout), id) { RaceTourPro3.parseStatus(it, id) }
        if (ok) _state.update { it.copy(smartTalk = on to timeout) }
        return ok
    }

    suspend fun setHiRes(on: Boolean): Boolean {
        val id = RaceTourPro3.HI_RES_SET
        val ok = _state.value.tourPro3 && raceSet(RaceTourPro3.setHiRes(on), id) { RaceTourPro3.parseStatus(it, id, minSize = 8) }
        if (ok) _state.update { it.copy(hiRes = on) }
        return ok
    }

    suspend fun setSpatial(mode: Int): Boolean {
        val id = RaceTourPro3.SEND_HM_CMD
        val ok = _state.value.tourPro3 && raceSet(RaceTourPro3.setSpatial(mode), id) { RaceTourPro3.parseStatus(it, id) }
        if (ok) _state.update { it.copy(spatial = mode) }
        return ok
    }

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

    private companion object {
        /** Waits before the first reconnect attempts after a drop; then every [RETRY_EVERY_MS]. */
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000)
        const val RETRY_EVERY_MS = 30_000L
    }
}

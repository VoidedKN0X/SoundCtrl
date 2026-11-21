package com.vknxworks.soundctrl.update

import com.vknxworks.soundctrl.R

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.vknxworks.soundctrl.device.DeviceSession
import com.vknxworks.soundctrl.device.DeviceState
import com.vknxworks.soundctrl.proto.Battery
import com.vknxworks.soundctrl.proto.Md5
import com.vknxworks.soundctrl.proto.Offer
import com.vknxworks.soundctrl.proto.Product
import com.vknxworks.soundctrl.proto.UpdateFamily
import com.vknxworks.soundctrl.proto.UpdateServer
import com.vknxworks.soundctrl.proto.compareVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the firmware update stands. One update at a time, app-wide. */
sealed interface UpdateStep {
    data object Idle : UpdateStep
    data object Checking : UpdateStep
    data class Checked(val offer: Offer, val newer: Boolean?) : UpdateStep
    data class Downloading(val progress: Float) : UpdateStep
    /** The download broke off after [received] of [total] bytes, even after retrying; [resume] carries on from there. */
    data class Interrupted(val offer: Offer, val received: Long, val total: Long) : UpdateStep
    /** A verified image (MD5 matched), ready to send. */
    data class Ready(val version: String, val size: Int, val fromFile: Boolean) : UpdateStep
    data class Sending(val sent: Long, val total: Long) : UpdateStep
    data object Done : UpdateStep
    data class Failed(val reason: Reason, val detail: String? = null) : UpdateStep
    data object Cancelled : UpdateStep
}

enum class Reason { NO_SOURCE, NETWORK, BAD_ANSWER, MD5_MISMATCH, NOT_NEWER, BATTERY, BUSY, NOT_SUPPORTED, TRANSFER }

/** Why an update can't start now; null = it can. */
enum class Blocker { NOT_SUPPORTED, NOT_NEWER, UNKNOWN_VERSION, BATTERY, PLAYING, GROUPED }

class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val _step = MutableStateFlow<UpdateStep>(UpdateStep.Idle)
    val step: StateFlow<UpdateStep> = _step
    var address: String? = null
        private set
    var deviceName: String? = null
        private set

    private var image: ByteArray? = null
    /** A download that broke off, kept in memory so it can carry on from where it stopped. */
    private var partial: PartialImage? = null
    private var imageVersion: String? = null
    private var job: Job? = null

    val running: Boolean get() = _step.value is UpdateStep.Sending

    fun reset(forAddress: String) {
        if (running) return
        job?.cancel()
        address = forAddress
        image = null
        partial = null
        imageVersion = null
        _step.value = UpdateStep.Idle
    }

    /** "Check for update": one anonymous request with only the product id in the address. */
    fun check(product: Product, currentVersion: String?) {
        if (running) return
        val url = UpdateServer.configUrl(product) ?: run { _step.value = UpdateStep.Failed(Reason.NO_SOURCE); return }
        job?.cancel()
        job = scope.launch {
            _step.value = UpdateStep.Checking
            val body = try { FileServer.getText(url) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _step.value = UpdateStep.Failed(Reason.NETWORK, e.message); return@launch
            }
            val offer = UpdateServer.parse(product, url, body) ?: run { _step.value = UpdateStep.Failed(Reason.BAD_ANSWER); return@launch }
            _step.value = UpdateStep.Checked(offer, currentVersion?.let { offer.isNewerThan(it) })
        }
    }

    /**
     * Downloads [offer]'s image. A connection that breaks is retried by itself a few times, each time asking only
     * for the part that's still missing; if it still fails, [UpdateStep.Interrupted] keeps what arrived and
     * "Resume download" (calling this again) carries on from there.
     */
    fun download(offer: Offer) {
        if (running) return
        job?.cancel()
        job = scope.launch {
            if (partial?.url != offer.url) partial = null
            var retries = 0
            var downloaded: ByteArray? = null
            while (downloaded == null) {
                _step.value = UpdateStep.Downloading(partial?.let { it.count.toFloat() / it.total } ?: 0f)
                try {
                    downloaded = FileServer.getImageResumable(offer.url, partial) { p -> _step.value = UpdateStep.Downloading(p) }.bytes()
                    partial = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DownloadInterrupted) {
                    partial = e.partial
                    if (retries++ < AUTO_RETRIES) {
                        delay(RETRY_DELAY_MS * retries)
                        continue
                    }
                    val p = partial
                    _step.value = if (p != null) UpdateStep.Interrupted(offer, p.count.toLong(), p.total) else UpdateStep.Failed(Reason.NETWORK, e.message)
                    return@launch
                } catch (e: Exception) {
                    partial = null
                    _step.value = UpdateStep.Failed(Reason.NETWORK, e.message)
                    return@launch
                }
            }
            val data = downloaded ?: return@launch
            if (!Md5.matches(data, offer.md5)) { _step.value = UpdateStep.Failed(Reason.MD5_MISMATCH); return@launch }
            image = data
            imageVersion = offer.version
            _step.value = UpdateStep.Ready(offer.version, data.size, fromFile = false)
        }
    }

    /** An image the user picked. They must give its MD5 and version; the MD5 must match. */
    fun loadFile(data: ByteArray, md5: String, version: String): Boolean {
        if (running) return false
        if (!Md5.matches(data, md5)) { _step.value = UpdateStep.Failed(Reason.MD5_MISMATCH); return false }
        if (compareVersions(version, "0") == null) { _step.value = UpdateStep.Failed(Reason.BAD_ANSWER); return false }
        image = data
        imageVersion = version.trim()
        _step.value = UpdateStep.Ready(version.trim(), data.size, fromFile = true)
        return true
    }

    /** Checks the rules: newer version only, battery at 50 % or more, nothing playing, not grouped. */
    fun blocker(s: DeviceState): Blocker? {
        val family = s.product?.update ?: UpdateFamily.UNKNOWN
        if (!family.writeSupported) return Blocker.NOT_SUPPORTED
        val v = imageVersion ?: return Blocker.UNKNOWN_VERSION
        val current = s.firmware ?: return Blocker.UNKNOWN_VERSION
        val c = compareVersions(v, current) ?: return Blocker.UNKNOWN_VERSION
        if (c <= 0) return Blocker.NOT_NEWER
        val batteries = listOfNotNull(s.battery, s.batteryLeft, s.batteryRight)
        if (batteries.isEmpty() || batteries.any { it.percent < MIN_BATTERY }) return Blocker.BATTERY
        if (family == UpdateFamily.SPEAKER_AA || family == UpdateFamily.SPEAKER_P4) {
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio?.isMusicActive == true) return Blocker.PLAYING
            if ((s.role ?: 0) != 0 || (s.linkMode ?: 0) != 0 || s.auracast == true) return Blocker.GROUPED
        }
        return null
    }

    fun start(session: DeviceSession, s: DeviceState) {
        if (running) return
        val data = image ?: return
        val version = imageVersion ?: return
        if (blocker(s) != null) return
        deviceName = s.name
        val family = s.product?.update ?: return
        session.updating = true
        _step.value = UpdateStep.Sending(0, data.size.toLong())
        context.startForegroundService(Intent(context, UpdateService::class.java))
        job = scope.launch {
            try {
                val progress: Progress = { sent, total -> _step.value = UpdateStep.Sending(sent, total) }
                when (family) {
                    UpdateFamily.SPEAKER_AA -> SpeakerAaTransfer.run(session, data, version, progress)
                    UpdateFamily.SPEAKER_P4 -> P4Transfer.run(session, data, speaker = true, progress)
                    UpdateFamily.HP_P4 -> P4Transfer.run(session, data, speaker = false, progress)
                    else -> throw TransferFailed(R.string.xfer_not_supported)
                }
                _step.value = UpdateStep.Done
            } catch (e: CancellationException) {
                _step.value = UpdateStep.Cancelled
            } catch (e: Exception) {
                val detail = if (e is TransferFailed) context.getString(e.text, *e.args) else e.message
                _step.value = UpdateStep.Failed(Reason.TRANSFER, detail)
            } finally {
                session.updating = false
            }
        }
    }

    /** Stops the transfer; the device keeps what it got and continues from there next time. */
    fun abort() {
        job?.cancel()
    }

    companion object {
        const val MIN_BATTERY = 50
        /** Automatic retries of a broken download before it waits for "Resume download". */
        private const val AUTO_RETRIES = 3
        private const val RETRY_DELAY_MS = 2_000L

        fun lowest(vararg b: Battery?): Int? = b.filterNotNull().minOfOrNull { it.percent }
    }
}

package com.vknxworks.soundctrl.update

import com.vknxworks.soundctrl.device.DeviceSession
import com.vknxworks.soundctrl.proto.Aa
import com.vknxworks.soundctrl.proto.AaFrame
import com.vknxworks.soundctrl.proto.DfuAction
import com.vknxworks.soundctrl.proto.DfuCrc
import com.vknxworks.soundctrl.proto.P4
import com.vknxworks.soundctrl.proto.P4OtaNotice
import com.vknxworks.soundctrl.proto.SpeakerDfuCommands
import com.vknxworks.soundctrl.proto.SpeakerDfuSession
import com.vknxworks.soundctrl.proto.compareVersions
import com.vknxworks.soundctrl.proto.u8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TransferFailed(msg: String) : Exception(msg)

/** Progress callback: bytes the device has confirmed, image size. */
typealias Progress = (Long, Long) -> Unit

/** The speaker update protocol over AA (Vimicro/Actions speakers). See PROTOCOL.md §5.1. */
object SpeakerAaTransfer {
    suspend fun run(session: DeviceSession, image: ByteArray, version: String, progress: Progress) = coroutineScope {
        val held = session.speakerDfuVersion()
        val resume = held != null && compareVersions(held, version) == 0
        val crc = DfuCrc.fileCrc(image)
        val dfu = SpeakerDfuSession(image, SpeakerDfuCommands.chunkSize(session.link.mtu))
        val inbox = Channel<AaFrame>(Channel.UNLIMITED)
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            session.aaFrames.collect { if (it.cmd == Aa.RET_DFU_STATUS || it.cmd == Aa.RET_TRIGGER_UPDATE) inbox.send(it) }
        }
        try {
            if (!session.link.write(SpeakerDfuCommands.start(crc, image.size, version, resume))) throw TransferFailed("couldn't start")
            var silent = 0
            while (true) {
                val f = withTimeoutOrNull(6_000) { inbox.receive() }
                if (f == null) {
                    // No answer: send the last packet again a few times (inferred; the app's command timeout is 6 s).
                    val last = dfu.lastSent ?: throw TransferFailed("the speaker didn't answer")
                    if (++silent > 5) throw TransferFailed("the speaker stopped answering")
                    session.link.write(last.frame)
                    continue
                }
                silent = 0
                if (f.cmd != Aa.RET_DFU_STATUS) continue
                when (val a = dfu.onStatus(f.payload)) {
                    is DfuAction.Send -> {
                        if (!session.link.write(a.frame)) throw TransferFailed("Bluetooth write failed")
                        progress(a.offset, a.total)
                    }
                    DfuAction.Ignore -> {}
                    is DfuAction.Failed -> throw TransferFailed(a.reason)
                    DfuAction.TransferDone -> break
                }
            }
            progress(image.size.toLong(), image.size.toLong())
            // Apply: AA 4D -> AA 4C [code], 0 = accepted.
            if (!session.link.write(SpeakerDfuCommands.applyNewFirmware())) throw TransferFailed("couldn't ask the speaker to apply the update")
            val reply = withTimeoutOrNull(15_000) {
                var r: AaFrame
                do { r = inbox.receive() } while (r.cmd != Aa.RET_TRIGGER_UPDATE)
                r
            }
            if (reply != null && reply.payload.isNotEmpty() && reply.payload.u8(0) != 0) throw TransferFailed("the speaker refused the new firmware (code ${reply.payload.u8(0)})")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { session.link.write(SpeakerDfuCommands.cancel()) }
            throw e
        } finally {
            reader.cancel()
        }
    }
}

/** Protocol-4 update (newer speakers and headphones): the device asks for each part by offset. */
object P4Transfer {
    suspend fun run(session: DeviceSession, image: ByteArray, speaker: Boolean, progress: Progress) = coroutineScope {
        val mtu = session.link.mtu
        // Speaker app: 240 bytes per packet over BLE; headphone app: MTU - 17. Both kept within the MTU.
        val chunk = if (speaker) minOf(240, mtu - 15) else mtu - 17
        if (chunk < 20) throw TransferFailed("Bluetooth packet size too small (MTU $mtu)")
        val inbox = Channel<P4OtaNotice>(Channel.UNLIMITED)
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            session.p4Messages.collect { m -> if (m.cmd == P4.OTA_NOTIFICATION) P4OtaNotice.parse(m.payload)?.let { inbox.send(it) } }
        }
        try {
            val start = if (speaker) P4.speakerStartOta(image.size) else P4.headphoneStartOta()
            if (!session.link.write(start)) throw TransferFailed("couldn't start")
            var previous = -1L
            while (true) {
                val n = withTimeoutOrNull(30_000) { inbox.receive() } ?: throw TransferFailed("the device stopped asking for data")
                if (if (speaker) n.speakerFinished else n.headphoneFinished) break
                if (previous > 0 && n.offset == 0L) throw TransferFailed("the device reported an error (status ${n.status})")
                previous = n.offset
                if (n.offset >= image.size) continue
                // Headphones say how much they want (up to 20 packets); speakers get one packet per request.
                val want = if (!speaker && n.length > 0) minOf(n.length, chunk * 20L) else chunk.toLong()
                var off = n.offset
                val end = minOf(image.size.toLong(), n.offset + want)
                while (off < end) {
                    val to = minOf(end, off + chunk)
                    if (!session.link.write(P4.otaData(off, image.copyOfRange(off.toInt(), to.toInt())))) throw TransferFailed("Bluetooth write failed")
                    off = to
                }
                progress(n.offset, image.size.toLong())
            }
            progress(image.size.toLong(), image.size.toLong())
        } catch (e: CancellationException) {
            withContext(NonCancellable) { session.link.write(P4.stopOta()) }
            throw e
        } catch (e: TransferFailed) {
            session.link.write(P4.stopOta())
            throw e
        } finally {
            reader.cancel()
        }
    }
}

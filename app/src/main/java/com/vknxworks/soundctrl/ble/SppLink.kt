package com.vknxworks.soundctrl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Airoha's RACE pipe over classic Bluetooth (RFCOMM), the way the JBL headphone app reaches Airoha-chip earbuds
 * such as the Tour Pro 3: a secure RFCOMM socket to Airoha's own SPP service ([AIROHA_SPP_UUID], found through
 * SDP; `com.airoha.liblinker.physical.spp.SppController.createRfcomm`, `SppLinkParam`). Needs only
 * BLUETOOTH_CONNECT and works with the address of the paired earbuds.
 *
 * The socket is a byte stream: [incoming] carries the bytes as they arrive, not whole frames
 * (`RaceAssembler` cuts them up).
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT first.
class SppLink(private val context: Context, val address: String) : DeviceLink {
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: SharedFlow<ByteArray> = _incoming
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    /** RFCOMM has no MTU to negotiate here; only the speaker and protocol-4 updates read this, never over SPP. */
    override val mtu: Int = 512
    override val pipe: Pipe = Pipe.RACE

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    private val writeLock = Mutex()

    /** Opens the socket (SDP lookup + RFCOMM connect), at most [timeoutMs]. False when the device has no such service. */
    suspend fun connect(timeoutMs: Long = 15_000): Boolean = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@withContext false
        if (!adapter.isEnabled || !BluetoothAdapterCompat.isValidAddress(address)) return@withContext false
        val s = try {
            adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(AIROHA_SPP_UUID)
        } catch (e: Exception) {
            return@withContext false
        }
        socket = s
        val ok = try {
            coroutineScope {
                // connect() blocks; closing the socket is the only way to give up early.
                val watchdog = launch { delay(timeoutMs); try { s.close() } catch (_: IOException) {} }
                val r = try {
                    s.connect()
                    true
                } catch (e: IOException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
                watchdog.cancel()
                r
            }
        } catch (e: CancellationException) {
            // The session was closed while connecting (its watchdog went with it): a socket that still connected
            // must not stay open, unread, holding the earbuds' RACE channel.
            try { s.close() } catch (_: IOException) {}
            socket = null
            throw e
        }
        if (!ok) {
            close()
            return@withContext false
        }
        val input = try {
            out = s.outputStream
            s.inputStream
        } catch (e: IOException) {
            close()
            return@withContext false
        }
        _connected.value = true
        thread(isDaemon = true, name = "SoundCtrl-SPP") {
            val buf = ByteArray(2000)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) _incoming.tryEmit(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            _connected.value = false
        }
        true
    }

    /** Writes one frame to the socket. */
    override suspend fun write(bytes: ByteArray): Boolean = writeLock.withLock {
        val o = out ?: return false
        withContext(Dispatchers.IO) {
            try {
                o.write(bytes)
                o.flush()
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    override fun close() {
        _connected.value = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        out = null
    }

    companion object {
        /** Airoha's SPP service (`com.airoha.liblinker.constant.UuidTable.AIROHA_SPP_UUID`). */
        val AIROHA_SPP_UUID: UUID = UUID.fromString("00000000-0000-0000-0099-AABBCCDDEEFF")
    }
}

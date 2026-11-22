package com.vknxworks.soundctrl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Which byte pipe the device offers. */
enum class Pipe { HARMAN, RACE }

/** A connection to one device that carries frames both ways: BLE GATT ([GattLink]) or classic RFCOMM ([SppLink]). */
interface DeviceLink {
    val incoming: SharedFlow<ByteArray>
    val connected: StateFlow<Boolean>
    val mtu: Int
    val pipe: Pipe?
    suspend fun write(bytes: ByteArray): Boolean
    fun close()
}

/**
 * One BLE GATT connection to a JBL device: the Harman pipe (service …0000, notify …0001, write …0002,
 * the same in both JBL apps) or Airoha's RACE pipe. Writes are queued and wait for the write callback.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT first.
class GattLink(private val context: Context, val address: String) : DeviceLink {
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: SharedFlow<ByteArray> = _incoming
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    override var mtu = 23
        private set
    override var pipe: Pipe? = null
        private set

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val writeLock = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingSetup: CompletableDeferred<Boolean>? = null
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingDescriptor: CompletableDeferred<Boolean>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else {
                _connected.value = false
                pendingSetup?.complete(false)
                pendingWrite?.complete(false)
                pendingMtu?.complete(mtu)
                pendingDescriptor?.complete(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingSetup?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            pendingMtu?.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _incoming.tryEmit(value.copyOf())
        }
    }

    /** Connects, finds a pipe, asks for MTU 512 (as both apps do) and turns on notifications. */
    suspend fun connect(le: Boolean = true): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!BluetoothAdapterCompat.isValidAddress(address)) return false
        val device = adapter.getRemoteDevice(address)
        val setup = CompletableDeferred<Boolean>().also { pendingSetup = it }
        gatt = device.connectGatt(context, false, callback, if (le) BluetoothDevice.TRANSPORT_LE else BluetoothDevice.TRANSPORT_AUTO)
        val g = gatt ?: return false
        if (withTimeoutOrNull(20_000) { setup.await() } != true) {
            close()
            return false
        }
        val (rxC, txC, kind) = findPipe(g) ?: run { close(); return false }
        pipe = kind
        tx = txC
        val m = CompletableDeferred<Int>().also { pendingMtu = it }
        if (g.requestMtu(512)) withTimeoutOrNull(5_000) { m.await() }
        g.setCharacteristicNotification(rxC, true)
        val cccd = rxC.getDescriptor(CCCD)
        if (cccd != null) {
            val d = CompletableDeferred<Boolean>().also { pendingDescriptor = it }
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            withTimeoutOrNull(5_000) { d.await() }
        }
        _connected.value = true
        return true
    }

    private fun findPipe(g: BluetoothGatt): Triple<BluetoothGattCharacteristic, BluetoothGattCharacteristic, Pipe>? {
        // The Harman characteristics can sit under the Harman service, a per-product service or GAP (1801).
        for (s in g.services) {
            val rx = s.getCharacteristic(HARMAN_RX)
            val tx = s.getCharacteristic(HARMAN_TX)
            if (rx != null && tx != null) return Triple(rx, tx, Pipe.HARMAN)
        }
        g.getService(RACE_SERVICE)?.let { s ->
            val rx = s.getCharacteristic(RACE_RX)
            val tx = s.getCharacteristic(RACE_TX)
            if (rx != null && tx != null) return Triple(rx, tx, Pipe.RACE)
        }
        return null
    }

    /** Writes one frame (with response) and waits for the stack to confirm it. */
    override suspend fun write(bytes: ByteArray): Boolean = writeLock.withLock {
        val g = gatt ?: return false
        val c = tx ?: return false
        val done = CompletableDeferred<Boolean>().also { pendingWrite = it }
        val r = g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (r != BluetoothGatt.GATT_SUCCESS) return false
        withTimeoutOrNull(6_000) { done.await() } == true
    }

    override fun close() {
        _connected.value = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        tx = null
    }

    companion object {
        val HARMAN_SERVICE: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000")
        val HARMAN_RX: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001")
        val HARMAN_TX: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002")
        val RACE_SERVICE: UUID = UUID.fromString("5052494d-2dab-0341-6972-6f6861424c45")
        val RACE_TX: UUID = UUID.fromString("43484152-2dab-3241-6972-6f6861424c45")
        val RACE_RX: UUID = UUID.fromString("43484152-2dab-3141-6972-6f6861424c45")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

object BluetoothAdapterCompat {
    fun isValidAddress(a: String) = android.bluetooth.BluetoothAdapter.checkBluetoothAddress(a)
}

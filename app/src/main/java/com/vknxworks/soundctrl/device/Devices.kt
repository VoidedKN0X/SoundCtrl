package com.vknxworks.soundctrl.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vknxworks.soundctrl.data.settings.appDataStore
import com.vknxworks.soundctrl.proto.AdvertParser
import com.vknxworks.soundctrl.proto.Catalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A device SoundCtrl remembers (stored on the phone only). */
@Serializable
data class Remembered(
    val address: String,
    val name: String,
    val pid: String? = null,
    val firmware: String? = null,
    /** Last battery levels seen (percent). */
    val battery: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val le: Boolean = true,
)

/** Something found by a scan or among the phone's paired devices. */
data class Found(val address: String, val name: String, val pid: String?, val le: Boolean, val rssi: Int?)

@SuppressLint("MissingPermission") // The UI asks for BLUETOOTH_SCAN / BLUETOOTH_CONNECT first.
class Devices(private val context: Context, private val scope: CoroutineScope) {
    private val key = stringPreferencesKey("devices")
    private val json = Json { ignoreUnknownKeys = true }

    val remembered: StateFlow<List<Remembered>> = context.appDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p -> p[key]?.let { runCatching { json.decodeFromString<List<Remembered>>(it) }.getOrNull() } ?: emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun remember(r: Remembered) = scope.launch {
        try {
            context.appDataStore.edit { p ->
                val list = p[key]?.let { runCatching { json.decodeFromString<List<Remembered>>(it) }.getOrNull() } ?: emptyList()
                p[key] = json.encodeToString(listOf(r) + list.filter { it.address != r.address })
            }
        } catch (_: Exception) {
        }
    }

    fun forget(address: String) = scope.launch {
        try {
            context.appDataStore.edit { p ->
                val list = p[key]?.let { runCatching { json.decodeFromString<List<Remembered>>(it) }.getOrNull() } ?: emptyList()
                p[key] = json.encodeToString(list.filter { it.address != address })
            }
        } catch (_: Exception) {
        }
    }

    fun forgetAll() = scope.launch {
        try { context.appDataStore.edit { it.remove(key) } } catch (_: Exception) {}
    }

    // ---------- scanning ----------

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private var scanJob: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val rec = r.scanRecord ?: return
            val data = rec.getManufacturerSpecificData(AdvertParser.HARMAN_COMPANY)
                ?: rec.getManufacturerSpecificData(AdvertParser.HARMAN_COMPANY_HEADPHONES)
                ?: rec.getServiceData(ParcelUuid.fromString(AdvertParser.SERVICE_FDDF))
                ?: rec.getServiceData(ParcelUuid.fromString(AdvertParser.SERVICE_DFFD))
                ?: rec.getServiceData(ParcelUuid.fromString(AdvertParser.SERVICE_FC69))
            val adv = AdvertParser.parse(data)
            val product = Catalog.find(adv?.pid)
            val name = rec.deviceName ?: r.device.name ?: product?.name ?: return
            val f = Found(r.device.address, name, adv?.pid, le = true, rssi = r.rssi)
            _found.update { list -> (list.filter { it.address != f.address } + f).sortedBy { it.name } }
        }
    }

    fun scan(seconds: Int = 15) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanJob?.cancel()
        // Paired JBL devices first (classic Bluetooth headphones may not advertise while connected).
        val paired = adapter.bondedDevices.orEmpty().filter { (it.name ?: "").contains("JBL", ignoreCase = true) }
            .map { Found(it.address, it.name ?: it.address, null, le = false, rssi = null) }
        _found.value = paired
        val filters = listOf(
            ScanFilter.Builder().setManufacturerData(AdvertParser.HARMAN_COMPANY, ByteArray(0)).build(),
            ScanFilter.Builder().setManufacturerData(AdvertParser.HARMAN_COMPANY_HEADPHONES, ByteArray(0)).build(),
            ScanFilter.Builder().setServiceData(ParcelUuid.fromString(AdvertParser.SERVICE_FDDF), ByteArray(0)).build(),
            ScanFilter.Builder().setServiceData(ParcelUuid.fromString(AdvertParser.SERVICE_DFFD), ByteArray(0)).build(),
            ScanFilter.Builder().setServiceData(ParcelUuid.fromString(AdvertParser.SERVICE_FC69), ByteArray(0)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (_: SecurityException) {
            return
        }
        _scanning.value = true
        scanJob = scope.launch {
            delay(seconds * 1000L)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        _scanning.value = false
    }

    // ---------- the one open connection ----------

    private val _session = MutableStateFlow<DeviceSession?>(null)
    val session: StateFlow<DeviceSession?> = _session

    /** Opens (or reuses) the connection to [address]. Only one device is connected at a time. */
    fun open(address: String, pid: String?, le: Boolean): DeviceSession {
        _session.value?.let { if (it.address == address) return it }
        _session.value?.let { if (it.updating) return it else it.close() }
        stopScan()
        val s = DeviceSession(context, address, pid, le)
        _session.value = s
        scope.launch {
            if (s.start()) {
                val st = s.state.value
                remember(rememberedFrom(address, st, le))
            }
        }
        return s
    }

    fun disconnect() {
        val s = _session.value ?: return
        if (s.updating) return
        s.close()
        _session.value = null
    }

    companion object {
        fun rememberedFrom(address: String, st: DeviceState, le: Boolean) = Remembered(
            address, st.name ?: st.product?.name ?: address, st.pid, st.firmware,
            st.battery?.percent, st.batteryLeft?.percent, st.batteryRight?.percent, st.batteryCase?.percent, le,
        )
    }
}

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
import com.vknxworks.soundctrl.data.crypto.DataKeyStore
import com.vknxworks.soundctrl.data.crypto.KeystoreWrapper
import com.vknxworks.soundctrl.data.crypto.SealedFile
import com.vknxworks.soundctrl.data.settings.appDataStore
import com.vknxworks.soundctrl.proto.AdvertParser
import com.vknxworks.soundctrl.proto.Catalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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

/**
 * The remembered devices are kept in `filesDir/devices.json`, encrypted at rest ([SealedFile]) under the app's
 * data key ([DataKeyStore]). Older versions kept the same JSON in the settings DataStore (key `devices`): that
 * value is copied into the file once, checked, and only then removed from the DataStore. Everything that reads
 * or writes the list goes through this class, one step at a time, off the main thread.
 *
 * The list is a snapshot of what the devices themselves report (name, model, firmware, battery), so a sealed
 * file that can't be opened shows as an empty list; it is left where it is until a device is remembered again,
 * and is then renamed aside (`devices.json.unreadable`), never deleted.
 */
@SuppressLint("MissingPermission") // The UI asks for BLUETOOTH_SCAN / BLUETOOTH_CONNECT first.
class Devices(private val context: Context, private val scope: CoroutineScope) {
    /** Where versions up to 0.1.3 kept the list (plain JSON in the settings DataStore). */
    private val legacyKey = stringPreferencesKey("devices")
    private val json = Json { ignoreUnknownKeys = true }

    private val dir: File = context.filesDir
    private val file = File(dir, FILE)
    private val tmp = File(dir, "$FILE.tmp")
    private val sealTmp = File(dir, "$FILE.seal.tmp")
    private val aside = File(dir, "$FILE.unreadable")
    private val keys = DataKeyStore(dir, KeystoreWrapper(KEY_ALIAS))

    /** One reader or writer of the file at a time. */
    private val mutex = Mutex()
    private var loadedOnce = false
    /** The stored file is sealed and can't be opened (or can't be read at all): it must not be overwritten. */
    private var blocked = false

    private val _remembered = MutableStateFlow<List<Remembered>>(emptyList())
    val remembered: StateFlow<List<Remembered>> = _remembered

    /** False until the stored list has been read; [remembered] is empty until then. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    init {
        scope.launch(Dispatchers.IO) { mutex.withLock { ensureLoaded() } }
    }

    fun remember(r: Remembered) = change { list -> listOf(r) + list.filter { it.address != r.address } }

    fun forget(address: String) = change { list -> list.filter { it.address != address } }

    /**
     * "Forgot PIN?": the user chose to reset SoundCtrl, so the stored list goes even when it can't be opened,
     * together with a list set aside earlier and a data key that can't be used any more.
     */
    fun forgetAll() = scope.launch(Dispatchers.IO) {
        try {
            mutex.withLock {
                loadedOnce = true
                _remembered.value = emptyList()
                file.delete()
                aside.delete()
                File(dir, DataKeyStore.FILE + ".unreadable").delete()
                if (keys.get(create = false) == null) keys.discard(keep = false)
                blocked = false
                dropLegacy()
                _loaded.value = true
            }
        } catch (_: Exception) {
        }
    }

    private fun change(transform: (List<Remembered>) -> List<Remembered>) = scope.launch(Dispatchers.IO) {
        try {
            mutex.withLock {
                ensureLoaded()
                val old = _remembered.value
                val next = transform(old)
                if (next != old) {
                    // A file that can't be opened is moved out of the way first; if that fails, the change is
                    // kept in memory only.
                    val writable = !blocked || setAside()
                    _remembered.value = next
                    if (writable) write(next)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Reads the stored list once. Called with [mutex] held, off the main thread. */
    private suspend fun ensureLoaded() {
        if (loadedOnce) return
        try {
            if (file.exists()) {
                val bytes = file.readBytes()
                if (SealedFile.isSealed(bytes)) {
                    val key = keys.get(create = false)
                    val plain = if (key == null) null else try {
                        SealedFile.open(key, bytes)
                    } catch (e: Exception) {
                        null
                    }
                    if (plain == null) blocked = true else _remembered.value = decode(plain) ?: emptyList()
                } else {
                    _remembered.value = decode(bytes) ?: emptyList()
                    // An ordinary file (written while there was no data key): seal it where it is.
                    val key = keys.get(create = true)
                    if (key != null) SealedFile.sealInPlace(key, file, sealTmp)
                }
                // The file is the list now; a copy left in the DataStore by an interrupted move can go.
                if (!blocked) dropLegacy()
            } else {
                val legacy = legacyRead()
                val list = if (legacy == null) null else decode(legacy.toByteArray(Charsets.UTF_8))
                if (list != null) {
                    _remembered.value = list
                    // Only when the file holds exactly this list is the old copy removed.
                    if (write(list)) dropLegacy()
                }
            }
        } catch (e: Exception) {
            // The file is there but couldn't be read: don't write over it.
            if (file.exists()) blocked = true
        }
        loadedOnce = true
        _loaded.value = true
    }

    /**
     * Saves [list] (sealed when there is a data key, as an ordinary file otherwise), in one step, and reads it
     * back. True when the file on disk holds exactly [list].
     */
    private fun write(list: List<Remembered>): Boolean = try {
        val plain = json.encodeToString(list).toByteArray(Charsets.UTF_8)
        val key = keys.get(create = true)
        val sealed = if (key != null) SealedFile.sealChecked(key, plain) else null
        SealedFile.writeAtomic(file, tmp, sealed ?: plain)
        val back = file.readBytes()
        val got = if (!SealedFile.isSealed(back)) back else if (key == null) null else SealedFile.open(key, back)
        got != null && got.contentEquals(plain)
    } catch (e: Exception) {
        false
    }

    /**
     * Moves a stored list that can't be opened out of the way (renamed, not deleted) so a new one can be
     * written; a data key that can't be used is set aside as well. False when the file couldn't be moved.
     */
    private fun setAside(): Boolean = try {
        if (file.exists() && !file.renameTo(aside)) {
            false
        } else {
            if (keys.get(create = false) == null) keys.discard(keep = true)
            blocked = false
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun decode(bytes: ByteArray): List<Remembered>? =
        runCatching { json.decodeFromString<List<Remembered>>(String(bytes, Charsets.UTF_8)) }.getOrNull()

    private suspend fun legacyRead(): String? = try {
        context.appDataStore.data.first()[legacyKey]
    } catch (e: Exception) {
        null
    }

    private suspend fun dropLegacy() {
        try {
            if (context.appDataStore.data.first()[legacyKey] != null) context.appDataStore.edit { it.remove(legacyKey) }
        } catch (_: Exception) {
        }
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
            _found.update { list ->
                val old = list.firstOrNull { it.address == f.address }
                // Signal strength isn't shown anywhere: an advertisement that changes nothing else keeps the list
                // as it is, so the screen isn't redrawn for every packet during a scan.
                if (old != null && old.copy(rssi = f.rssi) == f) list
                else (list.filter { it.address != f.address } + f).sortedBy { it.name }
            }
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
        private const val FILE = "devices.json"
        private const val KEY_ALIAS = "soundctrl_data_at_rest"

        fun rememberedFrom(address: String, st: DeviceState, le: Boolean) = Remembered(
            address, st.name ?: st.product?.name ?: address, st.pid, st.firmware,
            st.battery?.percent, st.batteryLeft?.percent, st.batteryRight?.percent, st.batteryCase?.percent, le,
        )
    }
}

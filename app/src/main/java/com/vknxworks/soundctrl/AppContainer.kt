package com.vknxworks.soundctrl

import android.content.Context
import com.vknxworks.soundctrl.data.lock.LockVault
import com.vknxworks.soundctrl.data.settings.AppSettings
import com.vknxworks.soundctrl.data.settings.SettingsRepository
import com.vknxworks.soundctrl.device.Devices
import com.vknxworks.soundctrl.ui.lock.AppLock
import com.vknxworks.soundctrl.update.Updater
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "soundctrl")

    /** Remembered devices, scanning and the one open connection. */
    val devices = Devices(context, appScope)

    /** Firmware check, download and transfer (one at a time). */
    val updater = Updater(context, appScope)

    init {
        AppLock.onLocked = { onLocked() }
    }

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered, so SoundCtrl's settings and its list of remembered
     * devices are reset and the lock turned off. Nothing on your devices changes.
     */
    suspend fun forgetEverything() {
        devices.disconnect()
        devices.forgetAll()
        settings.update { AppSettings() }
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        vault.close()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as SoundCtrlApp).container

package com.vknxworks.soundctrl

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vknxworks.soundctrl.ui.lock.AppLock

class SoundCtrlApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The app lock: locks after the chosen time away ("Lock again").
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLock.onForeground(container.vault.enabled, container.settings.settings.value.lockTimeout.millis)
                // Reconnecting (retries and listening for the phone connecting to the device) only while on screen.
                container.devices.setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                AppLock.onBackground(container.vault.enabled, container.settings.settings.value.lockTimeout.millis)
                container.devices.setForeground(false)
            }
        })
    }
}

package com.vknxworks.soundctrl.ui.nav

import kotlinx.serialization.Serializable

/** Your devices and the ones nearby: SoundCtrl opens here. */
@Serializable data object DevicesRoute

/** One connected device: battery, firmware and its settings. */
@Serializable data class DeviceRoute(val address: String)

/** Firmware check, download and install for the connected device. */
@Serializable data class UpdateRoute(val address: String)

// Opened from the profile picture: the You page, and Settings from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute

package com.vknxworks.soundctrl.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vknxworks.soundctrl.ui.device.DeviceScreen
import com.vknxworks.soundctrl.ui.device.DevicesScreen
import com.vknxworks.soundctrl.ui.device.UpdateScreen
import com.vknxworks.soundctrl.ui.settings.SettingsScreen
import com.vknxworks.soundctrl.ui.settings.YouScreen

/** No tabs: the device list opens first; a device, its update page, You and Settings open on top. */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NavHost(navController, startDestination = DevicesRoute) {
                composable<DevicesRoute> { DevicesScreen() }
                composable<DeviceRoute> { DeviceScreen(it.toRoute<DeviceRoute>().address) }
                composable<UpdateRoute> { UpdateScreen(it.toRoute<UpdateRoute>().address) }
                composable<YouRoute> { YouScreen() }
                composable<SettingsRoute> { SettingsScreen() }
            }
        }
    }
}

package com.vknxworks.soundctrl.ui.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.soundctrl.R
import com.vknxworks.soundctrl.container
import com.vknxworks.soundctrl.device.Found
import com.vknxworks.soundctrl.device.Remembered
import com.vknxworks.soundctrl.proto.Catalog
import com.vknxworks.soundctrl.proto.Kind
import com.vknxworks.soundctrl.ui.common.hasPermission
import com.vknxworks.soundctrl.ui.common.launch
import com.vknxworks.soundctrl.ui.common.openAppSettings
import com.vknxworks.soundctrl.ui.components.CtrlButton
import com.vknxworks.soundctrl.ui.components.Group
import com.vknxworks.soundctrl.ui.components.GroupRow
import com.vknxworks.soundctrl.ui.components.MessageView
import com.vknxworks.soundctrl.ui.components.TabTitle
import com.vknxworks.soundctrl.ui.nav.DeviceRoute
import com.vknxworks.soundctrl.ui.nav.LocalNavigator

private val BT_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    var granted by remember { mutableStateOf(BT_PERMISSIONS.all { context.hasPermission(it) }) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var btOn by remember { mutableStateOf(isBluetoothOn(context)) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = BT_PERMISSIONS.all { context.hasPermission(it) }
        btOn = isBluetoothOn(context)
        tick++
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
        asked = true
    }
    Column(Modifier.fillMaxSize()) {
        TabTitle(stringResource(R.string.devices))
        when {
            !granted -> MessageView(
                Icons.Outlined.Bluetooth,
                stringResource(R.string.bt_permission_title),
                stringResource(R.string.bt_permission_body),
                actionLabel = stringResource(if (asked) R.string.bt_permission_settings else R.string.bt_permission_action),
                onAction = { if (asked) openAppSettings(context) else ask.launch(BT_PERMISSIONS) },
            )
            !btOn -> MessageView(
                Icons.Outlined.BluetoothDisabled,
                stringResource(R.string.bt_off_title),
                stringResource(R.string.bt_off_body),
                actionLabel = stringResource(R.string.bt_off_action),
                onAction = { context.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            )
            else -> DeviceLists(tick, onOpen = { addr, pid, le ->
                c.devices.open(addr, pid, le)
                nav.navigate(DeviceRoute(addr))
            })
        }
    }
}

@Composable
private fun DeviceLists(tick: Int, onOpen: (String, String?, Boolean) -> Unit) {
    val c = LocalContext.current.container
    val remembered by c.devices.remembered.collectAsStateWithLifecycle()
    val found by c.devices.found.collectAsStateWithLifecycle()
    val scanning by c.devices.scanning.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var autoTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tick) { if (remembered.isEmpty() && found.isEmpty()) c.devices.scan() }
    LaunchedEffect(remembered) {
        val last = remembered.firstOrNull()
        if (!autoTried && settings.autoConnect && last != null && c.devices.session.value == null) {
            autoTried = true
            onOpen(last.address, last.pid, last.le)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        if (remembered.isNotEmpty()) {
            Group(stringResource(R.string.your_devices)) {
                remembered.forEach { r -> RememberedRow(r) { onOpen(r.address, r.pid, r.le) } }
            }
        }
        val nearby = found.filter { f -> remembered.none { it.address == f.address } }
        Group(stringResource(R.string.nearby), footer = stringResource(R.string.nearby_footer)) {
            if (remembered.isEmpty() && nearby.isEmpty() && !scanning) {
                GroupRow(stringResource(R.string.no_devices), stringResource(R.string.no_devices_body), icon = Icons.Outlined.Headphones)
            }
            nearby.forEach { f -> FoundRow(f) { onOpen(f.address, f.pid, f.le) } }
            GroupRow(
                stringResource(if (scanning) R.string.scanning else R.string.scan),
                icon = Icons.Outlined.Search,
                onClick = if (scanning) null else ({ c.devices.scan() }),
            )
        }
    }
}

@Composable
private fun RememberedRow(r: Remembered, onClick: () -> Unit) {
    val product = Catalog.find(r.pid)
    val sub = listOfNotNull(
        product?.name,
        r.firmware?.let { stringResource(R.string.firmware) + " " + it },
        batteryLine(r.battery, r.left, r.right, r.case),
    ).joinToString(" · ")
    GroupRow(r.name, sub.ifEmpty { null }, icon = if (product?.kind == Kind.SPEAKER) Icons.Outlined.Speaker else Icons.Outlined.Headphones, showChevron = true, onClick = onClick)
}

@Composable
private fun FoundRow(f: Found, onClick: () -> Unit) {
    val product = Catalog.find(f.pid)
    val sub = when {
        product != null -> product.name
        !f.le -> stringResource(R.string.paired_on_phone)
        else -> stringResource(R.string.unknown_model)
    }
    GroupRow(f.name, sub, icon = if (product?.kind == Kind.SPEAKER) Icons.Outlined.Speaker else Icons.Outlined.Headphones, showChevron = true, onClick = onClick)
}

@Composable
fun batteryLine(main: Int?, left: Int?, right: Int?, case: Int?): String? {
    val parts = buildList {
        main?.let { add(stringResource(R.string.percent, it)) }
        left?.let { add(stringResource(R.string.battery_left) + " " + stringResource(R.string.percent, it)) }
        right?.let { add(stringResource(R.string.battery_right) + " " + stringResource(R.string.percent, it)) }
        case?.let { add(stringResource(R.string.battery_case) + " " + stringResource(R.string.percent, it)) }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun isBluetoothOn(context: android.content.Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

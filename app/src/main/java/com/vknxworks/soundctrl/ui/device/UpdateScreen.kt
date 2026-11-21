package com.vknxworks.soundctrl.ui.device

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.soundctrl.R
import com.vknxworks.soundctrl.container
import com.vknxworks.soundctrl.proto.UpdateServer
import com.vknxworks.soundctrl.ui.common.hasPermission
import com.vknxworks.soundctrl.ui.components.ConfirmDialog
import com.vknxworks.soundctrl.ui.components.CtrlAlertDialog
import com.vknxworks.soundctrl.ui.components.CtrlButton
import com.vknxworks.soundctrl.ui.components.CtrlCard
import com.vknxworks.soundctrl.ui.components.CtrlProgressBar
import com.vknxworks.soundctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.soundctrl.ui.components.CtrlTextField
import com.vknxworks.soundctrl.ui.components.Group
import com.vknxworks.soundctrl.ui.components.GroupRow
import com.vknxworks.soundctrl.ui.components.MessageView
import com.vknxworks.soundctrl.ui.components.ScreenTopBar
import com.vknxworks.soundctrl.ui.components.WarningCard
import com.vknxworks.soundctrl.ui.theme.CtrlColors
import com.vknxworks.soundctrl.ui.theme.Gutter
import com.vknxworks.soundctrl.update.Blocker
import com.vknxworks.soundctrl.update.Reason
import com.vknxworks.soundctrl.update.UpdateStep
import com.vknxworks.soundctrl.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

@Composable
fun UpdateScreen(address: String) {
    val context = LocalContext.current
    val c = context.container
    val session by c.devices.session.collectAsStateWithLifecycle()
    val s = session?.takeIf { it.address == address }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.update_title))
        if (s == null) {
            MessageView(Icons.Outlined.Warning, stringResource(R.string.not_connected))
            return
        }
        val st by s.state.collectAsStateWithLifecycle()
        val step by c.updater.step.collectAsStateWithLifecycle()
        LaunchedEffect(address) { if (c.updater.address != address) c.updater.reset(address) }
        BackHandler(enabled = step is UpdateStep.Sending) { }
        val scope = rememberCoroutineScope()
        val product = st.product
        val family = product?.update
        val configUrl = product?.let { UpdateServer.configUrl(it) }
        var picked by remember { mutableStateOf<ByteArray?>(null) }
        var confirm by remember { mutableStateOf(false) }
        val unreadable = stringResource(R.string.file_unreadable)
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) scope.launch {
                picked = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readNBytes(64 * 1024 * 1024 + 1)
                            bytes.takeIf { it.size <= 64 * 1024 * 1024 }
                        }
                    }.getOrNull()
                }
                if (picked == null) Toast.makeText(context, unreadable, Toast.LENGTH_SHORT).show()
            }
        }
        val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Group {
                GroupRow(stringResource(R.string.installed), st.firmware ?: stringResource(R.string.not_reported))
                GroupRow(stringResource(R.string.model), product?.name ?: stringResource(R.string.unknown_model))
            }
            if (family?.writeSupported != true) {
                WarningCard(stringResource(R.string.write_not_available) + "\n\n" + stringResource(R.string.write_not_available_body), Icons.Outlined.Warning, Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            } else {
                WarningCard(stringResource(R.string.update_untested), Icons.Outlined.Warning, Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            }

            // ----- Check -----
            CtrlCard(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (product == null || configUrl == null) {
                        Text(stringResource(R.string.no_update_source), color = CtrlColors.Muted)
                    } else {
                        Text(stringResource(R.string.check_explain, URI(configUrl).host, product.pid), color = CtrlColors.Muted)
                        StepText(step)
                        val busy = step is UpdateStep.Checking || step is UpdateStep.Downloading || step is UpdateStep.Sending
                        CtrlButton(stringResource(R.string.check_update), { c.updater.check(product, st.firmware) }, Modifier.fillMaxWidth(), Icons.Outlined.CloudDownload, enabled = !busy)
                        val checked = step as? UpdateStep.Checked
                        if (checked != null && family?.writeSupported == true && checked.newer != false) {
                            CtrlButton(stringResource(R.string.download), { c.updater.download(checked.offer) }, Modifier.fillMaxWidth())
                        }
                        val interrupted = step as? UpdateStep.Interrupted
                        if (interrupted != null && family?.writeSupported == true) {
                            CtrlButton(stringResource(R.string.resume_download), { c.updater.download(interrupted.offer) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // ----- Own file -----
            if (family?.writeSupported == true && step !is UpdateStep.Sending) {
                Group {
                    GroupRow(stringResource(R.string.use_file), stringResource(R.string.use_file_sub), Icons.Outlined.FileOpen, showChevron = true, onClick = { pick.launch(arrayOf("*/*")) })
                }
            }

            // ----- Install -----
            val ready = step as? UpdateStep.Ready
            if (ready != null && family?.writeSupported == true) {
                val blocker = c.updater.blocker(st)
                CtrlCard(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (blocker != null) Text(blockerText(blocker), color = CtrlColors.Warning)
                        CtrlButton(stringResource(R.string.install), {
                            if (!context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            confirm = true
                        }, Modifier.fillMaxWidth(), enabled = blocker == null)
                    }
                }
            }
            val sending = step as? UpdateStep.Sending
            if (sending != null) {
                CtrlCard(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val pct = if (sending.total > 0) (sending.sent * 100 / sending.total).toInt() else 0
                        Text(stringResource(R.string.sending, pct), color = CtrlColors.Text)
                        CtrlProgressBar(pct / 100f, Modifier.fillMaxWidth())
                        Text(stringResource(R.string.sending_body), color = CtrlColors.Muted)
                        CtrlSecondaryButton(onClick = { c.updater.abort() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.abort)) }
                    }
                }
            }
        }
        if (confirm && ready(step) != null) {
            val r = ready(step)!!
            ConfirmDialog(
                stringResource(R.string.confirm_title, r.version),
                stringResource(R.string.confirm_body),
                stringResource(R.string.confirm_install),
                onDismiss = { confirm = false },
            ) {
                confirm = false
                c.updater.start(s, s.state.value)
            }
        }
        picked?.let { bytes ->
            FileDialog(onDismiss = { picked = null }) { md5, version ->
                picked = null
                c.updater.loadFile(bytes, md5, version)
            }
        }
    }
}

private fun ready(step: UpdateStep) = step as? UpdateStep.Ready

@Composable
private fun StepText(step: UpdateStep) {
    val text = when (step) {
        UpdateStep.Idle -> null
        UpdateStep.Checking -> stringResource(R.string.checking)
        is UpdateStep.Checked -> when (step.newer) {
            true -> stringResource(R.string.update_available, step.offer.version)
            false -> stringResource(R.string.update_not_newer, step.offer.version)
            null -> stringResource(R.string.update_cant_compare, step.offer.version)
        }
        is UpdateStep.Downloading -> stringResource(R.string.downloading, (step.progress * 100).toInt())
        is UpdateStep.Interrupted -> stringResource(R.string.download_interrupted, if (step.total > 0) (step.received * 100 / step.total).toInt() else 0)
        is UpdateStep.Ready -> stringResource(if (step.fromFile) R.string.ready_file else R.string.ready, step.version)
        is UpdateStep.Sending -> null
        UpdateStep.Done -> stringResource(R.string.update_done)
        UpdateStep.Cancelled -> stringResource(R.string.update_cancelled)
        is UpdateStep.Failed -> when (step.reason) {
            Reason.NO_SOURCE -> stringResource(R.string.failed_no_source)
            Reason.NETWORK -> stringResource(R.string.failed_network)
            Reason.BAD_ANSWER -> stringResource(R.string.failed_answer)
            Reason.MD5_MISMATCH -> stringResource(R.string.failed_md5)
            else -> stringResource(R.string.failed_transfer, step.detail ?: "")
        }
    }
    if (text != null) Text(text, color = if (step is UpdateStep.Failed || step is UpdateStep.Interrupted) CtrlColors.Error else CtrlColors.Text)
}

@Composable
private fun blockerText(b: Blocker): String = when (b) {
    Blocker.NOT_SUPPORTED -> stringResource(R.string.write_not_available)
    Blocker.NOT_NEWER -> stringResource(R.string.block_not_newer)
    Blocker.UNKNOWN_VERSION -> stringResource(R.string.block_unknown_version)
    Blocker.BATTERY -> stringResource(R.string.block_battery, Updater.MIN_BATTERY)
    Blocker.PLAYING -> stringResource(R.string.block_playing)
    Blocker.GROUPED -> stringResource(R.string.block_grouped)
}

@Composable
private fun FileDialog(onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    var md5 by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.use_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CtrlTextField(md5, { md5 = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_md5)) }, singleLine = true)
                CtrlTextField(version, { version = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_version)) }, singleLine = true)
            }
        },
        confirmButton = {
            CtrlButton(stringResource(R.string.file_check), { onOk(md5, version) }, enabled = md5.length == 32 && version.isNotEmpty())
        },
        dismissButton = {
            CtrlSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

package com.vknxworks.soundctrl.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.soundctrl.BuildConfig
import com.vknxworks.soundctrl.R
import com.vknxworks.soundctrl.container
import com.vknxworks.soundctrl.device.ProtocolLog
import com.vknxworks.soundctrl.ui.components.ConfirmDialog
import com.vknxworks.soundctrl.ui.components.CtrlSquareButton
import com.vknxworks.soundctrl.ui.components.MessageView
import com.vknxworks.soundctrl.ui.components.ScreenTopBar
import com.vknxworks.soundctrl.ui.lock.AppLock
import com.vknxworks.soundctrl.ui.theme.CtrlColors
import com.vknxworks.soundctrl.ui.theme.CtrlType
import com.vknxworks.soundctrl.ui.theme.Gutter

/**
 * Settings > Protocol log: the Bluetooth messages kept in memory while the switch is on. Share and Copy hand the
 * text to an app you pick; SoundCtrl itself never sends it anywhere.
 */
@Composable
fun ProtocolLogScreen() {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val session by c.devices.session.collectAsStateWithLifecycle()
    val version by ProtocolLog.version.collectAsState()
    val entries = remember(version) { ProtocolLog.snapshot().asReversed() }
    var confirmClear by remember { mutableStateOf(false) }

    fun fullText(): String {
        val st = session?.state?.value
        val header = listOf(
            "SoundCtrl ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}",
            listOfNotNull(st?.product?.name, st?.pid?.let { "pid $it" }, st?.firmware?.let { "firmware $it" }).joinToString(" · "),
        ).filter { it.isNotBlank() }
        return ProtocolLog.text(header)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.protocol_log)) {
            if (entries.isNotEmpty()) {
                CtrlSquareButton(Icons.Outlined.ContentCopy, stringResource(R.string.protocol_log_copy), onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.protocol_log), fullText()))
                    Toast.makeText(context, R.string.protocol_log_copied, Toast.LENGTH_SHORT).show()
                })
                CtrlSquareButton(Icons.Outlined.Share, stringResource(R.string.protocol_log_share), onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.protocol_log_subject))
                        .putExtra(Intent.EXTRA_TEXT, fullText())
                    try {
                        AppLock.expectReturn()
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.protocol_log_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                    }
                })
                CtrlSquareButton(Icons.Outlined.DeleteSweep, stringResource(R.string.protocol_log_clear), onClick = { confirmClear = true })
            }
        }
        if (entries.isEmpty()) {
            MessageView(
                Icons.Outlined.Description,
                stringResource(R.string.protocol_log_empty),
                stringResource(if (settings.protocolLog) R.string.protocol_log_empty_on else R.string.protocol_log_empty_off),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
            ) {
                item(key = "note") {
                    Text(stringResource(R.string.protocol_log_note, ProtocolLog.MAX_ENTRIES), style = CtrlType.Data, color = CtrlColors.Muted, modifier = Modifier.padding(bottom = 10.dp))
                }
                items(entries) { e ->
                    Text(
                        ProtocolLog.line(e),
                        style = CtrlType.Data,
                        color = if (e.direction == ProtocolLog.Direction.OUT) CtrlColors.Text else CtrlColors.AccentText,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(stringResource(R.string.protocol_log_clear), stringResource(R.string.protocol_log_clear_body), stringResource(R.string.protocol_log_clear), onDismiss = { confirmClear = false }) {
            confirmClear = false
            ProtocolLog.clear()
        }
    }
}

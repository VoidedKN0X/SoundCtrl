package com.vknxworks.soundctrl.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vknxworks.soundctrl.BuildConfig
import com.vknxworks.soundctrl.R
import com.vknxworks.soundctrl.container
import com.vknxworks.soundctrl.data.settings.AppSettings
import com.vknxworks.soundctrl.data.settings.SettingsFile
import com.vknxworks.soundctrl.data.settings.SettingsFileContent
import com.vknxworks.soundctrl.data.settings.SettingsFileException
import com.vknxworks.soundctrl.ui.components.CtrlAlertDialog
import com.vknxworks.soundctrl.ui.components.Group
import com.vknxworks.soundctrl.ui.components.GroupRow
import com.vknxworks.soundctrl.ui.lock.AppLock
import com.vknxworks.soundctrl.ui.theme.CtrlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/** Export settings / Import settings: a small JSON file with SoundCtrl's settings only (see [SettingsFile]). */
@Composable
fun SettingsFileGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<SettingsFileContent?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = SettingsFile.export(c.settings.settings.value, BuildConfig.VERSION_NAME, OffsetDateTime.now().toString())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("no stream")
                }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.settings_file_saved else R.string.settings_file_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > SettingsFile.MAX_BYTES) throw SettingsFileException(newer = false)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw IOException("no stream")
                }
                SettingsFile.parse(text)
            }.onSuccess { pending = it }.onFailure { e ->
                val msg = if (e is SettingsFileException && e.newer) R.string.settings_file_too_new else R.string.settings_file_not_valid
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.settings_file_group), footer = stringResource(R.string.settings_file_footer)) {
        GroupRow(
            stringResource(R.string.settings_file_export),
            stringResource(R.string.settings_file_export_sub),
            Icons.Outlined.FileDownload,
            onClick = { AppLock.expectReturn(); save.launch("soundctrl-settings-${LocalDate.now()}.json") },
        )
        GroupRow(
            stringResource(R.string.settings_file_import),
            stringResource(R.string.settings_file_import_sub),
            Icons.Outlined.FileUpload,
            onClick = { AppLock.expectReturn(); open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        )
    }

    pending?.let { f ->
        val after = SettingsFile.applied(f, settings)
        val changes = buildList {
            fun onOff(v: Boolean) = context.getString(if (v) R.string.settings_file_on else R.string.settings_file_off)
            if (after.autoConnect != settings.autoConnect) add(context.getString(R.string.settings_file_change, context.getString(R.string.auto_connect), onOff(settings.autoConnect), onOff(after.autoConnect)))
            if (after.lockTimeout != settings.lockTimeout) add(context.getString(R.string.settings_file_change, context.getString(R.string.lock_again), context.getString(settings.lockTimeout.label), context.getString(after.lockTimeout.label)))
            if (after.hideScreenshots != settings.hideScreenshots) add(context.getString(R.string.settings_file_change, context.getString(R.string.hide_screenshots), onOff(settings.hideScreenshots), onOff(after.hideScreenshots)))
            if (after.hideRecents != settings.hideRecents) add(context.getString(R.string.settings_file_change, context.getString(R.string.hide_recents), onOff(settings.hideRecents), onOff(after.hideRecents)))
            if (after.protocolLog != settings.protocolLog) add(context.getString(R.string.settings_file_change, context.getString(R.string.protocol_log), onOff(settings.protocolLog), onOff(after.protocolLog)))
        }
        CtrlAlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.settings_file_import_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    if (changes.isEmpty()) stringResource(R.string.settings_file_no_changes)
                    else stringResource(R.string.settings_file_import_body, f.createdAt?.take(10) ?: "?", changes.joinToString("\n")),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (changes.isNotEmpty()) {
                    TextButton(onClick = {
                        pending = null
                        c.appScope.launch {
                            c.settings.update { SettingsFile.applied(f, it) }
                            Toast.makeText(context, R.string.settings_file_imported, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.settings_file_replace), color = CtrlColors.AccentText) }
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(if (changes.isEmpty()) R.string.settings_file_close else R.string.cancel), color = CtrlColors.Text)
                }
            },
        )
    }
}

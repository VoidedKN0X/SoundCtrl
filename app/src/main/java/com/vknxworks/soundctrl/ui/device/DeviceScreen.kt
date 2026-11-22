package com.vknxworks.soundctrl.ui.device

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.SpeakerGroup
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
import com.vknxworks.soundctrl.device.DeviceSession
import com.vknxworks.soundctrl.device.DeviceState
import com.vknxworks.soundctrl.device.Devices
import com.vknxworks.soundctrl.proto.Battery
import com.vknxworks.soundctrl.proto.Control
import com.vknxworks.soundctrl.proto.HeadphoneP4
import com.vknxworks.soundctrl.proto.NoiseMode
import com.vknxworks.soundctrl.proto.Race
import com.vknxworks.soundctrl.ui.components.ConfirmDialog
import com.vknxworks.soundctrl.ui.components.CtrlButton
import com.vknxworks.soundctrl.ui.components.CtrlHeroCard
import com.vknxworks.soundctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.soundctrl.ui.components.Group
import com.vknxworks.soundctrl.ui.components.GroupChoice
import com.vknxworks.soundctrl.ui.components.GroupRow
import com.vknxworks.soundctrl.ui.components.GroupSwitch
import com.vknxworks.soundctrl.ui.components.MessageView
import com.vknxworks.soundctrl.ui.components.NameDialog
import com.vknxworks.soundctrl.ui.components.ScreenTopBar
import com.vknxworks.soundctrl.ui.components.StatusTag
import com.vknxworks.soundctrl.ui.nav.LocalNavigator
import com.vknxworks.soundctrl.ui.nav.UpdateRoute
import com.vknxworks.soundctrl.ui.theme.CtrlColors
import com.vknxworks.soundctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(address: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val session by c.devices.session.collectAsStateWithLifecycle()
    val s = session?.takeIf { it.address == address }
    if (s == null) {
        val remembered by c.devices.remembered.collectAsStateWithLifecycle()
        val r = remembered.firstOrNull { it.address == address }
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(r?.name ?: address)
            MessageView(
                Icons.Outlined.BluetoothSearching, stringResource(R.string.not_connected), null,
                actionLabel = stringResource(R.string.try_again),
                onAction = { c.devices.open(address, r?.pid, r?.le ?: true) },
            )
        }
        return
    }
    val st by s.state.collectAsStateWithLifecycle()
    // Keep the remembered entry current (name, firmware, battery).
    LaunchedEffect(st.name, st.firmware, st.battery, st.batteryLeft, st.batteryRight, st.batteryCase, st.pid) {
        if (st.connected && !st.connecting) c.devices.remember(Devices.rememberedFrom(address, st, s.le))
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(st.name ?: st.product?.name ?: address)
        when {
            st.connecting -> MessageView(Icons.Outlined.BluetoothSearching, stringResource(R.string.connecting))
            st.failed -> MessageView(
                Icons.Outlined.LinkOff, stringResource(R.string.connect_failed), stringResource(R.string.connect_failed_body),
                actionLabel = stringResource(R.string.try_again),
                onAction = { c.devices.disconnect(); c.devices.open(address, st.pid, true) },
            )
            else -> DeviceBody(s, st, onUpdate = { nav.navigate(UpdateRoute(address)) }, onGone = { nav.back() })
        }
    }
}

@Composable
private fun DeviceBody(s: DeviceSession, st: DeviceState, onUpdate: () -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.setting_failed)
    val locked = stringResource(R.string.busy_updating)
    fun act(block: suspend () -> Boolean) {
        if (s.updating) { Toast.makeText(context, locked, Toast.LENGTH_SHORT).show(); return }
        scope.launch { if (!block()) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
    }
    val needs = stringResource(R.string.needs_confirming)

    @Composable
    fun Disabled(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) =
        GroupRow(title, needs, icon = icon, iconTint = CtrlColors.Muted, titleColor = CtrlColors.Muted)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Header(st)
        if (st.control == Control.NONE) {
            Group { GroupRow(stringResource(R.string.model_not_supported), stringResource(R.string.model_not_supported_body)) }
        }
        val headphones = st.control == Control.HP_P4 || st.control == Control.HP_AA || st.control == Control.HP_AIROHA
        val speaker = st.control == Control.SPEAKER_AA || st.control == Control.SPEAKER_P4

        // ----- Sound -----
        Group(stringResource(R.string.group_sound), footer = stringResource(R.string.needs_confirming_footer)) {
            when {
                st.control == Control.HP_P4 || st.airohaSettings -> GroupChoice(
                    stringResource(R.string.noise_control),
                    listOf(
                        NoiseMode.OFF to stringResource(R.string.noise_off),
                        NoiseMode.ANC to stringResource(R.string.noise_anc),
                        NoiseMode.AMBIENT to stringResource(R.string.noise_ambient),
                        NoiseMode.TALKTHRU to stringResource(R.string.noise_talkthru),
                    ),
                    st.noise, icon = Icons.Outlined.Hearing, fallbackLabel = stringResource(R.string.not_reported),
                ) { m -> act { s.setNoise(m) } }
                st.control == Control.HP_AA -> {
                    GroupSwitch(stringResource(R.string.anc), checked = st.noise == NoiseMode.ANC, icon = Icons.Outlined.Hearing) { on ->
                        act { s.setNoise(if (on) NoiseMode.ANC else NoiseMode.OFF) }
                    }
                    Disabled(stringResource(R.string.ambient_talkthru), Icons.Outlined.Hearing)
                }
                st.control == Control.HP_AIROHA -> Disabled(stringResource(R.string.noise_control), Icons.Outlined.Hearing)
                else -> {}
            }
            if (st.control == Control.SPEAKER_AA && st.product?.has("eqindoor") == true) {
                GroupChoice(
                    stringResource(R.string.eq_mode),
                    listOf(0 to stringResource(R.string.eq_indoor), 1 to stringResource(R.string.eq_outdoor)),
                    st.eqMode, icon = Icons.Outlined.Equalizer, fallbackLabel = stringResource(R.string.not_reported),
                ) { m -> act { s.setEqMode(m) } }
            }
            Disabled(stringResource(R.string.eq_presets), Icons.Outlined.Equalizer)
            Disabled(stringResource(R.string.eq_custom), Icons.Outlined.Tune)
        }

        // ----- Controls (headphones) -----
        if (headphones) {
            Group(stringResource(R.string.group_controls)) {
                if (st.control == Control.HP_P4 && st.gestures.isNotEmpty()) {
                    st.gestures.toSortedMap().forEach { (feature, action) ->
                        val options = (HeadphoneP4.GESTURE_ACTIONS + action).distinct().map { it to actionLabel(it) }
                        GroupChoice(gestureLabel(feature), options, action, icon = Icons.Outlined.TouchApp) { a -> act { s.setGesture(feature, a) } }
                    }
                } else {
                    Disabled(stringResource(R.string.gestures), Icons.Outlined.TouchApp)
                }
                if (st.airohaSettings) {
                    GroupSwitch(stringResource(R.string.in_ear_play_pause), stringResource(R.string.in_ear_play_pause_sub), st.inEarPlayPause == true, Icons.Outlined.PlayCircle) { on ->
                        act { s.setInEarPlayPause(on) }
                    }
                }
                if (st.airohaBasics) {
                    // Without the second earbud only the one the phone talks to can ring (as in the JBL app).
                    val alone = st.partnerPresent == false
                    if (!alone || st.agentRight == false) {
                        GroupSwitch(stringResource(R.string.find_left), stringResource(R.string.find_sub), st.ringingLeft, Icons.Outlined.Headphones) { on ->
                            act { s.findBud(Race.FIND_LEFT, on) }
                        }
                    }
                    if (!alone || st.agentRight == true) {
                        GroupSwitch(stringResource(R.string.find_right), stringResource(R.string.find_sub), st.ringingRight, Icons.Outlined.Headphones) { on ->
                            act { s.findBud(Race.FIND_RIGHT, on) }
                        }
                    }
                }
            }
        }

        // ----- Party (speakers) -----
        if (speaker) {
            Group(stringResource(R.string.group_party)) {
                if (st.control == Control.SPEAKER_AA && st.product?.has("partyboost") == true) {
                    GroupSwitch(stringResource(R.string.partyboost), stringResource(R.string.partyboost_sub), st.linkMode == 1, Icons.Outlined.SpeakerGroup) { on ->
                        act { s.setLinkMode(if (on) 1 else 0) }
                    }
                }
                if (st.product?.has("auracast") == true) {
                    val on = if (st.control == Control.SPEAKER_P4) st.auracast == true else st.linkMode == 2
                    GroupSwitch(stringResource(R.string.auracast), stringResource(R.string.auracast_sub), on, Icons.Outlined.Groups) { v ->
                        act { if (st.control == Control.SPEAKER_P4) s.setAuracastP4(v) else s.setLinkMode(if (v) 2 else 0) }
                    }
                }
                if (st.control == Control.SPEAKER_AA) {
                    GroupChoice(
                        stringResource(R.string.channel),
                        listOf(0 to stringResource(R.string.channel_both), 1 to stringResource(R.string.channel_left), 2 to stringResource(R.string.channel_right)),
                        st.channel, icon = Icons.Outlined.GraphicEq, fallbackLabel = stringResource(R.string.not_reported),
                    ) { ch -> act { s.setChannel(ch) } }
                }
                Disabled(stringResource(R.string.auracast_groups), Icons.Outlined.Groups)
            }
        }

        // ----- Device -----
        Group(stringResource(R.string.group_device)) {
            if (st.control == Control.HP_P4) {
                val (on, minutes) = st.autoOff ?: (false to 0)
                val opts = listOf(0, 5, 10, 15, 30, 60).map { m -> m to if (m == 0) stringResource(R.string.auto_off_never) else stringResource(R.string.minutes, m) }
                GroupChoice(stringResource(R.string.auto_off), opts, if (st.autoOff == null) null else if (on) minutes else 0,
                    icon = Icons.Outlined.PowerSettingsNew, fallbackLabel = stringResource(R.string.not_reported)) { m ->
                    act { if (m == 0) s.setAutoOff(false, minutes.coerceAtLeast(5)) else s.setAutoOff(true, m) }
                }
                GroupSwitch(stringResource(R.string.voice_prompts), stringResource(R.string.voice_prompts_sub), st.voicePrompts == true, Icons.Outlined.RecordVoiceOver) { on2 ->
                    act { s.setVoicePrompts(on2) }
                }
            } else if (st.airohaSettings) {
                // The JBL app's choices for earbuds: 30 min, 1 h, 2 h or never. "Never" keeps the last time, switched off.
                val (on, minutes) = st.autoOff ?: (false to 0)
                val opts = listOf(0, 30, 60, 120).map { m -> m to if (m == 0) stringResource(R.string.auto_off_never) else stringResource(R.string.minutes, m) }
                GroupChoice(stringResource(R.string.auto_off), opts, if (st.autoOff == null) null else if (on) minutes else 0,
                    icon = Icons.Outlined.PowerSettingsNew, fallbackLabel = stringResource(R.string.not_reported)) { m ->
                    act { if (m == 0) s.setAutoOff(false, if (minutes > 0) minutes else 30) else s.setAutoOff(true, m) }
                }
                Disabled(stringResource(R.string.voice_prompts), Icons.Outlined.RecordVoiceOver)
            } else if (headphones) {
                Disabled(stringResource(R.string.auto_off), Icons.Outlined.PowerSettingsNew)
                Disabled(stringResource(R.string.voice_prompts), Icons.Outlined.RecordVoiceOver)
            }
            if (speaker && st.product?.has("feedback") == true) {
                GroupSwitch(stringResource(R.string.feedback_tone), checked = st.feedbackTone == true, icon = Icons.Outlined.NotificationsActive) { on ->
                    act { s.setFeedbackTone(on) }
                }
            }
            if (speaker) {
                GroupRow(stringResource(R.string.rename), stringResource(R.string.rename_sub), Icons.Outlined.Edit, showChevron = true, onClick = { renaming = true })
            } else {
                Disabled(stringResource(R.string.rename), Icons.Outlined.Edit)
            }
            if (st.control == Control.SPEAKER_AA) {
                GroupRow(stringResource(R.string.identify), stringResource(R.string.identify_sub), Icons.Outlined.Campaign, onClick = { act { s.identify() } })
            }
        }

        // ----- Firmware -----
        Group(stringResource(R.string.group_firmware)) {
            GroupRow(stringResource(R.string.firmware_update), stringResource(R.string.firmware_update_sub), Icons.Outlined.SystemUpdate, showChevron = true, onClick = onUpdate)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CtrlSecondaryButton(onClick = { scope.launch { s.refresh() } }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.refresh)) }
            CtrlSecondaryButton(onClick = { c.devices.disconnect(); onGone() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.disconnect)) }
        }
        Group {
            GroupRow(stringResource(R.string.forget), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = { forgetting = true })
        }
    }
    if (renaming) {
        NameDialog(stringResource(R.string.rename_title), st.name ?: "", onDismiss = { renaming = false }) { name ->
            renaming = false
            val n = name.trim().take(16)
            if (n.isNotEmpty()) act { s.rename(n) }
        }
    }
    if (forgetting) {
        ConfirmDialog(stringResource(R.string.forget_title), stringResource(R.string.forget_body), stringResource(R.string.forget), onDismiss = { forgetting = false }) {
            forgetting = false
            c.devices.disconnect()
            c.devices.forget(s.address)
            onGone()
        }
    }
}

@Composable
private fun Header(st: DeviceState) {
    CtrlHeroCard(
        label = st.product?.name ?: stringResource(R.string.unknown_model),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        meta = st.pid?.let { stringResource(R.string.product_id) + " " + it },
    ) {
        StatusTag(stringResource(if (st.connected) R.string.connected else R.string.not_connected), st.connected)
        InfoLine(stringResource(R.string.firmware), st.firmware)
        st.partnerFirmware?.let { InfoLine(stringResource(R.string.firmware_partner), it) }
        st.caseFirmware?.let { InfoLine(stringResource(R.string.firmware_case), it) }
        if (st.battery != null || (st.batteryLeft == null && st.batteryRight == null)) InfoLine(stringResource(R.string.battery), batteryText(st.battery))
        st.batteryLeft?.let { InfoLine(stringResource(R.string.battery_left), batteryText(it)) }
        st.batteryRight?.let { InfoLine(stringResource(R.string.battery_right), batteryText(it)) }
        st.batteryCase?.let { InfoLine(stringResource(R.string.battery_case), batteryText(it)) }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = CtrlColors.Muted, modifier = Modifier.weight(1f))
        Text(value ?: stringResource(R.string.not_reported), color = CtrlColors.Text)
    }
}

@Composable
private fun batteryText(b: Battery?): String? = b?.let {
    if (it.charging) stringResource(R.string.percent_charging, it.percent) else stringResource(R.string.percent, it.percent)
}

@Composable
private fun gestureLabel(feature: Int): String = stringResource(
    when (feature) {
        0x0F00 -> R.string.gesture_left_swipe_fwd
        0x0F01 -> R.string.gesture_left_swipe_back
        0x0F02 -> R.string.gesture_right_swipe_fwd
        0x0F03 -> R.string.gesture_right_swipe_back
        0x0F04 -> R.string.gesture_left_tap
        0x0F05 -> R.string.gesture_left_double
        0x0F06 -> R.string.gesture_left_triple
        0x0F07 -> R.string.gesture_right_tap
        0x0F08 -> R.string.gesture_right_double
        0x0F09 -> R.string.gesture_right_triple
        0x0F0A -> R.string.gesture_left_hold
        0x0F0B -> R.string.gesture_left_double_hold
        0x0F0C -> R.string.gesture_right_hold
        else -> R.string.gesture_right_double_hold
    },
)

@Composable
private fun actionLabel(a: Int): String = when (a) {
    0 -> stringResource(R.string.action_0)
    1 -> stringResource(R.string.action_1)
    2 -> stringResource(R.string.action_2)
    3 -> stringResource(R.string.action_3)
    4 -> stringResource(R.string.action_4)
    5 -> stringResource(R.string.action_5)
    6 -> stringResource(R.string.action_6)
    7 -> stringResource(R.string.action_7)
    8 -> stringResource(R.string.action_8)
    9 -> stringResource(R.string.action_9)
    11 -> stringResource(R.string.action_11)
    12 -> stringResource(R.string.action_12)
    13 -> stringResource(R.string.action_13)
    15 -> stringResource(R.string.action_15)
    else -> stringResource(R.string.action_unknown, a)
}

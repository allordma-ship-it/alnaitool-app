package com.aurastream.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.aurastream.tv.BuildConfig
import com.aurastream.tv.R
import com.aurastream.tv.data.local.AspectMode
import com.aurastream.tv.data.local.StreamFormat
import com.aurastream.tv.data.repository.LicenseStatus
import com.aurastream.tv.ui.activation.QrCode
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.ImportProgress
import com.aurastream.tv.ui.components.Panel
import com.aurastream.tv.ui.components.PinDialog
import com.aurastream.tv.ui.components.StopPlaybackEffect
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus

private enum class PinStep { NONE, VERIFY, NEW }

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    StopPlaybackEffect()
    val vm = auraViewModel { c, _ -> SettingsViewModel(c.settings, c.deviceRepository, c.playlistRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var pinStep by remember { mutableStateOf(PinStep.NONE) }
    BackHandler(onBack = onBack)

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(AuraColors.Canvas)) {
        val compact = maxWidth < 840.dp
        Row(Modifier.fillMaxSize().padding(if (compact) 12.dp else 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                item {
                    Text(stringResource(R.string.section_settings), style = MaterialTheme.typography.headlineMedium, color = AuraColors.NeonCyan)
                    Spacer(Modifier.height(8.dp))
                    state.message?.let { Text(it, color = AuraColors.Error) }
                    state.importing?.let {
                        Spacer(Modifier.height(8.dp))
                        ImportProgress(it, Modifier.fillMaxWidth())
                    }
                }
                if (compact) item { DevicePanel(state, compact = true) }

                // ---------------------------------------------------------- playlists
                item { Header(stringResource(R.string.settings_playlists)) }
                items(state.playlists, key = { it.id }) { p ->
                    SettingRow(
                        title = p.name,
                        value = when (p.sourceType) {
                            "xtream" -> "Xtream Codes"
                            "file" -> stringResource(R.string.source_file)
                            else -> "M3U URL"
                        },
                        selected = p.id == state.activePlaylistId,
                        onClick = { vm.selectPlaylist(p.id) },
                    )
                }
                item {
                    SettingRow(
                        title = stringResource(R.string.settings_refresh_now),
                        value = stringResource(R.string.settings_refresh_now_hint),
                        onClick = vm::refreshNow,
                        modifier = Modifier.focusRequester(first),
                    )
                }
                item { SettingRow(stringResource(R.string.settings_refresh_epg), null, onClick = vm::refreshEpg) }

                // ----------------------------------------------------------- playback
                item { Header(stringResource(R.string.settings_playback)) }
                item {
                    SettingRow(
                        title = stringResource(R.string.settings_stream_format),
                        value = if (state.settings.preferredFormat == StreamFormat.TS) "MPEG-TS (.ts)" else "HLS (.m3u8)",
                        onClick = {
                            vm.setFormat(if (state.settings.preferredFormat == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS)
                        },
                    )
                }
                item {
                    SettingRow(
                        title = stringResource(R.string.settings_aspect),
                        value = stringResource(
                            when (state.settings.aspectMode) {
                                AspectMode.FIT -> R.string.aspect_fit
                                AspectMode.FILL -> R.string.aspect_fill
                                AspectMode.ZOOM -> R.string.aspect_zoom
                            },
                        ),
                        onClick = vm::cycleAspect,
                    )
                }
                item {
                    ToggleRow(
                        title = stringResource(R.string.settings_auto_recovery),
                        subtitle = stringResource(R.string.settings_auto_recovery_hint),
                        checked = state.settings.autoRecovery,
                        onToggle = { vm.setAutoRecovery(!state.settings.autoRecovery) },
                    )
                }
                item { UserAgentEditor(state.settings.userAgent, onSave = vm::setUserAgent, onReset = vm::resetUserAgent) }

                // ------------------------------------------------------- parental / ui
                item { Header(stringResource(R.string.settings_general)) }
                item { SettingRow(stringResource(R.string.settings_change_pin), stringResource(R.string.settings_change_pin_hint), onClick = { pinStep = PinStep.VERIFY }) }
                item {
                    SettingRow(
                        title = stringResource(R.string.settings_language),
                        value = when (state.language) {
                            AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                            AppLanguage.ENGLISH -> "English"
                            AppLanguage.ARABIC -> "العربية"
                        },
                        onClick = {
                            val all = AppLanguage.entries
                            vm.setLanguage(all[(all.indexOf(state.language) + 1) % all.size])
                        },
                    )
                }
                item {
                    Text(
                        "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AuraColors.TextTertiary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            if (!compact) DevicePanel(state, compact = false)
        }
    }

    when (pinStep) {
        PinStep.VERIFY -> PinDialog(
            title = stringResource(R.string.pin_current),
            onDismiss = { pinStep = PinStep.NONE },
            onSubmit = { pin -> vm.checkPin(pin).also { ok -> if (ok) pinStep = PinStep.NEW } },
        )
        PinStep.NEW -> PinDialog(
            title = stringResource(R.string.pin_new),
            onDismiss = { pinStep = PinStep.NONE },
            onSubmit = { pin ->
                vm.setPin(pin)
                pinStep = PinStep.NONE
                true
            },
        )
        PinStep.NONE -> Unit
    }
}

@Composable
private fun DevicePanel(state: SettingsUiState, compact: Boolean) {
    Panel(Modifier.width(if (compact) 400.dp else 360.dp)) {
        Column {
            Text(stringResource(R.string.settings_device), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LabeledValue(stringResource(R.string.mac_address), state.session?.mac ?: state.mac)
            LabeledValue(stringResource(R.string.device_key), state.session?.deviceKey ?: "—")
            state.session?.let { s ->
                LabeledValue(
                    stringResource(R.string.license),
                    when (s.status) {
                        LicenseStatus.TRIAL -> stringResource(R.string.license_trial_days, s.daysLeft ?: 0)
                        LicenseStatus.ACTIVE -> stringResource(R.string.license_active)
                        LicenseStatus.EXPIRED -> stringResource(R.string.license_expired)
                        LicenseStatus.UNKNOWN -> "—"
                    },
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.size(180.dp).clip(RoundedCornerShape(10.dp))) { QrCode(s.pairingUrl, Modifier.fillMaxSize()) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_scan_to_manage), style = MaterialTheme.typography.bodySmall, color = AuraColors.TextSecondary)
                Text(
                    s.portalUrl,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = AuraColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AuraColors.TextTertiary)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = AuraColors.TextTertiary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.02f),
        border = AuraFocus.border(shape, 2.dp),
        colors = AuraFocus.colors(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (selected) Icon(Icons.Filled.Check, null, tint = AuraColors.NeonCyan)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.02f),
        border = AuraFocus.border(shape, 2.dp),
        colors = AuraFocus.colors(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedThumbColor = AuraColors.OnNeon, checkedTrackColor = AuraColors.NeonCyan),
            )
        }
    }
}

@Composable
private fun UserAgentEditor(current: String, onSave: (String) -> Unit, onReset: () -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AuraColors.Surface)
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.settings_user_agent), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(256) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AuraColors.TextPrimary, textDirection = TextDirection.Ltr),
            cursorBrush = SolidColor(AuraColors.NeonCyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) AuraColors.NeonCyan else AuraColors.Outline, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AuraButton(stringResource(R.string.save), onClick = { onSave(text) }, icon = Icons.Filled.Check)
            AuraButton(stringResource(R.string.reset_default), onClick = onReset, icon = Icons.Filled.Refresh)
        }
    }
}

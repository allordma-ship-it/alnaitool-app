package com.aurastream.tv.ui.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.repository.LicenseStatus
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.ImportProgress
import com.aurastream.tv.ui.components.LoadingIndicator
import com.aurastream.tv.ui.components.Panel
import com.aurastream.tv.ui.theme.AuraColors

@Composable
fun ActivationScreen(onActivated: () -> Unit) {
    val vm = auraViewModel { c, _ -> ActivationViewModel(c.deviceRepository, c.playlistRepository) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.activated.collect { onActivated() } }

    val primaryAction = remember { FocusRequester() }
    LaunchedEffect(state.loading, state.conflict) { runCatching { primaryAction.requestFocus() } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AuraColors.Surface, AuraColors.Canvas))),
    ) {
        val compact = maxWidth < 720.dp
        val content: @Composable () -> Unit = {
            when {
                state.loading && state.session == null -> LoadingIndicator(label = stringResource(R.string.startup_connecting))
                state.conflict -> ConflictPanel(onRepair = vm::repair, focus = primaryAction)
                else -> PairingContent(state, compact, primaryAction, onCheckNow = vm::checkNow)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 16.dp else 64.dp, vertical = if (compact) 24.dp else 48.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun PairingContent(
    state: ActivationUiState,
    compact: Boolean,
    focus: FocusRequester,
    onCheckNow: () -> Unit,
) {
    val session = state.session
    val info: @Composable () -> Unit = {
        Column(Modifier.infoWidth(compact)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = AuraColors.NeonCyan)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.activation_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.activation_steps), style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
            Spacer(Modifier.height(28.dp))

            CredentialRow(stringResource(R.string.mac_address), session?.mac ?: state.mac)
            Spacer(Modifier.height(16.dp))
            CredentialRow(stringResource(R.string.device_key), session?.deviceKey ?: "······", large = true)
            Spacer(Modifier.height(16.dp))
            session?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Link, null, tint = AuraColors.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it.portalUrl,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        color = AuraColors.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            LicenseLine(session?.status, session?.daysLeft, state.expired)
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = AuraColors.Error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            val importing = state.importing
            if (importing != null) {
                ImportProgress(importing, Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AuraButton(
                        text = stringResource(R.string.activation_check_now),
                        icon = Icons.Filled.Refresh,
                        primary = true,
                        onClick = onCheckNow,
                        modifier = Modifier.focusRequester(focus),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.activation_waiting), color = AuraColors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    val qr: @Composable () -> Unit = {
        Panel {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(if (compact) 220.dp else 280.dp).clip(RoundedCornerShape(12.dp))) {
                    if (session != null) QrCode(session.pairingUrl, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.activation_scan), style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
            }
        }
    }

    if (compact) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            qr()
            Spacer(Modifier.height(24.dp))
            info()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Box(Modifier.weight(1f)) { info() }
            qr()
        }
    }
}

private fun Modifier.infoWidth(compact: Boolean): Modifier = if (compact) fillMaxWidth() else this

@Composable
private fun CredentialRow(label: String, value: String, large: Boolean = false) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = AuraColors.TextTertiary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            // MAC & key are always rendered LTR, even in Arabic layouts.
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (large) 44.sp else 30.sp,
                letterSpacing = if (large) 8.sp else 2.sp,
                textDirection = TextDirection.Ltr,
            ),
            color = if (large) AuraColors.NeonCyan else AuraColors.TextPrimary,
        )
    }
}

@Composable
private fun LicenseLine(status: LicenseStatus?, daysLeft: Int?, expired: Boolean) {
    val (text, color) = when {
        expired || status == LicenseStatus.EXPIRED -> stringResource(R.string.license_expired) to AuraColors.Error
        status == LicenseStatus.ACTIVE -> stringResource(R.string.license_active) to AuraColors.Success
        status == LicenseStatus.TRIAL -> stringResource(R.string.license_trial_days, daysLeft ?: 0) to AuraColors.Warning
        else -> return
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AuraColors.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ConflictPanel(onRepair: () -> Unit, focus: FocusRequester) {
    Panel(Modifier.width(560.dp)) {
        Column {
            Icon(Icons.Filled.Warning, null, tint = AuraColors.Warning, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.conflict_message), style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
            Spacer(Modifier.height(24.dp))
            AuraButton(
                text = stringResource(R.string.conflict_repair),
                primary = true,
                onClick = onRepair,
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
}

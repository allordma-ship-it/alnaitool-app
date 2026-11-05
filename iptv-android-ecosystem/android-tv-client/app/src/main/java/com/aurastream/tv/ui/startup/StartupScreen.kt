package com.aurastream.tv.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.repository.ImportState
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.CenteredMessage
import com.aurastream.tv.ui.components.ImportProgress
import com.aurastream.tv.ui.components.LoadingIndicator
import com.aurastream.tv.ui.theme.AuraColors

@Composable
fun StartupScreen(onReady: () -> Unit, onNeedsActivation: () -> Unit) {
    val vm = auraViewModel { c, _ -> StartupViewModel(c.deviceRepository, c.playlistRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        when (state) {
            StartupState.Ready -> onReady()
            StartupState.NeedsActivation -> onNeedsActivation()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is StartupState.Error -> {
                val retry = remember { FocusRequester() }
                CenteredMessage(
                    title = stringResource(R.string.startup_error_title),
                    message = s.message,
                    icon = Icons.Filled.CloudOff,
                    action = {
                        AuraButton(
                            text = stringResource(R.string.retry),
                            icon = Icons.Filled.Refresh,
                            primary = true,
                            onClick = vm::start,
                            modifier = Modifier.focusRequester(retry),
                        )
                    },
                )
                LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displayMedium,
                    color = AuraColors.NeonCyan,
                )
                Spacer(Modifier.height(32.dp))
                val running = importState as? ImportState.Running
                if (running != null) {
                    ImportProgress(running, Modifier.width(420.dp))
                } else {
                    Box(Modifier.height(120.dp).padding(8.dp)) {
                        LoadingIndicator(
                            label = stringResource(
                                if (s == StartupState.Syncing) R.string.startup_syncing else R.string.startup_connecting,
                            ),
                        )
                    }
                }
            }
        }
    }
}

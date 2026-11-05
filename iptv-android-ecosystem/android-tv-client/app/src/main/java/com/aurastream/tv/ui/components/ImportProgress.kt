package com.aurastream.tv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.repository.ImportStage
import com.aurastream.tv.data.repository.ImportState
import com.aurastream.tv.ui.theme.AuraColors

@Composable
fun importStageLabel(stage: ImportStage): String = stringResource(
    when (stage) {
        ImportStage.CONNECTING -> R.string.import_connecting
        ImportStage.DOWNLOADING -> R.string.import_downloading
        ImportStage.LIVE -> R.string.import_live
        ImportStage.MOVIES -> R.string.import_movies
        ImportStage.SERIES -> R.string.import_series
        ImportStage.EPG -> R.string.import_epg
    },
)

/** Indeterminate progress for streaming imports (total size is unknown up front). */
@Composable
fun ImportProgress(state: ImportState.Running, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "import")
    val phase by transition.animateFloat(0.1f, 1f, infiniteRepeatable(tween(1400)), label = "phase")
    Column(modifier) {
        Text(state.playlistName, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            importStageLabel(state.stage) + if (state.items > 0) " · ${state.items}" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        ProgressLine(phase)
    }
}

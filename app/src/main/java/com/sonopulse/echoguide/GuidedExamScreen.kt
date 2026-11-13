package com.sonopulse.echoguide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** A flattened position in the exam: which view and which step inside it. */
private data class GuidePosition(val view: EchoView, val stepIndex: Int)

/**
 * Guided exam ("Anleitung"): walks through all steps of all views in protocol order inside the
 * zero-scroll [ViewCockpit]. The step card's inline bar moves through every step (e.g. 27 / 45);
 * the Ablauf drawer jumps straight to the first step of any view.
 */
@Composable
fun GuidedExamScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    when (val state = rememberProtocolState()) {
        ProtocolState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        is ProtocolState.Error -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.protocol_error, state.message), color = MaterialTheme.colorScheme.error)
        }
        is ProtocolState.Ready -> GuidedCockpit(state.protocol, language, onLanguageChange, modifier)
    }
}

@Composable
private fun GuidedCockpit(
    protocol: EchoProtocol,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier
) {
    val positions = remember(protocol) {
        protocol.allViews.flatMap { view -> view.steps.indices.map { GuidePosition(view, it) } }
    }
    if (positions.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.guided_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val pos = positions[index.coerceIn(0, positions.lastIndex)]
    val isLast = index >= positions.lastIndex

    ViewCockpit(
        protocol = protocol,
        view = pos.view,
        language = language,
        onLanguageChange = onLanguageChange,
        navigator = StepNavigator(
            step = pos.view.steps[pos.stepIndex],
            counter = "${index + 1} / ${positions.size}",
            canGoBack = index > 0,
            isLast = isLast,
            onBack = { if (index > 0) index-- },
            onNext = { index = if (isLast) 0 else index + 1 }
        ),
        onSelectView = { selected ->
            positions.indexOfFirst { it.view.id == selected.id }.takeIf { it >= 0 }?.let { index = it }
        },
        modifier = modifier
    )
}

package com.sonopulse.echoguide

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonopulse.echoguide.features.settings.SettingsRoute
import androidx.compose.runtime.staticCompositionLocalOf
import com.sonopulse.echoguide.features.settings.SettingsViewModel
import com.sonopulse.echoguide.features.settings.ThemeMode

// ---------------------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    // Edge-to-edge with transparent bars; their icon colour follows the theme (SystemBarAppearance).
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            EchoScopeTheme {
                EchoScopeApp()
            }
        }
    }
}

private enum class EchoDestination(val labelRes: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    PROTOCOL(R.string.nav_protocol, Icons.Filled.MonitorHeart, Icons.Outlined.MonitorHeart),
    GUIDED(R.string.nav_guided, Icons.Filled.School, Icons.Outlined.School),
    VALVES(R.string.nav_valves, Icons.AutoMirrored.Filled.FactCheck, Icons.AutoMirrored.Outlined.FactCheck),
    ZSCORE(R.string.nav_zscore, Icons.Filled.Calculate, Icons.Outlined.Calculate)
}

/** Opens the unified settings screen (gear in every top bar); provided by [EchoScopeApp]. */
val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * App root: reads the persisted settings (DataStore via [SettingsViewModel]) and applies theme
 * (system / light / dark), language and layout direction instantly – no activity restart.
 */
@Composable
fun EchoScopeApp(settingsViewModel: SettingsViewModel = viewModel()) {
    val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
    if (!ui.loaded) {
        // Settings are read off the main thread (a few ms): show the plain app background meanwhile.
        Box(Modifier.fillMaxSize().background(EchoPalette.BgPrimary))
        return
    }
    val appContext = LocalContext.current
    val language = ui.settings.language ?: AppLanguage.fromSystem(appContext.resources.configuration)
    val dark = when (ui.settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SystemBarAppearance(dark)
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    EchoScopeTheme(colors = if (dark) DarkEchoColors else LightEchoColors, language = language) {
        LocalizedRoot(language) {
            CompositionLocalProvider(LocalOpenSettings provides { settingsOpen = true }) {
                // The clinical screens stay composed underneath, so closing settings returns to the same place.
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlaybackSuspended provides settingsOpen) {
                        EchoScopeScaffold(language = language)
                    }
                    if (settingsOpen) {
                        SettingsRoute(
                            state = ui,
                            language = language,
                            onThemeMode = settingsViewModel::setThemeMode,
                            onLanguage = settingsViewModel::setLanguage,
                            onVerifyDatabase = settingsViewModel::verifyDatabase,
                            onClose = { settingsOpen = false }
                        )
                    }
                }
            }
        }
    }
}

/** Status / navigation bar icons follow the active theme (light icons on dark, dark on light). */
@Composable
private fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/**
 * Applies the in-app language to everything below it: string resources (via a localized
 * Context/Configuration), plural rules, and the layout direction (RTL for Arabic).
 */
@Composable
internal fun LocalizedRoot(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val localized = remember(language, baseConfiguration) {
        val configuration = Configuration(baseConfiguration).apply {
            setLocale(language.locale)
            setLayoutDirection(language.locale)
        }
        baseContext.createConfigurationContext(configuration) to configuration
    }
    CompositionLocalProvider(
        LocalContext provides localized.first,
        LocalConfiguration provides localized.second,
        LocalLayoutDirection provides if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        content()
    }
}



// ---------------------------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------------------------

/** Loads the bundled protocol database once per screen (cached in [EchoRepository]). */
@Composable
fun rememberProtocolState(): ProtocolState {
    val context = LocalContext.current
    val state by produceState<ProtocolState>(ProtocolState.Loading) {
        value = EchoRepository.load(context)
    }
    return state
}

/**
 * Bottom navigation: Schnitte (window & view selection), Anleitung (guided cockpit) and Z-Score.
 * Opening a view from Schnitte shows a dedicated full-screen cockpit; its back arrow and the
 * system back gesture return to the selection screen.
 */
@Composable
private fun EchoScopeScaffold(language: AppLanguage) {
    var destination by rememberSaveable { mutableStateOf(EchoDestination.PROTOCOL) }
    var openViewId by rememberSaveable { mutableStateOf<String?>(null) }
    // Schnitte selection lives here, above the detail screen, so Back returns to the same window,
    // scroll position and highlighted view instead of the defaults.
    var selectedWindowId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastViewId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectionScroll = rememberScrollState()
    val openView: (String) -> Unit = { id ->
        lastViewId = id
        openViewId = id
    }

    val detailId = openViewId
    // A view opened from Schnitte or from the valve assessment; Back returns to that tab.
    if (detailId != null && destination != EchoDestination.GUIDED) {
        BackHandler { openViewId = null }
        ViewDetailScreen(
            viewId = detailId,
            language = language,
            onBack = { openViewId = null },
            onSelectView = { openView(it.id) },
            modifier = Modifier.systemBarsPadding()
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (destination != EchoDestination.GUIDED) {
                EchoTopBar()
            } else {
                Spacer(Modifier.statusBarsPadding())
            }
        },
        bottomBar = {
            NavigationBar(containerColor = EchoSurface, tonalElevation = 0.dp) {
                EchoDestination.entries.forEach { item ->
                    val selected = destination == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = { destination = item },
                        icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EchoOnAccent,
                            selectedTextColor = EchoPrimary,
                            indicatorColor = EchoPrimaryFill,
                            unselectedIconColor = EchoMuted,
                            unselectedTextColor = EchoMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (destination) {
                EchoDestination.PROTOCOL -> ViewSelectionScreen(
                    language = language,
                    selection = SelectionState(selectedWindowId, lastViewId, selectionScroll),
                    onSelectWindow = { selectedWindowId = it },
                    onOpenView = openView
                )
                EchoDestination.GUIDED -> GuidedExamScreen(language)
                EchoDestination.VALVES -> ValveAssessmentScreen(language, onOpenView = openView)
                EchoDestination.ZSCORE -> ClinicalCalculatorsScreen(language)
            }
        }
    }
}

@Composable
private fun ViewDetailScreen(
    viewId: String,
    language: AppLanguage,
    onBack: () -> Unit,
    onSelectView: (EchoView) -> Unit,
    modifier: Modifier = Modifier
) {
    when (val state = rememberProtocolState()) {
        ProtocolState.Loading -> CenteredMessage { CircularProgressIndicator(color = EchoPrimary) }
        is ProtocolState.Error -> CenteredMessage {
            Text(stringResource(R.string.protocol_error, state.message), color = MaterialTheme.colorScheme.error)
        }
        is ProtocolState.Ready -> {
            val view = state.protocol.allViews.firstOrNull { it.id == viewId } ?: state.protocol.allViews.first()
            var stepIndex by rememberSaveable(view.id) { mutableIntStateOf(0) }
            val count = view.steps.size
            val isLast = stepIndex >= count - 1
            ViewCockpit(
                protocol = state.protocol,
                view = view,
                language = language,
                navigator = StepNavigator(
                    step = view.steps.getOrNull(stepIndex),
                    counter = if (count > 0) "${stepIndex + 1} / $count" else "–",
                    canGoBack = stepIndex > 0,
                    isLast = isLast,
                    onBack = { if (stepIndex > 0) stepIndex-- },
                    onNext = { if (count > 0) stepIndex = if (isLast) 0 else stepIndex + 1 }
                ),
                onSelectView = onSelectView,
                onBack = onBack,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) { content() }
}

// ---------------------------------------------------------------------------------------------
// Schnitte: window & view selection (no inline details)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ViewSelectionScreen(
    language: AppLanguage,
    selection: SelectionState,
    onSelectWindow: (String) -> Unit,
    onOpenView: (String) -> Unit
) {
    when (val state = rememberProtocolState()) {
        ProtocolState.Loading -> CenteredMessage {
            CircularProgressIndicator(color = EchoPrimary)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.loading_protocol), color = EchoMuted)
        }
        is ProtocolState.Error -> CenteredMessage {
            Text(stringResource(R.string.protocol_error, state.message), color = MaterialTheme.colorScheme.error)
        }
        is ProtocolState.Ready -> SelectionContent(state.protocol, language, selection, onSelectWindow, onOpenView)
    }
}

@Composable
private fun SelectionContent(
    protocol: EchoProtocol,
    language: AppLanguage,
    selection: SelectionState,
    onSelectWindow: (String) -> Unit,
    onOpenView: (String) -> Unit
) {
    // Selected window, else the window of the last opened view, else the first window.
    val window = protocol.windows.firstOrNull { it.id == selection.windowId }
        ?: protocol.windows.firstOrNull { w -> w.views.any { it.id == selection.lastViewId } }
        ?: protocol.windows.first()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(selection.scroll)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle(stringResource(R.string.section_windows))
        protocol.windows.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { w ->
                    WindowCard(w, language, selected = w.id == window.id, onClick = { onSelectWindow(w.id) }, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        SectionTitle(stringResource(R.string.section_views))
        Text(window.description.get(language), style = MaterialTheme.typography.bodySmall, color = EchoMuted)
        window.views.forEach { v -> ViewCard(v, language, highlighted = v.id == selection.lastViewId, onClick = { onOpenView(v.id) }) }
        Spacer(Modifier.height(8.dp))
    }
}

/** Schnitte selection hoisted into the scaffold (survives the view-detail screen and Back). */
@Stable
private class SelectionState(val windowId: String?, val lastViewId: String?, val scroll: ScrollState)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = EchoMuted
    )
}

@Composable
private fun WindowCard(
    window: AcousticWindow,
    language: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) EchoPrimary.copy(alpha = 0.12f) else EchoSurface),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) EchoPrimary else EchoOutline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                window.title.get(language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) EchoPrimary else EchoText
            )
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(R.plurals.views_count, window.views.size, window.views.size),
                style = MaterialTheme.typography.labelSmall,
                color = EchoMuted
            )
        }
    }
}

@Composable
private fun ViewCard(view: EchoView, language: AppLanguage, highlighted: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (highlighted) EchoPrimary.copy(alpha = 0.10f) else EchoSurface),
        border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, if (highlighted) EchoPrimary else EchoOutline)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 84.dp, height = 72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AtlasCard)
            ) {
                AsyncImage(
                    model = MediaRegistry.assetUri(view.diagramAsset ?: view.svgAsset),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    view.title.get(language),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = EchoText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The badge keeps its size; a long notch text ellipsizes instead of squeezing it.
                    Text(stringResource(R.string.notch_label, view.notchClock), style = MaterialTheme.typography.labelSmall, color = EchoMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    val simulated = view.videoSource.isSimulated
                    Text(
                        if (simulated) stringResource(R.string.badge_simulated) else ltr("${viewCode(view.id)} · ${stringResource(R.string.badge_live)}"),
                        color = if (simulated) EchoCaution else EchoNormal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EchoMuted)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// App bar, language menu, info dialog
// ---------------------------------------------------------------------------------------------

@Composable
private fun EchoTopBar() {
    val openSettings = LocalOpenSettings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EchoBackground)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EchoScopeLogo(size = 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = EchoText)
            Text(
                stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = EchoMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (openSettings != null) SettingsGearButton(openSettings)
    }
}

/** The single settings entry point (language, theme, about, references, contact). */
@Composable
internal fun SettingsGearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings), tint = EchoText)
    }
}


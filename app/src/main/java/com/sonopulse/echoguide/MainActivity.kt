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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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

// ---------------------------------------------------------------------------------------------
// Echo-Room OLED dark theme
// ---------------------------------------------------------------------------------------------

internal val EchoBackground = Color(0xFF0A0E14)
internal val EchoSurface = Color(0xFF111824)
internal val EchoSurfaceHigh = Color(0xFF1A2333)
internal val EchoTeal = Color(0xFF00D2B8)
internal val EchoOrange = Color(0xFFFF8A3D)
internal val EchoSky = Color(0xFF38BDF8)
internal val EchoAmber = Color(0xFFFBBF24)
internal val EchoText = Color(0xFFE2E8F0)
internal val EchoMuted = Color(0xFF94A3B8)
internal val EchoOutline = Color(0xFF263244)
/** Light-slate card behind the anatomical atlas illustrations so their dark labels stay readable. */
internal val AtlasCard = Color(0xFFE2E8F0)

internal val EchoColorScheme = darkColorScheme(
    primary = EchoTeal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF003D36),
    onPrimaryContainer = EchoTeal,
    secondary = EchoOrange,
    onSecondary = Color(0xFF2B1200),
    secondaryContainer = Color(0xFF4A2000),
    onSecondaryContainer = EchoOrange,
    tertiary = EchoSky,
    onTertiary = Color(0xFF00283A),
    background = EchoBackground,
    onBackground = EchoText,
    surface = EchoSurface,
    onSurface = EchoText,
    surfaceVariant = EchoSurfaceHigh,
    onSurfaceVariant = EchoMuted,
    surfaceContainer = EchoSurface,
    surfaceContainerLow = EchoSurface,
    surfaceContainerHigh = EchoSurfaceHigh,
    surfaceContainerHighest = EchoSurfaceHigh,
    outline = EchoOutline,
    outlineVariant = EchoOutline,
    error = Color(0xFFF87171),
    onError = Color(0xFF370B0B)
)

@Composable
fun EchoScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EchoColorScheme, typography = Typography(), content = content)
}

// ---------------------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
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
    ZSCORE(R.string.nav_zscore, Icons.Filled.Calculate, Icons.Outlined.Calculate)
}

@Composable
fun EchoScopeApp() {
    val appContext = LocalContext.current
    var language by remember {
        mutableStateOf(
            LanguagePreferences.load(appContext) ?: AppLanguage.fromSystem(appContext.resources.configuration)
        )
    }
    LocalizedRoot(language) {
        EchoScopeScaffold(
            language = language,
            onLanguageChange = { selected ->
                language = selected
                LanguagePreferences.save(appContext, selected)
            }
        )
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

/** Secondary language shown under titles: Arabic for German/English users, German for Arabic users. */
internal fun AppLanguage.companion(): AppLanguage = if (this == AppLanguage.AR) AppLanguage.DE else AppLanguage.AR


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
private fun EchoScopeScaffold(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    var destination by rememberSaveable { mutableStateOf(EchoDestination.PROTOCOL) }
    var openViewId by rememberSaveable { mutableStateOf<String?>(null) }

    val detailId = openViewId
    if (destination == EchoDestination.PROTOCOL && detailId != null) {
        BackHandler { openViewId = null }
        ViewDetailScreen(
            viewId = detailId,
            language = language,
            onLanguageChange = onLanguageChange,
            onBack = { openViewId = null },
            onSelectView = { openViewId = it.id },
            modifier = Modifier.systemBarsPadding()
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (destination != EchoDestination.GUIDED) {
                EchoTopBar(language = language, onLanguageChange = onLanguageChange)
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
                            selectedIconColor = EchoBackground,
                            selectedTextColor = EchoTeal,
                            indicatorColor = EchoTeal,
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
                EchoDestination.PROTOCOL -> ViewSelectionScreen(language, onOpenView = { openViewId = it })
                EchoDestination.GUIDED -> GuidedExamScreen(language, onLanguageChange)
                EchoDestination.ZSCORE -> ZScoreCalculatorScreen()
            }
        }
    }
}

@Composable
private fun ViewDetailScreen(
    viewId: String,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    onSelectView: (EchoView) -> Unit,
    modifier: Modifier = Modifier
) {
    when (val state = rememberProtocolState()) {
        ProtocolState.Loading -> CenteredMessage { CircularProgressIndicator(color = EchoTeal) }
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
                onLanguageChange = onLanguageChange,
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
private fun ViewSelectionScreen(language: AppLanguage, onOpenView: (String) -> Unit) {
    when (val state = rememberProtocolState()) {
        ProtocolState.Loading -> CenteredMessage {
            CircularProgressIndicator(color = EchoTeal)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.loading_protocol), color = EchoMuted)
        }
        is ProtocolState.Error -> CenteredMessage {
            Text(stringResource(R.string.protocol_error, state.message), color = MaterialTheme.colorScheme.error)
        }
        is ProtocolState.Ready -> SelectionContent(state.protocol, language, onOpenView)
    }
}

@Composable
private fun SelectionContent(protocol: EchoProtocol, language: AppLanguage, onOpenView: (String) -> Unit) {
    var windowId by rememberSaveable { mutableStateOf(protocol.windows.first().id) }
    val window = protocol.windows.firstOrNull { it.id == windowId } ?: protocol.windows.first()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle(stringResource(R.string.section_windows))
        protocol.windows.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { w ->
                    WindowCard(w, language, selected = w.id == window.id, onClick = { windowId = w.id }, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        SectionTitle(stringResource(R.string.section_views))
        Text(window.description.get(language), style = MaterialTheme.typography.bodySmall, color = EchoMuted)
        window.views.forEach { v -> ViewCard(v, language, onClick = { onOpenView(v.id) }) }
        Spacer(Modifier.height(8.dp))
    }
}

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
        colors = CardDefaults.cardColors(containerColor = if (selected) EchoTeal.copy(alpha = 0.12f) else EchoSurface),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) EchoTeal else EchoOutline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                window.title.get(language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) EchoTeal else EchoText
            )
            Text(window.title.get(language.companion()), style = MaterialTheme.typography.bodyMedium, color = EchoOrange)
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
private fun ViewCard(view: EchoView, language: AppLanguage, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = EchoSurface),
        border = BorderStroke(1.dp, EchoOutline)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 84.dp, height = 72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AtlasCard)
            ) {
                AsyncImage(
                    model = "file:///android_asset/${view.diagramAsset ?: view.svgAsset}",
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
                Text(view.title.get(language.companion()), style = MaterialTheme.typography.bodySmall, color = EchoOrange, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notch_label, view.notchClock), style = MaterialTheme.typography.labelSmall, color = EchoMuted)
                    val simulated = view.videoSource.isSimulated
                    Text(
                        stringResource(if (simulated) R.string.badge_simulated else R.string.badge_real),
                        color = if (simulated) EchoOrange else EchoTeal,
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
private fun EchoTopBar(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    var aboutOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EchoBackground)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(EchoTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = EchoTeal)
        }
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
        LanguageMenuButton(language, onLanguageChange)
        IconButton(onClick = { aboutOpen = true }) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about_title), tint = EchoMuted)
        }
    }
    if (aboutOpen) AboutDialog(view = null, language = language, onDismiss = { aboutOpen = false })
}

/** Language picker; [compact] shows only the language code (DE / AR / EN) for the slim cockpit bar. */
@Composable
internal fun LanguageMenuButton(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit, compact: Boolean = false) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { menuOpen = true },
            contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 10.dp)
        ) {
            Icon(Icons.Filled.Translate, contentDescription = stringResource(R.string.language_label), tint = EchoOrange,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (compact) language.tag.uppercase() else language.nativeName, color = EchoOrange,
                style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = EchoSurfaceHigh) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.nativeName,
                            color = if (option == language) EchoTeal else EchoText,
                            fontWeight = if (option == language) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    trailingIcon = { if (option == language) Icon(Icons.Filled.Check, contentDescription = null, tint = EchoTeal) },
                    onClick = {
                        menuOpen = false
                        onLanguageChange(option)
                    }
                )
            }
        }
    }
}

/**
 * Info dialog with all attribution: the current view's loop and diagram sources (when [view] is set),
 * the Patrick J. Lynch (CC-BY 2.5) illustration credit, the CardioNetworks ECHOpedia clip credit,
 * the open licence question for supplied reference images, and the disclaimer.
 */
@Composable
internal fun AboutDialog(view: EchoView?, language: AppLanguage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull().orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) } },
        title = { Text(stringResource(R.string.about_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.about_version, version), color = EchoMuted, style = MaterialTheme.typography.labelMedium)
                if (view != null) {
                    val src = view.videoSource
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.about_this_view, view.title.get(language)), color = EchoOrange,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (src.isSimulated) {
                                stringResource(R.string.player_simulated_note)
                            } else {
                                stringResource(R.string.player_real_credit, src.author.orEmpty().ifBlank { "Wikimedia Commons" }, src.license.orEmpty())
                            },
                            color = EchoText,
                            style = MaterialTheme.typography.bodySmall
                        )
                        view.diagramCredit?.let { Text(it.get(language), color = EchoText, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                AboutSection(R.string.about_illustrations_title, R.string.about_illustrations_credit, R.string.about_illustrations_detail)
                AboutSection(R.string.about_clips_title, R.string.about_clips_credit, null)
                AboutSection(R.string.about_reference_title, R.string.about_reference_credit, null)
                AboutSection(R.string.about_disclaimer_title, R.string.about_disclaimer, null)
            }
        },
        containerColor = EchoSurface
    )
}

@Composable
private fun AboutSection(titleRes: Int, bodyRes: Int, detailRes: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(titleRes), color = EchoTeal, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(bodyRes), color = EchoText, style = MaterialTheme.typography.bodyMedium)
        if (detailRes != null) {
            Text(stringResource(detailRes), color = EchoMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

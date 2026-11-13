package com.sonopulse.echoguide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Echo-Room OLED dark theme
// ---------------------------------------------------------------------------------------------

private val EchoBackground = Color(0xFF0A0E14)
private val EchoSurface = Color(0xFF111824)
private val EchoSurfaceHigh = Color(0xFF1A2333)
private val EchoTeal = Color(0xFF00D2B8)
private val EchoOrange = Color(0xFFFF8A3D)
private val EchoSky = Color(0xFF38BDF8)
private val EchoAmber = Color(0xFFFBBF24)
private val EchoText = Color(0xFFE2E8F0)
private val EchoMuted = Color(0xFF94A3B8)
private val EchoOutline = Color(0xFF263244)

private val EchoColorScheme = darkColorScheme(
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
    ZSCORE(R.string.nav_zscore, Icons.Filled.Calculate, Icons.Outlined.Calculate)
}

@Composable
fun EchoScopeApp() {
    var destination by rememberSaveable { mutableStateOf(EchoDestination.PROTOCOL) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = EchoSurface, tonalElevation = 0.dp) {
                EchoDestination.entries.forEach { item ->
                    val selected = destination == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = null
                            )
                        },
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
                EchoDestination.PROTOCOL -> ProtocolScreen()
                EchoDestination.ZSCORE -> ZScoreCalculatorScreen()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Protocol & Views
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProtocolScreen() {
    val context = LocalContext.current
    val state by produceState<ProtocolState>(ProtocolState.Loading) {
        value = EchoRepository.load(context)
    }

    when (val s = state) {
        ProtocolState.Loading -> CenteredMessage {
            CircularProgressIndicator(color = EchoTeal)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.loading_protocol), color = EchoMuted)
        }

        is ProtocolState.Error -> CenteredMessage {
            Text(
                text = stringResource(R.string.protocol_error, s.message),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        is ProtocolState.Ready -> ProtocolContent(s.protocol)
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

@Composable
private fun ProtocolContent(protocol: EchoProtocol) {
    var windowId by rememberSaveable { mutableStateOf(protocol.windows.first().id) }
    var viewId by rememberSaveable { mutableStateOf(protocol.windows.first().views.first().id) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    val window = protocol.windows.firstOrNull { it.id == windowId } ?: protocol.windows.first()
    val view = window.views.firstOrNull { it.id == viewId } ?: window.views.first()

    // Checklist progress per view survives tab and view switches for the whole session.
    val checked = remember { mutableStateMapOf<String, Boolean>() }

    // A plain scrolling Column (not a LazyColumn) keeps the single ExoPlayer composed while scrolling.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Header()

        SectionTitle(stringResource(R.string.section_windows))
        WindowGrid(
            windows = protocol.windows,
            selectedId = window.id,
            onSelect = { selected ->
                if (selected.id != windowId) {
                    windowId = selected.id
                    viewId = selected.views.first().id
                }
            }
        )

        SectionTitle(stringResource(R.string.section_views))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            window.views.forEach { v ->
                FilterChip(
                    selected = v.id == view.id,
                    onClick = { viewId = v.id },
                    label = { Text(v.titleEn, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = EchoSurface,
                        labelColor = EchoText,
                        selectedContainerColor = EchoTeal,
                        selectedLabelColor = EchoBackground
                    )
                )
            }
        }

        ViewTitle(view)

        EchoCinePlayer(videoAssetPath = view.videoAsset)

        ProbeNotchCard(view)

        DetailTabs(
            selectedIndex = tabIndex,
            onSelect = { tabIndex = it }
        )

        when (tabIndex) {
            0 -> SvgAnatomyPanel(view)
            1 -> MeasurementsPanel(view)
            else -> ChecklistPanel(
                view = view,
                isChecked = { i -> checked["${view.id}#$i"] == true },
                onToggle = { i, value -> checked["${view.id}#$i"] = value }
            )
        }

        Text(
            text = protocol.disclaimerEn,
            style = MaterialTheme.typography.bodySmall,
            color = EchoMuted
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(EchoTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = EchoTeal)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = EchoText
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = EchoMuted
            )
        }
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
private fun WindowGrid(
    windows: List<AcousticWindow>,
    selectedId: String,
    onSelect: (AcousticWindow) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        windows.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { w ->
                    WindowCard(
                        window = w,
                        selected = w.id == selectedId,
                        onClick = { onSelect(w) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WindowCard(
    window: AcousticWindow,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .heightIn(min = 92.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) EchoTeal.copy(alpha = 0.12f) else EchoSurface
        ),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) EchoTeal else EchoOutline)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = window.titleEn,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) EchoTeal else EchoText
            )
            Text(
                text = window.titleAr,
                style = MaterialTheme.typography.bodyMedium,
                color = EchoOrange
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${window.views.size} view" + if (window.views.size == 1) "" else "s",
                style = MaterialTheme.typography.labelSmall,
                color = EchoMuted
            )
        }
    }
}

@Composable
private fun ViewTitle(view: EchoView) {
    Column {
        Text(
            text = view.titleEn,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = EchoText
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Text(
                text = view.titleAr,
                style = MaterialTheme.typography.titleSmall,
                color = EchoOrange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProbeNotchCard(view: EchoView) {
    EchoCard {
        SectionTitle(stringResource(R.string.section_probe))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotchClock(hours = view.notchHours, modifier = Modifier.size(84.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notch_label, view.notchClock),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = EchoOrange
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = view.notchDescriptionEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoMuted
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = view.probePositionEn,
            style = MaterialTheme.typography.bodyMedium,
            color = EchoText
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = EchoOutline)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = view.probePositionAr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoText,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = view.notchDescriptionAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoOrange,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Clock face showing where the transducer index mark (notch) points. */
@Composable
private fun NotchClock(hours: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val c = center
        drawCircle(color = EchoSurfaceHigh, radius = radius)
        drawCircle(color = EchoOutline, radius = radius, style = Stroke(width = 2.dp.toPx()))
        for (h in 0 until 12) {
            val angle = h * PI / 6.0
            val outer = Offset(c.x + radius * 0.92f * sin(angle).toFloat(), c.y - radius * 0.92f * cos(angle).toFloat())
            val inner = Offset(c.x + radius * 0.78f * sin(angle).toFloat(), c.y - radius * 0.78f * cos(angle).toFloat())
            drawLine(
                color = if (h % 3 == 0) EchoMuted else EchoOutline,
                start = inner,
                end = outer,
                strokeWidth = (if (h % 3 == 0) 2.5f else 1.5f).dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        hours.forEach { hour ->
            val angle = (hour % 12.0) * PI / 6.0
            val tip = Offset(c.x + radius * 0.66f * sin(angle).toFloat(), c.y - radius * 0.66f * cos(angle).toFloat())
            drawLine(color = EchoOrange, start = c, end = tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color = EchoOrange, radius = 5.dp.toPx(), center = tip)
        }
        drawCircle(color = EchoOrange, radius = 3.dp.toPx(), center = c)
    }
}

@Composable
private fun DetailTabs(selectedIndex: Int, onSelect: (Int) -> Unit) {
    val titles = listOf(
        stringResource(R.string.tab_svg),
        stringResource(R.string.tab_measurements),
        stringResource(R.string.tab_checklist)
    )
    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = EchoSurface,
        contentColor = EchoTeal,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        indicator = { positions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(positions[selectedIndex]),
                color = EchoTeal
            )
        },
        divider = {}
    ) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                selectedContentColor = EchoTeal,
                unselectedContentColor = EchoMuted,
                text = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun SvgAnatomyPanel(view: EchoView) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
    EchoCard {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data("file:///android_asset/${view.svgAsset}")
                .decoderFactory(SvgDecoder.Factory())
                .build(),
            imageLoader = imageLoader,
            contentDescription = view.titleEn,
            contentScale = ContentScale.Fit,
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = EchoTeal, modifier = Modifier.size(28.dp))
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("assets/${view.svgAsset}", color = EchoMuted)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(EchoBackground)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.key_structures),
            style = MaterialTheme.typography.labelLarge,
            color = EchoTeal
        )
        Spacer(Modifier.height(6.dp))
        view.keyStructures.forEach { item ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("•", color = EchoOrange, modifier = Modifier.width(14.dp))
                Text(item, style = MaterialTheme.typography.bodyMedium, color = EchoText)
            }
        }
        Spacer(Modifier.height(8.dp))
        LegendRow()
    }
}

@Composable
private fun LegendRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        listOf(
            EchoTeal to "Chambers",
            EchoSky to "Septa",
            EchoAmber to "Valves",
            EchoOrange to "Notch"
        ).forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = EchoMuted)
            }
        }
    }
}

@Composable
private fun MeasurementsPanel(view: EchoView) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EchoCard {
            Text(
                text = stringResource(R.string.adult_reference),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = EchoTeal
            )
            view.referenceMeasurements.adult.forEachIndexed { index, m ->
                if (index > 0) HorizontalDivider(color = EchoOutline)
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = m.parameter,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = EchoText,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = m.normal,
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoTeal,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (m.note.isNotBlank()) {
                        Text(
                            text = m.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoMuted
                        )
                    }
                }
            }
        }
        EchoCard {
            Text(
                text = stringResource(R.string.pediatric_reference),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = EchoOrange
            )
            view.referenceMeasurements.pediatric.forEachIndexed { index, p ->
                if (index > 0) HorizontalDivider(color = EchoOutline)
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = p.parameter,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = EchoText
                    )
                    Text(
                        text = p.focus,
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistPanel(
    view: EchoView,
    isChecked: (Int) -> Boolean,
    onToggle: (Int, Boolean) -> Unit
) {
    val total = view.checklist.size
    val done = view.checklist.indices.count { isChecked(it) }
    EchoCard {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.checklist_progress, done, total),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (done == total) EchoTeal else EchoOrange
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                    color = EchoTeal,
                    trackColor = EchoSurfaceHigh,
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(8.dp))
                view.checklist.forEachIndexed { index, item ->
                    val itemChecked = isChecked(index)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggle(index, !itemChecked) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = itemChecked,
                            onCheckedChange = { onToggle(index, it) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = EchoTeal,
                                checkmarkColor = EchoBackground,
                                uncheckedColor = EchoMuted
                            )
                        )
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (itemChecked) EchoMuted else EchoText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

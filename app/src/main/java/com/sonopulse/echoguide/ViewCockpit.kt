package com.sonopulse.echoguide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.ExoPlayer
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/** Step navigation shown inside the cockpit's step card ("< Zurück | 27 / 45 | Weiter >"). */
class StepNavigator(
    val step: GuideStep?,
    val counter: String,
    val canGoBack: Boolean,
    val isLast: Boolean,
    val onBack: () -> Unit,
    val onNext: () -> Unit
)

private enum class DeckTab(val labelRes: Int) {
    STEPS(R.string.cockpit_tab_steps),
    CONSOLE(R.string.cockpit_tab_console),
    TIMING(R.string.cockpit_tab_timing)
}

private val StageHeight = 205.dp

/**
 * Zero-scroll clinical cockpit for one view, shared by the view-detail screen and the guided exam:
 * slim 48 dp top bar → 205 dp split stage (cine loop 52 % | diagram 48 %) → 54 dp ECG/transport bar →
 * a three-tab lower deck (steps · console · timing & probe) that fills the remaining height.
 * The Ablauf button opens a drawer with all views; credits live in the (i) dialog.
 */
@Composable
fun ViewCockpit(
    protocol: EchoProtocol,
    view: EchoView,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    navigator: StepNavigator,
    onSelectView: (EchoView) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val player = rememberEchoPlayer()
    val loop = rememberLoopState(player, view.videoAsset)

    var tab by rememberSaveable { mutableStateOf(DeckTab.STEPS) }
    var videoOverlay by rememberSaveable { mutableStateOf(false) }
    var diagramOverlay by rememberSaveable { mutableStateOf(false) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var referenceOpen by rememberSaveable { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ProtocolDrawer(protocol, view, language) { selected ->
                scope.launch { drawerState.close() }
                onSelectView(selected)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EchoBackground)
        ) {
            SlimTopBar(
                title = view.title.get(language),
                language = language,
                onLanguageChange = onLanguageChange,
                onBack = onBack,
                onInfo = { infoOpen = true },
                onDrawer = { scope.launch { drawerState.open() } }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SplitStage(
                    view = view,
                    player = player,
                    loop = loop,
                    caliper = navigator.step?.caliper,
                    videoAttached = !videoOverlay,
                    onOpenVideo = { videoOverlay = true },
                    onOpenDiagram = { diagramOverlay = true },
                    language = language
                )
                EcgTransportBar(player = player, loop = loop, timing = view.ecg)
                DeckTabBar(selected = tab, onSelect = { tab = it })
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        DeckTab.STEPS -> StepCard(view, navigator, language, onOpenReference = { referenceOpen = true })
                        DeckTab.CONSOLE -> EchoControlConsole(view, protocol.consoleControls, language)
                        DeckTab.TIMING -> TimingProbePanel(view, language)
                    }
                }
            }
        }
    }

    if (videoOverlay) VideoOverlay(player, loop, view.videoAsset) { videoOverlay = false }
    if (diagramOverlay) DiagramOverlay(view, navigator.step?.caliper, language) { diagramOverlay = false }
    if (infoOpen) AboutDialog(view = view, language = language, onDismiss = { infoOpen = false })
    if (referenceOpen) {
        ReferenceDialog(
            view = view,
            language = language,
            isChecked = { i -> checked["${view.id}#$i"] == true },
            onToggle = { i, value -> checked["${view.id}#$i"] = value },
            onDismiss = { referenceOpen = false }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Top bar & drawer
// ---------------------------------------------------------------------------------------------

@Composable
private fun SlimTopBar(
    title: String,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onBack: (() -> Unit)?,
    onInfo: () -> Unit,
    onDrawer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cockpit_back), tint = EchoText)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = EchoText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        LanguageMenuButton(language, onLanguageChange, compact = true)
        IconButton(onClick = onInfo, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about_title), tint = EchoMuted)
        }
        IconButton(onClick = onDrawer, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(R.string.guided_protocol_list), tint = EchoTeal)
        }
    }
}

@Composable
private fun ProtocolDrawer(protocol: EchoProtocol, current: EchoView, language: AppLanguage, onSelect: (EchoView) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = EchoSurface, modifier = Modifier.width(300.dp)) {
        Text(
            text = stringResource(R.string.guided_sequence_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = EchoText,
            modifier = Modifier.padding(16.dp)
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            var number = 0
            protocol.windows.forEach { window ->
                Text(
                    text = window.title.get(language).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = EchoMuted,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
                window.views.forEach { v ->
                    number++
                    val active = v.id == current.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) EchoTeal.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { onSelect(v) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NumberBadge(number, if (active) EchoTeal else EchoOrange)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(v.title.get(language), color = EchoText, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(
                                pluralStringResource(R.plurals.guided_steps_count, v.steps.size, v.steps.size),
                                color = EchoMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NumberBadge(number: Int, color: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(number.toString(), color = EchoBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------------------------
// Split stage: cine loop (52 %) | diagram (48 %)
// ---------------------------------------------------------------------------------------------

@Composable
private fun SplitStage(
    view: EchoView,
    player: ExoPlayer,
    loop: LoopState,
    caliper: List<Float>?,
    videoAttached: Boolean,
    onOpenVideo: () -> Unit,
    onOpenDiagram: () -> Unit,
    language: AppLanguage
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StageHeight),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StageCard(modifier = Modifier.weight(0.52f), onClick = onOpenVideo) {
            LoopBox(player, loop, view.videoAsset, attached = videoAttached, modifier = Modifier.fillMaxSize())
            SourceBadge(simulated = view.videoSource.isSimulated, modifier = Modifier.align(Alignment.TopStart).padding(5.dp))
        }
        // A measurement step shows its caliper on the schematic; otherwise the atlas illustration.
        val useSchematic = caliper != null || view.diagramAsset == null
        StageCard(
            modifier = Modifier.weight(0.48f),
            onClick = onOpenDiagram,
            background = if (useSchematic) EchoBackground else AtlasCard
        ) {
            DiagramThumb(
                assetPath = if (useSchematic) view.svgAsset else view.diagramAsset.orEmpty(),
                aspectRatio = if (useSchematic) SVG_WIDTH / SVG_HEIGHT else view.diagramAspect,
                caliper = if (useSchematic) caliper else null,
                contentDescription = view.title.get(language)
            )
        }
    }
}

@Composable
private fun StageCard(
    modifier: Modifier,
    onClick: () -> Unit,
    background: Color = Color.Black,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, EchoOutline, RoundedCornerShape(12.dp))
    ) {
        content()
        // Transparent tap layer above the (Android) video view so the whole card opens the overlay.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick)
        )
        Icon(
            Icons.Filled.OpenInFull,
            contentDescription = stringResource(R.string.cockpit_enlarge),
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(4.dp)
        )
    }
}

@Composable
private fun SourceBadge(simulated: Boolean, modifier: Modifier = Modifier) {
    val color = if (simulated) EchoOrange else EchoTeal
    Text(
        text = stringResource(if (simulated) R.string.badge_simulated else R.string.badge_real),
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** Non-interactive diagram preview fitted into its card, with an optional caliper on SVG diagrams. */
@Composable
private fun DiagramThumb(assetPath: String, aspectRatio: Float, caliper: List<Float>?, contentDescription: String) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext).components { add(SvgDecoder.Factory()) }.build()
    }
    val isSvg = assetPath.endsWith(".svg", ignoreCase = true)
    Box(modifier = Modifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/$assetPath")
                    .apply { if (isSvg) decoderFactory(SvgDecoder.Factory()) }
                    .build(),
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            if (isSvg && caliper != null && caliper.size == 4) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sx = size.width / SVG_WIDTH
                    val sy = size.height / SVG_HEIGHT
                    val a = Offset(caliper[0] * sx, caliper[1] * sy)
                    val b = Offset(caliper[2] * sx, caliper[3] * sy)
                    drawLine(CaliperRed, a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val len = sqrt(dx * dx + dy * dy)
                    if (len > 0f) {
                        val t = 4.dp.toPx()
                        val px = -dy / len * t
                        val py = dx / len * t
                        listOf(a, b).forEach { p ->
                            drawLine(CaliperRed, Offset(p.x - px, p.y - py), Offset(p.x + px, p.y + py), strokeWidth = 2.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}

private val CaliperRed = Color(0xFFFF4D6D)

// ---------------------------------------------------------------------------------------------
// Full-screen overlays
// ---------------------------------------------------------------------------------------------

@Composable
private fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EchoBackground)
                .systemBarsPadding()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.about_close), tint = EchoText)
                }
            }
            content()
        }
    }
}

/** Pinch-to-zoom container: two fingers zoom, one finger pans while zoomed, double-tap toggles 2.5×. */
@Composable
private fun ZoomBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(raw: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(raw.x.coerceIn(-mx, mx), raw.y.coerceIn(-my, my))
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2 || scale > 1f) {
                            val s = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = clamp(offset + event.calculatePan(), s)
                            scale = s
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        offset = clamp((Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f, 2.5f)
                    }
                })
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            content = content
        )
    }
}

@Composable
private fun VideoOverlay(player: ExoPlayer, loop: LoopState, assetPath: String, onDismiss: () -> Unit) {
    FullScreenDialog(onDismiss) {
        ZoomBox(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LoopBox(player, loop, assetPath, attached = true, modifier = Modifier.fillMaxSize(), compactFallback = false)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(onClick = { loop.stepFrame(forward = false) }, enabled = loop.ready) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_step_back))
                    }
                    FilledIconButton(onClick = { loop.togglePlay() }, enabled = loop.ready) {
                        Icon(
                            if (loop.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (loop.isPlaying) R.string.player_pause else R.string.player_play)
                        )
                    }
                    OutlinedIconButton(onClick = { loop.stepFrame(forward = true) }, enabled = loop.ready) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_step_forward))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.25f, 0.5f, 1.0f).forEach { option ->
                        FilterChip(
                            selected = loop.speed == option,
                            enabled = loop.ready,
                            onClick = { loop.setPlaybackSpeed(option) },
                            label = { Text("${option}x", fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
        Text(stringResource(R.string.zoom_hint), style = MaterialTheme.typography.labelSmall, color = EchoMuted)
    }
}

@Composable
private fun DiagramOverlay(view: EchoView, caliper: List<Float>?, language: AppLanguage, onDismiss: () -> Unit) {
    val atlas = view.diagramAsset
    var showAtlas by rememberSaveable { mutableStateOf(atlas != null && caliper == null) }
    FullScreenDialog(onDismiss) {
        if (atlas != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to R.string.anatomy_atlas, false to R.string.anatomy_schematic).forEach { (isAtlas, label) ->
                    FilterChip(
                        selected = showAtlas == isAtlas,
                        onClick = { showAtlas = isAtlas },
                        label = { Text(stringResource(label)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EchoTeal, selectedLabelColor = EchoBackground)
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (showAtlas && atlas != null) {
                ZoomableDiagram(
                    assetPath = atlas,
                    contentDescription = view.title.get(language),
                    aspectRatio = view.diagramAspect,
                    background = AtlasCard
                )
            } else {
                ZoomableSvgDiagram(svgAsset = view.svgAsset, contentDescription = view.title.get(language), caliper = caliper)
            }
        }
        if (!showAtlas || atlas == null) LegendRow()
        Text(stringResource(R.string.zoom_hint), style = MaterialTheme.typography.labelSmall, color = EchoMuted)
    }
}

/** Full reference tables (adult ED/ES & pediatric) and the clinical checklist for the view. */
@Composable
private fun ReferenceDialog(
    view: EchoView,
    language: AppLanguage,
    isChecked: (Int) -> Boolean,
    onToggle: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var showChecklist by rememberSaveable { mutableStateOf(false) }
    FullScreenDialog(onDismiss) {
        Text(view.title.get(language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = EchoText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to R.string.tab_measurements, true to R.string.tab_checklist).forEach { (checklist, label) ->
                FilterChip(
                    selected = showChecklist == checklist,
                    onClick = { showChecklist = checklist },
                    label = { Text(stringResource(label)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EchoTeal, selectedLabelColor = EchoBackground)
                )
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (showChecklist) ChecklistPanel(view, language, isChecked, onToggle) else MeasurementsPanel(view, language)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lower deck
// ---------------------------------------------------------------------------------------------

@Composable
private fun DeckTabBar(selected: DeckTab, onSelect: (DeckTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(EchoSurface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        DeckTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) EchoTeal.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    color = if (active) EchoTeal else EchoMuted,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DeckCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(EchoSurface)
            .border(1.dp, EchoOutline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/** Tab 1: the active step with its normal-range badge and inline step navigation. */
@Composable
private fun StepCard(view: EchoView, nav: StepNavigator, language: AppLanguage, onOpenReference: () -> Unit) {
    var noteOpen by remember { mutableStateOf(false) }
    val step = nav.step
    DeckCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = step?.title?.get(language) ?: view.title.get(language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = EchoTeal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (step?.note != null) {
                SmallPill(stringResource(R.string.guided_note), EchoOrange) { noteOpen = true }
                Spacer(Modifier.width(6.dp))
            }
            SmallPill(stringResource(R.string.cockpit_reference), EchoSky, onClick = onOpenReference)
        }
        // Long instructions wrap inside the card; the card itself never pushes the page to scroll.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                text = step?.instruction?.get(language) ?: view.probePosition.get(language),
                style = MaterialTheme.typography.bodyMedium,
                color = EchoText
            )
        }
        step?.normal?.let { normal ->
            Text(
                text = stringResource(R.string.guided_normal, normal.get(language)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = EchoTeal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(EchoTeal.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        StepNavBar(nav)
    }
    if (noteOpen && step?.note != null) {
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            confirmButton = { TextButton(onClick = { noteOpen = false }) { Text(stringResource(R.string.guided_done)) } },
            title = { Text(stringResource(R.string.guided_note_title), color = EchoOrange, fontWeight = FontWeight.Bold) },
            text = { Text(step.note.get(language), color = EchoText) },
            containerColor = EchoSurface
        )
    }
}

@Composable
private fun StepNavBar(nav: StepNavigator) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(EchoBackground),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = nav.onBack, enabled = nav.canGoBack, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.guided_previous), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = nav.counter,
            color = EchoMuted,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = nav.onNext, modifier = Modifier.weight(1f)) {
            Text(
                stringResource(if (nav.isLast) R.string.guided_restart else R.string.guided_next),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SmallPill(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

/** Tab 3: probe orientation clock, ED/ES measurement rules and view-specific optimisation tips. */
@Composable
private fun TimingProbePanel(view: EchoView, language: AppLanguage) {
    val ed = view.referenceMeasurements.adult.filter { "ED" in it.phases }.map { it.parameter.get(language) }
    val es = view.referenceMeasurements.adult.filter { "ES" in it.phases }.map { it.parameter.get(language) }
    val tips = view.steps.mapNotNull { it.note?.get(language) }
    DeckCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotchClock(hours = view.notchHours, modifier = Modifier.size(58.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.notch_label, view.notchClock),
                    color = EchoOrange,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    view.probePosition.get(language),
                    color = EchoText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            view.timingNote?.let {
                Text(it.get(language), color = EchoText, style = MaterialTheme.typography.bodySmall)
            }
            if (view.ecg.nominal) {
                Text(stringResource(R.string.ecg_nominal), color = EchoOrange, style = MaterialTheme.typography.labelSmall)
            }
            if (ed.isNotEmpty()) PhaseRule("ED", stringResource(R.string.phase_ed_hint), ed.joinToString(" · "))
            if (es.isNotEmpty()) PhaseRule("ES", stringResource(R.string.phase_es_hint), es.joinToString(" · "))
            if (tips.isNotEmpty()) {
                Text(stringResource(R.string.cockpit_tips), color = EchoSky, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                tips.forEach { tip ->
                    Row {
                        Text("•", color = EchoOrange, modifier = Modifier.width(12.dp))
                        Text(tip, color = EchoText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseRule(phase: String, hint: String, items: String) {
    Row(verticalAlignment = Alignment.Top) {
        PhaseChip(phase)
        Column(modifier = Modifier.weight(1f)) {
            Text(hint, color = EchoMuted, style = MaterialTheme.typography.labelSmall)
            Text(items, color = EchoText, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared building blocks (also used by the reference dialog)
// ---------------------------------------------------------------------------------------------

/** Clock face showing where the transducer index mark (notch) points. */
@Composable
internal fun NotchClock(hours: List<Double>, modifier: Modifier = Modifier) {
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
internal fun LegendRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        listOf(
            Color(0xFFC2475B) to stringResource(R.string.legend_myocardium),
            Color(0xFFB03A4E) to stringResource(R.string.legend_oxygenated),
            Color(0xFF3A5BA8) to stringResource(R.string.legend_deoxygenated),
            EchoAmber to stringResource(R.string.legend_valves),
            Color(0xFFFF4D6D) to stringResource(R.string.legend_doppler),
            EchoOrange to stringResource(R.string.legend_notch)
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

/** Small ED (teal) / ES (orange) chip showing which ECG phase to freeze for a measurement. */
@Composable
internal fun PhaseChip(phase: String) {
    val color = if (phase == "ES") EchoOrange else EchoTeal
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(phase, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun MeasurementsPanel(view: EchoView, language: AppLanguage) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EchoCard {
            Text(
                text = stringResource(R.string.adult_reference),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = EchoTeal
            )
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PhaseChip("ED")
                Text(stringResource(R.string.phase_ed_hint), style = MaterialTheme.typography.labelSmall, color = EchoMuted)
                Spacer(Modifier.width(10.dp))
                PhaseChip("ES")
                Text(stringResource(R.string.phase_es_hint), style = MaterialTheme.typography.labelSmall, color = EchoMuted)
            }
            view.referenceMeasurements.adult.forEachIndexed { index, m ->
                if (index > 0) HorizontalDivider(color = EchoOutline)
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = m.parameter.get(language),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = EchoText,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = m.normal.get(language),
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoTeal,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (m.phases.isNotEmpty()) {
                        Row(modifier = Modifier.padding(top = 2.dp)) { m.phases.forEach { PhaseChip(it) } }
                    }
                    val note = m.note?.get(language).orEmpty()
                    if (note.isNotBlank()) {
                        Text(
                            text = note,
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
                        text = p.parameter.get(language),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = EchoText
                    )
                    Text(
                        text = p.focus.get(language),
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoMuted
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChecklistPanel(
    view: EchoView,
    language: AppLanguage,
    isChecked: (Int) -> Boolean,
    onToggle: (Int, Boolean) -> Unit
) {
    val total = view.checklist.size
    val done = view.checklist.indices.count { isChecked(it) }
    EchoCard {
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
                        text = item.get(language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (itemChecked) EchoMuted else EchoText,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

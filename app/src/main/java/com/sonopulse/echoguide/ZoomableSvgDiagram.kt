package com.sonopulse.echoguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlin.math.sqrt

/** Coordinate space (viewBox) of every bundled anatomy SVG. */
const val SVG_WIDTH = 600f
const val SVG_HEIGHT = 420f

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private val CaliperColor = Color(0xFFFF4D6D)

/**
 * Anatomy diagram with pinch-to-zoom and pan.
 *
 * Two fingers zoom (1×–5×); one finger pans only while zoomed in, so the surrounding page keeps
 * scrolling normally at 1×. Double-tap toggles 2.5× at the tapped point. An optional measurement
 * [caliper] (`[x1, y1, x2, y2]` in SVG units) is drawn in the same transformed layer, so it stays
 * on its structure while zooming. Diagrams are never mirrored in RTL layouts.
 */
@Composable
fun ZoomableSvgDiagram(
    svgAsset: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    caliper: List<Float>? = null
) = ZoomableDiagram(
    assetPath = svgAsset,
    contentDescription = contentDescription,
    modifier = modifier,
    aspectRatio = SVG_WIDTH / SVG_HEIGHT,
    caliper = caliper
)

/**
 * Zoomable asset image (SVG or raster such as PNG). [caliper] coordinates are only meaningful
 * for the 600x420 SVG diagrams. [background] fills letter-boxing around raster images.
 */
@Composable
fun ZoomableDiagram(
    assetPath: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    aspectRatio: Float = SVG_WIDTH / SVG_HEIGHT,
    caliper: List<Float>? = null,
    background: Color = Color.Unspecified,
    /** Normalised measurements drawn on schema / atlas images, coordinates picked by [overlayCoords]. */
    overlays: List<CaliperOverlay> = emptyList(),
    overlayCoords: (CaliperOverlay) -> List<Float>? = { it.diagram },
    /** Shown instead of the asset path when the image cannot be loaded. */
    fallback: (@Composable () -> Unit)? = null
) {
    val svgAsset = assetPath
    val isSvg = assetPath.endsWith(".svg", ignoreCase = true)
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    var scale by remember(svgAsset) { mutableFloatStateOf(1f) }
    var offset by remember(svgAsset) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(raw: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(14.dp))
            .background(if (background != Color.Unspecified) background else MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .onSizeChanged { size = it }
            .pointerInput(svgAsset) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            offset = clampOffset(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(svgAsset) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        scale = DOUBLE_TAP_ZOOM
                        offset = clampOffset((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                    }
                })
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/$svgAsset")
                    .apply { if (isSvg) decoderFactory(SvgDecoder.Factory()) }
                    .build(),
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                contentScale = if (isSvg) ContentScale.FillBounds else ContentScale.Fit,
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                },
                error = {
                    if (fallback != null) {
                        fallback()
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("assets/$svgAsset", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (isSvg && caliper != null && caliper.size == 4) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawCaliper(caliper, scale) }
            }
            if (overlays.isNotEmpty()) {
                CaliperCanvas(overlays, overlayCoords, aspect = 0f, modifier = Modifier.fillMaxSize(), zoom = scale)
            }
        }

        if (scale > 1f) {
            FilledTonalIconButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(34.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                )
            ) {
                Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.zoom_reset), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Caliper line with perpendicular end ticks; stroke width stays constant on screen while zoomed. */
private fun DrawScope.drawCaliper(caliper: List<Float>, zoom: Float) {
    val sx = size.width / SVG_WIDTH
    val sy = size.height / SVG_HEIGHT
    val a = Offset(caliper[0] * sx, caliper[1] * sy)
    val b = Offset(caliper[2] * sx, caliper[3] * sy)
    val stroke = 2.5.dp.toPx() / zoom
    drawLine(CaliperColor, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = sqrt(dx * dx + dy * dy)
    if (length > 0f) {
        val tick = 6.dp.toPx() / zoom
        val px = -dy / length * tick
        val py = dx / length * tick
        listOf(a, b).forEach { p ->
            drawLine(CaliperColor, Offset(p.x - px, p.y - py), Offset(p.x + px, p.y + py), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

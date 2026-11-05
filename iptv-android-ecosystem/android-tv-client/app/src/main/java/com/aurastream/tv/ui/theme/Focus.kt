package com.aurastream.tv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape

/** Shared focus styling tokens: neon cyan ring + subtle scale-up. */
object AuraFocus {
    val CornerRadius = 12.dp
    val Shape = RoundedCornerShape(CornerRadius)
    const val FOCUSED_SCALE = 1.06f
    val RingWidth = 3.dp

    fun shape(shape: Shape = Shape): ClickableSurfaceShape = ClickableSurfaceDefaults.shape(shape = shape)

    fun scale(focused: Float = FOCUSED_SCALE): ClickableSurfaceScale =
        ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = focused, pressedScale = focused - 0.03f)

    fun border(shape: Shape = Shape, width: Dp = RingWidth): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
        focusedBorder = Border(border = BorderStroke(width, AuraColors.NeonCyan), shape = shape),
        pressedBorder = Border(border = BorderStroke(width, AuraColors.NeonCyan), shape = shape),
    )

    fun colors(
        container: Color = AuraColors.Surface,
        focusedContainer: Color = AuraColors.SurfaceHighest,
        content: Color = AuraColors.TextPrimary,
        focusedContent: Color = AuraColors.TextPrimary,
    ): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = container,
        contentColor = content,
        focusedContainerColor = focusedContainer,
        focusedContentColor = focusedContent,
        pressedContainerColor = focusedContainer,
        pressedContentColor = focusedContent,
    )
}

/**
 * Focus ring + scale for arbitrary (non tv-material) focusable composables.
 * Apply *before* `.focusable()` / `.clickable()` in the modifier chain.
 */
fun Modifier.auraFocusRing(
    shape: Shape = AuraFocus.Shape,
    focusedScale: Float = AuraFocus.FOCUSED_SCALE,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, animationSpec = spring(stiffness = 600f), label = "focusScale")
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .scale(scale)
        .border(
            width = AuraFocus.RingWidth,
            color = if (focused) AuraColors.NeonCyan else Color.Transparent,
            shape = shape,
        )
}

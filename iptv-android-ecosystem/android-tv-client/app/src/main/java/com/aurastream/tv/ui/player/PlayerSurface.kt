@file:OptIn(UnstableApi::class)

package com.aurastream.tv.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.aurastream.tv.data.local.AspectMode
import com.aurastream.tv.player.Media3PlayerAdapter

/**
 * Hosts Media3's [PlayerView] (video surface + subtitle renderer) inside Compose.
 * Controls are drawn in Compose (OSD), so the built-in controller is disabled and the
 * view is made non-focusable to keep D-pad focus in the Compose hierarchy.
 */
@Composable
fun PlayerSurface(adapter: Media3PlayerAdapter, modifier: Modifier = Modifier, showSubtitles: Boolean = true) {
    val player by adapter.player.collectAsStateWithLifecycle()
    val state by adapter.state.collectAsStateWithLifecycle()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(Color.BLACK)
                setKeepContentOnPlayerReset(true)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // buffering spinner is drawn in Compose
                subtitleView?.apply {
                    setApplyEmbeddedStyles(true)
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(0.0533f * 1.15f)
                    setStyle(
                        CaptionStyleCompat(
                            Color.WHITE,
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            Color.BLACK,
                            null,
                        ),
                    )
                }
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode = when (state.aspectMode) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            view.subtitleView?.visibility = if (showSubtitles) android.view.View.VISIBLE else android.view.View.GONE
        },
        onRelease = { view -> view.player = null },
    )
}

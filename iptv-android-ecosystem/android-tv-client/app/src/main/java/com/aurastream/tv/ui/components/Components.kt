package com.aurastream.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.SubcomposeAsyncImage
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus

/** Primary / secondary pill button with neon focus ring and scale. */
@Composable
fun AuraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier,
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.05f),
        border = AuraFocus.border(shape),
        colors = if (primary) {
            AuraFocus.colors(
                container = AuraColors.NeonCyan,
                focusedContainer = AuraColors.NeonCyan,
                content = AuraColors.OnNeon,
                focusedContent = AuraColors.OnNeon,
            )
        } else {
            AuraFocus.colors(container = AuraColors.SurfaceHigh, focusedContainer = AuraColors.TextPrimary, focusedContent = AuraColors.Canvas)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Channel / poster image with graceful initials fallback. */
@Composable
fun RemoteImage(
    url: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
) {
    Box(modifier.clip(shape).background(AuraColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Initials(fallbackText)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = fallbackText,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { Initials(fallbackText) },
                error = { Initials(fallbackText) },
            )
        }
    }
}

@Composable
private fun Initials(text: String) {
    val initials = text.trim().split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.titleMedium, color = AuraColors.TextSecondary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "LIVE") {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AuraColors.Live)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color = AuraColors.TextSecondary) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AuraColors.SurfaceHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Thin horizontal progress bar (EPG progress, resume position, import progress). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color = AuraColors.NeonCyan) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(AuraColors.Outline),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = AuraColors.TextPrimary,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun CenteredMessage(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AuraColors.NeonCyan, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        androidx.compose.material3.CircularProgressIndicator(color = AuraColors.NeonCyan, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        if (label != null) {
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
        }
    }
}

/** Static (non-focusable) card container. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(20.dp), content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = AuraColors.Surface),
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

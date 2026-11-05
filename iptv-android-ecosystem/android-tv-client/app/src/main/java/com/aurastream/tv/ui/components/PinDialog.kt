package com.aurastream.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus

/**
 * Remote-friendly 4-digit PIN entry: on-screen keypad navigable with the D-pad,
 * plus direct digit input from remotes / keyboards that have number keys.
 */
@Composable
fun PinDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    fun append(d: Char) {
        if (pin.length >= PIN_LENGTH) return
        pin += d
        error = false
        if (pin.length == PIN_LENGTH) {
            if (!onSubmit(pin)) {
                error = true
                pin = ""
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AuraColors.Scrim)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = digitFor(ev.key)
                    when {
                        digit != null -> { append(digit); true }
                        ev.key == Key.Backspace || ev.key == Key.Delete -> { pin = pin.dropLast(1); true }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AuraColors.Surface)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Lock, null, tint = AuraColors.NeonCyan, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(PIN_LENGTH) { i ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        error -> AuraColors.Error
                                        i < pin.length -> AuraColors.NeonCyan
                                        else -> AuraColors.Outline
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (error) stringResource(R.string.pin_wrong) else stringResource(R.string.pin_hint),
                    color = if (error) AuraColors.Error else AuraColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(20.dp))
                // Keypad always laid out LTR (like phone dial pads) even in Arabic.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("123", "456", "789", "<0").forEachIndexed { rowIndex, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEachIndexed { colIndex, c ->
                                    val modifier = if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstKey) else Modifier
                                    KeypadKey(
                                        modifier = modifier,
                                        onClick = { if (c == '<') pin = pin.dropLast(1) else append(c) },
                                    ) {
                                        if (c == '<') {
                                            Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.delete), modifier = Modifier.size(22.dp))
                                        } else {
                                            Text(c.toString(), style = MaterialTheme.typography.headlineSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
}

@Composable
private fun KeypadKey(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 72.dp, height = 56.dp),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.08f),
        border = AuraFocus.border(shape),
        colors = AuraFocus.colors(container = AuraColors.SurfaceHigh),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

private fun digitFor(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

const val PIN_LENGTH = 4

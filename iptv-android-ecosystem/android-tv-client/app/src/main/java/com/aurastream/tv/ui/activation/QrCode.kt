package com.aurastream.tv.ui.activation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Crisp vector QR code: the bit matrix is generated once at its native module
 * resolution and drawn as rectangles, so it scales to any TV resolution without blur.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    val matrix = remember(content) { encode(content) }
    Canvas(modifier) {
        drawRect(background)
        if (matrix == null) return@Canvas
        val cells = matrix.width
        val cell = size.minDimension / cells
        val offsetX = (size.width - cell * cells) / 2f
        val offsetY = (size.height - cell * cells) / 2f
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                if (matrix[x, y]) {
                    // +0.5px overlap avoids hairline gaps between modules on fractional densities
                    drawRect(foreground, Offset(offsetX + x * cell, offsetY + y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private fun encode(content: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
}.getOrNull()

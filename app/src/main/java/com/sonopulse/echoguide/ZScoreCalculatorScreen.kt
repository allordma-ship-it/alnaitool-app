package com.sonopulse.echoguide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Allometric reference model for one cardiac structure.
 *
 * Predicted mean (mm) = [a] · BSA^[b]; the measurement is assumed log-normally distributed,
 * so Z = (ln(measured) − ln(predicted)) / [sdLn].
 */
enum class CardiacStructure(
    val displayName: String,
    val a: Double,
    val b: Double,
    val sdLn: Double
) {
    AORTIC_ANNULUS("Aortic Valve Annulus", a = 14.8, b = 0.50, sdLn = 0.085),
    AORTIC_SINUSES("Aortic Sinuses of Valsalva", a = 21.2, b = 0.50, sdLn = 0.085),
    LVIDD("LVIDd", a = 39.5, b = 0.35, sdLn = 0.075),
    IVSD("IVSd", a = 6.9, b = 0.35, sdLn = 0.14),
    MAIN_PA("Main Pulmonary Artery", a = 17.5, b = 0.50, sdLn = 0.10);

    fun predictedMeanMm(bsa: Double): Double = a * bsa.pow(b)

    fun zScore(measuredMm: Double, bsa: Double): Double =
        (ln(measuredMm) - ln(predictedMeanMm(bsa))) / sdLn

    /** Value (mm) that corresponds to the given Z-score at this BSA. */
    fun valueAtZ(z: Double, bsa: Double): Double = predictedMeanMm(bsa) * exp(z * sdLn)
}

enum class ZInterpretation { NORMAL, BORDERLINE, SIGNIFICANT }

object ZScoreMath {
    /** Haycock formula: BSA (m²) = 0.024265 × W(kg)^0.5378 × H(cm)^0.3964. */
    fun haycockBsa(weightKg: Double, heightCm: Double): Double =
        0.024265 * weightKg.pow(0.5378) * heightCm.pow(0.3964)

    fun interpret(z: Double): ZInterpretation = when {
        abs(z) <= 2.0 -> ZInterpretation.NORMAL
        abs(z) <= 3.0 -> ZInterpretation.BORDERLINE
        else -> ZInterpretation.SIGNIFICANT
    }
}

private val ZTeal = Color(0xFF00D2B8)
private val ZAmber = Color(0xFFFBBF24)
private val ZRed = Color(0xFFF87171)

private fun String.toPositiveDoubleOrNull(): Double? =
    replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

private fun Double.fmt(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZScoreCalculatorScreen(modifier: Modifier = Modifier) {
    var weightText by rememberSaveable { mutableStateOf("") }
    var heightText by rememberSaveable { mutableStateOf("") }
    var measuredText by rememberSaveable { mutableStateOf("") }
    var structureName by rememberSaveable { mutableStateOf(CardiacStructure.AORTIC_ANNULUS.name) }
    var menuExpanded by remember { mutableStateOf(false) }

    val structure = CardiacStructure.valueOf(structureName)
    val weight = weightText.toPositiveDoubleOrNull()?.takeIf { it <= 250.0 }
    val height = heightText.toPositiveDoubleOrNull()?.takeIf { it <= 250.0 }
    val measured = measuredText.toPositiveDoubleOrNull()?.takeIf { it <= 120.0 }
    val bsa = if (weight != null && height != null) ZScoreMath.haycockBsa(weight, height) else null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Calculate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.zscore_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        EchoCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = stringResource(R.string.zscore_weight),
                    isError = weightText.isNotBlank() && weight == null,
                    modifier = Modifier.weight(1f)
                )
                NumberField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = stringResource(R.string.zscore_height),
                    isError = heightText.isNotBlank() && height == null,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            ResultRow(
                label = stringResource(R.string.zscore_bsa),
                value = bsa?.let { "${it.fmt(3)} m²" } ?: "—"
            )
        }

        EchoCard {
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it }
            ) {
                OutlinedTextField(
                    value = structure.displayName,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.zscore_structure)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    CardiacStructure.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                structureName = option.name
                                menuExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NumberField(
                value = measuredText,
                onValueChange = { measuredText = it },
                label = stringResource(R.string.zscore_measured),
                isError = measuredText.isNotBlank() && measured == null,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ResultCard(structure = structure, bsa = bsa, measured = measured)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                weightText = ""
                heightText = ""
                measuredText = ""
                structureName = CardiacStructure.AORTIC_ANNULUS.name
            }) {
                Text(stringResource(R.string.zscore_reset))
            }
        }

        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.zscore_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ResultCard(structure: CardiacStructure, bsa: Double?, measured: Double?) {
    EchoCard {
        if (bsa == null) {
            Text(
                text = stringResource(R.string.zscore_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@EchoCard
        }

        val predicted = structure.predictedMeanMm(bsa)
        ResultRow(stringResource(R.string.zscore_predicted), "${predicted.fmt(1)} mm")
        ResultRow(
            stringResource(R.string.zscore_range),
            "${structure.valueAtZ(-2.0, bsa).fmt(1)} – ${structure.valueAtZ(2.0, bsa).fmt(1)} mm"
        )

        if (measured == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.zscore_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@EchoCard
        }

        val z = structure.zScore(measured, bsa)
        val interpretation = ZScoreMath.interpret(z)
        val (color, labelRes) = when (interpretation) {
            ZInterpretation.NORMAL -> ZTeal to R.string.zscore_normal
            ZInterpretation.BORDERLINE -> ZAmber to R.string.zscore_borderline
            ZInterpretation.SIGNIFICANT -> ZRed to R.string.zscore_significant
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.zscore_value),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = (if (z > 0) "+" else "") + z.fmt(2),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = color
                )
            }
            Box(
                modifier = Modifier
                    .background(color.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                    .border(1.dp, color, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(color, CircleShape)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = color
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${structure.displayName}: ${measured.fmt(1)} mm @ BSA ${bsa.fmt(2)} m²",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            if (input.length <= 7 && input.all { it.isDigit() || it == '.' || it == ',' }) {
                onValueChange(input)
            }
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = modifier
    )
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Standard dark card used across the app (`#111824`). */
@Composable
fun EchoCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

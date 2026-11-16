package com.sonopulse.echoguide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.pow

// ---------------------------------------------------------------------------------------------
// Clinical echo calculations (ASE / EACVI 2009, 2016, 2017; ESC/ERS 2022 pulmonary hypertension)
// ---------------------------------------------------------------------------------------------

enum class Severity { NORMAL, MILD, MODERATE, SEVERE, INDETERMINATE }

/** Aortic valve area by the continuity equation. */
data class ContinuityResult(val lvotAreaCm2: Double, val strokeVolumeMl: Double, val avaCm2: Double, val dvi: Double, val severity: Severity)

/** Right-atrial pressure from the IVC and the systolic PA / RV pressure from the TR jet. */
data class RvspResult(val rapMmHg: Int, val gradientMmHg: Double, val rvspMmHg: Double, val phProbability: Severity)

/** Diastolic function (ASE/EACVI 2016 algorithm, LV filling pressure). */
data class DiastolicResult(
    val eOverA: Double,
    val eOverEPrimeAverage: Double,
    val grade: Int?,                    // 1, 2, 3 or null = indeterminate
    val criteriaPositive: Int,
    val criteriaAvailable: Int
)

data class MvaResult(val mvaCm2: Double, val severity: Severity)

object EchoCalc {
    /** LVOT area = π (D/2)², SV = area × LVOT VTI, AVA = SV / AV VTI (cm, cm², ml). */
    fun continuity(lvotDiameterCm: Double, lvotVtiCm: Double, avVtiCm: Double): ContinuityResult {
        val area = PI * (lvotDiameterCm / 2).pow(2)
        val sv = area * lvotVtiCm
        val ava = sv / avVtiCm
        return ContinuityResult(area, sv, ava, lvotVtiCm / avVtiCm, avaSeverity(ava))
    }

    /** Normal > 2.0, mild 1.5–2.0, moderate 1.0–1.5, severe < 1.0 cm². */
    fun avaSeverity(ava: Double): Severity = when {
        ava > 2.0 -> Severity.NORMAL
        ava >= 1.5 -> Severity.MILD
        ava >= 1.0 -> Severity.MODERATE
        else -> Severity.SEVERE
    }

    /**
     * RAP (ASE 2010 / 2015): IVC ≤ 2.1 cm and collapse > 50 % → 3 mmHg; IVC > 2.1 cm and
     * collapse < 50 % → 15 mmHg; otherwise 8 mmHg.
     */
    fun rightAtrialPressure(ivcCm: Double, collapsePercent: Double): Int = when {
        ivcCm <= 2.1 && collapsePercent > 50 -> 3
        ivcCm > 2.1 && collapsePercent < 50 -> 15
        else -> 8
    }

    /** RVSP = 4 · TR Vmax² + RAP; PH probability by TR Vmax (ESC/ERS 2022: ≤ 2.8, 2.9–3.4, > 3.4 m/s). */
    fun rvsp(trVmax: Double, ivcCm: Double, collapsePercent: Double): RvspResult {
        val rap = rightAtrialPressure(ivcCm, collapsePercent)
        val gradient = 4 * trVmax.pow(2)
        val probability = when {
            trVmax <= 2.8 -> Severity.NORMAL
            trVmax <= 3.4 -> Severity.MODERATE
            else -> Severity.SEVERE
        }
        return RvspResult(rap, gradient, gradient + rap, probability)
    }

    /**
     * ASE/EACVI 2016 (Nagueh): E/A ≤ 0.8 with E ≤ 50 cm/s → grade I; E/A ≥ 2 → grade III;
     * otherwise count average E/e' > 14, TR Vmax > 2.8 m/s and LAVI > 34 ml/m² (optional
     * inputs): ≥ 2 of 3 (or 2 of 2) positive → grade II, ≤ 1 of 3 (0 of 2) → grade I, else indeterminate.
     */
    fun diastolic(eCmS: Double, aCmS: Double, ePrimeSeptal: Double, ePrimeLateral: Double, trVmax: Double? = null, lavi: Double? = null): DiastolicResult {
        val ea = eCmS / aCmS
        val eeAvg = eCmS / ((ePrimeSeptal + ePrimeLateral) / 2)
        val criteria = listOfNotNull(eeAvg > 14, trVmax?.let { it > 2.8 }, lavi?.let { it > 34 })
        val positive = criteria.count { it }
        val grade = when {
            ea >= 2 -> 3
            ea <= 0.8 && eCmS <= 50 -> 1
            criteria.size >= 3 -> if (positive >= 2) 2 else 1
            criteria.size == 2 -> when (positive) { 2 -> 2; 0 -> 1; else -> null }
            else -> null
        }
        return DiastolicResult(ea, eeAvg, grade, positive, criteria.size)
    }

    /** MVA = 220 / PHT; mild > 1.5, moderate 1.0–1.5, severe < 1.0 cm² (ASE/EAE 2009). */
    fun mvaPht(phtMs: Double): MvaResult {
        val mva = 220.0 / phtMs
        val severity = when {
            mva > 1.5 -> Severity.MILD
            mva >= 1.0 -> Severity.MODERATE
            else -> Severity.SEVERE
        }
        return MvaResult(mva, severity)
    }
}

// ---------------------------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------------------------

/** Calculator tabs; the step chips in the cockpit open the matching one. */
enum class EchoCalculator(val label: LocalizedText) {
    ZSCORE(LocalizedText("Z-Score", "Z-Score", "Z-Score")),
    AVA(LocalizedText("AVA (continuity)", "KÖF (Kontinuität)", "مساحة الأبهري")),
    RVSP(LocalizedText("sPAP / RVSP", "sPAP / RVSP", "ضغط الرئوي")),
    DIASTOLE(LocalizedText("Diastology", "Diastologie", "الوظيفة الانبساطية")),
    MVA(LocalizedText("MVA (PHT)", "MÖF (PHT)", "مساحة التاجي"));
}

/** Calculator a guided step links to (by its caliper / Doppler label), if any. */
fun calculatorForStep(step: GuideStep?): EchoCalculator? {
    val labels = step?.overlays.orEmpty().map { it.label }
    return when {
        labels.any { it.contains("LVOT") || it.contains("CW · AV") } -> EchoCalculator.AVA
        labels.any { it.contains("TR") || it.contains("IVC") } -> EchoCalculator.RVSP
        labels.any { it.contains("E/A") || it.startsWith("e'") } -> EchoCalculator.DIASTOLE
        else -> null
    }
}

private fun t(en: String, de: String, ar: String) = LocalizedText(en, de, ar)

private fun Double.f(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)

private fun String.num(): Double? = normalizeNumberInput(this).trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

@Composable
private fun severityColor(s: Severity): Color = when (s) {
    Severity.NORMAL -> EchoNormal
    Severity.MILD -> EchoPrimary
    Severity.MODERATE -> EchoCaution
    Severity.SEVERE -> EchoCritical
    Severity.INDETERMINATE -> EchoCaution
}

/** Bottom-navigation screen: tabs for Z-Score and the four echo calculators. */
@Composable
fun ClinicalCalculatorsScreen(language: AppLanguage, modifier: Modifier = Modifier, initial: EchoCalculator = EchoCalculator.ZSCORE) {
    var tabName by rememberSaveable { mutableStateOf(initial.name) }
    val tab = EchoCalculator.valueOf(tabName)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EchoCalculator.entries.forEach { c ->
                FilterChip(
                    selected = c == tab,
                    onClick = { tabName = c.name },
                    label = { Text(c.label.get(language), fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EchoPrimaryFill,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
        when (tab) {
            EchoCalculator.ZSCORE -> ZScoreCalculatorScreen(modifier = Modifier.weight(1f))
            else -> CalculatorBody(tab, language, Modifier.weight(1f))
        }
    }
}

/** One echo calculator (AVA, RVSP, diastology, MVA) – also shown in a dialog from a measurement step. */
@Composable
fun CalculatorBody(calculator: EchoCalculator, language: AppLanguage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (calculator) {
            EchoCalculator.AVA -> AvaCalculator(language)
            EchoCalculator.RVSP -> RvspCalculator(language)
            EchoCalculator.DIASTOLE -> DiastoleCalculator(language)
            EchoCalculator.MVA -> MvaCalculator(language)
            EchoCalculator.ZSCORE -> ZScoreCalculatorScreen()
        }
        Text(
            t("Orientation aid only – interpret with the full study and current guidelines (ASE/EACVI, ESC).",
                "Nur Orientierungshilfe – Bewertung immer im Gesamtbefund und nach aktuellen Leitlinien (ASE/EACVI, ESC).",
                "أداة مساعدة فقط – يُفسَّر ضمن الفحص الكامل ووفق الإرشادات الحالية (ASE/EACVI, ESC).").get(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AvaCalculator(language: AppLanguage) {
    var d by rememberSaveable { mutableStateOf("") }
    var lvot by rememberSaveable { mutableStateOf("") }
    var av by rememberSaveable { mutableStateOf("") }
    CalcTitle(t("Aortic valve area – continuity equation", "Aortenklappenöffnungsfläche – Kontinuitätsgleichung", "مساحة الصمام الأبهري – معادلة الاستمرارية"), language,
        "SV = π·(D/2)²·VTI(LVOT)   ·   AVA = SV / VTI(AV)")
    EchoCard {
        CalcField(d, { d = it }, "LVOT Diameter (cm)", d.isNotBlank() && d.num()?.takeIf { it in 1.0..4.0 } == null)
        CalcField(lvot, { lvot = it }, "LVOT VTI (cm)", lvot.isNotBlank() && lvot.num() == null)
        CalcField(av, { av = it }, "AV VTI (cm)", av.isNotBlank() && av.num() == null, ImeAction.Done)
    }
    val dv = d.num()?.takeIf { it in 1.0..4.0 }
    val lv = lvot.num()
    val avv = av.num()
    EchoCard {
        if (dv == null || lv == null || avv == null) {
            Hint(t("Enter LVOT diameter (PLAX, mid-systole), LVOT VTI (PW, A5C) and AV VTI (CW).",
                "LVOT-Durchmesser (PLAX, Mesosystole), LVOT-VTI (PW, A5K) und AV-VTI (CW) eingeben.",
                "أدخل قطر LVOT (PLAX، منتصف الانقباض) وVTI في LVOT (PW) وVTI الأبهري (CW)."), language)
        } else {
            val r = EchoCalc.continuity(dv, lv, avv)
            Row2("LVOT area", "${r.lvotAreaCm2.f(2)} cm²")
            Row2(t("Stroke volume (SV)", "Schlagvolumen (SV)", "حجم الضربة (SV)").get(language), "${r.strokeVolumeMl.f(0)} ml")
            Row2("DVI (VTI LVOT / AV)", r.dvi.f(2) + if (r.dvi < 0.25) "  (< 0.25)" else "")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BigResult("AVA", "${r.avaCm2.f(2)} cm²", r.severity, when (r.severity) {
                Severity.NORMAL -> t("Normal (> 2.0 cm²)", "Normal (> 2,0 cm²)", "طبيعي (> 2.0 سم²)")
                Severity.MILD -> t("Mild stenosis (1.5–2.0 cm²)", "Leichte Stenose (1,5–2,0 cm²)", "تضيق خفيف (1.5–2.0 سم²)")
                Severity.MODERATE -> t("Moderate stenosis (1.0–1.5 cm²)", "Mittelgradige Stenose (1,0–1,5 cm²)", "تضيق متوسط (1.0–1.5 سم²)")
                else -> t("Severe stenosis (< 1.0 cm²)", "Schwere Stenose (< 1,0 cm²)", "تضيق شديد (< 1.0 سم²)")
            }, language)
        }
    }
}

@Composable
private fun RvspCalculator(language: AppLanguage) {
    var v by rememberSaveable { mutableStateOf("") }
    var ivc by rememberSaveable { mutableStateOf("") }
    var collapse by rememberSaveable { mutableStateOf("") }
    CalcTitle(t("Systolic PA pressure (sPAP / RVSP)", "Systolischer PA-Druck (sPAP / RVSP)", "الضغط الانقباضي للشريان الرئوي"), language,
        "RVSP = 4 · (TR Vmax)² + RAP")
    EchoCard {
        CalcField(v, { v = it }, "TR Vmax (m/s)", v.isNotBlank() && v.num()?.takeIf { it <= 7 } == null)
        CalcField(ivc, { ivc = it }, t("IVC diameter (cm)", "VCI-Durchmesser (cm)", "قطر الوريد الأجوف (سم)").get(language),
            ivc.isNotBlank() && ivc.num() == null)
        CalcField(collapse, { collapse = it }, t("IVC collapse on sniff (%)", "VCI-Kollaps beim Schnüffeln (%)", "انخماص الوريد الأجوف (%)").get(language),
            collapse.isNotBlank() && normalizeNumberInput(collapse).trim().toDoubleOrNull()?.takeIf { it in 0.0..100.0 } == null, ImeAction.Done)
    }
    val vv = v.num()?.takeIf { it <= 7 }
    val iv = ivc.num()
    val c = normalizeNumberInput(collapse).trim().toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
    EchoCard {
        if (vv == null || iv == null || c == null) {
            Hint(t("Enter TR Vmax (CW, A4C / PSAX) and IVC diameter with its collapse (subcostal). RAP: 3 / 8 / 15 mmHg.",
                "TR-Vmax (CW, A4K / PSAX) sowie VCI-Durchmesser und Kollaps (subkostal) eingeben. ZVD: 3 / 8 / 15 mmHg.",
                "أدخل سرعة TR القصوى (CW) وقطر الوريد الأجوف ونسبة انخماصه. RAP: 3 / 8 / 15 ملم زئبق."), language)
        } else {
            val r = EchoCalc.rvsp(vv, iv, c)
            Row2(t("RAP / ZVD (estimated)", "ZVD / RAP (geschätzt)", "ضغط الأذين الأيمن (تقديري)").get(language), "${r.rapMmHg} mmHg")
            Row2("4·V² (RV–RA)", "${r.gradientMmHg.f(0)} mmHg")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BigResult("sPAP", "${r.rvspMmHg.f(0)} mmHg", r.phProbability, when (r.phProbability) {
                Severity.NORMAL -> t("TR Vmax ≤ 2.8 m/s: low echo probability of PH", "TR-Vmax ≤ 2,8 m/s: niedrige echokardiographische PH-Wahrscheinlichkeit", "TR ≤ 2.8 م/ث: احتمال منخفض لارتفاع الضغط الرئوي")
                Severity.MODERATE -> t("TR Vmax 2.9–3.4 m/s: intermediate probability of PH", "TR-Vmax 2,9–3,4 m/s: intermediäre PH-Wahrscheinlichkeit", "TR 2.9–3.4 م/ث: احتمال متوسط")
                else -> t("TR Vmax > 3.4 m/s: high probability of PH", "TR-Vmax > 3,4 m/s: hohe PH-Wahrscheinlichkeit", "TR > 3.4 م/ث: احتمال مرتفع")
            }, language)
        }
    }
}

@Composable
private fun DiastoleCalculator(language: AppLanguage) {
    var e by rememberSaveable { mutableStateOf("") }
    var a by rememberSaveable { mutableStateOf("") }
    var sept by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf("") }
    var tr by rememberSaveable { mutableStateOf("") }
    var lavi by rememberSaveable { mutableStateOf("") }
    CalcTitle(t("Diastolic function (ASE/EACVI 2016)", "Diastolische Funktion (ASE/EACVI 2016)", "الوظيفة الانبساطية (ASE/EACVI 2016)"), language,
        "E/A · E/e' avg = E / ((e' sept + e' lat) / 2)")
    EchoCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CalcField(e, { e = it }, "E (cm/s)", e.isNotBlank() && e.num() == null, modifier = Modifier.weight(1f))
            CalcField(a, { a = it }, "A (cm/s)", a.isNotBlank() && a.num() == null, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CalcField(sept, { sept = it }, "e' septal (cm/s)", sept.isNotBlank() && sept.num() == null, modifier = Modifier.weight(1f))
            CalcField(lat, { lat = it }, "e' lateral (cm/s)", lat.isNotBlank() && lat.num() == null, modifier = Modifier.weight(1f))
        }
        Text(t("Optional:", "Optional:", "اختياري:").get(language), style = MaterialTheme.typography.labelMedium, color = EchoMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CalcField(tr, { tr = it }, "TR Vmax (m/s)", tr.isNotBlank() && tr.num() == null, modifier = Modifier.weight(1f))
            CalcField(lavi, { lavi = it }, "LAVI (ml/m²)", lavi.isNotBlank() && lavi.num() == null, ImeAction.Done, Modifier.weight(1f))
        }
    }
    val ev = e.num()
    val av = a.num()
    val sv = sept.num()
    val lv = lat.num()
    EchoCard {
        if (ev == null || av == null || sv == null || lv == null) {
            Hint(t("Enter mitral E and A (PW at the leaflet tips) and septal / lateral e' (TDI).",
                "Mitral-E und -A (PW an den Segelspitzen) sowie e' septal / lateral (TDI) eingeben.",
                "أدخل E وA (PW عند أطراف الوريقات) وe' الحاجزي والجانبي (TDI)."), language)
        } else {
            val r = EchoCalc.diastolic(ev, av, sv, lv, tr.num(), lavi.num())
            Row2("E/A", r.eOverA.f(2))
            Row2("E/e' (avg)", r.eOverEPrimeAverage.f(1) + if (r.eOverEPrimeAverage > 14) "  (> 14)" else "")
            Row2("e' septal / lateral", "${sv.f(0)} / ${lv.f(0)} cm/s" +
                if (sv < 7 || lv < 10) "  ↓" else "")
            if (r.criteriaAvailable > 0) {
                Row2(t("Criteria positive", "Kriterien positiv", "معايير إيجابية").get(language), "${r.criteriaPositive} / ${r.criteriaAvailable}")
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val (sev, text) = when (r.grade) {
                1 -> Severity.MILD to t("Grade I – impaired relaxation, normal LA pressure", "Grad I – Relaxationsstörung, normaler LA-Druck", "الدرجة I – اضطراب الارتخاء، ضغط أذيني طبيعي")
                2 -> Severity.MODERATE to t("Grade II – pseudonormal, elevated LA pressure", "Grad II – pseudonormal, erhöhter LA-Druck", "الدرجة II – نمط كاذب الطبيعي، ضغط أذيني مرتفع")
                3 -> Severity.SEVERE to t("Grade III – restrictive, elevated LA pressure", "Grad III – restriktiv, erhöhter LA-Druck", "الدرجة III – نمط تقييدي، ضغط أذيني مرتفع")
                else -> Severity.INDETERMINATE to t("Indeterminate – add TR Vmax / LAVI", "Nicht bestimmbar – TR-Vmax / LAVI ergänzen", "غير محدد – أضف TR وLAVI")
            }
            BigResult(t("Filling pattern", "Füllungsmuster", "نمط الامتلاء").get(language), r.grade?.let { "${t("Grade", "Grad", "الدرجة").get(language)} $it" } ?: "?", sev, text, language)
            Hint(t("In patients with normal EF first check e' septal < 7, e' lateral < 10, E/e' > 14, TR > 2.8 m/s, LAVI > 34 ml/m² (≥ 3 of 4 → diastolic dysfunction).",
                "Bei normaler EF zuerst prüfen: e' septal < 7, e' lateral < 10, E/e' > 14, TR > 2,8 m/s, LAVI > 34 ml/m² (≥ 3 von 4 → diastolische Dysfunktion).",
                "عند EF طبيعي تحقق أولاً: e' حاجزي < 7، جانبي < 10، E/e' > 14، TR > 2.8 م/ث، LAVI > 34 (≥ 3 من 4 → خلل انبساطي)."), language)
        }
    }
}

@Composable
private fun MvaCalculator(language: AppLanguage) {
    var pht by rememberSaveable { mutableStateOf("") }
    CalcTitle(t("Mitral valve area – pressure half-time", "Mitralklappenöffnungsfläche – Druckhalbwertszeit", "مساحة الصمام التاجي – زمن نصف الضغط"), language,
        "MVA = 220 / PHT")
    EchoCard {
        CalcField(pht, { pht = it }, "PHT (ms)", pht.isNotBlank() && pht.num()?.takeIf { it in 20.0..800.0 } == null, ImeAction.Done)
    }
    val p = pht.num()?.takeIf { it in 20.0..800.0 }
    EchoCard {
        if (p == null) {
            Hint(t("Measure PHT on the CW mitral inflow (A4C), along the E-wave deceleration slope.",
                "PHT am CW-Mitraleinstrom (A4K) entlang der E-Wellen-Dezeleration messen.",
                "قِس PHT على تدفق التاجي بـCW على ميل تباطؤ موجة E."), language)
        } else {
            val r = EchoCalc.mvaPht(p)
            BigResult("MVA", "${r.mvaCm2.f(2)} cm²", r.severity, when (r.severity) {
                Severity.MILD -> t("> 1.5 cm²: no significant stenosis / mild", "> 1,5 cm²: keine signifikante / leichte Stenose", "> 1.5 سم²: تضيق خفيف أو غير هام")
                Severity.MODERATE -> t("1.0–1.5 cm²: moderate (clinically significant)", "1,0–1,5 cm²: mittelgradig (klinisch signifikant)", "1.0–1.5 سم²: متوسط (هام سريرياً)")
                else -> t("< 1.0 cm²: severe stenosis", "< 1,0 cm²: schwere Stenose", "< 1.0 سم²: تضيق شديد")
            }, language)
            Hint(t("Not valid right after balloon valvotomy or with significant AR / reduced LV compliance.",
                "Nicht gültig direkt nach Ballonvalvotomie oder bei relevanter AI / reduzierter LV-Compliance.",
                "غير صالح بعد توسيع الصمام بالبالون مباشرةً أو مع قلس أبهري هام."), language)
        }
    }
}

@Composable
private fun CalcTitle(title: LocalizedText, language: AppLanguage, formula: String) {
    Column {
        Text(title.get(language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = EchoText)
        Text(ltr(formula), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = EchoSky)
    }
}

@Composable
private fun CalcField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    imeAction: ImeAction = ImeAction.Next,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            if (input.length <= 7 && input.all { it.isDigit() || it == '.' || it == ',' || it == '٫' || it in '٠'..'٩' }) {
                onValueChange(input)
            }
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = modifier.padding(vertical = 3.dp)
    )
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = EchoMuted, modifier = Modifier.weight(1f))
        Text(ltr(value), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = EchoText)
    }
}

@Composable
private fun Hint(text: LocalizedText, language: AppLanguage) {
    Text(text.get(language), style = MaterialTheme.typography.bodySmall, color = EchoMuted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun BigResult(label: String, value: String, severity: Severity, text: LocalizedText, language: AppLanguage) {
    val color = severityColor(severity)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = EchoMuted)
            Text(ltr(value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color)
        }
        Box(Modifier.size(14.dp).background(color, CircleShape))
    }
    Text(
        text.get(language),
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

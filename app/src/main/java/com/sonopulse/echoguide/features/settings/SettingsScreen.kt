package com.sonopulse.echoguide.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonopulse.echoguide.AboutDialog
import com.sonopulse.echoguide.AppLanguage
import com.sonopulse.echoguide.EchoCritical
import com.sonopulse.echoguide.EchoMuted
import com.sonopulse.echoguide.EchoNormal
import com.sonopulse.echoguide.EchoOutline
import com.sonopulse.echoguide.EchoPrimary
import com.sonopulse.echoguide.EchoPrimaryFill
import com.sonopulse.echoguide.EchoSurface
import com.sonopulse.echoguide.EchoCaution
import com.sonopulse.echoguide.EchoText
import com.sonopulse.echoguide.LocalizedText
import com.sonopulse.echoguide.ProtocolState
import com.sonopulse.echoguide.R
import com.sonopulse.echoguide.ltr
import com.sonopulse.echoguide.rememberProtocolState
import java.text.DateFormat
import java.util.Date

private fun t(en: String, de: String, ar: String) = LocalizedText(en, de, ar)

/**
 * Settings & info: appearance (system / light / dark), language (Arabic RTL, German, English –
 * applied instantly), offline-database status, version, clinical references, medical disclaimer
 * and contact options.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    language: AppLanguage,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onVerifyDatabase: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var systemInfoOpen by rememberSaveable { mutableStateOf(false) }
    val version = remember(context) { appVersion(context) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(t("Settings & info", "Einstellungen & Info", "الإعدادات والمعلومات").get(language),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = EchoText)

        // ---- Appearance ------------------------------------------------------------------
        SettingsSection(Icons.Filled.BrightnessAuto, t("Appearance", "Darstellung", "المظهر").get(language)) {
            val modes = listOf(
                Triple(ThemeMode.SYSTEM, Icons.Filled.BrightnessAuto, t("System", "System", "النظام")),
                Triple(ThemeMode.LIGHT, Icons.Filled.LightMode, t("Light", "Hell", "فاتح")),
                Triple(ThemeMode.DARK, Icons.Filled.DarkMode, t("Dark", "Dunkel", "داكن"))
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (mode, icon, label) ->
                    SegmentedButton(
                        selected = state.settings.themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = EchoPrimaryFill,
                            activeContentColor = Color.White,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = EchoText
                        )
                    ) { Text(label.get(language), maxLines = 1) }
                }
            }
            Text(
                t("Light mode for daylight and the ward; dark mode for the dimmed echo lab. Cine loops, ECG and console stay dark like the scanner.",
                    "Hell für Tageslicht und Station, Dunkel für das abgedunkelte Echolabor. Cine-Loops, EKG und Konsole bleiben wie am Gerät dunkel.",
                    "الوضع الفاتح لضوء النهار والجناح، والداكن لمختبر الإيكو المعتم. تبقى المقاطع وتخطيط القلب ولوحة التحكم داكنة كما في الجهاز.").get(language),
                style = MaterialTheme.typography.bodySmall, color = EchoMuted
            )
        }

        // ---- Language --------------------------------------------------------------------
        SettingsSection(Icons.Filled.Translate, t("Language", "Sprache", "اللغة").get(language)) {
            listOf(AppLanguage.AR, AppLanguage.DE, AppLanguage.EN).forEach { option ->
                val selected = option == language
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .selectable(selected = selected, onClick = { onLanguage(option) }, role = Role.RadioButton)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = EchoPrimary, unselectedColor = EchoMuted))
                    Spacer(Modifier.width(10.dp))
                    Text(option.nativeName, color = EchoText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (option.isRtl) "RTL" else "LTR", color = EchoMuted, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .border(1.dp, EchoOutline, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }

        // ---- Offline database ------------------------------------------------------------
        SettingsSection(Icons.Filled.Storage, t("Offline database", "Offline-Datenbank", "قاعدة البيانات دون اتصال").get(language)) {
            val db = state.database
            val (color, title) = when (db) {
                is DatabaseStatus.Active -> EchoNormal to t("Synced & active", "Synchronisiert & aktiv", "متزامنة ونشطة")
                is DatabaseStatus.Error -> EchoCritical to t("Error – reinstall the app", "Fehler – App neu installieren", "خطأ – أعد تثبيت التطبيق")
                DatabaseStatus.Checking -> EchoCaution to t("Checking …", "Wird geprüft …", "جارٍ التحقق …")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(title.get(language), color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onVerifyDatabase) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("Check", "Prüfen", "تحقق").get(language))
                }
            }
            when (db) {
                is DatabaseStatus.Active -> {
                    InfoRow(t("Views / guided steps", "Schnitte / Anleitungsschritte", "المقاطع / الخطوات").get(language), ltr("${db.views} / ${db.steps}"))
                    InfoRow(t("Real cine loops", "Echte Cine-Loops", "مقاطع حقيقية").get(language), ltr("${db.loops}"))
                    InfoRow(t("Valves / pathology cases", "Klappen / Pathologie-Fälle", "الصمامات / الحالات المرضية").get(language), ltr("${db.valves} / ${db.pathologyCases}"))
                    InfoRow(t("Schema version", "Schema-Version", "إصدار البنية").get(language), ltr("v${db.schemaVersion}"))
                    InfoRow(t("Last check", "Letzte Prüfung", "آخر تحقق").get(language),
                        ltr(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, language.locale).format(Date(db.verifiedAt))))
                }
                is DatabaseStatus.Error -> Text(db.message, color = EchoCritical, style = MaterialTheme.typography.bodySmall)
                DatabaseStatus.Checking -> Unit
            }
            Text(t("Everything is bundled in the app – no network, no account, no patient data stored.",
                "Alles ist in der App enthalten – kein Netz, kein Konto, keine Patientendaten gespeichert.",
                "كل المحتوى مضمَّن في التطبيق – بلا إنترنت أو حساب، ولا تُخزَّن بيانات مرضى.").get(language),
                style = MaterialTheme.typography.bodySmall, color = EchoMuted)
        }

        // ---- About -----------------------------------------------------------------------
        SettingsSection(Icons.Filled.Info, t("About EchoScope", "Über EchoScope", "حول EchoScope").get(language)) {
            InfoRow(t("Version", "Version", "الإصدار").get(language), ltr("${version.first} (Build ${version.second})"))
            InfoRow(t("Package", "Paket", "الحزمة").get(language), ltr(context.packageName))
            NavRow(Icons.Filled.Gavel, t("Image & video sources, licences", "Bild- & Videoquellen, Lizenzen", "مصادر الصور والفيديو والتراخيص").get(language)) { aboutOpen = true }
        }

        // ---- References --------------------------------------------------------------------
        SettingsSection(Icons.AutoMirrored.Filled.MenuBook, t("Clinical references", "Klinische Referenzen", "المراجع السريرية").get(language)) {
            when (val protocol = rememberProtocolState()) {
                is ProtocolState.Ready -> (protocol.protocol.references + CALCULATOR_REFERENCES).distinct().forEachIndexed { i, ref ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(ltr("${i + 1}."), color = EchoPrimary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(min = 24.dp))
                        Text(ref, color = EchoText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> Text("…", color = EchoMuted)
            }
        }

        // ---- Disclaimer --------------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(EchoCritical.copy(alpha = 0.08f))
                .border(BorderStroke(1.5.dp, EchoCritical), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = EchoCritical)
                Spacer(Modifier.width(8.dp))
                Text(t("Medical disclaimer", "Medizinischer Haftungsausschluss", "إخلاء مسؤولية طبي").get(language),
                    color = EchoCritical, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                t("EchoScope is a reference and educational tool only. It is not a medical device, does not provide diagnoses and must not replace clinical judgement, supervision by a qualified physician or the current ASE / EACVI / ESC guidelines. Calculated values are orientation aids; verify every measurement on the original study.",
                    "EchoScope ist ausschließlich ein Nachschlage- und Lernwerkzeug. Es ist kein Medizinprodukt, stellt keine Diagnosen und ersetzt weder die ärztliche Beurteilung noch die Supervision durch qualifizierte Ärztinnen und Ärzte oder die aktuellen ASE-/EACVI-/ESC-Leitlinien. Berechnete Werte sind Orientierungshilfen; jede Messung am Originalbefund überprüfen.",
                    "EchoScope أداة مرجعية وتعليمية فقط، وليس جهازاً طبياً، ولا يقدّم تشخيصاً، ولا يحلّ محل التقدير السريري أو إشراف طبيب مؤهل أو إرشادات ASE / EACVI / ESC الحالية. القيم المحسوبة للاسترشاد فقط؛ تحقّق من كل قياس على الفحص الأصلي.").get(language),
                color = EchoText, style = MaterialTheme.typography.bodySmall
            )
        }

        // ---- Contact -----------------------------------------------------------------------
        SettingsSection(Icons.Filled.Email, t("Contact & feedback", "Kontakt & Feedback", "التواصل والملاحظات").get(language)) {
            val support = stringResource(R.string.support_email)
            NavRow(Icons.Filled.Email, t("Support", "Support", "الدعم").get(language),
                subtitle = support.ifBlank { t("Choose recipient in your mail app", "Empfänger in der Mail-App wählen", "اختر المستلم في تطبيق البريد").get(language) }) {
                sendMail(context, support, "EchoScope ${version.first} – Support", systemInfo(context, language, state))
            }
            NavRow(Icons.Filled.RateReview, t("Send feedback", "Feedback senden", "إرسال ملاحظات").get(language)) {
                sendMail(context, support, "EchoScope ${version.first} – Feedback", systemInfo(context, language, state))
            }
            NavRow(Icons.Filled.MedicalServices, t("Clinical peer review (report a content error)", "Klinisches Peer-Review (inhaltlichen Fehler melden)", "المراجعة السريرية (الإبلاغ عن خطأ علمي)").get(language)) {
                sendMail(context, support, "EchoScope ${version.first} – Clinical review",
                    t("View / step:\nWhat is wrong:\nSource (guideline, page):\n\n", "Schnitt / Schritt:\nWas ist falsch:\nQuelle (Leitlinie, Seite):\n\n",
                        "المقطع / الخطوة:\nالخطأ:\nالمصدر (الإرشاد، الصفحة):\n\n").get(language) + systemInfo(context, language, state))
            }
            NavRow(Icons.Filled.PhoneAndroid, t("System information", "Systeminformationen", "معلومات النظام").get(language)) { systemInfoOpen = true }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (aboutOpen) AboutDialog(view = null, language = language, onDismiss = { aboutOpen = false })
    if (systemInfoOpen) {
        val info = systemInfo(context, language, state)
        AlertDialog(
            onDismissRequest = { systemInfoOpen = false },
            title = { Text(t("System information", "Systeminformationen", "معلومات النظام").get(language)) },
            text = { Text(info, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = EchoText) },
            confirmButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("EchoScope", info))
                    systemInfoOpen = false
                }) { Text(t("Copy", "Kopieren", "نسخ").get(language)) }
            },
            dismissButton = { TextButton(onClick = { systemInfoOpen = false }) { Text(stringResource(R.string.about_close)) } },
            containerColor = EchoSurface
        )
    }
}

/** Guideline sources of the calculators (shown with the protocol references). */
private val CALCULATOR_REFERENCES = listOf(
    "Baumgartner H et al. Recommendations on the echocardiographic assessment of aortic valve stenosis. J Am Soc Echocardiogr 2017;30:372–392.",
    "Nagueh SF et al. Recommendations for the evaluation of left ventricular diastolic function by echocardiography. J Am Soc Echocardiogr 2016;29:277–314.",
    "Rudski LG et al. Guidelines for the echocardiographic assessment of the right heart in adults. J Am Soc Echocardiogr 2010;23:685–713.",
    "Humbert M et al. 2022 ESC/ERS Guidelines for the diagnosis and treatment of pulmonary hypertension. Eur Heart J 2022;43:3618–3731.",
    "Baumgartner H et al. Echocardiographic assessment of valve stenosis: EAE/ASE recommendations for clinical practice. J Am Soc Echocardiogr 2009;22:1–23.",
    "Lang RM et al. Recommendations for cardiac chamber quantification by echocardiography in adults. J Am Soc Echocardiogr 2015;28:1–39."
)

private fun appVersion(context: Context): Pair<String, Long> = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    info.versionName.orEmpty() to code
}.getOrDefault("?" to 0L)

/** Device / app details attached to support mails (no personal or patient data). */
private fun systemInfo(context: Context, language: AppLanguage, state: SettingsUiState): String {
    val (name, code) = appVersion(context)
    return buildString {
        appendLine("EchoScope $name ($code)")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Language: ${language.tag} · Theme: ${state.settings.themeMode.name.lowercase()}")
        append("Database: ")
        append(
            when (val db = state.database) {
                is DatabaseStatus.Active -> "active, ${db.views} views / ${db.steps} steps / ${db.loops} loops / ${db.pathologyCases} cases"
                is DatabaseStatus.Error -> "error (${db.message})"
                DatabaseStatus.Checking -> "checking"
            }
        )
    }
}

private fun sendMail(context: Context, to: String, subject: String, body: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to))).apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
    }
    runCatching { context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun SettingsSection(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(EchoSurface)
            .border(1.dp, EchoOutline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = EchoPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = EchoText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = EchoMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = EchoText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = EchoMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = EchoText, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = EchoMuted, style = MaterialTheme.typography.bodySmall) }
        }
        // AutoMirrored: points left in Arabic (RTL) and right in German / English.
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EchoMuted)
    }
}

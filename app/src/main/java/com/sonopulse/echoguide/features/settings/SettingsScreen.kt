package com.sonopulse.echoguide.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonopulse.echoguide.AppLanguage
import com.sonopulse.echoguide.EchoBackground
import com.sonopulse.echoguide.EchoCaution
import com.sonopulse.echoguide.EchoCritical
import com.sonopulse.echoguide.EchoMuted
import com.sonopulse.echoguide.EchoNormal
import com.sonopulse.echoguide.EchoOutline
import com.sonopulse.echoguide.EchoPalette
import com.sonopulse.echoguide.EchoPrimary
import com.sonopulse.echoguide.EchoPrimaryFill
import com.sonopulse.echoguide.EchoProtocol
import com.sonopulse.echoguide.EchoScopeLogo
import com.sonopulse.echoguide.EchoSurface
import com.sonopulse.echoguide.EchoSurfaceHigh
import com.sonopulse.echoguide.EchoText
import com.sonopulse.echoguide.LocalizedText
import com.sonopulse.echoguide.ProtocolState
import com.sonopulse.echoguide.R
import com.sonopulse.echoguide.ltr
import com.sonopulse.echoguide.rememberProtocolState
import com.sonopulse.echoguide.tabularNumbers
import java.text.DateFormat
import java.util.Date

private fun t(en: String, de: String, ar: String) = LocalizedText(en, de, ar)

/**
 * Full-screen settings route opened from the gear in the Top App Bar (Back closes it).
 */
@Composable
fun SettingsRoute(
    state: SettingsUiState,
    language: AppLanguage,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onVerifyDatabase: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    Column(modifier = Modifier.fillMaxSize().background(EchoBackground).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cockpit_back), tint = EchoText)
            }
            Text(t("Settings", "Einstellungen", "الإعدادات").get(language), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = EchoText, modifier = Modifier.weight(1f))
            EchoScopeLogo(size = 30.dp)
        }
        SettingsScreen(state, language, onThemeMode, onLanguage, onVerifyDatabase, Modifier.weight(1f))
    }
}

/**
 * Settings: quick controls (language, appearance) always visible at the top; about & credits,
 * medical disclaimer, clinical references and contact in collapsible cards (all closed at start).
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
    var systemInfoOpen by rememberSaveable { mutableStateOf(false) }
    val version = remember(context) { appVersion(context) }
    val protocol = (rememberProtocolState() as? ProtocolState.Ready)?.protocol
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EchoBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- A: quick controls -------------------------------------------------------------
        QuickControl(Icons.Filled.Translate, t("Language", "Sprache", "اللغة").get(language)) {
            val options = listOf(AppLanguage.DE, AppLanguage.EN, AppLanguage.AR)
            Segmented(options.map { it.nativeName }, options.indexOf(language)) { onLanguage(options[it]) }
        }
        QuickControl(Icons.Filled.BrightnessAuto, t("Theme", "Darstellung", "المظهر").get(language)) {
            val modes = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)
            val icons = listOf(Icons.Filled.DarkMode, Icons.Filled.LightMode, Icons.Filled.BrightnessAuto)
            val labels = listOf(t("Dark", "Dunkel", "داكن"), t("Light", "Hell", "فاتح"), t("System", "System", "النظام"))
            Segmented(labels.map { it.get(language) }, modes.indexOf(state.settings.themeMode), icons) { onThemeMode(modes[it]) }
        }

        // ---- B: collapsible cards ---------------------------------------------------------
        val db = state.database
        val dbLabel = when (db) {
            is DatabaseStatus.Active -> t("Offline database active", "Offline-Datenbank aktiv", "قاعدة البيانات دون اتصال نشطة")
            is DatabaseStatus.Error -> t("Offline database error", "Offline-Datenbank fehlerhaft", "خطأ في قاعدة البيانات")
            DatabaseStatus.Checking -> t("Checking database …", "Datenbank wird geprüft …", "جارٍ فحص قاعدة البيانات …")
        }
        ExpandableSettingsCard(
            icon = Icons.Filled.Info,
            iconTint = EchoPrimary,
            title = t("About EchoScope & image credits", "Über EchoScope & Bildnachweise", "حول EchoScope ومصادر الصور").get(language),
            subtitle = "${t("Version", "Version", "الإصدار").get(language)} ${ltr(version.first)} • ${dbLabel.get(language)}"
        ) {
            DatabaseBlock(db, language, onVerifyDatabase)
            Divider()
            InfoRow(t("Version", "Version", "الإصدار").get(language), ltr("${version.first} (Build ${version.second})"))
            InfoRow(t("Package", "Paket", "الحزمة").get(language), ltr(context.packageName))
            Divider()
            Credit(stringResource(R.string.about_illustrations_title), stringResource(R.string.about_illustrations_credit),
                stringResource(R.string.about_illustrations_detail))
            Credit(stringResource(R.string.about_clips_title), clipCredits(protocol, language), null)
            Credit(stringResource(R.string.about_echoorg_title), stringResource(R.string.about_echoorg_credit),
                stringResource(R.string.about_echoorg_detail))
            Credit(stringResource(R.string.about_commons_title), stringResource(R.string.about_commons_credit),
                stringResource(R.string.about_commons_detail))
            Credit(stringResource(R.string.about_reference_title), stringResource(R.string.about_reference_credit), null)
            Credit(t("App icon", "App-Icon", "أيقونة التطبيق").get(language),
                t("EchoScope brand artwork (heart, transducer, acoustic waves, ECG).",
                    "EchoScope-Markengrafik (Herz, Schallkopf, Schallwellen, EKG).",
                    "رسم علامة EchoScope (القلب، المسبار، الموجات الصوتية، تخطيط القلب).").get(language), null)
        }

        ExpandableSettingsCard(
            icon = Icons.Filled.WarningAmber,
            iconTint = EchoPalette.Crimson,
            title = t("Medical disclaimer", "Medizinischer Haftungsausschluss", "إخلاء مسؤولية طبي").get(language)
        ) {
            Text(
                t("EchoScope is a reference and educational tool only. It is not a medical device, does not provide diagnoses and must not replace clinical judgement, supervision by a qualified physician or the current ASE / EACVI / ESC guidelines. Calculated values are orientation aids; verify every measurement on the original study and against your local laboratory standards.",
                    "EchoScope ist ausschließlich ein Nachschlage- und Lernwerkzeug. Es ist kein Medizinprodukt, stellt keine Diagnosen und ersetzt weder die ärztliche Beurteilung noch die Supervision durch qualifizierte Ärztinnen und Ärzte oder die aktuellen ASE-/EACVI-/ESC-Leitlinien. Berechnete Werte sind Orientierungshilfen; jede Messung am Originalbefund und an den Standards des eigenen Echolabors überprüfen.",
                    "EchoScope أداة مرجعية وتعليمية فقط، وليس جهازاً طبياً، ولا يقدّم تشخيصاً، ولا يحلّ محل التقدير السريري أو إشراف طبيب مؤهل أو إرشادات ASE / EACVI / ESC الحالية. القيم المحسوبة للاسترشاد فقط؛ تحقّق من كل قياس على الفحص الأصلي ووفق معايير مختبرك.").get(language),
                color = EchoText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(EchoPalette.Crimson.copy(alpha = 0.08f))
                    .border(1.dp, EchoCritical.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }

        val references = (protocol?.references.orEmpty() + GUIDELINE_REFERENCES).distinct()
        ExpandableSettingsCard(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            iconTint = EchoPrimary,
            title = t("Clinical references", "Klinische Referenzen", "المراجع السريرية").get(language),
            badge = "${ltr(references.size.toString())} ${t("guidelines", "Leitlinien", "إرشادات").get(language)}"
        ) {
            references.forEachIndexed { i, ref ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(ltr("${i + 1}."), color = EchoPrimary, style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 26.dp))
                    Text(ref, color = EchoText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ExpandableSettingsCard(
            icon = Icons.Filled.Email,
            iconTint = EchoPrimary,
            title = t("Contact, feedback & system information", "Kontakt, Feedback & Systeminformationen", "التواصل والملاحظات ومعلومات النظام").get(language)
        ) {
            val support = stringResource(R.string.support_email)
            NavRow(Icons.Filled.SupportAgent, t("Support", "Support", "الدعم").get(language),
                subtitle = support.ifBlank { t("Choose the recipient in your mail app", "Empfänger in der Mail-App wählen", "اختر المستلم في تطبيق البريد").get(language) }) {
                sendMail(context, support, "EchoScope ${version.first} – Support", "")
            }
            NavRow(Icons.Filled.RateReview, t("Send feedback", "Feedback senden", "إرسال ملاحظات").get(language)) {
                sendMail(context, support, "EchoScope ${version.first} – Feedback", "")
            }
            NavRow(Icons.Filled.MedicalServices, t("Clinical peer review", "Klinisches Peer-Review", "المراجعة السريرية").get(language),
                subtitle = t("Report a content error with its source", "Inhaltlichen Fehler mit Quelle melden", "الإبلاغ عن خطأ علمي مع مصدره").get(language)) {
                sendMail(context, support, "EchoScope ${version.first} – Clinical review",
                    t("View / step:\nWhat is wrong:\nSource (guideline, page):\n", "Schnitt / Schritt:\nWas ist falsch:\nQuelle (Leitlinie, Seite):\n",
                        "المقطع / الخطوة:\nالخطأ:\nالمصدر (الإرشاد، الصفحة):\n").get(language))
            }
            // Device details stay hidden until explicitly requested.
            NavRow(Icons.Filled.PhoneAndroid, t("Show system information", "Systeminformationen anzeigen", "عرض معلومات النظام").get(language)) {
                systemInfoOpen = true
            }
        }
    }

    if (systemInfoOpen) {
        val info = systemInfo(context, language, state)
        AlertDialog(
            onDismissRequest = { systemInfoOpen = false },
            title = { Text(t("System information", "Systeminformationen", "معلومات النظام").get(language)) },
            text = { Text(ltr(info), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = EchoText) },
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

/**
 * Collapsible settings card: header (icon, title, optional subtitle / badge, rotating chevron);
 * the body opens with [AnimatedVisibility]. Collapsed by default.
 */
@Composable
fun ExpandableSettingsCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color = EchoPrimary,
    subtitle: String? = null,
    badge: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(EchoSurface)
            .border(1.dp, EchoOutline.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = EchoText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                subtitle?.let { Text(it, color = EchoMuted, style = MaterialTheme.typography.bodySmall) }
            }
            badge?.let {
                Text(it, color = EchoPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EchoPrimary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = EchoMuted, modifier = Modifier.rotate(chevron))
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun QuickControl(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(EchoSurface)
            .border(1.dp, EchoOutline.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = EchoPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = EchoText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, icons: List<ImageVector>? = null, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                icon = { icons?.getOrNull(i)?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = EchoPrimaryFill,
                    activeContentColor = Color.White,
                    activeBorderColor = EchoPrimaryFill,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = EchoText,
                    inactiveBorderColor = EchoOutline
                )
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun DatabaseBlock(db: DatabaseStatus, language: AppLanguage, onVerify: () -> Unit) {
    val (color, title) = when (db) {
        is DatabaseStatus.Active -> EchoNormal to t("Offline database: synced & active", "Offline-Datenbank: synchronisiert & aktiv", "قاعدة البيانات: متزامنة ونشطة")
        is DatabaseStatus.Error -> EchoCritical to t("Error – reinstall the app", "Fehler – App neu installieren", "خطأ – أعد تثبيت التطبيق")
        DatabaseStatus.Checking -> EchoCaution to t("Checking …", "Wird geprüft …", "جارٍ التحقق …")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(title.get(language), color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onVerify) {
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
            InfoRow(t("Last check", "Letzte Prüfung", "آخر تحقق").get(language),
                ltr(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, language.locale).format(Date(db.verifiedAt))))
        }
        is DatabaseStatus.Error -> Text(db.message, color = EchoCritical, style = MaterialTheme.typography.bodySmall)
        DatabaseStatus.Checking -> Unit
    }
}

@Composable
private fun Credit(title: String, body: String, detail: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = EchoPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(body, color = EchoText, style = MaterialTheme.typography.bodySmall)
        detail?.let { Text(it, color = EchoMuted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(EchoSurfaceHigh))
}

/** One line per view: author, licence and platform of its real cine loop (from the protocol). */
private fun clipCredits(protocol: EchoProtocol?, language: AppLanguage): String =
    protocol?.allViews.orEmpty().joinToString("\n") { v ->
        val s = v.videoSource
        "• ${v.title.get(language)}: ${s.author.orEmpty()} · ${s.license.orEmpty()} · ${s.platform}"
    }

/** Guideline sources of the calculators and the valve / pathology modules (with the protocol's own six). */
private val GUIDELINE_REFERENCES = listOf(
    "Baumgartner H et al. Recommendations on the Echocardiographic Assessment of Aortic Valve Stenosis: A Focused Update from the EACVI and the ASE. J Am Soc Echocardiogr 2017;30:372-392.",
    "Baumgartner H et al. Echocardiographic Assessment of Valve Stenosis: EAE/ASE Recommendations for Clinical Practice. J Am Soc Echocardiogr 2009;22:1-23.",
    "Zoghbi WA et al. Recommendations for Noninvasive Evaluation of Native Valvular Regurgitation. J Am Soc Echocardiogr 2017;30:303-371.",
    "Lancellotti P et al. Recommendations for the echocardiographic assessment of native valvular regurgitation: an executive summary from the EACVI. Eur Heart J Cardiovasc Imaging 2013;14:611-644.",
    "Vahanian A et al. 2021 ESC/EACTS Guidelines for the management of valvular heart disease. Eur Heart J 2022;43:561-632.",
    "Humbert M et al. 2022 ESC/ERS Guidelines for the diagnosis and treatment of pulmonary hypertension. Eur Heart J 2022;43:3618-3731."
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

/** Device / app details, shown or copied only on request (no personal or patient data). */
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
        if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
        if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
    }
    runCatching { context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = EchoMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = EchoText, style = MaterialTheme.typography.bodyMedium.tabularNumbers(), fontWeight = FontWeight.SemiBold)
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
        // AutoMirrored: points left in Arabic (RTL), right in German / English.
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EchoMuted)
    }
}

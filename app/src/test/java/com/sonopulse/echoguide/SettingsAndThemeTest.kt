package com.sonopulse.echoguide

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.sonopulse.echoguide.features.settings.AppSettings
import com.sonopulse.echoguide.features.settings.DatabaseStatus
import com.sonopulse.echoguide.features.settings.SettingsScreen
import com.sonopulse.echoguide.features.settings.SettingsUiState
import com.sonopulse.echoguide.features.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings screen in light / dark and Arabic (RTL), and the cockpit in light mode (screenshots checked by eye). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1400dp-xxhdpi")
class SettingsAndThemeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val active = SettingsUiState(
        AppSettings(themeMode = ThemeMode.LIGHT),
        DatabaseStatus.Active(views = 10, steps = 52, loops = 34, pathologyCases = 14, valves = 4, schemaVersion = 3, verifiedAt = 1_794_000_000_000)
    )

    private fun settings(colors: EchoColors, language: AppLanguage, name: String) {
        var direction: LayoutDirection? = null
        compose.setContent {
            EchoScopeTheme(colors = colors, language = language) {
                LocalizedRoot(language) {
                    direction = LocalLayoutDirection.current
                    Column(Modifier.fillMaxSize()) {
                        SettingsScreen(active.copy(settings = active.settings.copy(language = language)), language, {}, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr, direction)
        System.setProperty("roborazzi.test.record", "true")
        compose.onRoot().captureRoboImage("build/cockpit-screens/settings_$name.png")
    }

    @Test
    fun settingsLightGerman() {
        settings(LightEchoColors, AppLanguage.DE, "light_de")
        compose.onNodeWithText("Synchronisiert & aktiv").assertIsDisplayed()
        compose.onNodeWithText("Medizinischer Haftungsausschluss").assertExists()
    }

    @Test
    fun settingsDarkArabicIsRtl() {
        settings(DarkEchoColors, AppLanguage.AR, "dark_ar")
        compose.onNodeWithText("إخلاء مسؤولية طبي").assertExists()
    }

    @Test
    fun settingsDarkEnglish() = settings(DarkEchoColors, AppLanguage.EN, "dark_en")

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun cockpitInLightModeKeepsImagingLeftToRight() {
        val protocol = runBlocking {
            (EchoRepository.load(ApplicationProvider.getApplicationContext()) as ProtocolState.Ready).protocol
        }
        val view = protocol.allViews.first { it.id == "apical_5c" }.copy(videoAsset = "loops/robolectric_placeholder.mp4")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalVideoSurfaceEnabled provides false) {
                EchoScopeTheme(colors = LightEchoColors, language = AppLanguage.AR) {
                    LocalizedRoot(AppLanguage.AR) {
                        ViewCockpit(
                            protocol = protocol, view = view, language = AppLanguage.AR, onLanguageChange = {},
                            navigator = StepNavigator(view.steps[2], "3 / ${view.steps.size}", true, false, {}, {}),
                            onSelectView = {}
                        )
                    }
                }
            }
        }
        repeat(8) {
            Thread.sleep(150)
            compose.mainClock.advanceTimeBy(300)
        }
        compose.onNodeWithText("خماسي الحجرات").assertExists()
        System.setProperty("roborazzi.test.record", "true")
        compose.onRoot().captureRoboImage("build/cockpit-screens/cockpit_light_ar_a5c.png")
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp-xxhdpi")
    fun pathologyGalleryForAorticValve() {
        val protocol = runBlocking {
            (EchoRepository.load(ApplicationProvider.getApplicationContext()) as ProtocolState.Ready).protocol
        }
        val cases = protocol.pathologyForValve("aortic")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalVideoSurfaceEnabled provides false) {
                EchoScopeTheme(colors = DarkEchoColors, language = AppLanguage.DE) {
                    LocalizedRoot(AppLanguage.DE) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            PathologyGallery(protocol, cases.sortedBy { it.id != "as_severe" }, AppLanguage.DE, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        repeat(10) {
            Thread.sleep(150)
            compose.mainClock.advanceTimeBy(300)
        }
        assertEquals(2, compose.onAllNodesWithText("Hochgradige Aortenklappenstenose").fetchSemanticsNodes().size)  // chip + title
        System.setProperty("roborazzi.test.record", "true")
        compose.onRoot().captureRoboImage("build/cockpit-screens/pathology_aortic_de.png")
    }
}

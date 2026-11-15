package com.sonopulse.echoguide

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Whole-app checks: language purity, Back navigation state, valve assessment tab. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class AppNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val arabic = Regex("[\\u0600-\\u06FF]")
    private val hasArabicText = SemanticsMatcher("contains Arabic text") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        texts.any { arabic.containsMatchIn(it) }
    }

    private fun launch(language: AppLanguage) {
        LanguagePreferences.save(ApplicationProvider.getApplicationContext(), language)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // Robolectric cannot draw the TextureView video surface.
            androidx.compose.runtime.CompositionLocalProvider(LocalVideoSurfaceEnabled provides false) {
                EchoScopeTheme { EchoScopeApp() }
            }
        }
        // The protocol JSON is parsed on an IO thread while the test clock is frozen: give it
        // real time and step the clock (no idle-waiting – the loading spinner never idles).
        repeat(15) {
            Thread.sleep(100)
            compose.mainClock.advanceTimeBy(200)
        }
    }

    private fun screenshot(name: String) {
        System.setProperty("roborazzi.test.record", "true")
        compose.onRoot().captureRoboImage("build/cockpit-screens/$name.png")
    }

    @Test
    fun germanSelectionShowsNoArabic() {
        launch(AppLanguage.DE)
        compose.onNodeWithText("Apikal").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodes(hasArabicText).assertCountEquals(0)
        screenshot("schnitte_de")
    }

    @Test
    fun englishSelectionShowsNoArabic() {
        launch(AppLanguage.EN)
        compose.onAllNodes(hasArabicText).assertCountEquals(0)
    }

    @Test
    fun backReturnsToTheLastWindowAndView() {
        launch(AppLanguage.EN)
        compose.onNodeWithText("Apical").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Apical 4-Chamber").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        // Still on the apical window (the default would be subcostal), with A4C highlighted.
        compose.onNodeWithText("Apical 4-Chamber").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Subcostal 4-Chamber").fetchSemanticsNodes().isEmpty())
        screenshot("schnitte_back_en")
    }

    @Test
    fun valveTabShowsGradingInGerman() {
        launch(AppLanguage.DE)
        compose.onNodeWithText("Herzklappen").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(compose.onAllNodesWithText("Aortenklappe").fetchSemanticsNodes().size >= 2)
        compose.onAllNodes(hasArabicText).assertCountEquals(0)
        screenshot("valves_de")
        compose.onNodeWithText("Aortenstenose (EACVI/ASE 2017)").assertExists()
        // Swipe instead of performScrollTo: its animation never settles under the frozen clock.
        repeat(3) {
            compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = top + 80f) }
            compose.mainClock.advanceTimeBy(1_000)
        }
        screenshot("valves_de_grading")
    }

    @Test
    fun valveMeasurementGuideShowsTraceSchematics() {
        launch(AppLanguage.EN)
        compose.onNodeWithText("Valves").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Measurement guide").assertExists()
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.75f, endY = bottom * 0.45f) }
        compose.mainClock.advanceTimeBy(1_000)
        screenshot("valves_guide_av_cw")
        compose.onNodeWithText("AR: pressure half-time (CW)").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        screenshot("valves_guide_ar_pht")
    }
}

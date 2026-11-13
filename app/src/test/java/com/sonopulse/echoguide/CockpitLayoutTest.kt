package com.sonopulse.echoguide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lays out the real cockpit on a 360×780 dp phone with the guided-exam bottom navigation height
 * (80 dp) reserved, and checks that the step navigation and the console's last key row are on
 * screen without any page scrolling. Screenshots go to build/cockpit-screens/ for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class CockpitLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val protocol: EchoProtocol = runBlocking {
        (EchoRepository.load(ApplicationProvider.getApplicationContext()) as ProtocolState.Ready).protocol
    }

    private fun showCockpit(viewId: String, language: AppLanguage = AppLanguage.EN) {
        // Robolectric cannot draw the TextureView video surface, so point the loop at a missing file:
        // the stage then shows the same-sized fallback card and the layout under test is unchanged.
        val view = protocol.allViews.first { it.id == viewId }.copy(videoAsset = "loops/robolectric_placeholder.mp4")
        // The ECG cursor animates while the loop plays, so drive the test clock manually.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EchoScopeTheme {
              LocalizedRoot(language) {
                Column(Modifier.fillMaxSize()) {
                    ViewCockpit(
                        protocol = protocol,
                        view = view,
                        language = language,
                        onLanguageChange = {},
                        navigator = StepNavigator(view.steps.first(), "1 / ${view.steps.size}", false, false, {}, {}),
                        onSelectView = {},
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.height(80.dp)) // bottom navigation bar of the Anleitung tab
                }
              }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun screenshot(name: String) {
        System.setProperty("roborazzi.test.record", "true")
        compose.onRoot().captureRoboImage("build/cockpit-screens/$name.png")
    }

    @Test
    fun stepCardWithNavigationFitsOnOneScreen() {
        showCockpit("apical_4c")
        val root = compose.onRoot().getBoundsInRoot()
        val next = compose.onNodeWithText("Next").getBoundsInRoot()
        assertTrue("step navigation below the screen (${next.bottom} > ${root.bottom - 80.dp})", next.bottom <= root.bottom - 80.dp)
        screenshot("apical_4c_steps")
    }

    @Test
    fun consoleFitsOnOneScreen() {
        showCockpit("apical_4c")
        compose.onNodeWithText("Device / Console").performClick()
        compose.mainClock.advanceTimeBy(500)
        val store = compose.onNodeWithText("STORE").getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("console keys below the screen (${store.bottom})", store.bottom <= root.bottom - 80.dp)
        screenshot("apical_4c_console")
    }

    @Test
    fun timingPanelFitsOnOneScreen() {
        showCockpit("plax_standard")
        compose.onNodeWithText("Timing & Probe").performClick()
        compose.mainClock.advanceTimeBy(500)
        val root = compose.onRoot().getBoundsInRoot()
        val notch = compose.onNodeWithText("Notch at 10 o'clock").getBoundsInRoot()
        assertTrue(notch.bottom <= root.bottom - 80.dp)
        screenshot("plax_timing")
    }

    @Test
    fun arabicRtlCockpitFitsOnOneScreen() {
        showCockpit("apical_4c", AppLanguage.AR)
        val root = compose.onRoot().getBoundsInRoot()
        val next = compose.onNodeWithText("التالي").getBoundsInRoot()
        assertTrue(next.bottom <= root.bottom - 80.dp)
        screenshot("apical_4c_steps_ar")
    }
}

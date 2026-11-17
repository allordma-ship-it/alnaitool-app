package com.sonopulse.echoguide

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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

/** Tablet (1280 x 800 dp, landscape): the cockpit uses the whole screen in two columns. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class TabletLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cockpitFillsTheTabletInTwoColumns() {
        val protocol = runBlocking {
            (EchoRepository.load(ApplicationProvider.getApplicationContext()) as ProtocolState.Ready).protocol
        }
        val view = protocol.allViews.first { it.id == "apical_4c" }.copy(videoAsset = "loops/robolectric_placeholder.mp4")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EchoScopeTheme {
                LocalizedRoot(AppLanguage.DE) {
                    ViewCockpit(
                        protocol = protocol, view = view, language = AppLanguage.DE,
                        navigator = StepNavigator(view.steps[1], "2 / ${view.steps.size}", true, false, {}, {}),
                        onSelectView = {}, modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        repeat(10) { Thread.sleep(150); compose.mainClock.advanceTimeBy(300) }
        val root = compose.onRoot().getBoundsInRoot()
        val next = compose.onNodeWithText("Weiter").getBoundsInRoot()
        // Deck sits in the right column, not under a phone-sized stage.
        assertTrue("deck in right column (${next.left} of ${root.right})", next.left > root.right * 0.55f)
        assertTrue(root.right >= 1200.dp)
        System.setProperty("roborazzi.test.record", "true")
        compose.onRoot().captureRoboImage("build/cockpit-screens/tablet_a4c.png")
    }
}

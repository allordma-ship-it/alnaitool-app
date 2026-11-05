package com.aurastream.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aurastream.tv.ui.navigation.AuraNavHost
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraTheme

/**
 * Single-activity host. Extends [AppCompatActivity] so per-app language switching
 * (Arabic / English) via `AppCompatDelegate.setApplicationLocales` works on API < 33.
 */
class MainActivity : AppCompatActivity() {

    private val container get() = (application as AuraApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            AuraTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(AuraColors.Canvas),
                ) {
                    AuraNavHost(container = container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.player.onForeground()
    }

    override fun onStop() {
        super.onStop()
        container.player.onBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !isChangingConfigurations) container.player.stop()
    }
}

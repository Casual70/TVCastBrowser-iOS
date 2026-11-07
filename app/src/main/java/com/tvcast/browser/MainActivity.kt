package com.tvcast.browser

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.tvcast.browser.cast.TvCastManager
import com.tvcast.browser.data.BrowserPreferences
import com.tvcast.browser.sniffer.MediaSniffer
import com.tvcast.browser.ui.BrowserCastScreen

class MainActivity : AppCompatActivity() {

    private lateinit var mediaSniffer: MediaSniffer
    private lateinit var tvCastManager: TvCastManager
    private lateinit var browserPreferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Impedisce lo standby aggressivo durante lo streaming verso la TV
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mediaSniffer = MediaSniffer()
        tvCastManager = TvCastManager(applicationContext)
        browserPreferences = BrowserPreferences(applicationContext)

        val startUrl = extractIntentUrl(intent) ?: browserPreferences.homeUrl.value

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BrowserCastScreen(
                    initialUrl = startUrl,
                    sniffer = mediaSniffer,
                    castManager = tvCastManager,
                    browserPrefs = browserPreferences
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tvCastManager.register()
    }

    override fun onPause() {
        tvCastManager.unregister()
        super.onPause()
    }

    override fun onDestroy() {
        tvCastManager.shutdown()
        super.onDestroy()
    }

    private fun extractIntentUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_VIEW) {
            return intent.dataString
        }
        return null
    }
}
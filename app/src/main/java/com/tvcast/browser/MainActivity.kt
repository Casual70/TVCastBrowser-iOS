package com.tvcast.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestNotificationPermissionIfNeeded()

        // Usa i Singleton di processo così la sessione Cast e il Buffer 100MB non vengono MAI persi
        mediaSniffer = MediaSniffer.getInstance()
        tvCastManager = TvCastManager.getInstance(applicationContext)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tvCastManager.onAppResumed()
    }

    override fun onResume() {
        super.onResume()
        tvCastManager.onAppResumed()
    }

    override fun onPause() {
        tvCastManager.onAppPaused()
        super.onPause()
    }

    override fun onDestroy() {
        // Chiudi il server proxy SOLO se l'utente non sta trasmettendo alcunché sulla TV
        if (isFinishing && tvCastManager.uiState.value.activeMedia == null && !tvCastManager.uiState.value.isConnected) {
            tvCastManager.stopDiscovery()
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    2001
                )
            }
        }
    }

    private fun extractIntentUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_VIEW) {
            return intent.dataString
        }
        return null
    }
}
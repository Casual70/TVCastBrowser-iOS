package com.tvcast.browser.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Sessione attiva sullo schermo dell'automobile.
 * Crea e gestisce la schermata principale [TvCastMainCarScreen].
 */
class TvCastCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return TvCastMainCarScreen(carContext)
    }
}


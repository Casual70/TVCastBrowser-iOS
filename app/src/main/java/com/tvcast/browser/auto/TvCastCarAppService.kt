package com.tvcast.browser.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Servizio principale Android Auto CarAppService per TV Cast Browser.
 * Gestisce la sessione interattiva proiettata sullo schermo dell'auto
 * tramite il protocollo Android Auto (supporta sia veicoli fisici che DHU).
 */
class TvCastCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // ALLOW_ALL_HOSTS_VALIDATOR permette all'applicazione FOSS di girare
        // su Android Auto (sideloaded / F-Droid con 'Origini sconosciute' abilitate),
        // su emulatori DHU e su adattatori wireless (AAWireless, CarlinKit, ecc.)
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return TvCastCarSession()
    }
}


package com.tvcast.browser.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Schermata principale renderizzata sullo schermo dell'auto.
 * Mostra lo stato di TV Cast Browser, i flussi video catturati,
 * il controllo del buffer RAM e l'avvio rapido del browser.
 */
class TvCastMainCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // Riga 1: Stato dello streaming e buffer
        listBuilder.addItem(
            Row.Builder()
                .setTitle("TV Cast Browser — Stato Attivo")
                .addText("Motore Cast FOSS • Buffer RAM 100 MB attivo")
                .addText("Supporto HLS (.m3u8), MP4 e proxy decrittazione AES-128")
                .setOnClickListener {
                    CarToast.makeText(carContext, "TV Cast Browser è attivo in background", CarToast.LENGTH_SHORT).show()
                }
                .build()
        )

        // Riga 2: Protezione Blocco Dominio Anti-Redirect
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Protezione Blocco Dominio")
                .addText("Popup e redirect aggressivi bloccati automaticamente")
                .setOnClickListener {
                    CarToast.makeText(carContext, "Blocco Dominio Anti-Redirect sempre attivo", CarToast.LENGTH_SHORT).show()
                }
                .build()
        )

        // Riga 3: Sincronizzazione Smartphone
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Controllo Streaming da Smartphone")
                .addText("Naviga sul web o seleziona video dal telefono a veicolo fermo")
                .setOnClickListener {
                    CarToast.makeText(
                        carContext,
                        "Apri TV Cast Browser dal telefono per selezionare un nuovo video",
                        CarToast.LENGTH_LONG
                    ).show()
                }
                .build()
        )

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Aggiorna")
                    .setOnClickListener {
                        invalidate()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("TV Cast Browser")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
}


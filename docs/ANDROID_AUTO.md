# Guida & Architettura Android Auto — TV Cast Browser

TV Cast Browser integra il supporto ad **Android Auto** rispettando al 100% le linee guida del software libero (**FOSS**), senza dipendere da componenti proprietari di Google Play Services.

---

## 1. Architettura Tecnica

Il modulo Android Auto si trova nel package `com.tvcast.browser.auto` ed è composto da due servizi complementari:

### A. `TvCastCarAppService` (Interfaccia Schermo Auto)
* **Classe base**: `androidx.car.app.CarAppService` (AndroidX Jetpack, licenza Apache 2.0).
* **Host Validator**: implementa `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`, permettendo l'esecuzione sicura su schermi auto reali, adattatori wireless (AAWireless, CarlinKit, Carlinkit TBox) ed emulatori di test Desktop Head Unit (DHU).
* **Sessione & Schermo**: `TvCastCarSession` genera `TvCastMainCarScreen`, che visualizza:
  - Stato del motore Cast FOSS e del buffer RAM a 100 MB.
  - Notifiche sullo stato del Blocco Dominio Anti-Redirect.
  - Pulsante per portare in primo piano il browser web sullo smartphone.
  - Pulsante di aggiornamento rapido nella barra delle azioni (*ActionStrip*).

### B. `TvCastMediaBrowserService` (Widget Multimediale & Comandi al Volante)
* **Classe base**: `androidx.media.MediaBrowserServiceCompat`.
* **Integrazione**: registra TV Cast Browser nel cassetto delle sorgenti audio dell'auto.
* **Controlli supportati**: Play, Pausa, Stop, gestione traccia sia dal widget sul cruscotto che dai comandi fisici sul volante dell'auto.

---

## 2. Come abilitare l'app in auto (Guida per l'utente)

Poiché l'app è distribuita tramite **F-Droid / GitHub** (e non dal Google Play Store), per vederla comparire nel display dell'auto è necessario abilitare una tantum le **Origini sconosciute** nelle impostazioni di Android Auto sul proprio telefono:

1. Sullo smartphone, apri le **Impostazioni** di Android e cerca **Android Auto**.
2. Scorri fino in fondo alla pagina fino alla voce **Versione** (o *Info versione e autorizzazioni*).
3. Tocca ripetutamente la voce **Versione** per **10 volte consecutive** finché non compare il messaggio: *"Consenti impostazioni di sviluppo?"*. Premi **OK**.
4. Tocca il menu con i **tre puntini in alto a destra** e seleziona **Impostazioni sviluppatore**.
5. Scorri l'elenco e attiva la spunta su: **"Origini sconosciute"** (*Unknown sources*).
6. *(Opzionale)*: nella sezione **Personalizza Avvio app** di Android Auto, assicurati che la casella accanto a **TV Cast Browser** sia spuntata.

Collega ora il telefono all'auto via cavo o wireless: **TV Cast Browser** sarà visibile e utilizzabile direttamente dallo schermo dell'infotainment!

---

## 3. Guida per gli sviluppatori: Test con Desktop Head Unit (DHU)

Per testare l'interfaccia di Android Auto su PC senza dover andare in auto:

1. Collega lo smartphone Android al PC con debug USB attivo (`adb devices`).
2. Sullo smartphone, apri le impostazioni di Android Auto -> tre puntini in alto a destra -> seleziona **Avvia server unità principale** (*Start Head Unit Server*).
3. Sul terminale PowerShell del PC, inoltra la porta con:
   ```powershell
   adb forward tcp:5277 tcp:5277
   ```
4. Avvia l'emulatore Desktop Head Unit (DHU) dall'Android SDK:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
   ```
5. Si aprirà la finestra dell'infotainment dell'auto simulata sul PC, dove potrai interagire con TV Cast Browser!


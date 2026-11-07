# Come installare TV Cast Browser su iPhone (da Windows)

Poiché Apple non consente di compilare app iOS nativamente su Windows né di installare pacchetti direttamente dal browser come su Android, il progetto include:
1. **L'app nativa iOS completa in SwiftUI** nella cartella [`ios/`](file:///c:/Users/casua/tv-cast-browser/ios/).
2. **Il compilatore automatico Cloud gratuito** ([`.github/workflows/build-ios-ipa.yml`](file:///c:/Users/casua/tv-cast-browser/.github/workflows/build-ios-ipa.yml)) che genera il file **`TVCastBrowser-iOS.ipa`** usando i server macOS di GitHub.

---

## Passo 1 — Genera e scarica il file `TVCastBrowser-iOS.ipa`
1. Carica questa cartella (`C:\Users\casua\tv-cast-browser`) su un tuo repository GitHub (anche privato).
2. Vai nella scheda **Actions** del repository su GitHub -> seleziona **Build iOS IPA & Android APK** -> clicca **Run workflow**.
3. Al termine (circa 1 minuto), scarica il pacchetto **`TVCastBrowser-iOS-IPA`** (contiene `TVCastBrowser-iOS.ipa`).

---

## Passo 2 — Installa `.ipa` su iPhone da Windows con Sideloadly (Gratuito)
1. Scarica e installa **[Sideloadly](https://sideloadly.io/)** sul tuo PC Windows (assicurati di avere anche iTunes e iCloud versione desktop — non Microsoft Store — installati sul PC).
2. Collega l'iPhone al PC via cavo (dopo la prima installazione potrai aggiornare anche via Wi-Fi).
3. Apri **Sideloadly**:
   * Trascina il file `TVCastBrowser-iOS.ipa` nel riquadro **IPA** in alto a sinistra.
   * Inserisci il tuo normale **Apple ID** (email).
   * Clicca **Start** e inserisci la password dell'Apple ID quando richiesto.
4. Sull'iPhone:
   * Vai in **Impostazioni -> Generali -> VPN e gestione dispositivi** -> tocca il tuo Apple ID sotto *App sviluppatore* e seleziona **Autorizza**.
   * Se sei su iOS 16 o successivo, attiva anche **Modalità sviluppatore** in **Impostazioni -> Privacy e sicurezza -> Modalità sviluppatore**.
   * Alla prima apertura dell'app su iPhone, quando iOS chiede *"TV Cast Browser vorrebbe trovare dispositivi sulla tua rete locale"*, tocca **Consenti** (indispensabile per trovare la Chromecast / Smart TV!).
5. **Nota sull'Apple ID gratuito**: Le app firmate con un account Apple gratuito durano 7 giorni; lasciando attivo il demone Wi-Fi di Sideloadly (o usando **AltStore**), il rinnovo avviene automaticamente quando l'iPhone è sotto la stessa rete Wi-Fi del PC.


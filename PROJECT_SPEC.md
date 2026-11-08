# TV Cast Browser - Specifica di Progetto & Architettura
# TV Cast Browser - Specifica di Progetto & Architettura (v1.7.0)

## 1. Visione del Progetto
**TV Cast Browser** è un'applicazione Android progettata per navigare sul web, intercettare automaticamente i flussi video presenti nelle pagine (HTML5 `<video>`, flussi HLS `.m3u8`, DASH `.mpd`, `.mp4`, `.webm`) e trasmetterli (Cast) direttamente a una Smart TV sulla stessa rete Wi-Fi, trasformando lo smartphone in un telecomando multimediale.
**TV Cast Browser** è un'applicazione open-source (100% FOSS) per Android e iOS progettata per navigare sul web, intercettare automaticamente flussi video (HLS `.m3u8`, MP4, DASH `.mpd`), neutralizzare popup e redirect aggressivi tramite **Blocco Dominio**, e trasmettere (Cast) i video a Smart TV, Chromecast, dispositivi DLNA/UPnP e **Android Auto**, con un buffer RAM dedicato fino a 100 MB e proxy locale di decrittazione HLS AES-128.

---

## 2. Architettura Tecnica (4 Moduli Core)
## 2. Architettura del Sistema

```
+-----------------------------------------------------------------------+
|                           UI Layer (Compose)                          |
|  [Barra URL + AdBlock]  [WebView]  [Sheet Video Rilevati]  [Remote]   |
+-----------------------------------+-----------------------------------+
                                    |
              +---------------------+---------------------+
              |                                           |
              v                                           v
+---------------------------+               +---------------------------+
|   Media Sniffer Engine    |               |    TV Cast Controller     |
| - Network Interceptor     |               | - Google Cast SDK         |
| - DOM JS Video Scanner    |               | - DLNA / UPnP (Fase 2)    |
| - Header Capture (Referer)|               | - Local Proxy Server      |
+---------------------------+               +---------------------------+
+-----------------------------------------------------------------------------------+
|                              Livello Interfaccia Utente                           |
|  [Android: Jetpack Compose Material 3]           [iOS: SwiftUI 15.0+]             |
|  [Barra URL + Lucchetto Blocco Dominio]          [Guida Utente 10 Lingue]         |
|  [Sheet Media Rilevati (Badge HLS/MP4)]          [Android Auto CarApp & Media]    |
+-----------------------------------------+-----------------------------------------+
                                          |
        +---------------------------------+---------------------------------+
        |                                                                   |
        v                                                                   v
+-------------------------------+                         +---------------------------------+
|   Media Sniffer & Sicurezza   |                         |      Motore di Casting FOSS     |
| - Intercettore Rete HTTP      |                         | - Sockets TLS CastV2 (Google)   |
| - Scanner DOM JavaScript      |                         | - SSDP & DLNA/UPnP AVTransport  |
| - Blocco Dominio Anti-Redirect|                         | - Buffer RAM Dinamico (100 MB)  |
| - Filtro AdBlock Popup        |                         | - Local Proxy HLS (AES-128 Decr)|
+-------------------------------+                         +---------------------------------+
```

### Modulo 1: Custom WebView & AdBlock (`sniffer/MediaSniffer.kt` + `ui/BrowserCastScreen.kt`)
* **Navigazione Web**: Supporto completo HTML5, JavaScript, DOM Storage, gestione schermo intero e blocco popup indesiderati (molto frequenti sui siti di streaming).
* **Filtro AdBlock base**: Intercetta in `shouldInterceptRequest` le richieste verso domini di advertising/tracking noti, velocizzando il caricamento e riducendo i falsi positivi (video pubblicitari).
---

### Modulo 2: Media Sniffer (Rilevamento Video) (`sniffer/MediaSniffer.kt`)
* **Livello Rete (`shouldInterceptRequest`)**: Analizza ogni URL richiesto dalla pagina web e cattura gli header HTTP associati (`Referer`, `User-Agent`, `Origin`, `Cookie`). Riconosce estensioni e pattern di streaming:
  * **HLS (HTTP Live Streaming)**: `.m3u8` -> `application/x-mpegURL`
  * **DASH**: `.mpd` -> `application/dash+xml`
  * **Progressivi**: `.mp4`, `.webm`, `.mkv` -> `video/mp4`, `video/webm`
* **Livello DOM (`@JavascriptInterface`)**: Inietta un observer JavaScript che monitora tutti gli elementi `<video>` e `<source>` creati dinamicamente nella pagina.
## 3. Moduli Core Implementati

### Modulo 3: Motore di Casting (`cast/TvCastManager.kt` & `cast/CastOptionsProvider.kt`)
* **Google Cast SDK**: Compatibile con tutte le Android TV, Google TV, Chromecast e Smart TV con Chromecast built-in.
* **Predisposizione Proxy HTTP Locale**: Molti siti generano link video protetti da `Referer` o `Cookie` di sessione. Quando la Smart TV prova a scaricare direttamente l'URL, riceve `403 Forbidden`. Un piccolo server HTTP locale sul telefono (`http://192.168.x.x:8080/stream`) scarica il flusso usando gli header del browser e lo serve alla TV.
* **Estensione DLNA / UPnP (Fase 2)**: Per Smart TV Samsung (Tizen) e LG (webOS) che utilizzano il protocollo `AVTransport` UPnP.
### Modulo 1: Browser con Blocco Dominio & AdBlock (`sniffer/`, `ui/`)
* **Blocco Dominio Anti-Redirect**: Funzionalità di sicurezza attiva impostando la pagina principale dal lucchetto. Blocca a livello nativo (WebViewClient / WKNavigationDelegate) qualsiasi apertura di scheda, finestra o navigazione verso domini di terze parti non autorizzati. Elimina falsi tasti Play, tab spazzatura e redirect forzati tipici dei siti di streaming.
* **AdBlock Integrato**: Intercetta e annulla richieste verso domini pubblicitari e script di tracciamento.

### Modulo 4: Telecomando e Gestione Sessione (`ui/BrowserCastScreen.kt`)
* **Selezione Qualità/Flusso**: Bottom Sheet che mostra tutti i flussi catturati nella pagina corrente con badge del formato (`HLS`, `MP4`, `DASH`).
* **Controlli Remoti**: Play/Pausa, Stop, stato di riproduzione e avanzamento sincronizzati con la TV.
### Modulo 2: Media Sniffer (`sniffer/MediaSniffer.kt`, `ios/.../WebMediaSniffer.swift`)
* Rileva istantaneamente stream video HLS (`.m3u8`), MP4 e DASH sia tramite analisi delle chiamate di rete (`shouldInterceptRequest`) che tramite iniezione JS nel DOM.
* Cattura e memorizza gli header di sessione (`Referer`, `User-Agent`, `Cookie`) necessari per riprodurre stream protetti.

### Modulo 3: Motore Cast 100% FOSS (`cast/`, `ios/.../Cast/`)
* **Zero dipendenze proprietarie Google Play Services**: Usa socket diretti TLS CastV2 (`urn:x-cast:com.google.cast.*`) compatibili con le policy F-Droid.
* **Buffer RAM 100 MB (`RamStreamBuffer`)**: Pre-carica e conserva in memoria RAM fino a 100 MB di flusso per garantire una riproduzione fluida e ininterrotta senza scrivere su disco.
* **Proxy Locale di Decrittazione HLS AES-128 (`proxy/LocalStreamProxy.kt`)**: Decodifica in locale i segmenti video HLS protetti da chiavi con restrizioni CORS/Referer, servendoli alla TV decrittati in chiaro.

### Modulo 4: Guida Utente Interattiva Multilingue (`ui/UserGuideDialog.kt`, `ui/UserGuideView.swift`)
* Guida a schede animate (primo avvio o richiamabile da Menu).
* Tradotta in **10 lingue**: Italiano, Inglese, Tedesco, Francese, Spagnolo, Portoghese, Cinese (semplificato), Giapponese, Arabo, Ucraino. *(Vincolo rigido di progetto: nessuna traduzione russa).*

### Modulo 5: Integrazione Android Auto (`auto/`)
* **`TvCastCarAppService`**: Servizio `CarAppService` di AndroidX Jetpack con `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`, che proietta sullo schermo dell'auto l'interfaccia FOSS per gestire flussi, stato del buffer e avvio rapido.
* **`TvCastMediaBrowserService`**: Servizio `MediaBrowserServiceCompat` che espone TV Cast Browser come lettore audio in Android Auto, consentendo di ascoltare i flussi video e usare i comandi al volante (Play, Pausa, traccia).

---

## 3. Struttura della Directory (`C:\Users\casua\tv-cast-browser`)
## 4. Struttura del Repository

```text
C:\Users\casua\tv-cast-browser\
├── PROJECT_SPEC.md                 # Documento di specifica e roadmap
├── settings.gradle.kts             # Configurazione moduli Gradle
├── build.gradle.kts                # Root build file
├── gradle.properties               # Proprietà JVM e AndroidX
├── gradle/
│   └── libs.versions.toml          # Catalogo dipendenze (Compose, Cast SDK, WebKit)
└── app/
    ├── build.gradle.kts            # Configurazione modulo app
    ├── proguard-rules.pro          # Regole offuscamento
    └── src/main/
        ├── AndroidManifest.xml     # Permessi rete e configurazione CastOptionsProvider
        ├── res/values/
        │   ├── strings.xml
        │   └── themes.xml
        └── java/com/tvcast/browser/
            ├── MainActivity.kt             # Activity principale (FragmentActivity)
            ├── model/
            │   └── DetectedMedia.kt        # Data class per flussi video e tipi stream
            ├── sniffer/
            │   └── MediaSniffer.kt         # Intercettore rete + JS bridge + AdBlock
            ├── proxy/
            │   └── LocalStreamProxy.kt     # Server HTTP Proxy locale (Referer, Cookie, CORS, HLS rewrite)
            ├── cast/
            │   ├── CastOptionsProvider.kt  # Configurazione Google Cast Receiver
            │   └── TvCastManager.kt        # Gestore sessioni TV e invio flussi
            └── ui/
                └── BrowserCastScreen.kt    # UI Jetpack Compose (Browser + Sheet + Remote)
tv-cast-browser/
├── app/                                # Modulo Android (Kotlin + Jetpack Compose)
│   ├── build.gradle.kts                # Configurazione Gradle (R8, shrinking, F-Droid flags)
│   └── src/main/
│       ├── AndroidManifest.xml         # Permessi, servizi Foreground e Android Auto
│       ├── java/com/tvcast/browser/
│       │   ├── auto/                   # Modulo Android Auto (CarAppService + MediaBrowser)
│       │   ├── cast/                   # Motore Cast FOSS (CastV2 TLS, DLNA SSDP, Buffer RAM)
│       │   ├── data/                   # Preferenze persistenti (DataStore)
│       │   ├── model/                  # Data class DetectedMedia e formati
│       │   ├── proxy/                  # LocalStreamProxy HTTP e decrittatore HLS
│       │   ├── sniffer/                # MediaSniffer e blocco domini
│       │   └── ui/                     # BrowserCastScreen e UserGuideDialog
│       └── res/
│           ├── xml/automotive_app_desc.xml # Descrittore Android Auto
│           └── values-*/strings.xml    # Risorse localizzate (10 lingue)
├── ios/                                # Modulo iOS (Swift 15.0+ SwiftUI)
│   ├── project.yml                     # Specifica XcodeGen
│   └── TVCastBrowser/                  # Sorgenti Swift e Localizable.strings
├── fastlane/metadata/android/          # Metadati Fastlane per F-Droid & IzzyOnDroid
├── metadata/com.tvcast.browser.yml     # Ricetta F-Droid ufficiale
├── docs/                               # Documentazione di dettaglio (es. ANDROID_AUTO.md)
└── .github/workflows/build-ios-ipa.yml # CI/CD per APK Android e .ipa iOS (macOS runner)
```

---

## 4. Roadmap di Sviluppo
## 5. Canali di Distribuzione

- [x] **Milestone 1 (Bozza Architetturale - Completata)**:
  - Struttura progetto Gradle (Kotlin + Jetpack Compose + Material 3).
  - Browser `WebView` con barra URL, navigazione e AdBlock base.
  - `MediaSniffer` (Network + JS DOM) per flussi `MP4`, `HLS (.m3u8)` e `DASH (.mpd)`.
  - Integrazione `Google Cast SDK` (`TvCastManager` + `CastOptionsProvider`).
  - UI per selezione dei video rilevati e mini-player di controllo TV.
- [x] **Milestone 2 (Local Stream Proxy - Completata)**:
  - Server HTTP locale (`LocalStreamProxy`) sulla rete Wi-Fi dello smartphone (`http://<ip-telefono>:8765/stream/...`) che inietta `Referer`, `Cookie` e `User-Agent`, aggiunge gli header CORS per Chromecast, supporta `Range` HTTP 206 e riscrive al volo le playlist HLS (`.m3u8`).
- [ ] **Milestone 3 (Supporto DLNA / UPnP)**:
  - Discovery SSDP sulla rete locale per TV LG/Samsung non-Chromecast.
- [ ] **Milestone 4 (Funzionalità Browser Avanzate)**:
  - Segnalibri, cronologia, gestione multi-tab e supporto sottotitoli `.vtt`/`.srt`.

| Piattaforma | Canale | Formato | Stato |
| :--- | :--- | :--- | :--- |
| **Android** | F-Droid Ufficiale (RFP) | Codice sorgente compilato da F-Droid | Richiesta `fdroid/rfp#4761` aperta |
| **Android** | GitHub Releases | APK Release (`TVCastBrowser-v1.x.apk`) | 100% Funzionante, minificato R8 |
| **Android Auto**| Sideloading (Unknown Sources / DHU) | Integrato nell'APK | Attivo (`auto/`) |
| **iOS** | GitHub Releases | File `.ipa` (Sideloadly / AltStore) | Compilato da GitHub Actions macOS |

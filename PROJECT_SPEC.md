# TV Cast Browser - Specifica di Progetto & Architettura (v1.7.0)

## 1. Visione del Progetto
**TV Cast Browser** è un'applicazione open-source (100% FOSS) per Android e iOS progettata per navigare sul web, intercettare automaticamente flussi video (HLS `.m3u8`, MP4, DASH `.mpd`), neutralizzare popup e redirect aggressivi tramite **Blocco Dominio**, e trasmettere (Cast) i video a Smart TV, Chromecast, dispositivi DLNA/UPnP e **Android Auto**, con un buffer RAM dedicato fino a 100 MB e proxy locale di decrittazione HLS AES-128.

---

## 2. Architettura del Sistema

```
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

---

## 3. Moduli Core Implementati

### Modulo 1: Browser con Blocco Dominio & AdBlock (`sniffer/`, `ui/`)
* **Blocco Dominio Anti-Redirect**: Funzionalità di sicurezza attiva impostando la pagina principale dal lucchetto. Blocca a livello nativo (WebViewClient / WKNavigationDelegate) qualsiasi apertura di scheda, finestra o navigazione verso domini di terze parti non autorizzati. Elimina falsi tasti Play, tab spazzatura e redirect forzati tipici dei siti di streaming.
* **AdBlock Integrato**: Intercetta e annulla richieste verso domini pubblicitari e script di tracciamento.

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

## 4. Struttura del Repository

```text
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

## 5. Canali di Distribuzione

| Piattaforma | Canale | Formato | Stato |
| :--- | :--- | :--- | :--- |
| **Android** | F-Droid Ufficiale (RFP) | Codice sorgente compilato da F-Droid | Richiesta `fdroid/rfp#4761` aperta |
| **Android** | GitHub Releases | APK Release (`TVCastBrowser-v1.x.apk`) | 100% Funzionante, minificato R8 |
| **Android Auto**| Sideloading (Unknown Sources / DHU) | Integrato nell'APK | Attivo (`auto/`) |
| **iOS** | GitHub Releases | File `.ipa` (Sideloadly / AltStore) | Compilato da GitHub Actions macOS |

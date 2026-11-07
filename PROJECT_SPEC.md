# TV Cast Browser - Specifica di Progetto & Architettura

## 1. Visione del Progetto
**TV Cast Browser** è un'applicazione Android progettata per navigare sul web, intercettare automaticamente i flussi video presenti nelle pagine (HTML5 `<video>`, flussi HLS `.m3u8`, DASH `.mpd`, `.mp4`, `.webm`) e trasmetterli (Cast) direttamente a una Smart TV sulla stessa rete Wi-Fi, trasformando lo smartphone in un telecomando multimediale.

---

## 2. Architettura Tecnica (4 Moduli Core)

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
```

### Modulo 1: Custom WebView & AdBlock (`sniffer/MediaSniffer.kt` + `ui/BrowserCastScreen.kt`)
* **Navigazione Web**: Supporto completo HTML5, JavaScript, DOM Storage, gestione schermo intero e blocco popup indesiderati (molto frequenti sui siti di streaming).
* **Filtro AdBlock base**: Intercetta in `shouldInterceptRequest` le richieste verso domini di advertising/tracking noti, velocizzando il caricamento e riducendo i falsi positivi (video pubblicitari).

### Modulo 2: Media Sniffer (Rilevamento Video) (`sniffer/MediaSniffer.kt`)
* **Livello Rete (`shouldInterceptRequest`)**: Analizza ogni URL richiesto dalla pagina web e cattura gli header HTTP associati (`Referer`, `User-Agent`, `Origin`, `Cookie`). Riconosce estensioni e pattern di streaming:
  * **HLS (HTTP Live Streaming)**: `.m3u8` -> `application/x-mpegURL`
  * **DASH**: `.mpd` -> `application/dash+xml`
  * **Progressivi**: `.mp4`, `.webm`, `.mkv` -> `video/mp4`, `video/webm`
* **Livello DOM (`@JavascriptInterface`)**: Inietta un observer JavaScript che monitora tutti gli elementi `<video>` e `<source>` creati dinamicamente nella pagina.

### Modulo 3: Motore di Casting (`cast/TvCastManager.kt` & `cast/CastOptionsProvider.kt`)
* **Google Cast SDK**: Compatibile con tutte le Android TV, Google TV, Chromecast e Smart TV con Chromecast built-in.
* **Predisposizione Proxy HTTP Locale**: Molti siti generano link video protetti da `Referer` o `Cookie` di sessione. Quando la Smart TV prova a scaricare direttamente l'URL, riceve `403 Forbidden`. Un piccolo server HTTP locale sul telefono (`http://192.168.x.x:8080/stream`) scarica il flusso usando gli header del browser e lo serve alla TV.
* **Estensione DLNA / UPnP (Fase 2)**: Per Smart TV Samsung (Tizen) e LG (webOS) che utilizzano il protocollo `AVTransport` UPnP.

### Modulo 4: Telecomando e Gestione Sessione (`ui/BrowserCastScreen.kt`)
* **Selezione Qualità/Flusso**: Bottom Sheet che mostra tutti i flussi catturati nella pagina corrente con badge del formato (`HLS`, `MP4`, `DASH`).
* **Controlli Remoti**: Play/Pausa, Stop, stato di riproduzione e avanzamento sincronizzati con la TV.

---

## 3. Struttura della Directory (`C:\Users\casua\tv-cast-browser`)

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
```

---

## 4. Roadmap di Sviluppo

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


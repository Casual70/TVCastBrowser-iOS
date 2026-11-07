# TV Cast Browser (Android & iOS)

**TV Cast Browser** è un browser web leggero e **100% Open Source (FOSS)** progettato per rilevare flussi multimediali HTML5 / HLS (`.m3u8`) / DASH (`.mpd`) / MP4 nelle pagine web e trasmetterli direttamente a una Smart TV o Chromecast sulla rete Wi-Fi locale.

## Caratteristiche principali
- **100% FOSS (Nessuna dipendenza proprietaria Google Play Services)**:
  - Implementazione nativa del protocollo **CASTv2 su TLS (porta 8009)** e scoperta **mDNS / DNS-SD (`_googlecast._tcp`)**.
- **Rilevamento Flussi Video Avanzato**:
  - Intercetta flussi `.m3u8` (HLS), `.mpd` (DASH), `.mp4` e `.webm` sia a livello di rete sia all'interno di `<iframe>` cross-origin.
- **Proxy HTTP Locale Integrato**:
  - Server HTTP locale sullo smartphone che inoltra il flusso video alla Smart TV allegando automaticamente `Referer`, `Cookie` e `User-Agent`, aggiungendo gli header CORS e riscrivendo al volo i manifest HLS (`#EXTM3U`).
- **Blocco Dominio Principale & Anti-Popup**:
  - Possibilità di fissare un sito come **Pagina Principale** bloccando automaticamente ogni tentativo di aprire nuove schede (`target="_blank"`, `window.open`) o redirect esterni fuori dal dominio principale.
- **Cronologia Recenti**:
  - Memorizza localmente gli ultimi 20 siti visitati.

## Compilazione da sorgente (Android)
```bash
./gradlew assembleRelease
```
L'APK firmato viene generato in `app/build/outputs/apk/release/app-release.apk`.

## Licenza
Rilasciato sotto licenza [MIT](LICENSE).


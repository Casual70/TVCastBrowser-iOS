package com.tvcast.browser.proxy

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.model.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class ProxyBufferStats(
    val cachedSegmentsCount: Int = 0,
    val cachedBytes: Long = 0L,
    val maxCacheBytes: Long = 100L * 1024L * 1024L,
    val activePrefetches: Int = 0,
    val warmupRemainingSeconds: Int = 0,
    val isBufferingUnlocked: Boolean = false,
    val totalRetries: Int = 0
) {
    val cachedMegabytes: String
        get() = String.format(java.util.Locale.US, "%.1f MB", cachedBytes / (1024.0 * 1024.0))

    val maxMegabytes: String
        get() = String.format(java.util.Locale.US, "%.0f MB", maxCacheBytes / (1024.0 * 1024.0))
}

/**
 * Server HTTP Proxy locale sullo smartphone con:
 * 1. **Pre-Buffer RAM fino a 100 MB con Attivazione Intelligente dopo 30 secondi**:
 *    - Quando inizia la riproduzione o l'utente avanza manualmente (seek) a un nuovo punto del video,
 *      il proxy attende **30 secondi di riproduzione continua** prima di avviare l'immagazzinamento
 *      massivo in RAM (così la scelta del punto video è istantanea e non spreca banda).
 *    - Trascorsi 30 secondi sul punto scelto, il proxy riempie fino a **100 MB di buffer RAM**
 *      scaricando in anticipo i segmenti successivi (sia video che audio separato).
 *    - Se l'utente avanza manualmente verso un punto già presente nei 100 MB di buffer, il buffer
 *      NON viene perso e viene servito istantaneamente dalla RAM.
 * 2. **Protezione totale contro crash da Socket Abort (`SocketException`)**:
 *    - Quando la TV chiude una connessione HTTP durante un seek, l'eccezione viene gestita
 *      silenziosamente senza mai far crashare i thread del proxy.
 */
class LocalStreamProxy(private val preferredPort: Int = 8765) {

    data class ProxySessionConfig(
        val rootUrl: String,
        val pageUrl: String,
        val headers: Map<String, String>,
        val cookies: String?
    )

    private class CachedSegment(
        val bytes: ByteArray,
        val contentType: String,
        val playlistId: String?,
        val segmentIndex: Int,
        val createdAt: Long = System.currentTimeMillis()
    )

    private data class SegmentMeta(
        val playlistId: String,
        val index: Int,
        val sessionId: String
    )

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val clientExecutor = Executors.newCachedThreadPool()
    private val prefetchExecutor = Executors.newSingleThreadExecutor()
    private val warmupTimerExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val sessions = ConcurrentHashMap<String, ProxySessionConfig>()

    // Cache LRU in RAM dei segmenti video/audio pre-scaricati (fino a 100 MB)
    private val cacheLock = Any()
    private var currentCacheBytes: Long = 0L
    private val segmentCache = LinkedHashMap<String, CachedSegment>()
    private val prefetchEpoch = AtomicLong(0L)

    // Download attualmente in corso (deduplicazione tra richiesta TV e Pre-Fetcher)
    private val inFlightSegments = ConcurrentHashMap<String, CompletableFuture<CachedSegment?>>()

    // Metadati e sequenza segmenti per ciascuna sub-playlist (es. traccia video e traccia audio)
    private val segmentMetaMap = ConcurrentHashMap<String, SegmentMeta>()
    private val playlistSegmentsMap = ConcurrentHashMap<String, List<String>>()
    private val lastIndexByPlaylist = ConcurrentHashMap<String, Int>()
    private val lastActiveSegmentByPlaylist = ConcurrentHashMap<String, String>()

    // Timestamp di inizio riproduzione sequenziale stabile (per la soglia dei 30 secondi)
    private val sequentialStartTimeMs = AtomicLong(0L)
    @Volatile private var activeSessionConfig: ProxySessionConfig? = null

    private val activePrefetchCount = AtomicInteger(0)
    private val totalRetriesCount = AtomicInteger(0)

    private val _bufferStats = MutableStateFlow(ProxyBufferStats())
    val bufferStats: StateFlow<ProxyBufferStats> = _bufferStats.asStateFlow()

    var boundPort: Int = preferredPort
        private set

    init {
        // Aggiorna ogni secondo il countdown dei 30s e sblocca automaticamente il buffer a 30s
        warmupTimerExecutor.scheduleAtFixedRate({
            try {
                val startMs = sequentialStartTimeMs.get()
                if (startMs > 0L) {
                    val elapsed = System.currentTimeMillis() - startMs
                    updateStatsFlow()
                    // Appena superati i 30 secondi di riproduzione stabile, avvia il riempimento dei 100 MB!
                    if (elapsed in PREBUFFER_DELAY_MS..(PREBUFFER_DELAY_MS + 1500L)) {
                        triggerAllActivePlaylistsPrefetch()
                    }
                }
            } catch (_: Throwable) {
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    @Synchronized
    fun start() {
        if (isRunning.get()) return
        try {
            val socket = try {
                ServerSocket(preferredPort)
            } catch (e: Exception) {
                ServerSocket(0)
            }
            serverSocket = socket
            boundPort = socket.localPort
            isRunning.set(true)

            clientExecutor.execute {
                Log.i(TAG, "LocalStreamProxy avviato sulla porta $boundPort (Buffer 100MB dopo 30s)")
                while (isRunning.get()) {
                    try {
                        val client = socket.accept()
                        clientExecutor.execute {
                            try {
                                handleClient(client)
                            } catch (t: Throwable) {
                                // Ignora SocketException quando la TV chiude una connessione durante un seek
                            }
                        }
                    } catch (t: Throwable) {
                        if (isRunning.get()) {
                            Log.w(TAG, "Errore accept client proxy: ${t.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile avviare LocalStreamProxy", e)
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        clearBufferCache()
        sessions.clear()
    }

    fun clearBufferCache() {
        prefetchEpoch.incrementAndGet()
        synchronized(cacheLock) {
            segmentCache.clear()
            currentCacheBytes = 0L
        }
        inFlightSegments.clear()
        segmentMetaMap.clear()
        playlistSegmentsMap.clear()
        lastIndexByPlaylist.clear()
        lastActiveSegmentByPlaylist.clear()
        sequentialStartTimeMs.set(0L)
        updateStatsFlow()
    }

    /**
     * Chiamato quando l'utente effettua un seek manuale dalla barra di controllo dell'app:
     * mantiene i segmenti già presenti in cache (nel caso il seek cada dentro i 100 MB già scaricati)
     * e riavvia il timer di stabilità di 30 secondi se la nuova posizione è fuori buffer.
     */
    fun notifyManualSeek() {
        prefetchEpoch.incrementAndGet()
        sequentialStartTimeMs.set(System.currentTimeMillis())
        updateStatsFlow()
    }

    fun createProxiedUrl(media: DetectedMedia): String {
        start()
        val sessionId = Integer.toHexString(media.url.hashCode())
        // Svuota il buffer solo se stiamo cambiando film/video
        if (!sessions.containsKey(sessionId)) {
            clearBufferCache()
        }

        val lanIp = findLocalLanIpAddress() ?: return media.url

        val cookieHeader = try {
            CookieManager.getInstance().getCookie(media.url)
                ?: media.pageUrl.takeIf { it.isNotBlank() }?.let { CookieManager.getInstance().getCookie(it) }
        } catch (_: Throwable) {
            null
        }

        val config = ProxySessionConfig(
            rootUrl = media.url,
            pageUrl = media.pageUrl,
            headers = media.headers,
            cookies = cookieHeader
        )
        sessions[sessionId] = config
        activeSessionConfig = config

        if (sequentialStartTimeMs.get() == 0L) {
            sequentialStartTimeMs.set(System.currentTimeMillis())
        }

        val encodedTarget = encodeUrl(media.url)
        val extHint = when (media.format) {
            StreamFormat.HLS -> "playlist.m3u8"
            StreamFormat.DASH -> "manifest.mpd"
            StreamFormat.WEBM -> "video.webm"
            else -> "video.mp4"
        }

        return "http://$lanIp:$boundPort/stream/$extHint?s=$sessionId&u=$encodedTarget"
    }

    fun findLocalLanIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackIp: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("dummy")) continue

                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                            return ip
                        }
                        if (fallbackIp == null) {
                            fallbackIp = ip
                        }
                    }
                }
            }
            return fallbackIp
        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura IP LAN", e)
            return null
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 45_000
                client.keepAlive = true
                client.tcpNoDelay = true
                client.sendBufferSize = 256 * 1024

                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val rawOutput = BufferedOutputStream(client.getOutputStream(), 64 * 1024)

                val requestLine = try {
                    reader.readLine()
                } catch (_: Throwable) {
                    null
                } ?: return

                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val pathAndQuery = parts[1]

                val clientHeaders = mutableMapOf<String, String>()
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (_: Throwable) {
                        break
                    }
                    if (line.isNullOrEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        clientHeaders[key] = value
                    }
                }

                if (method == "OPTIONS") {
                    writeCorsPreflight(rawOutput)
                    return
                }

                val pathPart = pathAndQuery.substringBefore("?")
                val query = pathAndQuery.substringAfter("?", "")
                val params = parseQueryParams(query)
                val sessionId = params["s"]
                val encodedUrl = params["u"]

                if (encodedUrl.isNullOrBlank()) {
                    writeError(rawOutput, 400, "Missing 'u' parameter")
                    return
                }

                val targetUrl = try {
                    decodeUrl(encodedUrl)
                } catch (e: Exception) {
                    writeError(rawOutput, 400, "Invalid 'u' parameter")
                    return
                }

                val sessionConfig = sessionId?.let { sessions[it] } ?: activeSessionConfig
                if (sessionConfig != null) {
                    activeSessionConfig = sessionConfig
                }
                val hostHeader = clientHeaders.entries.firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value
                    ?: "${findLocalLanIpAddress() ?: "127.0.0.1"}:$boundPort"
                val rangeHeader = clientHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value

                val isSegmentRequest = pathPart.endsWith("seg.ts") || pathPart.endsWith("seg.m4s")

                if (isSegmentRequest && (method == "GET" || method == "HEAD")) {
                    serveBufferedSegment(
                        method = method,
                        targetUrl = targetUrl,
                        sessionConfig = sessionConfig,
                        rangeHeader = rangeHeader,
                        output = rawOutput
                    )
                    return
                }

                proxyUpstreamRequest(
                    method = method,
                    targetUrl = targetUrl,
                    sessionId = sessionId ?: "default",
                    sessionConfig = sessionConfig,
                    clientRangeHeader = rangeHeader,
                    proxyHostPort = hostHeader,
                    output = rawOutput
                )
            }
        } catch (_: Throwable) {
            // Connessione chiusa o annullata dalla Smart TV (es. durante il seek): normale, non propagare
        }
    }

    /**
     * Gestisce la richiesta di un segmento HLS (`.ts` / `.m4s`):
     * - Controlla se l'utente ha effettuato un salto/seek fuori finestra o se la riproduzione
     *   è stabile da almeno 30 secondi.
     * - Se il segmento è già nei 100 MB di RAM, lo serve istantaneamente (anche con Range header!).
     * - Se la riproduzione è stabile da >= 30 secondi, avvia il pre-fetching fino a 100 MB.
     */
    private fun serveBufferedSegment(
        method: String,
        targetUrl: String,
        sessionConfig: ProxySessionConfig?,
        rangeHeader: String?,
        output: OutputStream
    ) {
        val alreadyInRam = synchronized(cacheLock) { segmentCache.containsKey(targetUrl) }
        val meta = segmentMetaMap[targetUrl]

        if (meta != null) {
            val prevIndex = lastIndexByPlaylist.put(meta.playlistId, meta.index)
            lastActiveSegmentByPlaylist[meta.playlistId] = targetUrl

            // Rileva se l'utente ha spostato manualmente il punto di riproduzione (seek fuori dal buffer)
            if (prevIndex == null) {
                if (sequentialStartTimeMs.get() == 0L) {
                    sequentialStartTimeMs.set(System.currentTimeMillis())
                }
            } else if (abs(meta.index - prevIndex) > 4 && !alreadyInRam) {
                // L'utente è saltato a un nuovo punto del video non ancora in buffer:
                // riavvia il timer di 30 secondi e libera i vecchi segmenti precedenti al nuovo punto
                Log.i(TAG, "Rilevato salto/seek manuale (segmento $prevIndex -> ${meta.index}): attendo 30s di stabilità")
                sequentialStartTimeMs.set(System.currentTimeMillis())
                prefetchEpoch.incrementAndGet()
                evictSegmentsBefore(meta.playlistId, meta.index - 1)
            } else {
                // Riproduzione sequenziale normale: libera i segmenti già riprodotti (più vecchi di 2 posizioni)
                // così tutti i 100 MB sono dedicati al video futuro!
                evictSegmentsBefore(meta.playlistId, meta.index - 2)
            }
        }

        // Controlla se sono passati 30 secondi di riproduzione normale
        val startMs = sequentialStartTimeMs.get()
        val elapsedMs = if (startMs > 0L) System.currentTimeMillis() - startMs else 0L
        val isBufferingUnlocked = elapsedMs >= PREBUFFER_DELAY_MS

        if (isBufferingUnlocked) {
            // Riproduzione stabile da >= 30s: pre-carica fino a READ_AHEAD_SEGMENTS (fino a 100 MB)!
            triggerReadAheadForSegment(targetUrl, sessionConfig, maxCount = READ_AHEAD_SEGMENTS)
        } else {
            // Primi 30s (fase scelta punto video): pre-carica 2 segmenti successivi per fluidità immediata
            triggerReadAheadForSegment(targetUrl, sessionConfig, maxCount = 2)
        }
        updateStatsFlow()

        val segment = getOrFetchSegment(targetUrl, sessionConfig)
        if (segment == null) {
            writeError(output, 502, "Segment fetch failed after retries")
            return
        }

        try {
            val totalLen = segment.bytes.size
            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").substringBefore(",")
                val startStr = rangeSpec.substringBefore("-")
                val endStr = rangeSpec.substringAfter("-", "")
                val start = startStr.toIntOrNull()?.coerceIn(0, (totalLen - 1).coerceAtLeast(0)) ?: 0
                val end = endStr.takeIf { it.isNotBlank() }?.toIntOrNull()?.coerceIn(start, totalLen - 1) ?: (totalLen - 1)
                val sliceLen = (end - start + 1).coerceAtLeast(0)

                val headers = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: ${segment.contentType}\r\n")
                    append("Content-Length: $sliceLen\r\n")
                    append("Content-Range: bytes $start-$end/$totalLen\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    appendCorsHeaders()
                    append("\r\n")
                }
                output.write(headers.toByteArray(Charsets.UTF_8))
                if (method != "HEAD" && sliceLen > 0) {
                    output.write(segment.bytes, start, sliceLen)
                }
                output.flush()
            } else {
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${segment.contentType}\r\n")
                    append("Content-Length: $totalLen\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Cache-Control: public, max-age=3600\r\n")
                    appendCorsHeaders()
                    append("\r\n")
                }
                output.write(headers.toByteArray(Charsets.UTF_8))
                if (method != "HEAD") {
                    output.write(segment.bytes)
                }
                output.flush()
            }
        } catch (_: Throwable) {
            // Il client TV ha chiuso lo stream
        }
    }

    private fun evictSegmentsBefore(playlistId: String, minIndexToKeep: Int) {
        if (minIndexToKeep <= 0) return
        synchronized(cacheLock) {
            val iterator = segmentCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val seg = entry.value
                if (seg.playlistId == playlistId && seg.segmentIndex in 0 until minIndexToKeep) {
                    currentCacheBytes = (currentCacheBytes - seg.bytes.size).coerceAtLeast(0L)
                    iterator.remove()
                }
            }
        }
    }

    private fun triggerAllActivePlaylistsPrefetch() {
        val config = activeSessionConfig ?: return
        for ((_, activeSegUrl) in lastActiveSegmentByPlaylist) {
            triggerReadAheadForSegment(activeSegUrl, config, maxCount = READ_AHEAD_SEGMENTS)
        }
    }

    private fun triggerReadAheadForSegment(
        currentSegmentUrl: String,
        sessionConfig: ProxySessionConfig?,
        maxCount: Int
    ) {
        val meta = segmentMetaMap[currentSegmentUrl] ?: return
        val list = playlistSegmentsMap[meta.playlistId] ?: return
        val startIdx = meta.index + 1
        if (startIdx >= list.size) return
        val endIdx = (startIdx + maxCount).coerceAtMost(list.size)
        val upcoming = list.subList(startIdx, endIdx)
        prefetchSegmentsList(upcoming, sessionConfig)
    }

    private fun prefetchSegmentsList(
        urls: List<String>,
        sessionConfig: ProxySessionConfig?
    ) {
        val epoch = prefetchEpoch.get()
        prefetchExecutor.execute {
            for (nextUrl in urls) {
                if (prefetchEpoch.get() != epoch) break
                val canSchedule = synchronized(cacheLock) {
                    !segmentCache.containsKey(nextUrl) && currentCacheBytes < MAX_CACHE_BYTES
                }
                if (!canSchedule) continue

                activePrefetchCount.set(1)
                updateStatsFlow()
                try {
                    getOrFetchSegment(nextUrl, sessionConfig)
                } catch (_: Throwable) {
                } finally {
                    activePrefetchCount.set(0)
                    updateStatsFlow()
                }
            }
        }
    }

    private fun getOrFetchSegment(
        targetUrl: String,
        sessionConfig: ProxySessionConfig?
    ): CachedSegment? {
        synchronized(cacheLock) {
            segmentCache[targetUrl]?.let { return it }
        }

        val inFlightFuture = inFlightSegments[targetUrl]
        if (inFlightFuture != null) {
            try {
                val res = inFlightFuture.get(25, TimeUnit.SECONDS)
                if (res != null) return res
            } catch (_: Throwable) {
            }
        }

        var myFuture: CompletableFuture<CachedSegment?>? = null
        val shouldDownloadDirectly: Boolean
        synchronized(inFlightSegments) {
            val current = inFlightSegments[targetUrl]
            if (current == null || current.isDone || current.isCompletedExceptionally) {
                val newFut = CompletableFuture<CachedSegment?>()
                inFlightSegments[targetUrl] = newFut
                myFuture = newFut
                shouldDownloadDirectly = true
            } else {
                myFuture = current
                shouldDownloadDirectly = false
            }
        }

        if (shouldDownloadDirectly) {
            try {
                val downloaded = downloadSegmentWithRetry(targetUrl, sessionConfig, maxAttempts = 4)
                if (downloaded != null) {
                    putSegmentInCache(targetUrl, downloaded)
                    myFuture?.complete(downloaded)
                    return downloaded
                } else {
                    myFuture?.complete(null)
                    return null
                }
            } catch (t: Throwable) {
                myFuture?.complete(null)
                return null
            } finally {
                inFlightSegments.remove(targetUrl)
            }
        } else {
            return try {
                val res = myFuture?.get(25, TimeUnit.SECONDS)
                if (res != null) {
                    res
                } else {
                    val retryDownloaded = downloadSegmentWithRetry(targetUrl, sessionConfig, maxAttempts = 2)
                    if (retryDownloaded != null) {
                        putSegmentInCache(targetUrl, retryDownloaded)
                    }
                    retryDownloaded
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun downloadSegmentWithRetry(
        targetUrl: String,
        sessionConfig: ProxySessionConfig?,
        maxAttempts: Int = 3
    ): CachedSegment? {
        val meta = segmentMetaMap[targetUrl]
        var attempt = 1
        while (attempt <= maxAttempts) {
            var connection: HttpURLConnection? = null
            try {
                connection = openFollowingRedirects(
                    urlStr = targetUrl,
                    method = "GET",
                    sessionConfig = sessionConfig,
                    rangeHeader = null
                )
                val code = connection.responseCode
                if (code in 200..299) {
                    val contentType = connection.contentType ?: guessMimeType(targetUrl)
                    val expectedLength = connection.contentLengthLong
                    val rawInput = BufferedInputStream(connection.inputStream, 64 * 1024)
                    val bos = ByteArrayOutputStream(
                        if (expectedLength in 1..(16 * 1024 * 1024)) expectedLength.toInt() else 512 * 1024
                    )
                    var readCompleted = false
                    rawInput.use { input ->
                        val buf = ByteArray(32 * 1024)
                        var n = input.read(buf)
                        while (n != -1) {
                            bos.write(buf, 0, n)
                            n = input.read(buf)
                        }
                        readCompleted = true
                    }
                    if (!readCompleted) {
                        throw java.io.IOException("Stream terminato prima dell'EOF completo")
                    }
                    val bytes = bos.toByteArray()
                    if (expectedLength > 0 && bytes.size.toLong() != expectedLength) {
                        throw java.io.IOException("Segmento troncato: ${bytes.size} / $expectedLength byte")
                    }
                    if (bytes.size < 64) {
                        throw java.io.IOException("Segmento anomalo (troppo piccolo: ${bytes.size} byte)")
                    }
                    if (bytes.isNotEmpty()) {
                        return CachedSegment(
                            bytes = bytes,
                            contentType = contentType,
                            playlistId = meta?.playlistId,
                            segmentIndex = meta?.index ?: -1
                        )
                    }
                } else if (code in 400..499 && code != 429 && code != 408) {
                    return null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Retry $attempt/$maxAttempts segmento: ${t.message}")
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Throwable) {
                }
            }

            attempt++
            if (attempt <= maxAttempts) {
                totalRetriesCount.incrementAndGet()
                updateStatsFlow()
                try {
                    Thread.sleep(350L * attempt)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return null
    }

    private fun putSegmentInCache(url: String, segment: CachedSegment) {
        if (segment.bytes.size > MAX_SINGLE_SEGMENT_BYTES) return
        synchronized(cacheLock) {
            val previous = segmentCache.put(url, segment)
            if (previous != null) {
                currentCacheBytes = (currentCacheBytes - previous.bytes.size).coerceAtLeast(0L)
            }
            currentCacheBytes += segment.bytes.size
            enforceCacheLimitLocked()
        }
        updateStatsFlow()
    }

    private fun enforceCacheLimitLocked() {
        while ((currentCacheBytes > MAX_CACHE_BYTES || segmentCache.size > MAX_CACHED_SEGMENTS) && segmentCache.isNotEmpty()) {
            var candidateKey: String? = null
            for ((key, seg) in segmentCache) {
                val plId = seg.playlistId
                val lastIndex = if (plId != null) lastIndexByPlaylist[plId] ?: -1 else -1
                if (lastIndex >= 0 && seg.segmentIndex in 0 until (lastIndex - 2)) {
                    candidateKey = key
                    break
                }
            }
            if (candidateKey == null) {
                var maxFutureDistance = -1
                for ((key, seg) in segmentCache) {
                    val plId = seg.playlistId
                    val lastIndex = if (plId != null) lastIndexByPlaylist[plId] ?: -1 else -1
                    val distance = if (lastIndex >= 0) seg.segmentIndex - lastIndex else 0
                    if (distance > maxFutureDistance) {
                        maxFutureDistance = distance
                        candidateKey = key
                    }
                }
            }
            val keyToRemove = candidateKey ?: segmentCache.keys.firstOrNull() ?: break
            val removed = segmentCache.remove(keyToRemove)
            if (removed != null) {
                currentCacheBytes = (currentCacheBytes - removed.bytes.size).coerceAtLeast(0L)
            }
        }
    }

    private fun updateStatsFlow() {
        val count: Int
        val bytes: Long
        synchronized(cacheLock) {
            count = segmentCache.size
            bytes = currentCacheBytes
        }
        val startMs = sequentialStartTimeMs.get()
        val elapsedMs = if (startMs > 0L) System.currentTimeMillis() - startMs else 0L
        val remainingSec = if (startMs > 0L && elapsedMs < PREBUFFER_DELAY_MS) {
            ((PREBUFFER_DELAY_MS - elapsedMs + 999L) / 1000L).toInt().coerceIn(0, 30)
        } else {
            0
        }
        val unlocked = startMs > 0L && elapsedMs >= PREBUFFER_DELAY_MS

        _bufferStats.value = ProxyBufferStats(
            cachedSegmentsCount = count,
            cachedBytes = bytes,
            maxCacheBytes = MAX_CACHE_BYTES,
            activePrefetches = activePrefetchCount.get(),
            warmupRemainingSeconds = remainingSec,
            isBufferingUnlocked = unlocked,
            totalRetries = totalRetriesCount.get()
        )
    }

    private fun proxyUpstreamRequest(
        method: String,
        targetUrl: String,
        sessionId: String,
        sessionConfig: ProxySessionConfig?,
        clientRangeHeader: String?,
        proxyHostPort: String,
        output: OutputStream
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirectsWithRetry(
                urlStr = targetUrl,
                method = if (method == "HEAD") "HEAD" else "GET",
                sessionConfig = sessionConfig,
                rangeHeader = clientRangeHeader,
                maxAttempts = 3
            )

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage ?: "OK"
            val contentType = connection.contentType ?: guessMimeType(targetUrl)
            val finalUpstreamUrl = connection.url.toString()

            val rawStream: InputStream? = if (method == "HEAD") {
                null
            } else if (responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val bufferedInput = rawStream?.let { BufferedInputStream(it, 64 * 1024) }

            val isHlsPlaylist = if (method != "HEAD" && responseCode in 200..299 && bufferedInput != null) {
                isM3u8Content(finalUpstreamUrl, contentType) || startsWithExtM3u(bufferedInput)
            } else {
                isM3u8Content(finalUpstreamUrl, contentType)
            }

            if (isHlsPlaylist && method != "HEAD" && responseCode in 200..299 && bufferedInput != null) {
                val playlistText = bufferedInput.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val rewrittenPlaylist = rewriteM3u8Playlist(
                    playlist = playlistText,
                    baseUrl = finalUpstreamUrl,
                    proxyHostPort = proxyHostPort,
                    sessionId = sessionId
                )
                val payloadBytes = rewrittenPlaylist.toByteArray(Charsets.UTF_8)

                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/vnd.apple.mpegurl\r\n")
                    append("Content-Length: ${payloadBytes.size}\r\n")
                    append("Cache-Control: no-cache\r\n")
                    appendCorsHeaders()
                    append("\r\n")
                }
                output.write(headers.toByteArray(Charsets.UTF_8))
                output.write(payloadBytes)
                output.flush()
                return
            }

            val contentLength = connection.contentLengthLong
            val contentRange = connection.getHeaderField("Content-Range")
            val acceptRanges = connection.getHeaderField("Accept-Ranges") ?: "bytes"

            val responseHeaderBuilder = StringBuilder().apply {
                append("HTTP/1.1 $responseCode $responseMessage\r\n")
                append("Content-Type: $contentType\r\n")
                if (contentLength >= 0) {
                    append("Content-Length: $contentLength\r\n")
                }
                if (!contentRange.isNullOrBlank()) {
                    append("Content-Range: $contentRange\r\n")
                }
                append("Accept-Ranges: $acceptRanges\r\n")
                appendCorsHeaders()
                append("\r\n")
            }

            output.write(responseHeaderBuilder.toString().toByteArray(Charsets.UTF_8))
            output.flush()

            if (method != "HEAD" && bufferedInput != null) {
                bufferedInput.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }
        } catch (_: Throwable) {
            // Normale quando la TV chiude uno stream HTTP durante un seek
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun openFollowingRedirectsWithRetry(
        urlStr: String,
        method: String,
        sessionConfig: ProxySessionConfig?,
        rangeHeader: String?,
        maxAttempts: Int = 3
    ): HttpURLConnection {
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return openFollowingRedirects(urlStr, method, sessionConfig, rangeHeader)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    totalRetriesCount.incrementAndGet()
                    updateStatsFlow()
                    try {
                        Thread.sleep(300L * attempt)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        throw lastException ?: java.io.IOException("Connessione fallita verso $urlStr")
    }

    private fun openFollowingRedirects(
        urlStr: String,
        method: String,
        sessionConfig: ProxySessionConfig?,
        rangeHeader: String?,
        maxRedirects: Int = 5
    ): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0

        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = 20_000
                requestMethod = method
            }

            val capturedHeaders = sessionConfig?.headers ?: emptyMap()
            var hasReferer = false
            var hasUserAgent = false

            for ((key, value) in capturedHeaders) {
                val lower = key.lowercase()
                if (lower == "host" || lower == "connection" || lower == "range" || lower == "accept-encoding") {
                    continue
                }
                if (lower == "referer") hasReferer = true
                if (lower == "user-agent") hasUserAgent = true
                conn.setRequestProperty(key, value)
            }

            if (!hasReferer && !sessionConfig?.pageUrl.isNullOrBlank()) {
                conn.setRequestProperty("Referer", sessionConfig!!.pageUrl)
                try {
                    val uri = URI(sessionConfig.pageUrl)
                    if (uri.scheme != null && uri.host != null) {
                        conn.setRequestProperty("Origin", "${uri.scheme}://${uri.host}")
                    }
                } catch (_: Exception) {
                }
            }

            if (!hasUserAgent) {
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
            }

            val liveCookie = try {
                CookieManager.getInstance().getCookie(currentUrl)
            } catch (_: Throwable) {
                null
            } ?: sessionConfig?.cookies

            if (!liveCookie.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", liveCookie)
            }

            if (!rangeHeader.isNullOrBlank()) {
                conn.setRequestProperty("Range", rangeHeader)
            }

            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308) && redirects < maxRedirects) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = resolveRelativeUrl(currentUrl, location)
                    redirects++
                    continue
                }
            }
            return conn
        }
    }

    /**
     * Riscrive un manifest `.m3u8` e indicizza tutti i segmenti della sub-playlist
     * per gestire sia il seek manuale sia il Pre-Buffer fino a 100 MB.
     */
    internal fun rewriteM3u8Playlist(
        playlist: String,
        baseUrl: String,
        proxyHostPort: String,
        sessionId: String
    ): String {
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        var expectSubPlaylist = false
        var expectMediaSegment = false
        val discoveredSegments = mutableListOf<String>()
        val playlistId = Integer.toHexString(baseUrl.hashCode())

        val rewritten = playlist.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> rawLine
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        expectSubPlaylist = true
                        expectMediaSegment = false
                    } else if (line.startsWith("#EXTINF")) {
                        expectSubPlaylist = false
                        expectMediaSegment = true
                    }
                    if (line.contains("URI=\"")) {
                        val isMediaPlaylistTag = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                        val isInitMapTag = line.startsWith("#EXT-X-MAP")
                        uriAttrRegex.replace(line) { match ->
                            val originalUri = match.groupValues[1]
                            val absoluteUri = resolveRelativeUrl(baseUrl, originalUri)
                            val proxiedUri = buildProxyItemUrl(
                                proxyHostPort = proxyHostPort,
                                sessionId = sessionId,
                                absoluteTargetUrl = absoluteUri,
                                forcePlaylistHint = isMediaPlaylistTag,
                                forceSegmentHint = isInitMapTag
                            )
                            """URI="$proxiedUri""""
                        }
                    } else {
                        rawLine
                    }
                }
                else -> {
                    val absoluteUri = resolveRelativeUrl(baseUrl, line)
                    val isSegment = expectMediaSegment || (!expectSubPlaylist && !isLikelyPlaylistUrl(absoluteUri))
                    if (isSegment) {
                        val idx = discoveredSegments.size
                        discoveredSegments.add(absoluteUri)
                        segmentMetaMap[absoluteUri] = SegmentMeta(
                            playlistId = playlistId,
                            index = idx,
                            sessionId = sessionId
                        )
                    }
                    val result = buildProxyItemUrl(
                        proxyHostPort = proxyHostPort,
                        sessionId = sessionId,
                        absoluteTargetUrl = absoluteUri,
                        forcePlaylistHint = expectSubPlaylist && !expectMediaSegment,
                        forceSegmentHint = isSegment
                    )
                    expectSubPlaylist = false
                    expectMediaSegment = false
                    result
                }
            }
        }

        if (discoveredSegments.isNotEmpty()) {
            playlistSegmentsMap[playlistId] = discoveredSegments.toList()
        }

        return rewritten
    }

    private fun isLikelyPlaylistUrl(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".m3u8") || clean.contains("/playlist/")
    }

    private fun buildProxyItemUrl(
        proxyHostPort: String,
        sessionId: String,
        absoluteTargetUrl: String,
        forcePlaylistHint: Boolean = false,
        forceSegmentHint: Boolean = false
    ): String {
        val cleanPath = absoluteTargetUrl.substringBefore("?").lowercase()
        val extHint = when {
            forceSegmentHint -> if (cleanPath.endsWith(".m4s")) "seg.m4s" else "seg.ts"
            forcePlaylistHint || cleanPath.endsWith(".m3u8") || cleanPath.contains("/playlist/") -> "sub.m3u8"
            cleanPath.endsWith(".m4s") -> "seg.m4s"
            else -> "seg.ts"
        }
        return "http://$proxyHostPort/stream/$extHint?s=$sessionId&u=${encodeUrl(absoluteTargetUrl)}"
    }

    private fun startsWithExtM3u(bufferedInput: BufferedInputStream): Boolean {
        return try {
            bufferedInput.mark(16)
            val peek = ByteArray(10)
            val n = bufferedInput.read(peek)
            bufferedInput.reset()
            if (n >= 7) {
                val head = String(peek, 0, n, Charsets.UTF_8).trimStart()
                head.startsWith("#EXTM3U")
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) {
            return reference
        }
        return try {
            URL(URL(baseUrl), reference).toString()
        } catch (_: Exception) {
            reference
        }
    }

    private fun isM3u8Content(url: String, contentType: String?): Boolean {
        val cleanUrl = url.substringBefore("?").lowercase()
        val mime = contentType?.lowercase().orEmpty()
        return cleanUrl.endsWith(".m3u8") ||
            url.lowercase().contains(".m3u8?") ||
            mime.contains("mpegurl") ||
            mime.contains("x-mpegurl")
    }

    private fun guessMimeType(url: String): String {
        val clean = url.substringBefore("?").lowercase()
        return when {
            clean.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            clean.endsWith(".mpd") -> "application/dash+xml"
            clean.endsWith(".ts") -> "video/mp2t"
            clean.endsWith(".m4s") -> "video/iso.segment"
            clean.endsWith(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }

    private fun StringBuilder.appendCorsHeaders() {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: *\r\n")
        append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
    }

    private fun writeCorsPreflight(output: OutputStream) {
        val response = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            appendCorsHeaders()
            append("Content-Length: 0\r\n\r\n")
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeError(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $message\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            appendCorsHeaders()
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                pair.substring(0, idx) to pair.substring(idx + 1)
            } else {
                null
            }
        }.toMap()
    }

    private fun encodeUrl(url: String): String {
        return Base64.encodeToString(
            url.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun decodeUrl(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "LocalStreamProxy"
        private const val PREBUFFER_DELAY_MS = 30_000L // Attende 30s di riproduzione stabile prima del buffer massivo
        private const val READ_AHEAD_SEGMENTS = 18     // Pre-carica fino a 18 segmenti in avanti
        private const val MAX_CACHED_SEGMENTS = 110
        private const val MAX_CACHE_BYTES = 100L * 1024L * 1024L // 100 MB di Buffer RAM
        private const val MAX_SINGLE_SEGMENT_BYTES = 16 * 1024 * 1024 // 16 MB max per singolo segmento
    }
}
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
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ProxyBufferStats(
    val cachedSegmentsCount: Int = 0,
    val cachedBytes: Long = 0L,
    val activePrefetches: Int = 0,
    val totalRetries: Int = 0
) {
    val cachedMegabytes: String
        get() = String.format(java.util.Locale.US, "%.1f MB", cachedBytes / (1024.0 * 1024.0))
}

/**
 * Server HTTP Proxy locale sullo smartphone con Motore di Pre-Buffering RAM (Read-Ahead Cache)
 * e Auto-Retry su errori di rete.
 *
 * Risolve le interruzioni di trasmissione durante lo streaming su Smart TV / Chromecast:
 * 1. Scarica i segmenti HLS (`.ts`, `.m4s`, init map, chiavi AES) interamente in RAM con fino a 3 tentativi
 *    automatici (backoff esponenziale) in caso di micro-cadute della linea o rallentamenti del CDN.
 *    In questo modo la Smart TV non riceve mai segmenti troncati (`ERR_CONTENT_LENGTH_MISMATCH`).
 * 2. Effettua il **Pre-Buffering Read-Ahead** dei successivi 6 segmenti video in background (fino a ~56 MB in RAM),
 *    servendoli alla TV istantaneamente a velocità LAN Wi-Fi (< 5 ms).
 * 3. Deduplica i download in corso (`inFlightSegments`): se la TV richiede un segmento che il pre-fetcher
 *    sta già scaricando, attende lo stesso download senza aprire connessioni duplicate.
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
        val createdAt: Long = System.currentTimeMillis()
    )

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val clientExecutor = Executors.newCachedThreadPool()
    private val prefetchExecutor = Executors.newFixedThreadPool(4)

    private val sessions = ConcurrentHashMap<String, ProxySessionConfig>()

    // Cache LRU in RAM dei segmenti video pre-scaricati (max ~56 MB o 45 segmenti)
    private val cacheLock = Any()
    private var currentCacheBytes: Long = 0L
    private val segmentCache = object : LinkedHashMap<String, CachedSegment>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSegment>?): Boolean {
            val shouldEvict = size > MAX_CACHED_SEGMENTS || currentCacheBytes > MAX_CACHE_BYTES
            if (shouldEvict && eldest != null) {
                currentCacheBytes = (currentCacheBytes - eldest.value.bytes.size).coerceAtLeast(0L)
            }
            return shouldEvict
        }
    }

    // Download attualmente in corso (per evitare richieste duplicate tra TV e Pre-Fetcher)
    private val inFlightSegments = ConcurrentHashMap<String, CompletableFuture<CachedSegment?>>()

    // Mappa ogni segmento URL ai successivi N segmenti della stessa playlist per il Read-Ahead
    private val nextSegmentsMap = ConcurrentHashMap<String, List<String>>()

    private val activePrefetchCount = AtomicInteger(0)
    private val totalRetriesCount = AtomicInteger(0)

    private val _bufferStats = MutableStateFlow(ProxyBufferStats())
    val bufferStats: StateFlow<ProxyBufferStats> = _bufferStats.asStateFlow()

    var boundPort: Int = preferredPort
        private set

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
                Log.i(TAG, "LocalStreamProxy avviato sulla porta $boundPort (Pre-Buffer RAM attivo)")
                while (isRunning.get()) {
                    try {
                        val client = socket.accept()
                        clientExecutor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.w(TAG, "Errore accept client proxy: ${e.message}")
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
        } catch (_: Exception) {
        }
        serverSocket = null
        clearBufferCache()
        sessions.clear()
    }

    fun clearBufferCache() {
        synchronized(cacheLock) {
            segmentCache.clear()
            currentCacheBytes = 0L
        }
        inFlightSegments.clear()
        nextSegmentsMap.clear()
        updateStatsFlow()
    }

    fun createProxiedUrl(media: DetectedMedia): String {
        start()
        clearBufferCache()
        val lanIp = findLocalLanIpAddress() ?: return media.url

        val sessionId = Integer.toHexString(media.url.hashCode())
        val cookieHeader = try {
            CookieManager.getInstance().getCookie(media.url)
                ?: media.pageUrl.takeIf { it.isNotBlank() }?.let { CookieManager.getInstance().getCookie(it) }
        } catch (_: Exception) {
            null
        }

        sessions[sessionId] = ProxySessionConfig(
            rootUrl = media.url,
            pageUrl = media.pageUrl,
            headers = media.headers,
            cookies = cookieHeader
        )

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
        socket.use { client ->
            client.soTimeout = 45_000
            client.keepAlive = true
            client.tcpNoDelay = true
            client.sendBufferSize = 256 * 1024

            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val rawOutput = BufferedOutputStream(client.getOutputStream(), 64 * 1024)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val pathAndQuery = parts[1]

            val clientHeaders = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    clientHeaders[key] = value
                }
                line = reader.readLine()
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

            val sessionConfig = sessionId?.let { sessions[it] }
            val hostHeader = clientHeaders.entries.firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value
                ?: "${findLocalLanIpAddress() ?: "127.0.0.1"}:$boundPort"
            val rangeHeader = clientHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value

            val isSegmentRequest = pathPart.endsWith("seg.ts") || pathPart.endsWith("seg.m4s")

            // Se è un segmento HLS (.ts / .m4s) senza Range parziale, servilo dal Pre-Buffer RAM
            // e avvia il pre-fetching dei successivi 6 segmenti!
            if (isSegmentRequest && method == "GET" && rangeHeader.isNullOrBlank()) {
                serveBufferedSegment(
                    targetUrl = targetUrl,
                    sessionConfig = sessionConfig,
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
    }

    /**
     * Serve un segmento HLS dalla cache RAM (o attende il download con auto-retry)
     * e avvia in parallelo il pre-fetching dei successivi 6 segmenti della playlist.
     */
    private fun serveBufferedSegment(
        targetUrl: String,
        sessionConfig: ProxySessionConfig?,
        output: OutputStream
    ) {
        // 1. Avvia subito il pre-fetch dei prossimi segmenti in background
        triggerReadAheadPrefetch(targetUrl, sessionConfig)

        // 2. Recupera il segmento corrente dalla RAM o scaricalo con retry
        val segment = getOrFetchSegment(targetUrl, sessionConfig)
        if (segment == null) {
            writeError(output, 502, "Segment fetch failed after retries")
            return
        }

        try {
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${segment.contentType}\r\n")
                append("Content-Length: ${segment.bytes.size}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Cache-Control: public, max-age=3600\r\n")
                appendCorsHeaders()
                append("\r\n")
            }
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.write(segment.bytes)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Errore invio segmento bufferizzato alla TV: ${e.message}")
        }
    }

    private fun getOrFetchSegment(
        targetUrl: String,
        sessionConfig: ProxySessionConfig?
    ): CachedSegment? {
        synchronized(cacheLock) {
            segmentCache[targetUrl]?.let { return it }
        }

        var createdByUs = false
        val future = inFlightSegments.computeIfAbsent(targetUrl) {
            createdByUs = true
            CompletableFuture<CachedSegment?>()
        }

        if (createdByUs) {
            try {
                val downloaded = downloadSegmentWithRetry(targetUrl, sessionConfig, maxAttempts = 3)
                if (downloaded != null) {
                    putSegmentInCache(targetUrl, downloaded)
                }
                future.complete(downloaded)
                return downloaded
            } catch (e: Exception) {
                future.complete(null)
                return null
            } finally {
                inFlightSegments.remove(targetUrl)
            }
        } else {
            return try {
                future.get()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun triggerReadAheadPrefetch(
        currentSegmentUrl: String,
        sessionConfig: ProxySessionConfig?
    ) {
        val upcoming = nextSegmentsMap[currentSegmentUrl] ?: return
        prefetchSegmentsList(upcoming, sessionConfig)
    }

    private fun prefetchSegmentsList(
        urls: List<String>,
        sessionConfig: ProxySessionConfig?
    ) {
        for (nextUrl in urls) {
            val alreadyCached = synchronized(cacheLock) { segmentCache.containsKey(nextUrl) }
            if (alreadyCached || inFlightSegments.containsKey(nextUrl)) continue

            prefetchExecutor.execute {
                val cachedNow = synchronized(cacheLock) { segmentCache.containsKey(nextUrl) }
                if (cachedNow || inFlightSegments.containsKey(nextUrl)) return@execute

                activePrefetchCount.incrementAndGet()
                updateStatsFlow()
                try {
                    getOrFetchSegment(nextUrl, sessionConfig)
                } finally {
                    activePrefetchCount.decrementAndGet()
                    updateStatsFlow()
                }
            }
        }
    }

    /**
     * Scarica un singolo segmento completo in RAM con massimo [maxAttempts] tentativi
     * per assorbire micro-interruzioni Wi-Fi/Internet o errori temporanei del server CDN.
     */
    private fun downloadSegmentWithRetry(
        targetUrl: String,
        sessionConfig: ProxySessionConfig?,
        maxAttempts: Int = 3
    ): CachedSegment? {
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
                    rawInput.use { input ->
                        val buf = ByteArray(32 * 1024)
                        var n = input.read(buf)
                        while (n != -1) {
                            bos.write(buf, 0, n)
                            n = input.read(buf)
                        }
                    }
                    val bytes = bos.toByteArray()
                    // Verifica che il segmento non sia stato troncato a metà dal server remoto
                    if (expectedLength > 0 && bytes.size.toLong() != expectedLength) {
                        throw java.io.IOException("Segmento incompleto: ricevuti ${bytes.size} su $expectedLength byte")
                    }
                    if (bytes.isNotEmpty()) {
                        return CachedSegment(bytes = bytes, contentType = contentType)
                    }
                } else if (code in 400..499 && code != 429 && code != 408) {
                    // Errore client definitivo (es. 404/403), inutile riprovare
                    return null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tentativo $attempt/$maxAttempts fallito per segmento: ${e.message}")
            } finally {
                connection?.disconnect()
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
                currentCacheBytes -= previous.bytes.size
            }
            currentCacheBytes += segment.bytes.size
            while (currentCacheBytes > MAX_CACHE_BYTES && segmentCache.isNotEmpty()) {
                val firstKey = segmentCache.keys.firstOrNull() ?: break
                val removed = segmentCache.remove(firstKey)
                if (removed != null) {
                    currentCacheBytes = (currentCacheBytes - removed.bytes.size).coerceAtLeast(0L)
                }
            }
        }
        updateStatsFlow()
    }

    private fun updateStatsFlow() {
        val count: Int
        val bytes: Long
        synchronized(cacheLock) {
            count = segmentCache.size
            bytes = currentCacheBytes
        }
        _bufferStats.value = ProxyBufferStats(
            cachedSegmentsCount = count,
            cachedBytes = bytes,
            activePrefetches = activePrefetchCount.get(),
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
                    sessionId = sessionId,
                    sessionConfig = sessionConfig
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
        } catch (e: Exception) {
            Log.w(TAG, "Connessione interrotta durante streaming di $targetUrl: ${e.message}")
        } finally {
            connection?.disconnect()
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
            } catch (_: Exception) {
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
     * Riscrive un manifest `.m3u8` e registra la catena sequenziale dei segmenti
     * per attivare il Pre-Buffering Read-Ahead automatico in RAM.
     */
    internal fun rewriteM3u8Playlist(
        playlist: String,
        baseUrl: String,
        proxyHostPort: String,
        sessionId: String,
        sessionConfig: ProxySessionConfig? = null
    ): String {
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        var expectSubPlaylist = false
        val discoveredSegments = mutableListOf<String>()

        val rewritten = playlist.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> rawLine
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        expectSubPlaylist = true
                    } else if (line.startsWith("#EXTINF")) {
                        expectSubPlaylist = false
                    }
                    if (line.contains("URI=\"")) {
                        val isMediaPlaylistTag = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                        uriAttrRegex.replace(line) { match ->
                            val originalUri = match.groupValues[1]
                            val absoluteUri = resolveRelativeUrl(baseUrl, originalUri)
                            val proxiedUri = buildProxyItemUrl(
                                proxyHostPort = proxyHostPort,
                                sessionId = sessionId,
                                absoluteTargetUrl = absoluteUri,
                                forcePlaylistHint = isMediaPlaylistTag
                            )
                            """URI="$proxiedUri""""
                        }
                    } else {
                        rawLine
                    }
                }
                else -> {
                    val absoluteUri = resolveRelativeUrl(baseUrl, line)
                    val isSubPlaylist = expectSubPlaylist ||
                        absoluteUri.substringBefore("?").lowercase().let { it.endsWith(".m3u8") || it.contains("/playlist/") }
                    if (!isSubPlaylist) {
                        discoveredSegments.add(absoluteUri)
                    }
                    val result = buildProxyItemUrl(
                        proxyHostPort = proxyHostPort,
                        sessionId = sessionId,
                        absoluteTargetUrl = absoluteUri,
                        forcePlaylistHint = expectSubPlaylist
                    )
                    expectSubPlaylist = false
                    result
                }
            }
        }

        // Costruisci la mappa di Read-Ahead (per ogni segmento i -> salva i successivi READ_AHEAD_SEGMENTS)
        if (discoveredSegments.isNotEmpty()) {
            for (i in discoveredSegments.indices) {
                val endIdx = (i + 1 + READ_AHEAD_SEGMENTS).coerceAtMost(discoveredSegments.size)
                if (i + 1 < endIdx) {
                    nextSegmentsMap[discoveredSegments[i]] = discoveredSegments.subList(i + 1, endIdx).toList()
                }
            }
            // Pre-carica subito i primi 3 segmenti della playlist così l'avvio sulla TV è istantaneo
            prefetchSegmentsList(discoveredSegments.take(3), sessionConfig)
        }

        return rewritten
    }

    private fun buildProxyItemUrl(
        proxyHostPort: String,
        sessionId: String,
        absoluteTargetUrl: String,
        forcePlaylistHint: Boolean = false
    ): String {
        val cleanPath = absoluteTargetUrl.substringBefore("?").lowercase()
        val extHint = when {
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
        } catch (_: Exception) {
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
        private const val READ_AHEAD_SEGMENTS = 6
        private const val MAX_CACHED_SEGMENTS = 45
        private const val MAX_CACHE_BYTES = 56L * 1024L * 1024L // 56 MB RAM buffer
        private const val MAX_SINGLE_SEGMENT_BYTES = 12 * 1024 * 1024 // 12 MB
    }
}
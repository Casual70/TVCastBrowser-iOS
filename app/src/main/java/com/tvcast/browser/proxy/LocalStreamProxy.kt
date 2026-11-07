package com.tvcast.browser.proxy

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.model.StreamFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server HTTP Proxy locale eseguito sullo smartphone.
 *
 * Scopo:
 * 1. Riceve le richieste dalla Smart TV (Chromecast) sulla rete Wi-Fi locale:
 *    es. `http://192.168.1.50:8765/stream?s=<sessionId>&u=<base64Url>`
 * 2. Scarica il flusso originale dal sito web iniettando gli header protetti
 *    (`Referer`, `Origin`, `Cookie`, `User-Agent`) catturati dal WebView.
 * 3. Aggiunge gli header CORS (`Access-Control-Allow-Origin: *`) indispensabili per il
 *    player HTML5/Shaka del ricevitore Google Cast.
 * 4. Supporta le richieste `Range: bytes=...` (HTTP 206) per consentire il seek avanti/indietro nei file MP4/WebM.
 * 5. Riscrive al volo i manifest HLS (`.m3u8`) in modo che anche i sotto-manifest e i segmenti `.ts`/`.m4s`
 *    e le chiavi `#EXT-X-KEY` passino attraverso il proxy locale con gli stessi header.
 */
class LocalStreamProxy(private val preferredPort: Int = 8765) {

    data class ProxySessionConfig(
        val rootUrl: String,
        val pageUrl: String,
        val headers: Map<String, String>,
        val cookies: String?
    )

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, ProxySessionConfig>()

    var boundPort: Int = preferredPort
        private set

    /**
     * Avvia il server socket locale se non è già attivo.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return
        try {
            val socket = try {
                ServerSocket(preferredPort)
            } catch (e: Exception) {
                ServerSocket(0) // Porta libera casuale se la 8765 è occupata
            }
            serverSocket = socket
            boundPort = socket.localPort
            isRunning.set(true)

            executor.execute {
                Log.i(TAG, "LocalStreamProxy avviato sulla porta $boundPort")
                while (isRunning.get()) {
                    try {
                        val client = socket.accept()
                        executor.execute { handleClient(client) }
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

    /**
     * Ferma il server socket locale.
     */
    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        sessions.clear()
    }

    /**
     * Registra un flusso intercettato e restituisce l'URL `http://<ip-telefono>:<porta>/...`
     * da inviare alla Smart TV. Se l'IP Wi-Fi non è disponibile, restituisce l'URL originale.
     */
    fun createProxiedUrl(media: DetectedMedia): String {
        start()
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

    /**
     * Restituisce l'indirizzo IPv4 del telefono sulla rete locale Wi-Fi (es. 192.168.1.x).
     */
    fun findLocalLanIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackIp: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                // Ignora interfacce cellulari o VPN/docker
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
            client.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val rawOutput = BufferedOutputStream(client.getOutputStream())

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

            // Gestione preflight CORS (OPTIONS) richiesto da Chromecast
            if (method == "OPTIONS") {
                writeCorsPreflight(rawOutput)
                return
            }

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

            proxyUpstreamRequest(
                method = method,
                targetUrl = targetUrl,
                sessionId = sessionId ?: "default",
                sessionConfig = sessionConfig,
                clientRangeHeader = clientHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value,
                proxyHostPort = hostHeader,
                output = rawOutput
            )
        }
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
            connection = openFollowingRedirects(
                urlStr = targetUrl,
                method = if (method == "HEAD") "HEAD" else "GET",
                sessionConfig = sessionConfig,
                rangeHeader = clientRangeHeader
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
            val bufferedInput = rawStream?.let { BufferedInputStream(it, DEFAULT_BUFFER_SIZE) }

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

            // Flusso binario diretto (MP4, WebM, segmenti .ts / .m4s, chiavi AES)
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
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connessione interrotta o errore durante lo streaming di $targetUrl: ${e.message}")
        } finally {
            connection?.disconnect()
        }
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
                connectTimeout = 15_000
                readTimeout = 25_000
                requestMethod = method
            }

            // Inietta gli header catturati dal WebView (User-Agent, Referer, Origin, ecc.)
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

            // Cookie dal CookieManager o dalla sessione
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
     * Riscrive ogni URL di segmento o sotto-playlist all'interno di un file `.m3u8`
     * in modo che punti al proxy locale.
     */
    internal fun rewriteM3u8Playlist(
        playlist: String,
        baseUrl: String,
        proxyHostPort: String,
        sessionId: String
    ): String {
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        var expectSubPlaylist = false

        return playlist.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> rawLine
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        expectSubPlaylist = true
                    } else if (line.startsWith("#EXTINF")) {
                        expectSubPlaylist = false
                    }
                    // Riscrive eventuali URI="..." presenti in #EXT-X-KEY, #EXT-X-MAP o #EXT-X-MEDIA
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
    }
}


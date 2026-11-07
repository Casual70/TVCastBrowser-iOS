package com.tvcast.browser.sniffer

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.model.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream

/**
 * Motore di intercettazione flussi video (inclusi iframe cross-origin come VixCloud / StreamingCommunity)
 * e filtro AdBlock / Anti-Popup avanzato per il WebView.
 */
class MediaSniffer {

    private val _detectedMediaList = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val detectedMediaList: StateFlow<List<DetectedMedia>> = _detectedMediaList.asStateFlow()

    private val _blockedAdsCount = MutableStateFlow(0)
    val blockedAdsCount: StateFlow<Int> = _blockedAdsCount.asStateFlow()

    var adBlockEnabled: Boolean = true

    private val blockedAdDomains = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.",
        "popads.net",
        "popcash.net",
        "exoclick.com",
        "exosrv.com",
        "juicyads.com",
        "propellerads.com",
        "onclickads.net",
        "onclkds.com",
        "magsrv.com",
        "realsrv.com",
        "tsyndicate.com",
        "acscdn.com",
        "adtng.com",
        "adskeeper.com",
        "mgid.com",
        "taboola.com",
        "outbrain.com",
        "bidgear.com",
        "pubfuture.com",
        "rtmark.net",
        "usrpubtrk.com",
        "pushame.com",
        "trafficjunky.net",
        "adnxs.com",
        "rubiconproject.com",
        "criteo.com",
        "smartadserver.com",
        "histats.com",
        "yandex.ru",
        "a-ads.com",
        "adsterra.com",
        "hilltopads.net",
        "clickadu.com",
        "galaksion.com"
    )

    private val blockedAdPathKeywords = listOf(
        "/popunder",
        "/popup.js",
        "/adserver",
        "/adframe",
        "/vast.xml",
        "/preroll"
    )

    /**
     * Svuota la lista dei video trovati (es. al cambio pagina principale).
     */
    fun clearDetectedMedia() {
        _detectedMediaList.value = emptyList()
    }

    fun incrementBlockedPopup() {
        _blockedAdsCount.update { it + 1 }
    }

    /**
     * Verifica se la richiesta appartiene a un dominio o script pubblicitario noto.
     */
    fun shouldBlockRequest(request: WebResourceRequest): WebResourceResponse? {
        if (!adBlockEnabled) return null
        val uri = request.url ?: return null
        val host = uri.host?.lowercase() ?: return null
        val fullUrl = uri.toString().lowercase()

        val isAdDomain = blockedAdDomains.any { domain ->
            host == domain || host.endsWith(".$domain") || host.contains(domain)
        }
        val isAdPath = blockedAdPathKeywords.any { keyword ->
            fullUrl.contains(keyword)
        }

        if (isAdDomain || isAdPath) {
            _blockedAdsCount.update { it + 1 }
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream(ByteArray(0))
            )
        }
        return null
    }

    /**
     * Esamina ogni richiesta di rete del WebView (inclusi gli iframe cross-origin)
     * per individuare stream video e salvarne gli header (`Referer`, `User-Agent`, `Origin`).
     */
    fun inspectRequest(
        request: WebResourceRequest,
        currentPageTitle: String?,
        currentPageUrl: String?
    ) {
        val urlString = request.url?.toString() ?: return
        val headers = request.requestHeaders ?: emptyMap()
        val acceptHeader = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value

        val format = detectFormatFromUrl(urlString, acceptHeader) ?: return

        val effectiveHeaders = headers.toMutableMap().apply {
            if (keys.none { it.equals("Referer", ignoreCase = true) } && !currentPageUrl.isNullOrBlank()) {
                put("Referer", currentPageUrl)
            }
        }

        addMediaIfNew(
            DetectedMedia(
                url = urlString,
                title = currentPageTitle?.takeIf { it.isNotBlank() } ?: "Video Web",
                pageUrl = currentPageUrl ?: "",
                format = format,
                headers = effectiveHeaders
            )
        )
    }

    internal fun detectFormatFromUrl(url: String, acceptHeader: String? = null): StreamFormat? {
        val fullLower = url.lowercase()
        val cleanUrl = fullLower.substringBefore("?")

        // 1. Ignora segmenti singoli, sottotitoli, font, stili, script e immagini
        val ignoredExtensions = listOf(
            ".ts", ".m4s", ".aac", ".vtt", ".srt", ".jpg", ".jpeg", ".png",
            ".gif", ".webp", ".svg", ".ico", ".js", ".css", ".woff", ".woff2", ".ttf", ".json", ".html"
        )
        if (ignoredExtensions.any { cleanUrl.endsWith(it) }) {
            return null
        }

        // 2. Ignora sotto-tracce separate solo-audio o solo-sottotitoli o singole rendition secondarie
        //    (es. su VixCloud / StreamingCommunity dove `/playlist/<id>?token=...` è la Master Playlist completa
        //    mentre `&type=audio`, `&type=subtitle`, `&type=video&rendition=` sono i sotto-manifest)
        if (fullLower.contains("type=audio") ||
            fullLower.contains("type=subtitle") ||
            fullLower.contains("type=text") ||
            (fullLower.contains("/playlist/") && fullLower.contains("rendition="))
        ) {
            return null
        }

        // 3. Riconoscimento HLS (.m3u8 e playlist dinamiche come VixCloud / StreamingCommunity)
        val isHls = cleanUrl.endsWith(".m3u8") ||
            fullLower.contains(".m3u8?") ||
            fullLower.contains(".m3u8&") ||
            cleanUrl.contains(".m3u8/") ||
            cleanUrl.contains("/playlist/") ||
            (cleanUrl.contains("/hls/") && (cleanUrl.endsWith("/master") || cleanUrl.endsWith("/index") || cleanUrl.endsWith(".txt"))) ||
            acceptHeader?.lowercase()?.contains("mpegurl") == true

        if (isHls) {
            return StreamFormat.HLS
        }

        // 4. Riconoscimento DASH (.mpd)
        if (cleanUrl.endsWith(".mpd") || fullLower.contains(".mpd?") || fullLower.contains("format=mpd")) {
            return StreamFormat.DASH
        }

        // 5. Riconoscimento MP4 / WebM / flussi diretti
        if (cleanUrl.endsWith(".mp4") ||
            cleanUrl.endsWith(".m4v") ||
            fullLower.contains("mime=video/mp4") ||
            (fullLower.contains("/videoplayback") && fullLower.contains("mime=video"))
        ) {
            return StreamFormat.MP4
        }

        if (cleanUrl.endsWith(".webm") || fullLower.contains("mime=video/webm")) {
            return StreamFormat.WEBM
        }

        return null
    }

    private fun addMediaIfNew(media: DetectedMedia) {
        if (media.url.startsWith("blob:") || media.url.startsWith("data:")) return
        _detectedMediaList.update { current ->
            val existingIndex = current.indexOfFirst { it.url == media.url }
            if (existingIndex >= 0) {
                val existing = current[existingIndex]
                val mergedTitle = if (existing.title == "Video Web" || existing.title == "Video HTML5") {
                    media.title
                } else {
                    existing.title
                }
                if (media.headers.size > existing.headers.size || mergedTitle != existing.title) {
                    current.toMutableList().apply {
                        set(existingIndex, existing.copy(title = mergedTitle, headers = media.headers.ifEmpty { existing.headers }))
                    }
                } else {
                    current
                }
            } else {
                listOf(media) + current
            }
        }
    }

    /**
     * Bridge JavaScript esposto come `window.TvCastBridge` dentro il WebView (anche negli iframe).
     */
    inner class JsMediaBridge {
        @JavascriptInterface
        fun onVideoFound(
            videoUrl: String,
            pageTitle: String,
            frameUrl: String,
            userAgent: String,
            formatHint: String
        ) {
            if (videoUrl.isBlank() || videoUrl.startsWith("blob:") || videoUrl.startsWith("data:")) return
            val lower = videoUrl.lowercase()
            if (lower.contains("type=audio") ||
                lower.contains("type=subtitle") ||
                (lower.contains("/playlist/") && lower.contains("rendition="))
            ) {
                return
            }

            val format = when (formatHint.uppercase()) {
                "HLS" -> StreamFormat.HLS
                "DASH" -> StreamFormat.DASH
                "MP4" -> StreamFormat.MP4
                else -> detectFormatFromUrl(videoUrl) ?: StreamFormat.GENERIC
            }

            val fallbackHeaders = buildMap {
                if (frameUrl.isNotBlank()) put("Referer", frameUrl)
                if (userAgent.isNotBlank()) put("User-Agent", userAgent)
            }

            addMediaIfNew(
                DetectedMedia(
                    url = videoUrl,
                    title = pageTitle.ifBlank { "Video Stream" },
                    pageUrl = frameUrl,
                    format = format,
                    headers = fallbackHeaders
                )
            )
        }
    }

    companion object {
        /**
         * Script iniettato in TUTTI i frame (inclusi `<iframe>` cross-origin come `vixcloud.co`)
         * tramite `WebViewCompat.addDocumentStartJavaScript` prima dell'esecuzione degli script pagina:
         * 1. Blocca `window.open` pubblicitari.
         * 2. Intercetta `fetch()` e `XMLHttpRequest` che scaricano manifest HLS (`#EXTM3U` o `/playlist/`).
         * 3. Scansiona tag `<video>` e `<source>`.
         */
        val DOCUMENT_START_SCANNER_JS = """
            (function() {
                if (window.__tvCastHookInstalled) return;
                window.__tvCastHookInstalled = true;

                // 1. Blocca window.open verso domini pubblicitari esterni
                try {
                    var origOpen = window.open;
                    window.open = function(url, target, features) {
                        if (!url) return null;
                        try {
                            var dest = new URL(url, window.location.href);
                            if (dest.hostname === window.location.hostname) {
                                window.location.href = dest.href;
                            }
                        } catch (e) {}
                        return null;
                    };
                } catch (e) {}

                function notifyBridge(url, hint) {
                    try {
                        if (!url || typeof url !== 'string') return;
                        if (url.startsWith('blob:') || url.startsWith('data:')) return;
                        var absUrl = new URL(url, window.location.href).href;
                        var title = '';
                        try { title = window.top.document.title || document.title; } catch(e) { title = document.title; }
                        if (window.TvCastBridge && window.TvCastBridge.onVideoFound) {
                            window.TvCastBridge.onVideoFound(
                                absUrl,
                                title || 'Video Stream',
                                window.location.href,
                                navigator.userAgent || '',
                                hint || ''
                            );
                        }
                    } catch (e) {}
                }

                // 2. Hook XMLHttpRequest (usato da hls.js / jwplayer negli iframe VixCloud)
                try {
                    var origXhrOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__tvCastUrl = url;
                        this.addEventListener('load', function() {
                            try {
                                var respUrl = this.responseURL || this.__tvCastUrl;
                                if (!respUrl) return;
                                var ct = (this.getResponseHeader('content-type') || '').toLowerCase();
                                if (ct.indexOf('mpegurl') !== -1 || respUrl.indexOf('.m3u8') !== -1 || respUrl.indexOf('/playlist/') !== -1) {
                                    notifyBridge(respUrl, 'HLS');
                                    return;
                                }
                                if ((this.responseType === '' || this.responseType === 'text') && typeof this.responseText === 'string') {
                                    var sample = this.responseText.substring(0, 64).trim();
                                    if (sample.indexOf('#EXTM3U') === 0) {
                                        notifyBridge(respUrl, 'HLS');
                                    }
                                }
                            } catch (e) {}
                        });
                        return origXhrOpen.apply(this, arguments);
                    };
                } catch (e) {}

                // 3. Hook window.fetch
                try {
                    if (window.fetch) {
                        var origFetch = window.fetch;
                        window.fetch = function(input, init) {
                            var reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                            if (reqUrl && (reqUrl.indexOf('.m3u8') !== -1 || reqUrl.indexOf('/playlist/') !== -1)) {
                                notifyBridge(reqUrl, 'HLS');
                            }
                            return origFetch.apply(this, arguments).then(function(response) {
                                try {
                                    var finalUrl = response.url || reqUrl;
                                    var ct = (response.headers && response.headers.get('content-type') || '').toLowerCase();
                                    if (ct.indexOf('mpegurl') !== -1 || (finalUrl && finalUrl.indexOf('/playlist/') !== -1)) {
                                        notifyBridge(finalUrl, 'HLS');
                                    }
                                } catch (e) {}
                                return response;
                            });
                        };
                    }
                } catch (e) {}

                // 4. Scansione tag <video> e <source>
                function scanDomVideos() {
                    try {
                        var videos = document.querySelectorAll('video, video source');
                        for (var i = 0; i < videos.length; i++) {
                            var src = videos[i].currentSrc || videos[i].src;
                            if (src && !src.startsWith('blob:')) {
                                notifyBridge(src, '');
                            }
                        }
                    } catch (e) {}
                }

                document.addEventListener('DOMContentLoaded', scanDomVideos);
                setInterval(scanDomVideos, 2000);
            })();
        """.trimIndent()
    }
}

package com.tvcast.browser.model

/**
 * Tipologia di flusso multimediale rilevato nella pagina web.
 */
enum class StreamFormat(val label: String, val defaultMimeType: String) {
    HLS("HLS (.m3u8)", "application/x-mpegURL"),
    DASH("DASH (.mpd)", "application/dash+xml"),
    MP4("MP4 Video", "video/mp4"),
    WEBM("WebM Video", "video/webm"),
    GENERIC("Video Stream", "video/mp4")
}

/**
 * Rappresenta un flusso video o audio intercettato dal MediaSniffer durante la navigazione.
 */
data class DetectedMedia(
    val url: String,
    val title: String,
    val pageUrl: String,
    val format: StreamFormat,
    val mimeType: String = format.defaultMimeType,
    val headers: Map<String, String> = emptyMap(),
    val detectedAt: Long = System.currentTimeMillis()
)


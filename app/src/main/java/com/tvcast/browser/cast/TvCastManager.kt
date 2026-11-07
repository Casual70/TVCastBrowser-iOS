package com.tvcast.browser.cast

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.proxy.LocalStreamProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CastUiState(
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val activeMedia: DetectedMedia? = null,
    val activeStreamUrl: String? = null,
    val isPlaying: Boolean = false,
    val useLocalProxy: Boolean = true,
    val localProxyEndpoint: String? = null,
    val statusMessage: String? = null
)

/**
 * Gestisce la connessione con la Smart TV (Google Cast), il server Proxy HTTP locale
 * e il caricamento dei flussi video.
 */
class TvCastManager(
    context: Context,
    private val streamProxy: LocalStreamProxy = LocalStreamProxy()
) {

    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    private var castContext: CastContext? = try {
        CastContext.getSharedInstance(context)
    } catch (e: Exception) {
        null
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _uiState.update { it.copy(statusMessage = "Connessione alla TV in corso...") }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            refreshProxyEndpoint()
            _uiState.update {
                it.copy(
                    isConnected = true,
                    deviceName = session.castDevice?.friendlyName ?: "Smart TV",
                    statusMessage = "Connesso a ${session.castDevice?.friendlyName ?: "Smart TV"}"
                )
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _uiState.update {
                it.copy(isConnected = false, statusMessage = "Connessione TV fallita (codice $error)")
            }
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            _uiState.update { prev ->
                CastUiState(
                    useLocalProxy = prev.useLocalProxy,
                    localProxyEndpoint = prev.localProxyEndpoint,
                    statusMessage = "Disconnesso dalla TV"
                )
            }
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            refreshProxyEndpoint()
            _uiState.update {
                it.copy(
                    isConnected = true,
                    deviceName = session.castDevice?.friendlyName ?: "Smart TV"
                )
            }
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {}

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun register() {
        streamProxy.start()
        refreshProxyEndpoint()

        castContext?.sessionManager?.addSessionManagerListener(
            sessionListener,
            CastSession::class.java
        )
        val currentSession = castContext?.sessionManager?.currentCastSession
        if (currentSession != null && currentSession.isConnected) {
            _uiState.update {
                it.copy(
                    isConnected = true,
                    deviceName = currentSession.castDevice?.friendlyName ?: "Smart TV"
                )
            }
        }
    }

    fun unregister() {
        castContext?.sessionManager?.removeSessionManagerListener(
            sessionListener,
            CastSession::class.java
        )
    }

    fun shutdown() {
        unregister()
        streamProxy.stop()
    }

    fun setUseLocalProxy(enabled: Boolean) {
        if (enabled) {
            streamProxy.start()
        }
        refreshProxyEndpoint()
        _uiState.update { it.copy(useLocalProxy = enabled) }
    }

    private fun refreshProxyEndpoint() {
        val ip = streamProxy.findLocalLanIpAddress()
        val endpoint = if (ip != null) "$ip:${streamProxy.boundPort}" else null
        _uiState.update { it.copy(localProxyEndpoint = endpoint) }
    }

    /**
     * Invia il flusso video selezionato alla Smart TV connessa.
     * Se `useLocalProxy` è attivo, instrada il flusso attraverso `LocalStreamProxy` sul telefono
     * per iniettare `Referer`, `Cookie`, `User-Agent` e gli header CORS per Chromecast.
     */
    fun castVideo(media: DetectedMedia) {
        refreshProxyEndpoint()
        val streamUrlToCast = if (_uiState.value.useLocalProxy) {
            streamProxy.createProxiedUrl(media)
        } else {
            media.url
        }

        val session = castContext?.sessionManager?.currentCastSession
        val remoteMediaClient = session?.remoteMediaClient

        if (session == null || !session.isConnected || remoteMediaClient == null) {
            _uiState.update {
                it.copy(
                    activeMedia = media,
                    activeStreamUrl = streamUrlToCast,
                    statusMessage = "Seleziona prima una Smart TV dal pulsante Cast in alto a destra"
                )
            }
            return
        }

        val modeLabel = if (_uiState.value.useLocalProxy && streamUrlToCast != media.url) {
            "${media.format.label} • Proxy Locale"
        } else {
            "${media.format.label} • Diretto"
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            putString(MediaMetadata.KEY_SUBTITLE, modeLabel)
        }

        val mediaInfo = MediaInfo.Builder(streamUrlToCast)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.mimeType)
            .setMetadata(metadata)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        remoteMediaClient.load(loadRequest)
        _uiState.update {
            it.copy(
                activeMedia = media,
                activeStreamUrl = streamUrlToCast,
                isPlaying = true,
                statusMessage = "Riproduzione avviata su ${it.deviceName ?: "TV"} ($modeLabel)"
            )
        }
    }

    fun togglePlayPause() {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        if (client.isPlaying) {
            client.pause()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            client.play()
            _uiState.update { it.copy(isPlaying = true) }
        }
    }

    fun stopCasting() {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
        client?.stop()
        _uiState.update {
            it.copy(
                activeMedia = null,
                activeStreamUrl = null,
                isPlaying = false,
                statusMessage = "Riproduzione terminata"
            )
        }
    }
}


package com.tvcast.browser.cast

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.proxy.LocalStreamProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class CastDevice(
    val id: String,
    val friendlyName: String,
    val modelName: String,
    val host: String,
    val port: Int = 8009
)

data class CastUiState(
    val discoveredDevices: List<CastDevice> = emptyList(),
    val isDiscovering: Boolean = false,
    val isConnected: Boolean = false,
    val connectedDevice: CastDevice? = null,
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
 * Implementazione 100% Open Source (FOSS) del protocollo Google Cast (CASTv2 su TLS porta 8009)
 * e discovery mDNS (`_googlecast._tcp`) tramite `NsdManager` nativo Android.
 *
 * Non utilizza `com.google.android.gms:play-services-cast-framework`, rendendo l'app
 * pienamente compatibile con i requisiti di F-Droid, IzzyOnDroid e dispositivi senza Google Play Services.
 */
class TvCastManager(
    context: Context,
    private val streamProxy: LocalStreamProxy = LocalStreamProxy()
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    private var castContext: CastContext? = try {
        CastContext.getSharedInstance(context)
    } catch (e: Exception) {
        null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    // Stato connessione socket CASTv2 TLS
    private var sslSocket: SSLSocket? = null
    private var socketOut: DataOutputStream? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    private val requestIdCounter = AtomicInteger(1)
    @Volatile private var receiverTransportId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var pendingMediaToLoad: DetectedMedia? = null

    fun register() {
        streamProxy.start()
        refreshProxyEndpoint()
        startDiscovery()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _uiState.update { it.copy(statusMessage = "Connessione alla TV in corso...") }
    fun unregister() {
        stopDiscovery()
    }

    fun shutdown() {
        stopDiscovery()
        disconnect()
        streamProxy.stop()
    }

    fun setUseLocalProxy(enabled: Boolean) {
        if (enabled) {
            streamProxy.start()
        }
        refreshProxyEndpoint()
        _uiState.update { it.copy(useLocalProxy = enabled) }
    }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            refreshProxyEndpoint()
            _uiState.update {
                it.copy(
                    isConnected = true,
                    deviceName = session.castDevice?.friendlyName ?: "Smart TV",
                    statusMessage = "Connesso a ${session.castDevice?.friendlyName ?: "Smart TV"}"
                )
    private fun refreshProxyEndpoint() {
        val ip = streamProxy.findLocalLanIpAddress()
        val endpoint = if (ip != null) "$ip:${streamProxy.boundPort}" else null
        _uiState.update { it.copy(localProxyEndpoint = endpoint) }
    }

    // MARK: - mDNS / NSD Discovery (_googlecast._tcp.)

    fun startDiscovery() {
        refreshProxyEndpoint()
        stopDiscovery()

        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("TVCastBrowserMulticast")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        } catch (_: Exception) {
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _uiState.update {
                it.copy(isConnected = false, statusMessage = "Connessione TV fallita (codice $error)")
        val manager = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {
                _uiState.update { it.copy(isDiscovering = true) }
            }
        }

        override fun onSessionEnding(session: CastSession) {}
            override fun onServiceFound(service: NsdServiceInfo?) {
                if (service == null) return
                resolveQueue.offer(service)
                processNextResolve()
            }

        override fun onSessionEnded(session: CastSession, error: Int) {
            _uiState.update { prev ->
                CastUiState(
                    useLocalProxy = prev.useLocalProxy,
                    localProxyEndpoint = prev.localProxyEndpoint,
                    statusMessage = "Disconnesso dalla TV"
                )
            override fun onServiceLost(service: NsdServiceInfo?) {}

            override fun onDiscoveryStopped(serviceType: String?) {
                _uiState.update { it.copy(isDiscovering = false) }
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _uiState.update { it.copy(isDiscovering = false) }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _uiState.update { it.copy(isDiscovering = false) }
            }
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Errore avvio NSD discovery: ${e.message}")
        }
    }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            refreshProxyEndpoint()
            _uiState.update {
                it.copy(
                    isConnected = true,
                    deviceName = session.castDevice?.friendlyName ?: "Smart TV"
                )
    fun stopDiscovery() {
        val manager = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        if (manager != null && listener != null) {
            try {
                manager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        _uiState.update { it.copy(isDiscovering = false) }
    }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
    @Suppress("DEPRECATION")
    private fun processNextResolve() {
        val manager = nsdManager ?: return
        if (!isResolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            isResolving.set(false)
            return
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    isResolving.set(false)
                    processNextResolve()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    isResolving.set(false)
                    if (serviceInfo != null) {
                        handleResolvedService(serviceInfo)
                    }
                    processNextResolve()
                }
            })
        } catch (e: Exception) {
            isResolving.set(false)
            processNextResolve()
        }
    }

    fun register() {
        streamProxy.start()
        refreshProxyEndpoint()
    @Suppress("DEPRECATION")
    private fun handleResolvedService(info: NsdServiceInfo) {
        val hostAddress = info.host?.hostAddress ?: return
        // Preferisci IPv4
        if (info.host !is Inet4Address && hostAddress.contains(":")) return

        castContext?.sessionManager?.addSessionManagerListener(
            sessionListener,
            CastSession::class.java
        val port = if (info.port > 0) info.port else 8009
        val attrs = info.attributes ?: emptyMap()

        val friendlyName = attrs["fn"]?.let { String(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
            ?: info.serviceName

        val modelName = attrs["md"]?.let { String(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
            ?: "Smart TV / Chromecast"

        val device = CastDevice(
            id = "$hostAddress:$port",
            friendlyName = friendlyName,
            modelName = modelName,
            host = hostAddress,
            port = port
        )
        val currentSession = castContext?.sessionManager?.currentCastSession
        if (currentSession != null && currentSession.isConnected) {
            _uiState.update {
                it.copy(
                    isConnected = true,
                    deviceName = currentSession.castDevice?.friendlyName ?: "Smart TV"

        _uiState.update { current ->
            val updated = current.discoveredDevices.toMutableList()
            val idx = updated.indexOfFirst { it.id == device.id }
            if (idx >= 0) {
                updated[idx] = device
            } else {
                updated.add(device)
            }
            current.copy(discoveredDevices = updated)
        }
    }

    // MARK: - Connessione CASTv2 su TLS (Porta 8009)

    fun connectToDevice(device: CastDevice) {
        disconnect()
        _uiState.update {
            it.copy(statusMessage = "Connessione a ${device.friendlyName}...")
        }

        scope.launch {
            try {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAll, SecureRandom())

                val socket = sslContext.socketFactory.createSocket() as SSLSocket
                socket.connect(InetSocketAddress(device.host, device.port), 10_000)
                socket.startHandshake()

                sslSocket = socket
                socketOut = DataOutputStream(socket.outputStream)

                _uiState.update {
                    it.copy(
                        isConnected = true,
                        connectedDevice = device,
                        deviceName = device.friendlyName,
                        statusMessage = "Connesso a ${device.friendlyName}"
                    )
                }

                // 1. Invia CONNECT al receiver-0
                sendCastMessage(
                    namespace = NS_CONNECTION,
                    destinationId = "receiver-0",
                    payload = JSONObject().put("type", "CONNECT")
                )

                // 2. Avvia loop di lettura e heartbeat
                startReaderLoop(socket)
                startHeartbeatLoop()

                // 3. Se c'era un video in attesa di trasmissione, avvialo subito
                pendingMediaToLoad?.let { pending ->
                    pendingMediaToLoad = null
                    castVideo(pending)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore connessione CASTv2 a ${device.host}:${device.port}", e)
                _uiState.update {
                    it.copy(
                        isConnected = false,
                        connectedDevice = null,
                        deviceName = null,
                        statusMessage = "Connessione fallita a ${device.friendlyName}"
                    )
                }
            }
        }
    }

    fun unregister() {
        castContext?.sessionManager?.removeSessionManagerListener(
            sessionListener,
            CastSession::class.java
    fun connectByManualIp(ipAddress: String) {
        val trimmed = ipAddress.trim()
        if (trimmed.isEmpty()) return
        val device = CastDevice(
            id = "$trimmed:8009",
            friendlyName = "Smart TV ($trimmed)",
            modelName = "Connessione diretta",
            host = trimmed,
            port = 8009
        )
        connectToDevice(device)
    }

    fun shutdown() {
        unregister()
        streamProxy.stop()
    }
    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        readerJob?.cancel()
        readerJob = null
        receiverTransportId = null
        mediaSessionId = null
        try {
            sslSocket?.close()
        } catch (_: Exception) {
        }
        sslSocket = null
        socketOut = null

    fun setUseLocalProxy(enabled: Boolean) {
        if (enabled) {
            streamProxy.start()
        _uiState.update {
            it.copy(
                isConnected = false,
                connectedDevice = null,
                deviceName = null,
                activeMedia = null,
                activeStreamUrl = null,
                isPlaying = false,
                statusMessage = "Disconnesso dalla TV"
            )
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
        _uiState.update {
            it.copy(
                activeMedia = media,
                activeStreamUrl = streamUrlToCast
            )
        }

        if (session == null || !session.isConnected || remoteMediaClient == null) {
            _uiState.update {
                it.copy(
                    activeMedia = media,
                    activeStreamUrl = streamUrlToCast,
                    statusMessage = "Seleziona prima una Smart TV dal pulsante Cast in alto a destra"
                )
        if (!_uiState.value.isConnected || sslSocket == null) {
            pendingMediaToLoad = media
            // Se c'è già almeno una Smart TV trovata sulla rete, connettiti automaticamente alla prima!
            val firstTv = _uiState.value.discoveredDevices.firstOrNull()
            if (firstTv != null) {
                connectToDevice(firstTv)
            } else {
                _uiState.update {
                    it.copy(statusMessage = "Seleziona una Smart TV dal pulsante Cast in alto a destra")
                }
            }
            return
        }

        scope.launch {
            receiverTransportId = null
            // Lancia il Default Media Receiver (CC1AD845) sulla Smart TV
            val launchJson = JSONObject()
                .put("type", "LAUNCH")
                .put("appId", DEFAULT_MEDIA_RECEIVER_APP_ID)
                .put("requestId", requestIdCounter.getAndIncrement())

            sendCastMessage(
                namespace = NS_RECEIVER,
                destinationId = "receiver-0",
                payload = launchJson
            )
        }
    }

    private fun sendMediaLoad(transportId: String, media: DetectedMedia) {
        val streamUrlToCast = if (_uiState.value.useLocalProxy) {
            streamProxy.createProxiedUrl(media)
        } else {
            media.url
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
        // 1. Apri canale virtuale verso il transportId dell'app Media Receiver
        sendCastMessage(
            namespace = NS_CONNECTION,
            destinationId = transportId,
            payload = JSONObject().put("type", "CONNECT")
        )

        val mediaInfo = MediaInfo.Builder(streamUrlToCast)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.mimeType)
            .setMetadata(metadata)
            .build()
        // 2. Costruisci e invia il comando LOAD
        val metadata = JSONObject()
            .put("metadataType", 1)
            .put("title", media.title)
            .put("subtitle", modeLabel)

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()
        val mediaObj = JSONObject()
            .put("contentId", streamUrlToCast)
            .put("streamType", "BUFFERED")
            .put("contentType", media.mimeType)
            .put("metadata", metadata)

        remoteMediaClient.load(loadRequest)
        val loadRequest = JSONObject()
            .put("type", "LOAD")
            .put("requestId", requestIdCounter.getAndIncrement())
            .put("autoplay", true)
            .put("media", mediaObj)

        sendCastMessage(
            namespace = NS_MEDIA,
            destinationId = transportId,
            payload = loadRequest
        )

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
        val transportId = receiverTransportId ?: return
        val sessionId = mediaSessionId ?: return
        val currentlyPlaying = _uiState.value.isPlaying
        val command = if (currentlyPlaying) "PAUSE" else "PLAY"

        scope.launch {
            val payload = JSONObject()
                .put("type", command)
                .put("mediaSessionId", sessionId)
                .put("requestId", requestIdCounter.getAndIncrement())
            sendCastMessage(
                namespace = NS_MEDIA,
                destinationId = transportId,
                payload = payload
            )
            _uiState.update { it.copy(isPlaying = !currentlyPlaying) }
        }
    }

    fun stopCasting() {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
        client?.stop()
        val transportId = receiverTransportId
        val sessionId = mediaSessionId
        if (transportId != null && sessionId != null) {
            scope.launch {
                val payload = JSONObject()
                    .put("type", "STOP")
                    .put("mediaSessionId", sessionId)
                    .put("requestId", requestIdCounter.getAndIncrement())
                sendCastMessage(
                    namespace = NS_MEDIA,
                    destinationId = transportId,
                    payload = payload
                )
            }
        }
        _uiState.update {
            it.copy(
                activeMedia = null,
                activeStreamUrl = null,
                isPlaying = false,
                statusMessage = "Riproduzione terminata"
            )
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && sslSocket?.isConnected == true) {
                delay(5_000)
                sendCastMessage(
                    namespace = NS_HEARTBEAT,
                    destinationId = "receiver-0",
                    payload = JSONObject().put("type", "PING")
                )
            }
        }
    }

    private fun startReaderLoop(socket: SSLSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val input = DataInputStream(socket.inputStream)
                while (isActive && !socket.isClosed) {
                    val length = input.readInt()
                    if (length <= 0 || length > 262_144) continue
                    val packetBytes = ByteArray(length)
                    input.readFully(packetBytes)
                    val utf8Payload = extractUtf8PayloadFromProtobuf(packetBytes) ?: continue
                    handleIncomingJson(utf8Payload)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Connessione CASTv2 chiusa: ${e.message}")
                    disconnect()
                }
            }
        }
    }

    private fun handleIncomingJson(rawJson: String) {
        try {
            val obj = JSONObject(rawJson)
            when (obj.optString("type")) {
                "PING" -> {
                    sendCastMessage(
                        namespace = NS_HEARTBEAT,
                        destinationId = "receiver-0",
                        payload = JSONObject().put("type", "PONG")
                    )
                }
                "RECEIVER_STATUS" -> {
                    val status = obj.optJSONObject("status") ?: return
                    val apps = status.optJSONArray("applications") ?: return
                    for (i in 0 until apps.length()) {
                        val app = apps.optJSONObject(i) ?: continue
                        if (app.optString("appId") == DEFAULT_MEDIA_RECEIVER_APP_ID) {
                            val transportId = app.optString("transportId")
                            if (transportId.isNotBlank() && receiverTransportId != transportId) {
                                receiverTransportId = transportId
                                _uiState.value.activeMedia?.let { media ->
                                    sendMediaLoad(transportId, media)
                                }
                            }
                        }
                    }
                }
                "MEDIA_STATUS" -> {
                    val statuses = obj.optJSONArray("status") ?: return
                    if (statuses.length() > 0) {
                        val first = statuses.optJSONObject(0) ?: return
                        val sessionId = first.optInt("mediaSessionId", -1)
                        if (sessionId != -1) {
                            mediaSessionId = sessionId
                        }
                        val playerState = first.optString("playerState")
                        if (playerState.isNotBlank()) {
                            val playing = (playerState == "PLAYING" || playerState == "BUFFERING")
                            _uiState.update { it.copy(isPlaying = playing) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun sendCastMessage(namespace: String, destinationId: String, payload: JSONObject) {
        val out = socketOut ?: return
        try {
            val protoBytes = encodeCastMessageProtobuf(
                sourceId = "sender-0",
                destinationId = destinationId,
                namespace = namespace,
                payloadUtf8 = payload.toString()
            )
            out.writeInt(protoBytes.size)
            out.write(protoBytes)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Errore invio messaggio CASTv2: ${e.message}")
        }
    }

    // MARK: - Protobuf Encoding / Decoding minimale per CastMessage

    private fun encodeCastMessageProtobuf(
        sourceId: String,
        destinationId: String,
        namespace: String,
        payloadUtf8: String
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        // 1: protocol_version = 0 (CASTV2_1_0) -> tag 0x08, val 0x00
        bos.write(0x08)
        bos.write(0x00)
        // 2: source_id -> tag 0x12
        writeProtobufString(bos, 0x12, sourceId)
        // 3: destination_id -> tag 0x1A
        writeProtobufString(bos, 0x1A, destinationId)
        // 4: namespace -> tag 0x22
        writeProtobufString(bos, 0x22, namespace)
        // 5: payload_type = 0 (STRING) -> tag 0x28, val 0x00
        bos.write(0x28)
        bos.write(0x00)
        // 6: payload_utf8 -> tag 0x32
        writeProtobufString(bos, 0x32, payloadUtf8)
        return bos.toByteArray()
    }

    private fun writeProtobufString(bos: ByteArrayOutputStream, tag: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        bos.write(tag)
        var len = bytes.size
        while (len >= 0x80) {
            bos.write((len and 0x7F) or 0x80)
            len = len ushr 7
        }
        bos.write(len)
        bos.write(bytes)
    }

    private fun extractUtf8PayloadFromProtobuf(bytes: ByteArray): String? {
        var idx = 0
        while (idx < bytes.size) {
            val tag = bytes[idx].toInt() and 0xFF
            idx++
            val wireType = tag and 0x07
            val fieldNum = tag ushr 3

            if (wireType == 0) {
                while (idx < bytes.size && (bytes[idx].toInt() and 0x80) != 0) {
                    idx++
                }
                idx++
            } else if (wireType == 2) {
                var length = 0
                var shift = 0
                while (idx < bytes.size) {
                    val b = bytes[idx].toInt() and 0xFF
                    idx++
                    length = length or ((b and 0x7F) shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                if (length < 0 || idx + length > bytes.size) return null
                if (fieldNum == 6) {
                    return String(bytes, idx, length, Charsets.UTF_8)
                }
                idx += length
            } else {
                break
            }
        }
        return null
    }

    companion object {
        private const val TAG = "TvCastManager"
        private const val SERVICE_TYPE = "_googlecast._tcp."
        private const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}


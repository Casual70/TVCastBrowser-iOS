package com.tvcast.browser.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.proxy.LocalStreamProxy
import com.tvcast.browser.proxy.ProxyBufferStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val currentPositionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val useLocalProxy: Boolean = true,
    val localProxyEndpoint: String? = null,
    val bufferStats: ProxyBufferStats = ProxyBufferStats(),
    val statusMessage: String? = null
)

/**
 * Gestore 100% Open Source (FOSS) del protocollo Google Cast (CASTv2 su TLS porta 8009)
 * con:
 * - Pre-Buffering RAM dei segmenti HLS tramite [LocalStreamProxy]
 * - Blocco risparmio energetico Wi-Fi (`WifiLock` Low-Latency/High-Perf) e CPU (`PartialWakeLock`)
 * - Riconnessione automatica trasparente del canale di controllo CASTv2 senza interrompere il video sulla TV.
 */
class TvCastManager(
    context: Context,
    private val streamProxy: LocalStreamProxy = LocalStreamProxy()
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    private var sslSocket: SSLSocket? = null
    private var socketOut: DataOutputStream? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private val requestIdCounter = AtomicInteger(1)
    private val shouldLoadNewMedia = AtomicBoolean(false)
    private val userRequestedDisconnect = AtomicBoolean(false)

    @Volatile private var receiverTransportId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var pendingMediaToLoad: DetectedMedia? = null

    init {
        scope.launch {
            streamProxy.bufferStats.collectLatest { stats ->
                _uiState.update { it.copy(bufferStats = stats) }
            }
        }
    }

    fun register() {
        streamProxy.start()
        refreshProxyEndpoint()
        startDiscovery()
    }

    fun unregister() {
        stopDiscovery()
        // Nota: NON fermiamo streamProxy né i WakeLock/WifiLock se c'è una trasmissione attiva!
    }

    fun shutdown() {
        stopDiscovery()
        disconnect()
        streamProxy.stop()
        releaseStreamingLocks()
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

    @Suppress("DEPRECATION")
    private fun acquireStreamingLocks() {
        try {
            if (wifiHighPerfLock == null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiHighPerfLock = wifiManager?.createWifiLock(lockMode, "TVCastBrowser:WifiStreamingLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiHighPerfLock?.isHeld == false) {
                wifiHighPerfLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile acquisire WifiLock: ${e.message}")
        }

        try {
            if (cpuWakeLock == null) {
                cpuWakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TVCastBrowser::StreamingWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (cpuWakeLock?.isHeld == false) {
                cpuWakeLock?.acquire(4 * 60 * 60 * 1000L) // Max 4 ore
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile acquisire WakeLock: ${e.message}")
        }
    }

    private fun releaseStreamingLocks() {
        try {
            if (wifiHighPerfLock?.isHeld == true) {
                wifiHighPerfLock?.release()
            }
        } catch (_: Exception) {
        }
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        } catch (_: Exception) {
        }
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

        val manager = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {
                _uiState.update { it.copy(isDiscovering = true) }
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                if (service == null) return
                resolveQueue.offer(service)
                processNextResolve()
            }

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

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Errore avvio NSD discovery: ${e.message}")
        }
    }

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

    @Suppress("DEPRECATION")
    private fun processNextResolve() {
        val manager = nsdManager ?: return
        if (!isResolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            isResolving.set(false)
            return
        }

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

    @Suppress("DEPRECATION")
    private fun handleResolvedService(info: NsdServiceInfo) {
        val hostAddress = info.host?.hostAddress ?: return
        if (info.host !is Inet4Address && hostAddress.contains(":")) return

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

    // MARK: - Connessione CASTv2 su TLS (Porta 8009) con Auto-Reconnect

    fun connectToDevice(device: CastDevice) {
        userRequestedDisconnect.set(false)
        reconnectJob?.cancel()
        closeSocketOnly()
        acquireStreamingLocks()

        _uiState.update {
            it.copy(statusMessage = "Connessione a ${device.friendlyName}...")
        }

        scope.launch {
            openCastSocket(device, isAutoReconnect = false)
        }
    }

    private suspend fun openCastSocket(device: CastDevice, isAutoReconnect: Boolean): Boolean {
        return try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())

            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(device.host, device.port), 10_000)
            socket.startHandshake()

            sslSocket = socket
            socketOut = DataOutputStream(socket.outputStream)

            _uiState.update {
                it.copy(
                    isConnected = true,
                    connectedDevice = device,
                    deviceName = device.friendlyName,
                    statusMessage = if (isAutoReconnect && it.activeMedia != null) {
                        "Connesso a ${device.friendlyName} (Buffer attivo)"
                    } else {
                        "Connesso a ${device.friendlyName}"
                    }
                )
            }

            // 1. Invia CONNECT al receiver-0
            sendCastMessage(
                namespace = NS_CONNECTION,
                destinationId = "receiver-0",
                payload = JSONObject().put("type", "CONNECT")
            )

            // 2. Avvia loop di lettura e heartbeat
            startReaderLoop(socket, device)
            startHeartbeatLoop()

            // 3. Se c'era un video in attesa di trasmissione, avvialo subito;
            //    altrimenti chiedi lo stato attuale del receiver senza interrompere la riproduzione!
            val pending = pendingMediaToLoad
            if (pending != null) {
                pendingMediaToLoad = null
                castVideo(pending)
            } else {
                sendCastMessage(
                    namespace = NS_RECEIVER,
                    destinationId = "receiver-0",
                    payload = JSONObject()
                        .put("type", "GET_STATUS")
                        .put("requestId", requestIdCounter.getAndIncrement())
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Errore connessione CASTv2 a ${device.host}:${device.port}", e)
            if (!isAutoReconnect) {
                _uiState.update {
                    it.copy(
                        isConnected = false,
                        connectedDevice = null,
                        deviceName = null,
                        statusMessage = "Connessione fallita a ${device.friendlyName}"
                    )
                }
            }
            false
        }
    }

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

    private fun closeSocketOnly() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        readerJob?.cancel()
        readerJob = null
        try {
            sslSocket?.close()
        } catch (_: Exception) {
        }
        sslSocket = null
        socketOut = null
    }

    fun disconnect() {
        userRequestedDisconnect.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocketOnly()
        receiverTransportId = null
        mediaSessionId = null
        shouldLoadNewMedia.set(false)
        releaseStreamingLocks()

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
    }

    fun castVideo(media: DetectedMedia) {
        acquireStreamingLocks()
        refreshProxyEndpoint()
        val streamUrlToCast = if (_uiState.value.useLocalProxy) {
            streamProxy.createProxiedUrl(media)
        } else {
            media.url
        }

        _uiState.update {
            it.copy(
                activeMedia = media,
                activeStreamUrl = streamUrlToCast
            )
        }

        if (!_uiState.value.isConnected || sslSocket == null) {
            pendingMediaToLoad = media
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
            shouldLoadNewMedia.set(true)
            receiverTransportId = null
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
        val streamUrlToCast = _uiState.value.activeStreamUrl ?: if (_uiState.value.useLocalProxy) {
            streamProxy.createProxiedUrl(media)
        } else {
            media.url
        }
        val modeLabel = if (_uiState.value.useLocalProxy && streamUrlToCast != media.url) {
            "${media.format.label} • Proxy + Buffer RAM"
        } else {
            "${media.format.label} • Diretto"
        }

        sendCastMessage(
            namespace = NS_CONNECTION,
            destinationId = transportId,
            payload = JSONObject().put("type", "CONNECT")
        )

        val metadata = JSONObject()
            .put("metadataType", 1)
            .put("title", media.title)
            .put("subtitle", modeLabel)

        val mediaObj = JSONObject()
            .put("contentId", streamUrlToCast)
            .put("streamType", "BUFFERED")
            .put("contentType", media.mimeType)
            .put("metadata", metadata)

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

    /**
     * Sposta la riproduzione sulla TV a un secondo specifico (Seek manuale).
     * Riavvia il timer di stabilità di 30 secondi del buffer senza perdere i segmenti già in RAM.
     */
    fun seekTo(positionSeconds: Double) {
        val transportId = receiverTransportId ?: return
        val sessionId = mediaSessionId ?: return
        val duration = _uiState.value.durationSeconds
        val targetPos = if (duration > 0) positionSeconds.coerceIn(0.0, duration) else positionSeconds.coerceAtLeast(0.0)

        streamProxy.notifyManualSeek()
        _uiState.update { it.copy(currentPositionSeconds = targetPos) }

        scope.launch {
            val payload = JSONObject()
                .put("type", "SEEK")
                .put("mediaSessionId", sessionId)
                .put("currentTime", targetPos)
                .put("requestId", requestIdCounter.getAndIncrement())
            sendCastMessage(
                namespace = NS_MEDIA,
                destinationId = transportId,
                payload = payload
            )
        }
    }

    fun seekRelative(deltaSeconds: Double) {
        val current = _uiState.value.currentPositionSeconds
        seekTo(current + deltaSeconds)
    }

    fun stopCasting() {
        val transportId = receiverTransportId
        val sessionId = mediaSessionId
        shouldLoadNewMedia.set(false)
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
        streamProxy.clearBufferCache()
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
            var ticks = 0
            while (isActive && sslSocket?.isConnected == true) {
                delay(1_000)
                ticks++
                if (_uiState.value.isPlaying) {
                    _uiState.update { state ->
                        val nextPos = state.currentPositionSeconds + 1.0
                        val clamped = if (state.durationSeconds > 0) nextPos.coerceAtMost(state.durationSeconds) else nextPos
                        state.copy(currentPositionSeconds = clamped)
                    }
                }
                if (ticks % 4 == 0) {
                    sendCastMessage(
                        namespace = NS_HEARTBEAT,
                        destinationId = "receiver-0",
                        payload = JSONObject().put("type", "PING")
                    )
                }
                if (ticks % 10 == 0) {
                    val tId = receiverTransportId
                    if (tId != null) {
                        sendCastMessage(
                            namespace = NS_MEDIA,
                            destinationId = tId,
                            payload = JSONObject()
                                .put("type", "GET_STATUS")
                                .put("requestId", requestIdCounter.getAndIncrement())
                        )
                    }
                }
            }
        }
    }

    private fun startReaderLoop(socket: SSLSocket, device: CastDevice) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val input = DataInputStream(socket.inputStream)
                while (isActive && !socket.isClosed) {
                    val length = input.readInt()
                    if (length <= 0 || length > 262_144) continue
                    val packetBytes = ByteArray(length)
                    input.readFully(packetBytes)
                    val parsed = extractCastPacketFromProtobuf(packetBytes) ?: continue
                    handleIncomingPacket(parsed)
                }
            } catch (e: Exception) {
                if (isActive && !userRequestedDisconnect.get()) {
                    Log.w(TAG, "Socket controllo CASTv2 chiuso (${e.message}), avvio auto-reconnect...")
                    scheduleAutoReconnect(device)
                }
            }
        }
    }

    /**
     * Se il socket di controllo sulla porta 8009 cade temporaneamente mentre la TV sta riproducendo
     * dal LocalStreamProxy, riconnette il socket di controllo in background senza interrompere il video!
     */
    private fun scheduleAutoReconnect(device: CastDevice) {
        if (userRequestedDisconnect.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            closeSocketOnly()
            for (attempt in 1..10) {
                if (userRequestedDisconnect.get()) return@launch
                delay(2_000L)
                Log.i(TAG, "Tentativo auto-reconnect CASTv2 #$attempt a ${device.friendlyName}...")
                if (openCastSocket(device, isAutoReconnect = true)) {
                    Log.i(TAG, "Auto-reconnect CASTv2 riuscito!")
                    return@launch
                }
            }
        }
    }

    private data class IncomingCastPacket(
        val sourceId: String,
        val destinationId: String,
        val namespace: String,
        val payloadUtf8: String
    )

    private fun handleIncomingPacket(packet: IncomingCastPacket) {
        try {
            val obj = JSONObject(packet.payloadUtf8)
            when (obj.optString("type")) {
                "PING" -> {
                    val replyDest = packet.sourceId.ifBlank { "receiver-0" }
                    sendCastMessage(
                        namespace = NS_HEARTBEAT,
                        destinationId = replyDest,
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
                            if (transportId.isNotBlank()) {
                                val isNewTransport = (receiverTransportId != transportId)
                                receiverTransportId = transportId
                                if (shouldLoadNewMedia.compareAndSet(true, false)) {
                                    _uiState.value.activeMedia?.let { media ->
                                        sendMediaLoad(transportId, media)
                                    }
                                } else if (isNewTransport) {
                                    // Ri-aggancia la sessione media in corso senza riavviare il video
                                    sendCastMessage(
                                        namespace = NS_CONNECTION,
                                        destinationId = transportId,
                                        payload = JSONObject().put("type", "CONNECT")
                                    )
                                    sendCastMessage(
                                        namespace = NS_MEDIA,
                                        destinationId = transportId,
                                        payload = JSONObject()
                                            .put("type", "GET_STATUS")
                                            .put("requestId", requestIdCounter.getAndIncrement())
                                    )
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
                        val currentTime = first.optDouble("currentTime", -1.0)
                        val mediaObj = first.optJSONObject("media")
                        val duration = mediaObj?.optDouble("duration", -1.0) ?: -1.0
                        val playerState = first.optString("playerState")

                        _uiState.update { prev ->
                            val playing = if (playerState.isNotBlank()) {
                                (playerState == "PLAYING" || playerState == "BUFFERING")
                            } else {
                                prev.isPlaying
                            }
                            prev.copy(
                                isPlaying = playing,
                                currentPositionSeconds = if (currentTime >= 0.0) currentTime else prev.currentPositionSeconds,
                                durationSeconds = if (duration > 0.0) duration else prev.durationSeconds
                            )
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

    private fun encodeCastMessageProtobuf(
        sourceId: String,
        destinationId: String,
        namespace: String,
        payloadUtf8: String
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(0x08)
        bos.write(0x00)
        writeProtobufString(bos, 0x12, sourceId)
        writeProtobufString(bos, 0x1A, destinationId)
        writeProtobufString(bos, 0x22, namespace)
        bos.write(0x28)
        bos.write(0x00)
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

    private fun extractCastPacketFromProtobuf(bytes: ByteArray): IncomingCastPacket? {
        var idx = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadUtf8: String? = null

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
                val strVal = String(bytes, idx, length, Charsets.UTF_8)
                when (fieldNum) {
                    2 -> sourceId = strVal
                    3 -> destinationId = strVal
                    4 -> namespace = strVal
                    6 -> payloadUtf8 = strVal
                }
                idx += length
            } else {
                break
            }
        }
        val payload = payloadUtf8 ?: return null
        return IncomingCastPacket(
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = payload
        )
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
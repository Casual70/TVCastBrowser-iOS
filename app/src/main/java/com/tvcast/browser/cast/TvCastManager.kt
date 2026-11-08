package com.tvcast.browser.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.model.StreamFormat
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Gestore Singleton 100% Open Source (FOSS) del protocollo Google Cast (CASTv2 su TLS porta 8009):
 * - Mantiene l'istanza viva a livello di processo tramite [CastForegroundService] così il passaggio
 *   in background o l'apertura delle notifiche non disconnette MAI l'app dal video in riproduzione.
 * - Quando l'app torna in primo piano ([onAppResumed]), ri-sincronizza istantaneamente il canale
 *   virtuale (`transportId`) e lo stato `MEDIA_STATUS` (minutaggio, durata, titolo, play/pausa)
 *   con la Smart TV, permettendo sempre di avanzare (seek) o fermare il video direttamente dall'app.
 */
class TvCastManager private constructor(
    context: Context,
    private val streamProxy: LocalStreamProxy = LocalStreamProxy()
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tv_cast_session_prefs", Context.MODE_PRIVATE)
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    @Volatile private var sslSocket: SSLSocket? = null
    @Volatile private var socketOut: DataOutputStream? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private val requestIdCounter = AtomicInteger(1)
    private val shouldLoadNewMedia = AtomicBoolean(false)
    private val userRequestedDisconnect = AtomicBoolean(false)

    @Volatile private var receiverTransportId: String? = null
    @Volatile private var connectedTransportIdOnCurrentSocket: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var pendingMediaToLoad: DetectedMedia? = null

    init {
        scope.launch {
            streamProxy.bufferStats.collectLatest { stats ->
                _uiState.update { it.copy(bufferStats = stats) }
            }
        }
    }

    /**
     * Chiamato quando `MainActivity` torna in primo piano (`onResume` / `onNewIntent`).
     * Riavvia il proxy se necessario, avvia la scoperta mDNS e **ri-sincronizza immediatamente**
     * lo stato della riproduzione con la Smart TV!
     */
    fun onAppResumed() {
        streamProxy.start()
        refreshProxyEndpoint()
        startDiscovery()

        scope.launch {
            val targetDevice = _uiState.value.connectedDevice ?: loadSavedDevice()
            if (targetDevice != null && !userRequestedDisconnect.get()) {
                val sock = sslSocket
                if (sock != null && sock.isConnected && !sock.isClosed) {
                    // Socket già attivo: forza un GET_STATUS immediato sia sul receiver sia sul player media!
                    requestFullStatusSync()
                } else {
                    // Socket chiuso mentre l'app era in background: riconnetti e ri-sincronizza subito!
                    reconnectJob?.cancel()
                    openCastSocket(targetDevice, isAutoReconnect = true)
                }
            }
        }
    }

    fun onAppPaused() {
        stopDiscovery()
        // NON chiudiamo MAI sslSocket né LocalStreamProxy quando l'app va in background!
    }

    fun register() = onAppResumed()
    fun unregister() = onAppPaused()

    fun shutdown() {
        stopDiscovery()
        disconnect()
        streamProxy.stop()
        releaseStreamingLocks()
    }

    /**
     * Forza la ri-sincronizzazione immediata con il video in riproduzione sulla TV.
     */
    fun requestFullStatusSync() {
        scope.launch {
            val sock = sslSocket
            val device = _uiState.value.connectedDevice ?: loadSavedDevice()
            if (sock == null || !sock.isConnected || sock.isClosed) {
                if (device != null && !userRequestedDisconnect.get()) {
                    openCastSocket(device, isAutoReconnect = true)
                }
                return@launch
            }

            sendCastMessage(
                namespace = NS_RECEIVER,
                destinationId = "receiver-0",
                payload = JSONObject()
                    .put("type", "GET_STATUS")
                    .put("requestId", requestIdCounter.getAndIncrement())
            )
            val tId = receiverTransportId
            if (!tId.isNullOrBlank()) {
                ensureTransportConnected(tId)
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

    private fun saveConnectedDevice(device: CastDevice) {
        prefs.edit()
            .putString("id", device.id)
            .putString("friendlyName", device.friendlyName)
            .putString("modelName", device.modelName)
            .putString("host", device.host)
            .putInt("port", device.port)
            .apply()
    }

    private fun clearSavedDevice() {
        prefs.edit().clear().apply()
    }

    private fun loadSavedDevice(): CastDevice? {
        val host = prefs.getString("host", null) ?: return null
        val port = prefs.getInt("port", 8009)
        val id = prefs.getString("id", "$host:$port") ?: "$host:$port"
        val name = prefs.getString("friendlyName", "Smart TV") ?: "Smart TV"
        val model = prefs.getString("modelName", "Chromecast") ?: "Chromecast"
        return CastDevice(id = id, friendlyName = name, modelName = model, host = host, port = port)
    }

    private fun updateForegroundServiceNotification() {
        val state = _uiState.value
        if (state.isConnected && (state.activeMedia != null || state.isPlaying)) {
            CastForegroundService.startOrUpdate(
                context = appContext,
                deviceName = state.deviceName ?: "Smart TV",
                mediaTitle = state.activeMedia?.title ?: "Video in riproduzione sulla TV",
                isPlaying = state.isPlaying
            )
        } else if (!state.isConnected && state.activeMedia == null) {
            CastForegroundService.stop(appContext)
        }
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
                cpuWakeLock?.acquire(4 * 60 * 60 * 1000L)
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

        // Se questa è l'ultima TV usata e non siamo ancora connessi, riconnettiti automaticamente per sincronizzare!
        val saved = loadSavedDevice()
        if (saved != null && saved.host == device.host && !_uiState.value.isConnected && !userRequestedDisconnect.get()) {
            scope.launch {
                openCastSocket(device, isAutoReconnect = true)
            }
        }
    }

    // MARK: - Connessione CASTv2 su TLS (Porta 8009) con Auto-Reconnect & Re-Sync

    fun connectToDevice(device: CastDevice) {
        userRequestedDisconnect.set(false)
        saveConnectedDevice(device)
        reconnectJob?.cancel()
        acquireStreamingLocks()

        _uiState.update {
            it.copy(statusMessage = "Connessione a ${device.friendlyName}...")
        }

        scope.launch {
            openCastSocket(device, isAutoReconnect = false)
        }
    }

    private suspend fun openCastSocket(device: CastDevice, isAutoReconnect: Boolean): Boolean {
        return connectMutex.withLock {
            // Se un altro job ha già ristabilito la connessione, non aprire un secondo socket!
            val existing = sslSocket
            if (existing != null && existing.isConnected && !existing.isClosed && _uiState.value.connectedDevice?.host == device.host) {
                return@withLock true
            }

            closeSocketOnly()

            try {
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
                socket.connect(InetSocketAddress(device.host, device.port), 6_000)
                socket.startHandshake()

                sslSocket = socket
                socketOut = DataOutputStream(socket.outputStream)
                connectedTransportIdOnCurrentSocket = null

                saveConnectedDevice(device)
                acquireStreamingLocks()

                _uiState.update {
                    it.copy(
                        isConnected = true,
                        connectedDevice = device,
                        deviceName = device.friendlyName,
                        statusMessage = if (isAutoReconnect && it.activeMedia != null) {
                            "Sincronizzato con ${device.friendlyName}"
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

                // 3. Se c'era un video in attesa di trasmissione, avvialo;
                //    altrimenti chiedi subito GET_STATUS per ri-agganciare la sessione attiva sulla TV!
                val pending = pendingMediaToLoad
                if (pending != null) {
                    pendingMediaToLoad = null
                    launchMediaOnConnectedSocket(pending)
                } else {
                    sendCastMessage(
                        namespace = NS_RECEIVER,
                        destinationId = "receiver-0",
                        payload = JSONObject()
                            .put("type", "GET_STATUS")
                            .put("requestId", requestIdCounter.getAndIncrement())
                    )
                }

                updateForegroundServiceNotification()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Connessione CASTv2 non riuscita a ${device.host}:${device.port}: ${e.message}")
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

    /**
     * Chiude solo il socket TLS corrente resettando SEMPRE `receiverTransportId` e `connectedTransportIdOnCurrentSocket`
     * così alla riconnessione il nuovo socket invierà obbligatoriamente `CONNECT` e `GET_STATUS` al `transportId`!
     */
    private fun closeSocketOnly() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        readerJob?.cancel()
        readerJob = null
        receiverTransportId = null
        connectedTransportIdOnCurrentSocket = null
        try {
            sslSocket?.close()
        } catch (_: Throwable) {
        }
        sslSocket = null
        socketOut = null
    }

    fun disconnect() {
        userRequestedDisconnect.set(true)
        clearSavedDevice()
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocketOnly()
        mediaSessionId = null
        shouldLoadNewMedia.set(false)
        releaseStreamingLocks()
        CastForegroundService.stop(appContext)

        _uiState.update {
            it.copy(
                isConnected = false,
                connectedDevice = null,
                deviceName = null,
                activeMedia = null,
                activeStreamUrl = null,
                isPlaying = false,
                currentPositionSeconds = 0.0,
                durationSeconds = 0.0,
                statusMessage = "Disconnesso dalla TV"
            )
        }
    }

    fun castVideo(media: DetectedMedia) {
        userRequestedDisconnect.set(false)
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
                activeStreamUrl = streamUrlToCast,
                currentPositionSeconds = 0.0,
                durationSeconds = 0.0
            )
        }
        updateForegroundServiceNotification()

        val sock = sslSocket
        if (!_uiState.value.isConnected || sock == null || !sock.isConnected || sock.isClosed) {
            pendingMediaToLoad = media
            val targetTv = _uiState.value.connectedDevice
                ?: loadSavedDevice()
                ?: _uiState.value.discoveredDevices.firstOrNull()
            if (targetTv != null) {
                connectToDevice(targetTv)
            } else {
                _uiState.update {
                    it.copy(statusMessage = "Seleziona una Smart TV dal pulsante Cast in alto a destra")
                }
            }
            return
        }

        scope.launch {
            launchMediaOnConnectedSocket(media)
        }
    }

    private fun launchMediaOnConnectedSocket(media: DetectedMedia) {
        shouldLoadNewMedia.set(true)
        receiverTransportId = null
        connectedTransportIdOnCurrentSocket = null
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

    private fun ensureTransportConnected(transportId: String) {
        if (connectedTransportIdOnCurrentSocket != transportId) {
            connectedTransportIdOnCurrentSocket = transportId
            sendCastMessage(
                namespace = NS_CONNECTION,
                destinationId = transportId,
                payload = JSONObject().put("type", "CONNECT")
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
            "${media.format.label} • Proxy + Buffer 100MB"
        } else {
            "${media.format.label} • Diretto"
        }

        ensureTransportConnected(transportId)

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
        updateForegroundServiceNotification()
    }

    fun togglePlayPause() {
        scope.launch {
            val tId = ensureReadyForMediaCommand() ?: return@launch
            val sId = mediaSessionId ?: return@launch
            val currentlyPlaying = _uiState.value.isPlaying
            val command = if (currentlyPlaying) "PAUSE" else "PLAY"

            val payload = JSONObject()
                .put("type", command)
                .put("mediaSessionId", sId)
                .put("requestId", requestIdCounter.getAndIncrement())
            sendCastMessage(
                namespace = NS_MEDIA,
                destinationId = tId,
                payload = payload
            )
            _uiState.update { it.copy(isPlaying = !currentlyPlaying) }
            updateForegroundServiceNotification()
        }
    }

    fun seekTo(positionSeconds: Double) {
        val duration = _uiState.value.durationSeconds
        val targetPos = if (duration > 0) positionSeconds.coerceIn(0.0, duration) else positionSeconds.coerceAtLeast(0.0)

        streamProxy.notifyManualSeek()
        _uiState.update { it.copy(currentPositionSeconds = targetPos) }

        scope.launch {
            val tId = ensureReadyForMediaCommand() ?: return@launch
            val sId = mediaSessionId ?: return@launch
            val payload = JSONObject()
                .put("type", "SEEK")
                .put("mediaSessionId", sId)
                .put("currentTime", targetPos)
                .put("requestId", requestIdCounter.getAndIncrement())
            sendCastMessage(
                namespace = NS_MEDIA,
                destinationId = tId,
                payload = payload
            )
        }
    }

    fun seekRelative(deltaSeconds: Double) {
        val current = _uiState.value.currentPositionSeconds
        seekTo(current + deltaSeconds)
    }

    fun stopCasting() {
        shouldLoadNewMedia.set(false)
        scope.launch {
            val tId = receiverTransportId
            val sId = mediaSessionId
            if (tId != null && sId != null) {
                ensureTransportConnected(tId)
                val payload = JSONObject()
                    .put("type", "STOP")
                    .put("mediaSessionId", sId)
                    .put("requestId", requestIdCounter.getAndIncrement())
                sendCastMessage(
                    namespace = NS_MEDIA,
                    destinationId = tId,
                    payload = payload
                )
            }
            // Chiude anche l'app receiver sulla TV così la TV torna alla schermata principale
            sendCastMessage(
                namespace = NS_RECEIVER,
                destinationId = "receiver-0",
                payload = JSONObject()
                    .put("type", "STOP")
                    .put("requestId", requestIdCounter.getAndIncrement())
            )
        }
        streamProxy.clearBufferCache()
        CastForegroundService.stop(appContext)
        _uiState.update {
            it.copy(
                activeMedia = null,
                activeStreamUrl = null,
                isPlaying = false,
                currentPositionSeconds = 0.0,
                durationSeconds = 0.0,
                statusMessage = "Riproduzione terminata"
            )
        }
    }

    /**
     * Garantisce che il socket e il `transportId` siano connessi prima di inviare un comando (Play/Pausa/Seek).
     * Se l'app era appena tornata dal background, attende fino a 2 secondi la ri-sincronizzazione automatica!
     */
    private suspend fun ensureReadyForMediaCommand(): String? {
        val sock = sslSocket
        if (sock == null || !sock.isConnected || sock.isClosed) {
            val device = _uiState.value.connectedDevice ?: loadSavedDevice() ?: return null
            openCastSocket(device, isAutoReconnect = true)
        }
        if (receiverTransportId == null || mediaSessionId == null) {
            requestFullStatusSync()
            repeat(15) {
                delay(120)
                if (receiverTransportId != null && mediaSessionId != null) {
                    val tId = receiverTransportId!!
                    ensureTransportConnected(tId)
                    return tId
                }
            }
        }
        val tId = receiverTransportId ?: return null
        ensureTransportConnected(tId)
        return tId
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
                if (ticks % 8 == 0) {
                    val tId = receiverTransportId
                    if (tId != null) {
                        ensureTransportConnected(tId)
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

    private fun scheduleAutoReconnect(device: CastDevice) {
        if (userRequestedDisconnect.get()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            closeSocketOnly()
            var attempt = 0
            while (isActive && !userRequestedDisconnect.get()) {
                attempt++
                val delayMs = (1_500L * attempt.coerceAtMost(4))
                delay(delayMs)
                if (userRequestedDisconnect.get()) return@launch
                if (openCastSocket(device, isAutoReconnect = true)) {
                    Log.i(TAG, "Auto-reconnect e ri-sincronizzazione CASTv2 riusciti con ${device.friendlyName}!")
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
                    val apps = status.optJSONArray("applications")
                    var foundMediaReceiver = false

                    if (apps != null) {
                        for (i in 0 until apps.length()) {
                            val app = apps.optJSONObject(i) ?: continue
                            if (app.optString("appId") == DEFAULT_MEDIA_RECEIVER_APP_ID) {
                                foundMediaReceiver = true
                                val transportId = app.optString("transportId")
                                if (transportId.isNotBlank()) {
                                    receiverTransportId = transportId
                                    ensureTransportConnected(transportId)

                                    if (shouldLoadNewMedia.compareAndSet(true, false)) {
                                        _uiState.value.activeMedia?.let { media ->
                                            sendMediaLoad(transportId, media)
                                        }
                                    } else {
                                        // Chiede SEMPRE lo stato media attuale al transportId per ri-sincronizzarsi!
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

                    // Se il receiver sulla TV è stato chiuso (es. fermato dal telecomando o da Google Home)
                    if (!foundMediaReceiver && !shouldLoadNewMedia.get() && _uiState.value.activeMedia != null) {
                        receiverTransportId = null
                        connectedTransportIdOnCurrentSocket = null
                        mediaSessionId = null
                        CastForegroundService.stop(appContext)
                        _uiState.update {
                            it.copy(
                                activeMedia = null,
                                activeStreamUrl = null,
                                isPlaying = false,
                                currentPositionSeconds = 0.0,
                                durationSeconds = 0.0,
                                statusMessage = "Riproduzione terminata sulla TV"
                            )
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
                        val contentId = mediaObj?.optString("contentId").orEmpty()
                        val metaTitle = mediaObj?.optJSONObject("metadata")?.optString("title").orEmpty()
                        val playerState = first.optString("playerState")
                        val idleReason = first.optString("idleReason")

                        if (playerState == "IDLE" && idleReason in listOf("CANCELLED", "FINISHED", "INTERRUPTED", "ERROR")) {
                            mediaSessionId = null
                            CastForegroundService.stop(appContext)
                            _uiState.update { prev ->
                                prev.copy(
                                    activeMedia = null,
                                    activeStreamUrl = null,
                                    isPlaying = false,
                                    currentPositionSeconds = 0.0,
                                    durationSeconds = 0.0,
                                    statusMessage = "Riproduzione terminata"
                                )
                            }
                            return
                        }

                        _uiState.update { prev ->
                            val playing = if (playerState.isNotBlank()) {
                                (playerState == "PLAYING" || playerState == "BUFFERING")
                            } else {
                                prev.isPlaying
                            }
                            // Se l'app era stata chiusa/riaperta mentre il video andava sulla TV, ricostruisci activeMedia!
                            val syncedMedia = prev.activeMedia ?: if (contentId.isNotBlank()) {
                                DetectedMedia(
                                    url = contentId,
                                    title = metaTitle.ifBlank { "Video in riproduzione sulla TV" },
                                    pageUrl = "",
                                    format = StreamFormat.HLS
                                )
                            } else {
                                null
                            }

                            prev.copy(
                                activeMedia = syncedMedia,
                                activeStreamUrl = prev.activeStreamUrl ?: contentId.takeIf { it.isNotBlank() },
                                isPlaying = playing,
                                currentPositionSeconds = if (currentTime >= 0.0) currentTime else prev.currentPositionSeconds,
                                durationSeconds = if (duration > 0.0) duration else prev.durationSeconds
                            )
                        }
                        updateForegroundServiceNotification()
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

        @Volatile
        private var INSTANCE: TvCastManager? = null

        fun getInstance(context: Context): TvCastManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TvCastManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
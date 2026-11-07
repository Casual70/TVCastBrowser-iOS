package com.tvcast.browser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tvcast.browser.cast.CastDevice
import com.tvcast.browser.cast.TvCastManager
import com.tvcast.browser.data.BrowserPreferences
import com.tvcast.browser.data.HistoryItem
import com.tvcast.browser.model.DetectedMedia
import com.tvcast.browser.proxy.ProxyBufferStats
import com.tvcast.browser.sniffer.MediaSniffer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserCastScreen(
    initialUrl: String,
    sniffer: MediaSniffer,
    castManager: TvCastManager,
    browserPrefs: BrowserPreferences
) {
    val detectedMedia by sniffer.detectedMediaList.collectAsState()
    val blockedAdsCount by sniffer.blockedAdsCount.collectAsState()
    val castState by castManager.uiState.collectAsState()
    val savedHomeUrl by browserPrefs.homeUrl.collectAsState()
    val domainLockEnabled by browserPrefs.domainLockEnabled.collectAsState()
    val recentHistory by browserPrefs.recentHistory.collectAsState()

    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(initialUrl) }
    var urlInput by remember { mutableStateOf(initialUrl) }
    var currentTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var adBlockEnabled by remember { mutableStateOf(sniffer.adBlockEnabled) }

    var showMediaSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showCastDevicesSheet by remember { mutableStateOf(false) }
    var showHomeMenu by remember { mutableStateOf(false) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var allowNextUserNavigation by remember { mutableStateOf(false) }

    val currentBaseDomain = remember(currentUrl) { BrowserPreferences.extractBaseDomain(currentUrl) }
    val lockedBaseDomain = remember(savedHomeUrl) { BrowserPreferences.extractBaseDomain(savedHomeUrl) }
    val isCurrentDomainLockedAsMain = remember(currentBaseDomain, lockedBaseDomain, domainLockEnabled) {
        domainLockEnabled && currentBaseDomain != null && currentBaseDomain == lockedBaseDomain && lockedBaseDomain != "google.com"
    }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    fun navigateExplicitly(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val targetUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(trimmed)}"
        }
        allowNextUserNavigation = true
        currentUrl = targetUrl
        urlInput = targetUrl
        sniffer.clearDetectedMedia()
        webViewRef?.loadUrl(targetUrl)
        focusManager.clearFocus()
    }

    fun setCurrentPageAsMain() {
        val activeUrl = webViewRef?.url ?: currentUrl
        browserPrefs.setHomePage(activeUrl, enableDomainLock = true)
        val domain = BrowserPreferences.extractBaseDomain(activeUrl) ?: activeUrl
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = "Pagina principale impostata! Bloccate nuove schede fuori da $domain",
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                            }

                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 46.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                placeholder = { Text("Cerca o inserisci URL...") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { navigateExplicitly(urlInput) }),
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (urlInput.isNotEmpty() && urlInput != currentUrl) {
                                            IconButton(
                                                onClick = { urlInput = "" },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Clear, contentDescription = "Cancella")
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                allowNextUserNavigation = true
                                                webViewRef?.reload()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Ricarica")
                                        }
                                    }
                                }
                            )

                            // Pulsante Pagina Principale + Blocco Dominio (Domain Lock)
                            Box {
                                IconButton(
                                    onClick = { showHomeMenu = true },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isCurrentDomainLockedAsMain) Icons.Default.Lock else Icons.Default.Home,
                                        contentDescription = "Pagina Principale e Blocco Dominio",
                                        tint = if (isCurrentDomainLockedAsMain) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }

                                DropdownMenu(
                                    expanded = showHomeMenu,
                                    onDismissRequest = { showHomeMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Imposta questa pagina come Principale")
                                                Text(
                                                    text = "Blocca nuove schede fuori da ${currentBaseDomain ?: "questo sito"}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                                        onClick = {
                                            showHomeMenu = false
                                            setCurrentPageAsMain()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Vai alla Pagina Principale")
                                                Text(
                                                    text = savedHomeUrl,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                                        onClick = {
                                            showHomeMenu = false
                                            navigateExplicitly(savedHomeUrl)
                                        }
                                    )

                                    HorizontalDivider()

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (domainLockEnabled) {
                                                    "Blocco fuori dominio (${lockedBaseDomain ?: "attivo"}): ON"
                                                } else {
                                                    "Blocco fuori dominio: OFF"
                                                }
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (domainLockEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            val newState = !domainLockEnabled
                                            browserPrefs.setDomainLockEnabled(newState)
                                            showHomeMenu = false
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (adBlockEnabled) "Scudo AdBlock ($blockedAdsCount bloccati): ON"
                                                else "Scudo AdBlock: OFF"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (adBlockEnabled) Icons.Default.Shield else Icons.Default.RemoveModerator,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            adBlockEnabled = !adBlockEnabled
                                            sniffer.adBlockEnabled = adBlockEnabled
                                            showHomeMenu = false
                                        }
                                    )
                                }
                            }

                            // Pulsante Cronologia Recenti (ultimi 20 siti)
                            IconButton(
                                onClick = { showHistorySheet = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "Cronologia ultimi 20 siti"
                                )
                            }

                            // Pulsante FOSS Selettore Smart TV / Chromecast sulla rete Wi-Fi
                            IconButton(
                                onClick = {
                                    castManager.startDiscovery()
                                    showCastDevicesSheet = true
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (castState.isConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                                    contentDescription = "Connetti Smart TV",
                                    tint = if (castState.isConnected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }

                        // Barra di stato compatta per fissare velocemente la Pagina Principale o vedere il blocco attivo
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrentDomainLockedAsMain) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Dominio principale bloccato: $lockedBaseDomain • $blockedAdsCount popup/ad bloccati",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                TextButton(
                                    onClick = { setCurrentPageAsMain() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Imposta ${currentBaseDomain ?: "pagina"} come principale (blocca popup esterni)",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            if (castState.activeMedia != null || castState.isConnected) {
                RemoteControlBar(
                    deviceName = castState.deviceName ?: "Smart TV non connessa",
                    mediaTitle = castState.activeMedia?.title ?: (castState.statusMessage ?: "Pronto per trasmettere"),
                    bufferStats = castState.bufferStats,
                    isPlaying = castState.isPlaying,
                    onPlayPause = { castManager.togglePlayPause() },
                    onStop = { castManager.stopCasting() }
                )
            }
        }
    ) { innerPadding ->
        var parentBoxSize by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged { parentBoxSize = it }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = false
                            userAgentString = settings.userAgentString.replace("; wv", "")
                        }

                        addJavascriptInterface(sniffer.JsMediaBridge(), "TvCastBridge")

                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            try {
                                WebViewCompat.addDocumentStartJavaScript(
                                    this,
                                    MediaSniffer.DOCUMENT_START_SCANNER_JS,
                                    setOf("*")
                                )
                            } catch (_: Exception) {
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val req = request ?: return false
                                val targetUri = req.url ?: return false
                                val scheme = targetUri.scheme?.lowercase() ?: return true
                                val targetUrl = targetUri.toString()

                                if (scheme != "http" && scheme != "https") {
                                    sniffer.incrementBlockedPopup()
                                    return true
                                }

                                if (!req.isForMainFrame) {
                                    return false
                                }

                                if (allowNextUserNavigation) {
                                    allowNextUserNavigation = false
                                    return false
                                }

                                if (!browserPrefs.isUrlAllowedByDomainLock(targetUrl)) {
                                    sniffer.incrementBlockedPopup()
                                    val blockedHost = targetUri.host ?: targetUrl
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "Bloccato redirect esterno: $blockedHost",
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                    return true
                                }

                                if (sniffer.shouldBlockRequest(req) != null) {
                                    return true
                                }

                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                url?.let {
                                    currentUrl = it
                                    urlInput = it
                                }
                                view?.evaluateJavascript(MediaSniffer.DOCUMENT_START_SCANNER_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                val loadedUrl = url ?: view?.url ?: return
                                currentUrl = loadedUrl
                                urlInput = loadedUrl
                                currentTitle = view?.title.orEmpty()
                                view?.evaluateJavascript(MediaSniffer.DOCUMENT_START_SCANNER_JS, null)

                                browserPrefs.addHistoryEntry(
                                    url = loadedUrl,
                                    title = currentTitle.ifBlank { loadedUrl }
                                )
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request == null) return null

                                sniffer.shouldBlockRequest(request)?.let { blockedResponse ->
                                    return blockedResponse
                                }

                                sniffer.inspectRequest(
                                    request = request,
                                    currentPageTitle = currentTitle,
                                    currentPageUrl = currentUrl
                                )

                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                currentTitle = title.orEmpty()
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                val parentWebView = view ?: return false
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

                                val tempWebView = WebView(parentWebView.context)
                                tempWebView.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        tempView: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val popupUrl = request?.url?.toString().orEmpty()
                                        tempView?.destroy()

                                        val popupDomain = BrowserPreferences.extractBaseDomain(popupUrl)
                                        val mainDomain = browserPrefs.getLockedBaseDomain()
                                            ?.takeIf { it != "google.com" }
                                            ?: BrowserPreferences.extractBaseDomain(currentUrl)

                                        if (popupUrl.startsWith("http") &&
                                            popupDomain != null &&
                                            popupDomain == mainDomain
                                        ) {
                                            allowNextUserNavigation = true
                                            parentWebView.loadUrl(popupUrl)
                                        } else {
                                            sniffer.incrementBlockedPopup()
                                            if (popupDomain != null) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        message = "Bloccata nuova scheda esterna: $popupDomain",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            }
                                        }
                                        return true
                                    }
                                }

                                transport.webView = tempWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        loadUrl(initialUrl)
                        webViewRef = this
                    }
                }
            )

            // Pulsante Cast Flottante e Trascinabile (Long-Press + Drag o Trascinamento diretto)
            DraggableFloatingCastButton(
                detectedCount = detectedMedia.size,
                parentSize = parentBoxSize,
                onClick = { showMediaSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }
    }

    // BottomSheet Selezione Smart TV / Chromecast
    if (showCastDevicesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCastDevicesSheet = false }
        ) {
            CastDevicesSheetContent(
                devices = castState.discoveredDevices,
                connectedDevice = castState.connectedDevice,
                isDiscovering = castState.isDiscovering,
                onSelectDevice = { device ->
                    castManager.connectToDevice(device)
                    showCastDevicesSheet = false
                },
                onManualIpConnect = { ip ->
                    castManager.connectByManualIp(ip)
                    showCastDevicesSheet = false
                },
                onRefresh = { castManager.startDiscovery() },
                onDisconnect = {
                    castManager.disconnect()
                    showCastDevicesSheet = false
                }
            )
        }
    }

    // BottomSheet Flussi Video Rilevati
    if (showMediaSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMediaSheet = false }
        ) {
            DetectedMediaSheetContent(
                mediaList = detectedMedia,
                isConnectedToTv = castState.isConnected,
                tvName = castState.deviceName,
                useLocalProxy = castState.useLocalProxy,
                localProxyEndpoint = castState.localProxyEndpoint,
                bufferStats = castState.bufferStats,
                onToggleLocalProxy = { castManager.setUseLocalProxy(it) },
                onSelectMedia = { media ->
                    castManager.castVideo(media)
                    showMediaSheet = false
                    if (!castState.isConnected && castState.discoveredDevices.isEmpty()) {
                        showCastDevicesSheet = true
                    }
                },
                onClearAll = { sniffer.clearDetectedMedia() }
            )
        }
    }

    // BottomSheet Cronologia Recenti (max 20 siti) + Pagina Principale
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false }
        ) {
            RecentHistorySheetContent(
                homeUrl = savedHomeUrl,
                currentUrl = currentUrl,
                domainLockEnabled = domainLockEnabled,
                historyList = recentHistory,
                onSetCurrentAsHome = {
                    setCurrentPageAsMain()
                    showHistorySheet = false
                },
                onSelectUrl = { targetUrl ->
                    showHistorySheet = false
                    navigateExplicitly(targetUrl)
                },
                onDeleteEntry = { url -> browserPrefs.removeHistoryEntry(url) },
                onClearHistory = { browserPrefs.clearHistory() }
            )
        }
    }
}

/**
 * Pulsante Cast flottante spostabile liberamente sullo schermo (tramite pressione prolungata + trascinamento
 * o trascinamento diretto) per non coprire mai i controlli dei siti web sottostanti.
 */
@Composable
private fun DraggableFloatingCastButton(
    detectedCount: Int,
    parentSize: IntSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.08f else 1f,
        label = "fabScale"
    )

    fun applyDragDelta(dx: Float, dy: Float) {
        val minX = if (parentSize.width > buttonSize.width) {
            -(parentSize.width - buttonSize.width - 48).toFloat().coerceAtLeast(0f)
        } else {
            -800f
        }
        val minY = if (parentSize.height > buttonSize.height) {
            -(parentSize.height - buttonSize.height - 48).toFloat().coerceAtLeast(0f)
        } else {
            -1600f
        }
        offsetX = (offsetX + dx).coerceIn(minX, 0f)
        offsetY = (offsetY + dy).coerceIn(minY, 0f)
    }

    val hasVideos = detectedCount > 0
    val containerColor = if (hasVideos) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
    }
    val contentColor = if (hasVideos) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .onSizeChanged { buttonSize = it }
            .scale(scale)
            // Supporta sia long-press + trascinamento sia trascinamento diretto (1:1 col dito + feedback aptico)
            .pointerInput(parentSize) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        applyDragDelta(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .clickable {
                if (!isDragging) onClick()
            },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = if (isDragging) 14.dp else 6.dp,
        border = if (isDragging) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = "Trascina per spostare",
                modifier = Modifier.size(18.dp),
                tint = contentColor.copy(alpha = 0.75f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            BadgedBox(
                badge = {
                    if (hasVideos) {
                        Badge { Text(detectedCount.toString()) }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.CastConnected,
                    contentDescription = "Video rilevati"
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (!hasVideos) "Nessun video" else "$detectedCount Video pronti",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun CastDevicesSheetContent(
    devices: List<CastDevice>,
    connectedDevice: CastDevice?,
    isDiscovering: Boolean,
    onSelectDevice: (CastDevice) -> Unit,
    onManualIpConnect: (String) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit
) {
    var manualIp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Connetti Smart TV / Chromecast",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (isDiscovering) "Ricerca sulla rete Wi-Fi locale..." else "Seleziona il dispositivo TV",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRefresh) {
                Text("Aggiorna")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Ricerca Smart TV sulla rete Wi-Fi...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.id }) { device ->
                    val isSelected = connectedDevice?.id == device.id
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDevice(device) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CastConnected else Icons.Default.Tv,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.friendlyName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "${device.modelName} • ${device.host}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Text(
                                    text = "Connesso",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Oppure inserisci IP TV (es. 192.168.1.50)") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { if (manualIp.isNotBlank()) onManualIpConnect(manualIp) }
            ) {
                Text("Connetti")
            }
        }

        if (connectedDevice != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnetti da ${connectedDevice.friendlyName}")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RecentHistorySheetContent(
    homeUrl: String,
    currentUrl: String,
    domainLockEnabled: Boolean,
    historyList: List<HistoryItem>,
    onSetCurrentAsHome: () -> Unit,
    onSelectUrl: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Pagina Principale & Recenti (max 20)",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(10.dp))

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectUrl(homeUrl) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (domainLockEnabled) Icons.Default.Lock else Icons.Default.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pagina Principale (Dominio bloccato)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = homeUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(onClick = onSetCurrentAsHome) {
                    Text("Usa attuale")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ultimi siti visitati (${historyList.size}/${BrowserPreferences.MAX_HISTORY_ITEMS})",
                style = MaterialTheme.typography.titleSmall
            )
            if (historyList.isNotEmpty()) {
                TextButton(onClick = onClearHistory) {
                    Text("Cancella tutto")
                }
            }
        }

        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nessun sito recente memorizzato.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(historyList, key = { it.url }) { item ->
                    Surface(
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectUrl(item.url) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onDeleteEntry(item.url) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Rimuovi",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DetectedMediaSheetContent(
    mediaList: List<DetectedMedia>,
    isConnectedToTv: Boolean,
    tvName: String?,
    useLocalProxy: Boolean,
    localProxyEndpoint: String?,
    bufferStats: ProxyBufferStats,
    onToggleLocalProxy: (Boolean) -> Unit,
    onSelectMedia: (DetectedMedia) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Flussi Video Rilevati (${mediaList.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (isConnectedToTv) "TV connessa: $tvName"
                    else "Suggerimento: connetti la Smart TV dall'icona Cast in alto (o premi Cast per auto-connettere)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (mediaList.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text("Pulisci")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Card configurazione Proxy HTTP Locale + Pre-Buffer RAM
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = if (useLocalProxy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Proxy + Pre-Buffer RAM Anti-Interruzione",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = if (useLocalProxy) {
                            val bufInfo = if (bufferStats.cachedSegmentsCount > 0) {
                                " • Buffer: ${bufferStats.cachedSegmentsCount} seg (${bufferStats.cachedMegabytes})"
                            } else {
                                " • Pre-buffer 6 segmenti attivo"
                            }
                            (localProxyEndpoint ?: "Wi-Fi") + bufInfo
                        } else {
                            "Disattivato (invia URL diretto alla TV)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useLocalProxy,
                    onCheckedChange = onToggleLocalProxy
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (mediaList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Avvia la riproduzione di un video nella pagina web per intercettare il flusso.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mediaList, key = { it.url }) { media ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMedia(media) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.OndemandVideo,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = media.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${media.format.label} • ${media.url}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(onClick = { onSelectMedia(media) }) {
                                Text("Cast")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RemoteControlBar(
    deviceName: String,
    mediaTitle: String,
    bufferStats: ProxyBufferStats,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val bufferLabel = if (bufferStats.cachedSegmentsCount > 0) {
                    " • Buffer RAM: ${bufferStats.cachedSegmentsCount} seg (${bufferStats.cachedMegabytes})"
                } else {
                    ""
                }
                Text(
                    text = "$deviceName$bufferLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mediaTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pausa"
                )
            }
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop"
                )
            }
        }
    }
}
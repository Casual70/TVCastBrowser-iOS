import SwiftUI
import WebKit

final class WebViewControllerStore: ObservableObject {
    weak var webView: WKWebView?
    var allowNextUserNavigation: Bool = false

    func loadUrlExplicitly(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        allowNextUserNavigation = true
        webView?.load(URLRequest(url: url))
    }

    func goBack() {
        if webView?.canGoBack == true {
            webView?.goBack()
        }
    }

    func reload() {
        allowNextUserNavigation = true
        webView?.reload()
    }
}

struct WebViewContainer: UIViewRepresentable {
    let initialUrl: String
    @ObservedObject var store: WebViewControllerStore
    @ObservedObject var sniffer: WebMediaSniffer
    @ObservedObject var prefs: BrowserPreferences
    @Binding var currentUrl: String
    @Binding var urlInput: String
    @Binding var currentTitle: String
    @Binding var isLoading: Bool
    var onPopupBlocked: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []

        let contentController = WKUserContentController()
        let scannerScript = WKUserScript(
            source: WebMediaSniffer.documentStartScannerJS,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        )
        contentController.addUserScript(scannerScript)
        contentController.add(sniffer, name: "tvCastBridge")
        config.userContentController = contentController

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true

        store.webView = webView
        if let startUrl = URL(string: initialUrl) {
            store.allowNextUserNavigation = true
            webView.load(URLRequest(url: startUrl))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let parent: WebViewContainer

        init(parent: WebViewContainer) {
            self.parent = parent
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }

            let scheme = url.scheme?.lowercased() ?? ""
            if scheme != "http" && scheme != "https" && scheme != "about" {
                parent.sniffer.incrementBlockedPopup()
                decisionHandler(.cancel)
                return
            }

            let urlString = url.absoluteString
            if let format = parent.sniffer.detectFormat(from: urlString) {
                var headers = navigationAction.request.allHTTPHeaderFields ?? [:]
                if headers["Referer"] == nil && !parent.currentUrl.isEmpty {
                    headers["Referer"] = parent.currentUrl
                }
                parent.sniffer.addMediaIfNew(
                    DetectedMedia(
                        url: urlString,
                        title: parent.currentTitle.isEmpty ? "Video Web" : parent.currentTitle,
                        pageUrl: parent.currentUrl,
                        format: format,
                        headers: headers
                    )
                )
            }

            if parent.sniffer.shouldBlockUrl(url) {
                decisionHandler(.cancel)
                return
            }

            let isMainFrame = navigationAction.targetFrame?.isMainFrame ?? false
            if !isMainFrame {
                decisionHandler(.allow)
                return
            }

            if parent.store.allowNextUserNavigation {
                parent.store.allowNextUserNavigation = false
                decisionHandler(.allow)
                return
            }

            if !parent.prefs.isUrlAllowedByDomainLock(urlString) {
                parent.sniffer.incrementBlockedPopup()
                parent.onPopupBlocked(url.host ?? urlString)
                decisionHandler(.cancel)
                return
            }

            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            parent.isLoading = true
            if let urlStr = webView.url?.absoluteString {
                parent.currentUrl = urlStr
                parent.urlInput = urlStr
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.isLoading = false
            guard let urlStr = webView.url?.absoluteString else { return }
            parent.currentUrl = urlStr
            parent.urlInput = urlStr
            let title = webView.title ?? ""
            parent.currentTitle = title
            parent.prefs.addHistoryEntry(url: urlStr, title: title.isEmpty ? urlStr : title)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            parent.isLoading = false
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.isLoading = false
        }

        /// Intercetta ogni richiesta di nuova scheda (`target="_blank"` o `window.open`).
        /// Consente solo URL appartenenti al dominio principale (aprendoli nella scheda corrente) e blocca il resto.
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            guard let targetUrl = navigationAction.request.url else { return nil }
            let targetUrlStr = targetUrl.absoluteString
            let targetDomain = BrowserPreferences.extractBaseDomain(from: targetUrlStr)
            let allowedDomain = parent.prefs.lockedBaseDomain.flatMap { $0 == "google.com" ? nil : $0 }
                ?? BrowserPreferences.extractBaseDomain(from: parent.currentUrl)

            if let tDom = targetDomain, let aDom = allowedDomain, tDom == aDom {
                parent.store.allowNextUserNavigation = true
                webView.load(navigationAction.request)
            } else {
                parent.sniffer.incrementBlockedPopup()
                if let host = targetUrl.host {
                    parent.onPopupBlocked(host)
                }
            }
            return nil
        }
    }
}

struct BrowserCastView: View {
    @StateObject private var webStore = WebViewControllerStore()
    @StateObject private var sniffer = WebMediaSniffer()
    @StateObject private var prefs = BrowserPreferences()
    @StateObject private var castManager = ChromecastManager()

    @State private var currentUrl: String = ""
    @State private var urlInput: String = ""
    @State private var currentTitle: String = ""
    @State private var isLoading: Bool = false

    @State private var showMediaSheet = false
    @State private var showHistorySheet = false
    @State private var showCastDevicesSheet = false
    @State private var bannerMessage: String?

    private var currentBaseDomain: String? {
        BrowserPreferences.extractBaseDomain(from: currentUrl)
    }

    private var isCurrentDomainLockedAsMain: Bool {
        guard prefs.domainLockEnabled,
              let cur = currentBaseDomain,
              let locked = prefs.lockedBaseDomain,
              locked != "google.com" else { return false }
        return cur == locked
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                topBar
                if isLoading {
                    ProgressView()
                        .progressViewStyle(.linear)
                }
                WebViewContainer(
                    initialUrl: prefs.homeUrl,
                    store: webStore,
                    sniffer: sniffer,
                    prefs: prefs,
                    currentUrl: $currentUrl,
                    urlInput: $urlInput,
                    currentTitle: $currentTitle,
                    isLoading: $isLoading,
                    onPopupBlocked: { host in
                        showTemporaryBanner("Bloccata apertura esterna: \(host)")
                    }
                )
                if castManager.activeMedia != nil || castManager.connectedDevice != nil {
                    remoteControlBar
                }
            }

            VStack(spacing: 8) {
                if let msg = bannerMessage {
                    Text(msg)
                        .font(.footnote.weight(.medium))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial)
                        .clipShape(Capsule())
                        .shadow(radius: 4)
                }

                HStack {
                    Spacer()
                    Button(action: { showMediaSheet = true }) {
                        HStack(spacing: 8) {
                            Image(systemName: "tv.and.mediabox")
                            Text(sniffer.detectedMediaList.isEmpty ? "Nessun video" : "\(sniffer.detectedMediaList.count) Video pronti")
                                .fontWeight(.semibold)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(sniffer.detectedMediaList.isEmpty ? Color(.secondarySystemBackground) : Color.accentColor)
                        .foregroundColor(sniffer.detectedMediaList.isEmpty ? .primary : .white)
                        .clipShape(Capsule())
                        .shadow(radius: 5)
                    }
                    .padding(.trailing, 16)
                    .padding(.bottom, (castManager.activeMedia != nil || castManager.connectedDevice != nil) ? 70 : 16)
                }
            }
        }
        .onAppear {
            currentUrl = prefs.homeUrl
            urlInput = prefs.homeUrl
            castManager.startDiscovery()
        }
        .sheet(isPresented: $showMediaSheet) {
            mediaSheet
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showHistorySheet) {
            historySheet
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showCastDevicesSheet) {
            castDevicesSheet
                .presentationDetents([.medium])
        }
    }

    private var topBar: some View {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                Button(action: { webStore.goBack() }) {
                    Image(systemName: "chevron.left")
                        .frame(width: 32, height: 32)
                }

                HStack(spacing: 4) {
                    TextField("Cerca o inserisci URL...", text: $urlInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .keyboardType(.URL)
                        .submitLabel(.go)
                        .onSubmit {
                            navigateExplicitly(urlInput)
                        }

                    Button(action: { webStore.reload() }) {
                        Image(systemName: "arrow.clockwise")
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(10)

                Menu {
                    Button {
                        setCurrentPageAsMain()
                    } label: {
                        Label("Imposta questa pagina come Principale", systemImage: "pin.fill")
                    }

                    Button {
                        navigateExplicitly(prefs.homeUrl)
                    } label: {
                        Label("Vai alla Pagina Principale", systemImage: "house.fill")
                    }

                    Divider()

                    Button {
                        prefs.setDomainLockEnabled(!prefs.domainLockEnabled)
                    } label: {
                        Label(
                            prefs.domainLockEnabled
                                ? "Blocco fuori dominio (\(prefs.lockedBaseDomain ?? "attivo")): ON"
                                : "Blocco fuori dominio: OFF",
                            systemImage: prefs.domainLockEnabled ? "lock.fill" : "lock.open"
                        )
                    }

                    Button {
                        sniffer.adBlockEnabled.toggle()
                    } label: {
                        Label(
                            sniffer.adBlockEnabled
                                ? "Scudo AdBlock (\(sniffer.blockedAdsCount) bloccati): ON"
                                : "Scudo AdBlock: OFF",
                            systemImage: "shield.lefthalf.filled"
                        )
                    }
                } label: {
                    Image(systemName: isCurrentDomainLockedAsMain ? "lock.shield.fill" : "house")
                        .foregroundColor(isCurrentDomainLockedAsMain ? .accentColor : .primary)
                        .frame(width: 32, height: 32)
                }

                Button(action: { showHistorySheet = true }) {
                    Image(systemName: "clock.arrow.circlepath")
                        .frame(width: 32, height: 32)
                }

                Button(action: {
                    castManager.startDiscovery()
                    showCastDevicesSheet = true
                }) {
                    Image(systemName: castManager.connectedDevice != nil ? "tv.fill" : "tv")
                        .foregroundColor(castManager.connectedDevice != nil ? .accentColor : .primary)
                        .frame(width: 32, height: 32)
                }
            }
            .padding(.horizontal, 10)
            .padding(.top, 6)

            HStack {
                if isCurrentDomainLockedAsMain {
                    Label(
                        "Dominio principale bloccato: \(prefs.lockedBaseDomain ?? "") • \(sniffer.blockedAdsCount) popup/ad bloccati",
                        systemImage: "checkmark.shield.fill"
                    )
                    .font(.caption2)
                    .foregroundColor(.accentColor)
                    .lineLimit(1)
                } else {
                    Button(action: { setCurrentPageAsMain() }) {
                        Label(
                            "Imposta \(currentBaseDomain ?? "pagina") come principale (blocca popup esterni)",
                            systemImage: "pin.fill"
                        )
                        .font(.caption2)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 4)
        }
        .background(Color(.systemBackground))
    }

    private var remoteControlBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "tv.fill")
                .foregroundColor(.accentColor)
            VStack(alignment: .leading, spacing: 2) {
                Text(castManager.connectedDevice?.friendlyName ?? "Smart TV")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.accentColor)
                Text(castManager.activeMedia?.title ?? castManager.statusMessage)
                    .font(.subheadline)
                    .lineLimit(1)
            }
            Spacer()
            Button(action: { castManager.togglePlayPause() }) {
                Image(systemName: castManager.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title3)
                    .frame(width: 36, height: 36)
            }
            Button(action: { castManager.stopCasting() }) {
                Image(systemName: "stop.fill")
                    .font(.title3)
                    .frame(width: 36, height: 36)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemBackground))
    }

    private var mediaSheet: some View {
        NavigationStack {
            List {
                Section {
                    Toggle(isOn: $castManager.useLocalProxy) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Proxy HTTP Locale (Referer & Cookie)")
                                .font(.subheadline.weight(.medium))
                            Text(
                                castManager.useLocalProxy
                                    ? "Attivo su Wi-Fi (\(castManager.localProxyEndpoint ?? "in ascolto"))"
                                    : "Disattivato (invia URL diretto)"
                            )
                            .font(.caption)
                            .foregroundColor(.secondary)
                        }
                    }
                }

                Section("Flussi Video Rilevati (\(sniffer.detectedMediaList.count))") {
                    if sniffer.detectedMediaList.isEmpty {
                        Text("Avvia la riproduzione di un video nella pagina per intercettare il flusso.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(sniffer.detectedMediaList) { media in
                            Button {
                                castManager.castVideo(media)
                                showMediaSheet = false
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(media.title)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundColor(.primary)
                                            .lineLimit(1)
                                        Text("\(media.format.label) • \(media.url)")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                            .lineLimit(2)
                                    }
                                    Spacer()
                                    Text("Cast")
                                        .font(.caption.weight(.bold))
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(Color.accentColor.opacity(0.15))
                                        .foregroundColor(.accentColor)
                                        .cornerRadius(8)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Trasmetti Video")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if !sniffer.detectedMediaList.isEmpty {
                        Button("Pulisci") {
                            sniffer.clearDetectedMedia()
                        }
                    }
                }
            }
        }
    }

    private var historySheet: some View {
        NavigationStack {
            List {
                Section("Pagina Principale (Dominio bloccato)") {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(prefs.homeUrl)
                                .font(.subheadline)
                                .lineLimit(1)
                            Text("Dominio protetto: \(prefs.lockedBaseDomain ?? "-")")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Button("Usa attuale") {
                            setCurrentPageAsMain()
                            showHistorySheet = false
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        showHistorySheet = false
                        navigateExplicitly(prefs.homeUrl)
                    }
                }

                Section("Ultimi siti visitati (\(prefs.recentHistory.count)/\(BrowserPreferences.maxHistoryItems))") {
                    if prefs.recentHistory.isEmpty {
                        Text("Nessun sito recente memorizzato.")
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(prefs.recentHistory) { item in
                            Button {
                                showHistorySheet = false
                                navigateExplicitly(item.url)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title)
                                        .font(.subheadline)
                                        .foregroundColor(.primary)
                                        .lineLimit(1)
                                    Text(item.url)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                }
                            }
                        }
                        .onDelete { offsets in
                            for idx in offsets {
                                let item = prefs.recentHistory[idx]
                                prefs.removeHistoryEntry(url: item.url)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Principale & Recenti")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if !prefs.recentHistory.isEmpty {
                        Button("Cancella tutto") {
                            prefs.clearHistory()
                        }
                    }
                }
            }
        }
    }

    private var castDevicesSheet: some View {
        NavigationStack {
            List {
                Section("Smart TV / Chromecast sulla rete Wi-Fi") {
                    if castManager.discoveredDevices.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Ricerca Smart TV / Chromecast in corso...")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    } else {
                        ForEach(castManager.discoveredDevices) { device in
                            Button {
                                castManager.connect(to: device)
                                showCastDevicesSheet = false
                            } label: {
                                HStack {
                                    Image(systemName: "tv")
                                    VStack(alignment: .leading) {
                                        Text(device.friendlyName)
                                            .font(.subheadline.weight(.medium))
                                            .foregroundColor(.primary)
                                        Text("\(device.modelName) • \(device.host)")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    if castManager.connectedDevice?.id == device.id {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundColor(.accentColor)
                                    }
                                }
                            }
                        }
                    }
                }

                if castManager.connectedDevice != nil {
                    Section {
                        Button(role: .destructive) {
                            castManager.disconnect()
                            showCastDevicesSheet = false
                        } label: {
                            Text("Disconnetti dalla Smart TV")
                        }
                    }
                }
            }
            .navigationTitle("Connetti Smart TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Aggiorna") {
                        castManager.startDiscovery()
                    }
                }
            }
        }
    }

    private func navigateExplicitly(_ rawInput: String) {
        let trimmed = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let targetUrl: String
        if trimmed.lowercased().hasPrefix("http://") || trimmed.lowercased().hasPrefix("https://") {
            targetUrl = trimmed
        } else if trimmed.contains(".") && !trimmed.contains(" ") {
            targetUrl = "https://\(trimmed)"
        } else {
            let encoded = trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? trimmed
            targetUrl = "https://www.google.com/search?q=\(encoded)"
        }
        currentUrl = targetUrl
        urlInput = targetUrl
        sniffer.clearDetectedMedia()
        webStore.loadUrlExplicitly(targetUrl)
    }

    private func setCurrentPageAsMain() {
        let active = currentUrl.isEmpty ? prefs.homeUrl : currentUrl
        prefs.setHomePage(active, enableDomainLock: true)
        let domain = BrowserPreferences.extractBaseDomain(from: active) ?? active
        showTemporaryBanner("Impostata come principale! Bloccate nuove schede fuori da \(domain)")
    }

    private func showTemporaryBanner(_ text: String) {
        bannerMessage = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.8) {
            if bannerMessage == text {
                bannerMessage = nil
            }
        }
    }
}

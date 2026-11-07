import Foundation
import WebKit

final class WebMediaSniffer: NSObject, ObservableObject, WKScriptMessageHandler {
    @Published private(set) var detectedMediaList: [DetectedMedia] = []
    @Published private(set) var blockedAdsCount: Int = 0
    @Published var adBlockEnabled: Bool = true

    private let blockedAdDomains: [String] = [
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
    ]

    func clearDetectedMedia() {
        DispatchQueue.main.async {
            self.detectedMediaList = []
        }
    }

    func incrementBlockedPopup() {
        DispatchQueue.main.async {
            self.blockedAdsCount += 1
        }
    }

    func shouldBlockUrl(_ url: URL) -> Bool {
        guard adBlockEnabled, let host = url.host?.lowercased() else { return false }
        let full = url.absoluteString.lowercased()
        let isAdDomain = blockedAdDomains.contains { domain in
            host == domain || host.hasSuffix(".\(domain)") || host.contains(domain)
        }
        let isAdPath = full.contains("/popunder") || full.contains("/popup.js") || full.contains("/adserver")
        if isAdDomain || isAdPath {
            incrementBlockedPopup()
            return true
        }
        return false
    }

    func detectFormat(from url: String) -> StreamFormat? {
        let fullLower = url.lowercased()
        let cleanUrl = fullLower.components(separatedBy: "?").first ?? fullLower

        let ignoredSuffixes = [
            ".ts", ".m4s", ".aac", ".vtt", ".srt", ".jpg", ".jpeg", ".png",
            ".gif", ".webp", ".svg", ".ico", ".js", ".css", ".woff", ".woff2", ".ttf", ".json", ".html"
        ]
        if ignoredSuffixes.contains(where: { cleanUrl.hasSuffix($0) }) {
            return nil
        }

        if fullLower.contains("type=audio") ||
           fullLower.contains("type=subtitle") ||
           fullLower.contains("type=text") ||
           (fullLower.contains("/playlist/") && fullLower.contains("rendition=")) {
            return nil
        }

        if cleanUrl.hasSuffix(".m3u8") ||
           fullLower.contains(".m3u8?") ||
           fullLower.contains(".m3u8&") ||
           cleanUrl.contains(".m3u8/") ||
           cleanUrl.contains("/playlist/") ||
           (cleanUrl.contains("/hls/") && (cleanUrl.hasSuffix("/master") || cleanUrl.hasSuffix("/index") || cleanUrl.hasSuffix(".txt"))) {
            return .hls
        }

        if cleanUrl.hasSuffix(".mpd") || fullLower.contains(".mpd?") {
            return .dash
        }

        if cleanUrl.hasSuffix(".mp4") || cleanUrl.hasSuffix(".m4v") || fullLower.contains("mime=video/mp4") {
            return .mp4
        }

        if cleanUrl.hasSuffix(".webm") {
            return .webm
        }

        return nil
    }

    func addMediaIfNew(_ media: DetectedMedia) {
        guard !media.url.hasPrefix("blob:"), !media.url.hasPrefix("data:") else { return }
        DispatchQueue.main.async {
            if !self.detectedMediaList.contains(where: { $0.url == media.url }) {
                self.detectedMediaList.insert(media, at: 0)
            }
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "tvCastBridge",
              let body = message.body as? [String: Any],
              let videoUrl = body["url"] as? String,
              !videoUrl.isEmpty else { return }

        let lower = videoUrl.lowercased()
        if lower.contains("type=audio") ||
           lower.contains("type=subtitle") ||
           (lower.contains("/playlist/") && lower.contains("rendition=")) {
            return
        }

        let title = (body["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "Video Stream"
        let frameUrl = (body["frameUrl"] as? String) ?? ""
        let userAgent = (body["userAgent"] as? String) ?? ""
        let hint = (body["hint"] as? String)?.uppercased() ?? ""

        let format: StreamFormat
        switch hint {
        case "HLS": format = .hls
        case "DASH": format = .dash
        case "MP4": format = .mp4
        default: format = detectFormat(from: videoUrl) ?? .generic
        }

        var headers: [String: String] = [:]
        if !frameUrl.isEmpty { headers["Referer"] = frameUrl }
        if !userAgent.isEmpty { headers["User-Agent"] = userAgent }

        addMediaIfNew(
            DetectedMedia(
                url: videoUrl,
                title: title.isEmpty ? "Video Stream" : title,
                pageUrl: frameUrl,
                format: format,
                headers: headers
            )
        )
    }

    /// Script JS iniettato in TUTTI i frame (`forMainFrameOnly: false`, inclusi `<iframe>` cross-origin come VixCloud)
    static let documentStartScannerJS = """
    (function() {
        if (window.__tvCastIosHookInstalled) return;
        window.__tvCastIosHookInstalled = true;

        try {
            window.open = function(url) {
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
                if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.tvCastBridge) {
                    window.webkit.messageHandlers.tvCastBridge.postMessage({
                        url: absUrl,
                        title: title || 'Video Stream',
                        frameUrl: window.location.href,
                        userAgent: navigator.userAgent || '',
                        hint: hint || ''
                    });
                }
            } catch (e) {}
        }

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
    """
}


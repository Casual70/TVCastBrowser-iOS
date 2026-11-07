import Foundation
import Darwin

final class LocalStreamProxy {
    struct ProxySessionConfig {
        let rootUrl: String
        let pageUrl: String
        let headers: [String: String]
        let cookies: String?
    }

    private let preferredPort: UInt16
    private(set) var boundPort: UInt16
    private var serverSocket: Int32 = -1
    private var isRunning = false
    private let lock = NSLock()
    private var sessions: [String: ProxySessionConfig] = [:]

    init(preferredPort: UInt16 = 8765) {
        self.preferredPort = preferredPort
        self.boundPort = preferredPort
    }

    func start() {
        lock.lock()
        defer { lock.unlock() }
        if isRunning { return }

        let sock = socket(AF_INET, SOCK_STREAM, 0)
        guard sock >= 0 else { return }

        var yes: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = preferredPort.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        if bindResult < 0 {
            addr.sin_port = 0
            let fallbackBind = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    bind(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            if fallbackBind < 0 {
                close(sock)
                return
            }
        }

        var actualAddr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &actualAddr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                _ = getsockname(sock, $0, &len)
            }
        }
        boundPort = UInt16(bigEndian: actualAddr.sin_port)

        guard listen(sock, 32) == 0 else {
            close(sock)
            return
        }

        serverSocket = sock
        isRunning = true

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.acceptLoop(serverFd: sock)
        }
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        isRunning = false
        if serverSocket >= 0 {
            close(serverSocket)
            serverSocket = -1
        }
        sessions.removeAll()
    }

    func createProxiedUrl(for media: DetectedMedia, cookies: String? = nil) -> String {
        start()
        guard let lanIp = Self.findLocalLanIpAddress() else { return media.url }
        let sessionId = String(format: "%08x", abs(media.url.hashValue))

        lock.lock()
        sessions[sessionId] = ProxySessionConfig(
            rootUrl: media.url,
            pageUrl: media.pageUrl,
            headers: media.headers,
            cookies: cookies
        )
        lock.unlock()

        let encoded = Self.base64UrlEncode(media.url)
        let extHint: String
        switch media.format {
        case .hls: extHint = "playlist.m3u8"
        case .dash: extHint = "manifest.mpd"
        case .webm: extHint = "video.webm"
        default: extHint = "video.mp4"
        }
        return "http://\(lanIp):\(boundPort)/stream/\(extHint)?s=\(sessionId)&u=\(encoded)"
    }

    private func acceptLoop(serverFd: Int32) {
        while true {
            var clientAddr = sockaddr_in()
            var addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let clientFd = withUnsafeMutablePointer(to: &clientAddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    accept(serverFd, $0, &addrLen)
                }
            }
            if clientFd < 0 { break }
            var noSigPipe: Int32 = 1
            setsockopt(clientFd, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout<Int32>.size))
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.handleClient(clientFd: clientFd)
            }
        }
    }

    private func handleClient(clientFd: Int32) {
        defer { close(clientFd) }

        var buffer = [UInt8](repeating: 0, count: 8192)
        let bytesRead = read(clientFd, &buffer, buffer.count - 1)
        guard bytesRead > 0,
              let requestString = String(bytes: buffer[0..<bytesRead], encoding: .utf8) else { return }

        let lines = requestString.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return }

        let method = String(parts[0]).uppercased()
        let pathAndQuery = String(parts[1])

        if method == "OPTIONS" {
            let cors = "HTTP/1.1 204 No Content\r\n" + Self.corsHeaders + "Content-Length: 0\r\n\r\n"
            Self.writeAll(fd: clientFd, data: Data(cors.utf8))
            return
        }

        var clientHeaders: [String: String] = [:]
        for line in lines.dropFirst() {
            if line.isEmpty { break }
            if let idx = line.firstIndex(of: ":") {
                let k = line[..<idx].trimmingCharacters(in: .whitespaces)
                let v = line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
                clientHeaders[k] = v
            }
        }

        guard let queryPart = pathAndQuery.components(separatedBy: "?").dropFirst().first else { return }
        var params: [String: String] = [:]
        for item in queryPart.components(separatedBy: "&") {
            let kv = item.components(separatedBy: "=")
            if kv.count == 2 {
                params[kv[0]] = kv[1]
            }
        }

        guard let encodedUrl = params["u"],
              let targetUrlStr = Self.base64UrlDecode(encodedUrl),
              let targetUrl = URL(string: targetUrlStr) else { return }

        let sessionId = params["s"] ?? "default"
        lock.lock()
        let sessionConfig = sessions[sessionId]
        lock.unlock()

        let proxyHost = clientHeaders.first(where: { $0.key.caseInsensitiveCompare("Host") == .orderedSame })?.value
            ?? "\(Self.findLocalLanIpAddress() ?? "127.0.0.1"):\(boundPort)"
        let rangeHeader = clientHeaders.first(where: { $0.key.caseInsensitiveCompare("Range") == .orderedSame })?.value

        var req = URLRequest(url: targetUrl, timeoutInterval: 25)
        req.httpMethod = (method == "HEAD") ? "HEAD" : "GET"

        var hasReferer = false
        var hasUserAgent = false
        if let headers = sessionConfig?.headers {
            for (k, v) in headers {
                let lower = k.lowercased()
                if lower == "host" || lower == "connection" || lower == "range" || lower == "accept-encoding" { continue }
                if lower == "referer" { hasReferer = true }
                if lower == "user-agent" { hasUserAgent = true }
                req.setValue(v, forHTTPHeaderField: k)
            }
        }
        if !hasReferer, let pageUrl = sessionConfig?.pageUrl, !pageUrl.isEmpty {
            req.setValue(pageUrl, forHTTPHeaderField: "Referer")
        }
        if !hasUserAgent {
            req.setValue(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                forHTTPHeaderField: "User-Agent"
            )
        }
        if let cookies = sessionConfig?.cookies, !cookies.isEmpty {
            req.setValue(cookies, forHTTPHeaderField: "Cookie")
        }
        if let range = rangeHeader, !range.isEmpty {
            req.setValue(range, forHTTPHeaderField: "Range")
        }

        let semaphore = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: req) { data, response, _ in
            defer { semaphore.signal() }
            guard let httpResp = response as? HTTPURLResponse, let body = data else { return }

            let finalUrlStr = httpResp.url?.absoluteString ?? targetUrlStr
            let contentType = httpResp.value(forHTTPHeaderField: "Content-Type") ?? "video/mp4"
            let startsWithExtM3u = String(data: body.prefix(10), encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .hasPrefix("#EXTM3U") == true
            let isHls = startsWithExtM3u ||
                finalUrlStr.lowercased().contains(".m3u8") ||
                contentType.lowercased().contains("mpegurl")

            if isHls, let playlistText = String(data: body, encoding: .utf8) {
                let rewritten = Self.rewriteM3u8Playlist(
                    playlist: playlistText,
                    baseUrl: finalUrlStr,
                    proxyHostPort: proxyHost,
                    sessionId: sessionId
                )
                let outData = Data(rewritten.utf8)
                let header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: \(outData.count)\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    Self.corsHeaders + "\r\n"
                Self.writeAll(fd: clientFd, data: Data(header.utf8))
                if method != "HEAD" {
                    Self.writeAll(fd: clientFd, data: outData)
                }
                return
            }

            var header = "HTTP/1.1 \(httpResp.statusCode) OK\r\n"
            header += "Content-Type: \(contentType)\r\n"
            header += "Content-Length: \(body.count)\r\n"
            if let cr = httpResp.value(forHTTPHeaderField: "Content-Range") {
                header += "Content-Range: \(cr)\r\n"
            }
            header += "Accept-Ranges: bytes\r\n"
            header += Self.corsHeaders + "\r\n"
            Self.writeAll(fd: clientFd, data: Data(header.utf8))
            if method != "HEAD" {
                Self.writeAll(fd: clientFd, data: body)
            }
        }
        task.resume()
        _ = semaphore.wait(timeout: .now() + 30)
    }

    static func rewriteM3u8Playlist(
        playlist: String,
        baseUrl: String,
        proxyHostPort: String,
        sessionId: String
    ) -> String {
        var expectSubPlaylist = false
        let lines = playlist.components(separatedBy: .newlines)
        let uriRegex = try? NSRegularExpression(pattern: #"URI="([^"]+)""#)

        return lines.map { rawLine in
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { return rawLine }
            if line.hasPrefix("#") {
                if line.hasPrefix("#EXT-X-STREAM-INF") {
                    expectSubPlaylist = true
                } else if line.hasPrefix("#EXTINF") {
                    expectSubPlaylist = false
                }
                if line.contains("URI=\""), let regex = uriRegex {
                    let isMediaTag = line.hasPrefix("#EXT-X-MEDIA") || line.hasPrefix("#EXT-X-I-FRAME-STREAM-INF")
                    let nsLine = line as NSString
                    let matches = regex.matches(in: line, range: NSRange(location: 0, length: nsLine.length))
                    var result = line
                    for match in matches.reversed() {
                        let origUri = nsLine.substring(with: match.range(at: 1))
                        let absUri = resolveUrl(base: baseUrl, relative: origUri)
                        let proxied = buildProxyItemUrl(
                            proxyHostPort: proxyHostPort,
                            sessionId: sessionId,
                            targetUrl: absUri,
                            forcePlaylist: isMediaTag
                        )
                        if let range = Range(match.range, in: result) {
                            result.replaceSubrange(range, with: "URI=\"\(proxied)\"")
                        }
                    }
                    return result
                }
                return rawLine
            } else {
                let absUri = resolveUrl(base: baseUrl, relative: line)
                let proxied = buildProxyItemUrl(
                    proxyHostPort: proxyHostPort,
                    sessionId: sessionId,
                    targetUrl: absUri,
                    forcePlaylist: expectSubPlaylist
                )
                expectSubPlaylist = false
                return proxied
            }
        }.joined(separator: "\n")
    }

    private static func buildProxyItemUrl(
        proxyHostPort: String,
        sessionId: String,
        targetUrl: String,
        forcePlaylist: Bool
    ) -> String {
        let clean = targetUrl.components(separatedBy: "?").first?.lowercased() ?? ""
        let ext: String
        if forcePlaylist || clean.hasSuffix(".m3u8") || clean.contains("/playlist/") {
            ext = "sub.m3u8"
        } else if clean.hasSuffix(".m4s") {
            ext = "seg.m4s"
        } else {
            ext = "seg.ts"
        }
        return "http://\(proxyHostPort)/stream/\(ext)?s=\(sessionId)&u=\(base64UrlEncode(targetUrl))"
    }

    private static func resolveUrl(base: String, relative: String) -> String {
        if relative.hasPrefix("http://") || relative.hasPrefix("https://") {
            return relative
        }
        if let baseUrl = URL(string: base), let resolved = URL(string: relative, relativeTo: baseUrl)?.absoluteURL {
            return resolved.absoluteString
        }
        return relative
    }

    private static let corsHeaders =
        "Access-Control-Allow-Origin: *\r\n" +
        "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
        "Access-Control-Allow-Headers: *\r\n" +
        "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n"

    private static func writeAll(fd: Int32, data: Data) {
        data.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            var total = 0
            while total < data.count {
                let written = write(fd, base.advanced(by: total), data.count - total)
                if written <= 0 { break }
                total += written
            }
        }
    }

    static func base64UrlEncode(_ value: String) -> String {
        Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func base64UrlDecode(_ encoded: String) -> String? {
        var base64 = encoded
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let rem = base64.count % 4
        if rem > 0 {
            base64 += String(repeating: "=", count: 4 - rem)
        }
        guard let data = Data(base64Encoded: base64) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func findLocalLanIpAddress() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            let addr = ptr.pointee.ifa_addr.pointee
            if (flags & (IFF_UP | IFF_RUNNING | IFF_LOOPBACK)) == (IFF_UP | IFF_RUNNING),
               addr.sa_family == UInt8(AF_INET) {
                let name = String(cString: ptr.pointee.ifa_name)
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(
                    ptr.pointee.ifa_addr,
                    socklen_t(addr.sa_len),
                    &hostname,
                    socklen_t(hostname.count),
                    nil,
                    socklen_t(0),
                    NI_NUMERICHOST
                ) == 0 {
                    let ip = String(cString: hostname)
                    if name == "en0" {
                        return ip
                    } else if address == nil {
                        address = ip
                    }
                }
            }
        }
        return address
    }
}

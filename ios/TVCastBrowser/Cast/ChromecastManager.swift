import Foundation
import Network
import Security

struct CastDevice: Identifiable, Equatable {
    var id: String { "\(host):\(port)" }
    let friendlyName: String
    let modelName: String
    let host: String
    let port: UInt16
}

/**
 * Implementazione nativa Swift del protocollo Google Cast (CASTv2 su TLS porta 8009 + mDNS `_googlecast._tcp`).
 * Non richiede CocoaPods né l'entitlement multicast a pagamento di Apple: funziona anche con
 * Apple ID gratuito tramite Sideloadly / AltStore.
 */
final class ChromecastManager: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    @Published private(set) var discoveredDevices: [CastDevice] = []
    @Published private(set) var connectedDevice: CastDevice?
    @Published private(set) var activeMedia: DetectedMedia?
    @Published private(set) var isPlaying: Bool = false
    @Published var useLocalProxy: Bool = true
    @Published private(set) var localProxyEndpoint: String?
    @Published private(set) var statusMessage: String = "Pronto"

    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var connection: NWConnection?
    private var heartbeatTimer: Timer?
    private var receiverTransportId: String?
    private var mediaSessionId: Int?
    private var requestIdCounter: Int = 1
    private var pendingMediaToLoad: DetectedMedia?
    private var pendingStartTimeSeconds: Double = 0.0

    let streamProxy = LocalStreamProxy()

    override init() {
        super.init()
        browser.delegate = self
    }

    func startDiscovery() {
        streamProxy.start()
        refreshProxyEndpoint()
        services.removeAll()
        browser.stop()
        browser.searchForServices(ofType: "_googlecast._tcp.", inDomain: "local.")
    }

    func refreshProxyEndpoint() {
        if let ip = LocalStreamProxy.findLocalLanIpAddress() {
            localProxyEndpoint = "\(ip):\(streamProxy.boundPort)"
        }
    }

    // MARK: - Bonjour / mDNS Discovery

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        services.append(service)
        service.resolve(withTimeout: 5.0)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let host = Self.extractIPv4(from: sender.addresses) ?? sender.hostName?.trimmingCharacters(in: CharacterSet(charactersIn: ".")) else {
            return
        }
        let port = UInt16(sender.port > 0 ? sender.port : 8009)
        var friendlyName = sender.name
        var modelName = "Smart TV"

        if let txtData = sender.txtRecordData() {
            let dict = NetService.dictionary(fromTXTRecord: txtData)
            if let fnData = dict["fn"], let fn = String(data: fnData, encoding: .utf8), !fn.isEmpty {
                friendlyName = fn
            }
            if let mdData = dict["md"], let md = String(data: mdData, encoding: .utf8), !md.isEmpty {
                modelName = md
            }
        }

        let device = CastDevice(friendlyName: friendlyName, modelName: modelName, host: host, port: port)
        DispatchQueue.main.async {
            if !self.discoveredDevices.contains(where: { $0.id == device.id }) {
                self.discoveredDevices.append(device)
            }
        }
    }

    private static func extractIPv4(from addresses: [Data]?) -> String? {
        guard let addresses = addresses else { return nil }
        for addrData in addresses {
            var storage = sockaddr_storage()
            (addrData as NSData).getBytes(&storage, length: min(addrData.count, MemoryLayout<sockaddr_storage>.size))
            if Int32(storage.ss_family) == AF_INET {
                var addr = withUnsafePointer(to: &storage) {
                    $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                }
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                inet_ntop(AF_INET, &addr.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN))
                return String(cString: buffer)
            }
        }
        return nil
    }

    // MARK: - CASTv2 TLS Session (Port 8009)

    func connect(to device: CastDevice) {
        disconnect()
        statusMessage = "Connessione a \(device.friendlyName)..."

        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(
            tlsOptions.securityProtocolOptions,
            { _, _, completionHandler in
                // Chromecast utilizza un certificato TLS device-self-signed
                completionHandler(true)
            },
            DispatchQueue.global(qos: .userInitiated)
        )

        let params = NWParameters(tls: tlsOptions, tcp: NWProtocolTCP.Options())
        let conn = NWConnection(
            host: NWEndpoint.Host(device.host),
            port: NWEndpoint.Port(rawValue: device.port) ?? 8009,
            using: params
        )
        self.connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                guard let self = self else { return }
                switch state {
                case .ready:
                    self.connectedDevice = device
                    self.statusMessage = "Connesso a \(device.friendlyName)"
                    self.sendCastJson(
                        namespace: "urn:x-cast:com.google.cast.tp.connection",
                        destinationId: "receiver-0",
                        json: ["type": "CONNECT"]
                    )
                    self.startHeartbeat()
                    self.receiveNextPacket()
                    if let pending = self.pendingMediaToLoad {
                        self.pendingMediaToLoad = nil
                        self.castVideo(pending, startTime: self.pendingStartTimeSeconds)
                        self.castVideo(pending)
                    }
                case .failed(let err):
                    self.statusMessage = "Errore connessione TV: \(err.localizedDescription)"
                    self.connectedDevice = nil
                default:
                    break
                }
            }
        }

        conn.start(queue: DispatchQueue.global(qos: .userInitiated))
    }

    func disconnect() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        connection?.cancel()
        connection = nil
        connectedDevice = nil
        receiverTransportId = nil
        mediaSessionId = nil
        isPlaying = false
    }

    func castVideo(_ media: DetectedMedia, cookies: String? = nil, startTime: Double = 0.0) {
        pendingStartTimeSeconds = startTime
    func castVideo(_ media: DetectedMedia, cookies: String? = nil) {
        refreshProxyEndpoint()
        activeMedia = media

        guard connectedDevice != nil, connection != nil else {
            pendingMediaToLoad = media
            statusMessage = "Seleziona una Smart TV dall'icona Cast in alto"
            return
        }

        // Lancia il Default Media Receiver (CC1AD845) sulla Smart TV
        let reqId = nextRequestId()
        sendCastJson(
            namespace: "urn:x-cast:com.google.cast.receiver",
            destinationId: "receiver-0",
            json: [
                "type": "LAUNCH",
                "appId": "CC1AD845",
                "requestId": reqId
            ]
        )
        statusMessage = "Avvio ricevitore su \(connectedDevice?.friendlyName ?? "TV")..."
    }

    private func sendMediaLoadCommand(transportId: String, media: DetectedMedia) {
        let streamUrl = useLocalProxy ? streamProxy.createProxiedUrl(for: media) : media.url
        let modeLabel = useLocalProxy ? "\(media.format.label) • Proxy Locale" : "\(media.format.label) • Diretto"

        // Connette il canale virtuale al transportId del Media Receiver
        sendCastJson(
            namespace: "urn:x-cast:com.google.cast.tp.connection",
            destinationId: transportId,
            json: ["type": "CONNECT"]
        )

        var loadPayload: [String: Any] = [
        let loadPayload: [String: Any] = [
            "type": "LOAD",
            "requestId": nextRequestId(),
            "autoplay": true,
            "media": [
                "contentId": streamUrl,
                "streamType": "BUFFERED",
                "contentType": media.mimeType,
                "metadata": [
                    "metadataType": 1,
                    "title": media.title,
                    "subtitle": modeLabel
                ]
            ]
        ]
        if pendingStartTimeSeconds > 1.0 {
            loadPayload["currentTime"] = pendingStartTimeSeconds
        }

        sendCastJson(
            namespace: "urn:x-cast:com.google.cast.media",
            destinationId: transportId,
            json: loadPayload
        )

        DispatchQueue.main.async {
            self.isPlaying = true
            self.statusMessage = "In riproduzione su \(self.connectedDevice?.friendlyName ?? "TV")"
        }
    }

    func togglePlayPause() {
        guard let transportId = receiverTransportId, let sessionId = mediaSessionId else { return }
        let command = isPlaying ? "PAUSE" : "PLAY"
        sendCastJson(
            namespace: "urn:x-cast:com.google.cast.media",
            destinationId: transportId,
            json: [
                "type": command,
                "mediaSessionId": sessionId,
                "requestId": nextRequestId()
            ]
        )
        isPlaying.toggle()
    }

    func stopCasting() {
        if let transportId = receiverTransportId, let sessionId = mediaSessionId {
            sendCastJson(
                namespace: "urn:x-cast:com.google.cast.media",
                destinationId: transportId,
                json: [
                    "type": "STOP",
                    "mediaSessionId": sessionId,
                    "requestId": nextRequestId()
                ]
            )
        }
        activeMedia = nil
        isPlaying = false
        statusMessage = "Riproduzione terminata"
    }

    private func startHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            self?.sendCastJson(
                namespace: "urn:x-cast:com.google.cast.tp.heartbeat",
                destinationId: "receiver-0",
                json: ["type": "PING"]
            )
        }
    }

    private func nextRequestId() -> Int {
        requestIdCounter += 1
        return requestIdCounter
    }

    // MARK: - Protobuf Framing & Parsing (CastMessage)

    private func sendCastJson(namespace: String, destinationId: String, json: [String: Any]) {
        guard let conn = connection,
              let jsonData = try? JSONSerialization.data(withJSONObject: json),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }

        let packet = Self.encodeCastMessage(
            sourceId: "sender-0",
            destinationId: destinationId,
            namespace: namespace,
            payloadUtf8: jsonString
        )
        conn.send(content: packet, completion: .contentProcessed({ _ in }))
    }

    private func receiveNextPacket() {
        guard let conn = connection else { return }
        conn.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] headerData, _, isComplete, error in
            guard let self = self, error == nil, !isComplete, let header = headerData, header.count == 4 else { return }
            let length = header.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            guard length > 0 && length < 262_144 else {
                self.receiveNextPacket()
                return
            }
            conn.receive(minimumIncompleteLength: Int(length), maximumLength: Int(length)) { [weak self] bodyData, _, _, err in
                guard let self = self, err == nil, let body = bodyData else { return }
                self.handleIncomingProtobuf(body)
                self.receiveNextPacket()
            }
        }
    }

    private func handleIncomingProtobuf(_ data: Data) {
        guard let payload = Self.extractUtf8Payload(from: data),
              let jsonData = payload.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              let type = obj["type"] as? String else { return }

        if type == "PING" {
            sendCastJson(
                namespace: "urn:x-cast:com.google.cast.tp.heartbeat",
                destinationId: "receiver-0",
                json: ["type": "PONG"]
            )
        } else if type == "RECEIVER_STATUS",
                  let status = obj["status"] as? [String: Any],
                  let apps = status["applications"] as? [[String: Any]],
                  let mediaApp = apps.first(where: { ($0["appId"] as? String) == "CC1AD845" }),
                  let transportId = mediaApp["transportId"] as? String {
            if self.receiverTransportId != transportId {
                self.receiverTransportId = transportId
                if let media = self.activeMedia {
                    self.sendMediaLoadCommand(transportId: transportId, media: media)
                }
            }
        } else if type == "MEDIA_STATUS",
                  let statuses = obj["status"] as? [[String: Any]],
                  let first = statuses.first,
                  let mediaSessionId = first["mediaSessionId"] as? Int {
            DispatchQueue.main.async {
                self.mediaSessionId = mediaSessionId
                if let playerState = first["playerState"] as? String {
                    self.isPlaying = (playerState == "PLAYING" || playerState == "BUFFERING")
                }
            }
        }
    }

    private static func encodeCastMessage(
        sourceId: String,
        destinationId: String,
        namespace: String,
        payloadUtf8: String
    ) -> Data {
        var proto = Data()
        // 1: protocol_version = 0 (CASTV2_1_0) -> tag 0x08, val 0x00
        proto.append(contentsOf: [0x08, 0x00])
        // 2: source_id -> tag 0x12
        appendProtobufString(tag: 0x12, value: sourceId, to: &proto)
        // 3: destination_id -> tag 0x1A
        appendProtobufString(tag: 0x1A, value: destinationId, to: &proto)
        // 4: namespace -> tag 0x22
        appendProtobufString(tag: 0x22, value: namespace, to: &proto)
        // 5: payload_type = 0 (STRING) -> tag 0x28, val 0x00
        proto.append(contentsOf: [0x28, 0x00])
        // 6: payload_utf8 -> tag 0x32
        appendProtobufString(tag: 0x32, value: payloadUtf8, to: &proto)

        var packet = Data()
        var bigEndianLen = UInt32(proto.count).bigEndian
        withUnsafeBytes(of: &bigEndianLen) { packet.append(contentsOf: $0) }
        packet.append(proto)
        return packet
    }

    private static func appendProtobufString(tag: UInt8, value: String, to data: inout Data) {
        let utf8 = Data(value.utf8)
        data.append(tag)
        var len = utf8.count
        while len >= 0x80 {
            data.append(UInt8((len & 0x7F) | 0x80))
            len >>= 7
        }
        data.append(UInt8(len))
        data.append(utf8)
    }

    private static func extractUtf8Payload(from data: Data) -> String? {
        var idx = 0
        let bytes = [UInt8](data)
        while idx < bytes.count {
            let tag = bytes[idx]
            idx += 1
            let wireType = tag & 0x07
            let fieldNum = tag >> 3

            if wireType == 0 {
                // Varint
                while idx < bytes.count && (bytes[idx] & 0x80) != 0 { idx += 1 }
                idx += 1
            } else if wireType == 2 {
                // Length-delimited
                var length = 0
                var shift = 0
                while idx < bytes.count {
                    let b = bytes[idx]
                    idx += 1
                    length |= Int(b & 0x7F) << shift
                    if (b & 0x80) == 0 { break }
                    shift += 7
                }
                guard idx + length <= bytes.count else { return nil }
                if fieldNum == 6 {
                    return String(bytes: bytes[idx..<(idx + length)], encoding: .utf8)
                }
                idx += length
            } else {
                break
            }
        }
        return nil
    }
}


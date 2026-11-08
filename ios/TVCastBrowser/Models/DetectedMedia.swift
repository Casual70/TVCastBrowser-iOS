import Foundation

enum StreamFormat: String, Codable {
    case hls = "HLS"
    case dash = "DASH"
    case mp4 = "MP4"
    case webm = "WEBM"
    case generic = "GENERIC"

    var label: String {
        switch self {
        case .hls: return "HLS (.m3u8)"
        case .dash: return "DASH (.mpd)"
        case .mp4: return "MP4 Video"
        case .webm: return "WebM Video"
        case .generic: return "Video Stream"
        }
    }

    var defaultMimeType: String {
        switch self {
        case .hls: return "application/x-mpegURL"
        case .dash: return "application/dash+xml"
        case .mp4, .generic: return "video/mp4"
        case .webm: return "video/webm"
        }
    }
}

struct DetectedMedia: Identifiable, Equatable {
    var id: String { url }
    let url: String
    let title: String
    let pageUrl: String
    let format: StreamFormat
    let mimeType: String
    let headers: [String: String]
    let detectedAt: Date

    init(
        url: String,
        title: String,
        pageUrl: String,
        format: StreamFormat,
        mimeType: String? = nil,
        headers: [String: String] = [:],
        detectedAt: Date = Date()
    ) {
        self.url = url
        self.title = title
        self.pageUrl = pageUrl
        self.format = format
        self.mimeType = mimeType ?? format.defaultMimeType
        self.headers = headers
        self.detectedAt = detectedAt
    }
}

struct HistoryItem: Identifiable, Codable, Equatable {
    var id: String { url }
    let url: String
    let title: String
    let timestamp: TimeInterval
}

final class BrowserPreferences: ObservableObject {
    static let maxHistoryItems = 20
    static let defaultHomeUrl = "https://www.google.com"

    private let homeUrlKey = "tv_cast_home_url"
    private let domainLockKey = "tv_cast_domain_lock_enabled"
    private let historyKey = "tv_cast_recent_history"
    private let onboardingKey = "tv_cast_has_seen_onboarding"

    @Published private(set) var homeUrl: String
    @Published private(set) var domainLockEnabled: Bool
    @Published private(set) var recentHistory: [HistoryItem]
    @Published private(set) var hasSeenOnboarding: Bool

    init() {
        let defaults = UserDefaults.standard
        self.homeUrl = defaults.string(forKey: homeUrlKey) ?? Self.defaultHomeUrl
        if defaults.object(forKey: domainLockKey) == nil {
            self.domainLockEnabled = true
        } else {
            self.domainLockEnabled = defaults.bool(forKey: domainLockKey)
        }
        self.hasSeenOnboarding = defaults.bool(forKey: onboardingKey)
        if let data = defaults.data(forKey: historyKey),
           let decoded = try? JSONDecoder().decode([HistoryItem].self, from: data) {
            self.recentHistory = Array(decoded.prefix(Self.maxHistoryItems))
        } else {
            self.recentHistory = []
        }
    }

    func setOnboardingSeen(_ seen: Bool = true) {
        hasSeenOnboarding = seen
        UserDefaults.standard.set(seen, forKey: onboardingKey)
    }

    func setHomePage(_ url: String, enableDomainLock: Bool = true) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        homeUrl = trimmed
        domainLockEnabled = enableDomainLock
        UserDefaults.standard.set(trimmed, forKey: homeUrlKey)
        UserDefaults.standard.set(enableDomainLock, forKey: domainLockKey)
    }

    func setDomainLockEnabled(_ enabled: Bool) {
        domainLockEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: domainLockKey)
    }

    var lockedBaseDomain: String? {
        Self.extractBaseDomain(from: homeUrl)
    }

    func isUrlAllowedByDomainLock(_ targetUrl: String) -> Bool {
        guard domainLockEnabled else { return true }
        guard let locked = lockedBaseDomain, locked != "google.com" else { return true }
        guard let targetDomain = Self.extractBaseDomain(from: targetUrl) else { return false }
        return targetDomain == locked
    }

    func addHistoryEntry(url: String, title: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix("http://") || trimmed.lowercased().hasPrefix("https://") else { return }
        guard trimmed != "about:blank" else { return }

        let displayTitle = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? trimmed : title
        var updated = recentHistory.filter { $0.url.caseInsensitiveCompare(trimmed) != .orderedSame }
        updated.insert(
            HistoryItem(url: trimmed, title: displayTitle, timestamp: Date().timeIntervalSince1970),
            at: 0
        )
        recentHistory = Array(updated.prefix(Self.maxHistoryItems))
        saveHistory()
    }

    func removeHistoryEntry(url: String) {
        recentHistory.removeAll { $0.url == url }
        saveHistory()
    }

    func clearHistory() {
        recentHistory = []
        UserDefaults.standard.removeObject(forKey: historyKey)
    }

    private func saveHistory() {
        if let data = try? JSONEncoder().encode(recentHistory) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
    }

    static func extractBaseDomain(from urlString: String) -> String? {
        guard let host = URL(string: urlString)?.host?.lowercased() else { return nil }
        let cleaned = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        let parts = cleaned.split(separator: ".")
        if parts.count >= 2 {
            return parts.suffix(2).joined(separator: ".")
        }
        return cleaned
    }
}


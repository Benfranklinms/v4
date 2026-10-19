import Foundation
import Security

/// The shared Rust core (UniFFI). All heavy calls are async and run on the core's tokio runtime.
enum Engine {
    static let supportDir: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("iTune", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    static let core: Core = {
        do {
            let c = try Core(dataDir: supportDir.appendingPathComponent("core").path, platform: "macos")
            let info = Bundle.main.infoDictionary ?? [:]
            c.lastfmConfigure(apiKey: info["LastfmApiKey"] as? String ?? "", apiSecret: info["LastfmApiSecret"] as? String ?? "")
            return c
        }
        catch { fatalError("core init failed: \(error)") }
    }()

    static let updateRepo = "Benfranklinms/v4"

    static var tzMinutes: Int32 { Int32(TimeZone.current.secondsFromGMT() / 60) }

    static func message(_ e: Error) -> String {
        let s = "\(e)"
        if let r = s.range(of: "msg: \"") { return String(s[r.upperBound...]).replacingOccurrences(of: "\")", with: "") }
        return s
    }

    static func isAuth(_ e: Error) -> Bool {
        if case CoreError.Auth = e { return true }
        return false
    }
}

/// Optional update token for a private repo, kept in the login Keychain.
enum Keychain {
    private static let service = "v4.update"
    private static let account = "token"

    static func get() -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func set(_ token: String?) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        guard let t = token?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return }
        var add = base
        add[kSecValueData as String] = Data(t.utf8)
        SecItemAdd(add as CFDictionary, nil)
    }

    static var has: Bool { Keychain.get() != nil }
}

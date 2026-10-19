import Foundation
import Security

/// The shared Rust core (UniFFI). Every call from UI code goes through `Engine.off` / `Engine.offThrowing`
/// so SQLite, file and network work never runs on the main thread. Rust panics are caught by UniFFI's
/// scaffolding (catch_unwind) and arrive here as Swift errors, which these helpers swallow or return.
enum Engine {
    static let supportDir: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("iTune", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    /// Set when the core couldn't open (shown on screen instead of crashing).
    nonisolated(unsafe) static var initError: String?

    static let core: Core = {
        let dir = supportDir.appendingPathComponent("core").path
        do {
            let c = try Core(dataDir: dir, platform: "macos")
            let info = Bundle.main.infoDictionary ?? [:]
            c.lastfmConfigure(apiKey: info["LastfmApiKey"] as? String ?? "", apiSecret: info["LastfmApiSecret"] as? String ?? "")
            return c
        } catch {
            initError = "\(error)"
            // fall back to a fresh data folder so the app still opens
            let alt = supportDir.appendingPathComponent("core-recovered-\(Int(Date().timeIntervalSince1970))").path
            if let c = try? Core(dataDir: alt, platform: "macos") { return c }
            fatalError("core init failed: \(error)")
        }
    }()

    static let updateRepo = "Benfranklinms/v4"
    static let coreVersion: String = core.version()

    static var tzMinutes: Int32 { Int32(TimeZone.current.secondsFromGMT() / 60) }

    /// Blocking core work (SQLite, files) runs on its own GCD queue, never on the main thread and never on
    /// Swift's small cooperative pool (a blocked FFI call there could stall every other task).
    static let work = DispatchQueue(label: "core.work", qos: .userInitiated, attributes: .concurrent)

    /// Run a synchronous core call on a background thread; nil on error.
    static func off<T>(_ f: @escaping () throws -> T) async -> T? {
        await withCheckedContinuation { (c: CheckedContinuation<T?, Never>) in
            work.async { c.resume(returning: try? f()) }
        }
    }

    /// Run a synchronous core call on a background thread, keeping the error.
    static func offResult<T>(_ f: @escaping () throws -> T) async -> Result<T, Error> {
        await withCheckedContinuation { (c: CheckedContinuation<Result<T, Error>, Never>) in
            work.async { do { c.resume(returning: .success(try f())) } catch { c.resume(returning: .failure(error)) } }
        }
    }

    /// Fire-and-forget writes run in order on one serial queue (a save followed by an unsave can't swap).
    static let writes = DispatchQueue(label: "core.writes", qos: .userInitiated)
    static func bg(_ f: @escaping () throws -> Void) {
        writes.async { try? f() }
    }
    /// Same, then `done` on the writes queue once the call returned (success or not).
    static func bg(_ f: @escaping () throws -> Void, done: @escaping () -> Void) {
        writes.async { try? f(); done() }
    }

    static func message(_ e: Error) -> String {
        let s = "\(e)"
        if let r = s.range(of: "msg: \"") { return String(s[r.upperBound...]).replacingOccurrences(of: "\")", with: "") }
        return s
    }

    /// Plain-language error for people (no internal jargon).
    static func friendly(_ e: Error, _ fallback: String) -> String {
        let m = message(e)
        if m.contains("HTTP") || m.contains("network") || m.contains("error sending") || m.contains("timed out") { return fallback }
        return m.count > 140 ? fallback : m
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

/// Crash reporter: Objective-C exceptions and fatal signals are written to Application Support/iTune/crash.log;
/// the next launch shows them with a Copy button (Diagnostics).
enum CrashReporter {
    static var logURL: URL { Engine.supportDir.appendingPathComponent("crash.log") }
    static var historyURL: URL { Engine.supportDir.appendingPathComponent("crash-history.log") }
    nonisolated(unsafe) private static var fd: Int32 = -1

    static func install() {
        // pre-open the file so the signal handler only calls async-signal-safe write()
        fd = open(logURL.path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
        NSSetUncaughtExceptionHandler { e in
            let text = "iTune \(CrashReporter.version) uncaught exception: \(e.name.rawValue) \(e.reason ?? "")\n" + e.callStackSymbols.joined(separator: "\n") + "\n"
            CrashReporter.writeRaw(text)
        }
        for sig in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP] {
            signal(sig) { s in
                let msg = "iTune crashed with signal \(s)\n"
                CrashReporter.writeRaw(msg)
                Thread.callStackSymbols.forEach { CrashReporter.writeRaw($0 + "\n") }
                signal(s, SIG_DFL)
                raise(s)
            }
        }
    }

    static var version: String {
        let i = Bundle.main.infoDictionary ?? [:]
        return "\(i["CFBundleShortVersionString"] as? String ?? "?") (\(i["CFBundleVersion"] as? String ?? "?"))"
    }

    static func writeRaw(_ s: String) {
        guard fd >= 0 else { return }
        s.utf8CString.withUnsafeBufferPointer { p in _ = write(fd, p.baseAddress, max(0, p.count - 1)) }
    }

    /// Returns and archives the last crash report, if any.
    static func takePending() -> String? {
        guard let t = try? String(contentsOf: logURL, encoding: .utf8), !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let stamp = ISO8601DateFormatter().string(from: Date())
        if let h = FileHandle(forWritingAtPath: historyURL.path) ?? { FileManager.default.createFile(atPath: historyURL.path, contents: nil); return FileHandle(forWritingAtPath: historyURL.path) }() {
            h.seekToEndOfFile(); h.write(Data("\n==== \(stamp)\n\(t)".utf8)); try? h.close()
        }
        try? FileManager.default.removeItem(at: logURL)
        fd = open(logURL.path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
        return t
    }

    static func history() -> String { (try? String(contentsOf: historyURL, encoding: .utf8)) ?? "" }
    static func clearHistory() { try? FileManager.default.removeItem(at: historyURL) }
}

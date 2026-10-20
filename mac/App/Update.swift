import AppKit
import Darwin
import SwiftUI

// In-app updates from the GitHub releases of `Engine.updateRepo`.
//
// Download DMG -> (off the main thread) strip quarantine, mount with a timeout, check the new bundle's identifier,
// version and signature, stage a verified copy next to the running app -> a detached helper (own session, survives
// this process) waits for the app to exit, swaps the bundle with two renames and relaunches it.
// Every stage is logged to ~/Library/Logs/v4-update.log. Any failure or a stage running past 60 s opens the DMG in
// Finder instead, with a message and "Show in Finder" (never an endless "Installing…").
//
// 0.2.1 hang: the update UI is a SwiftUI sheet, and AppKit swallows NSApp.terminate while a sheet is attached, so
// the app never quit and the helper's `while kill -0` waited forever. Now the sheets are ended before terminate,
// the sheet opts out of blocking termination, and the app exits on its own 3 s after asking (session saved first).

/// Updater log: ~/Library/Logs/v4-update.log (shared with the helper script).
enum UpdateLog {
    static let url: URL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/v4-update.log")
    private static let q = DispatchQueue(label: "v4.update.log")

    static func write(_ s: String) {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let line = "\(f.string(from: Date())) [app \(ProcessInfo.processInfo.processIdentifier)] \(s)\n"
        q.async {
            let fm = FileManager.default
            try? fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            if !fm.fileExists(atPath: url.path) { fm.createFile(atPath: url.path, contents: nil) }
            if let h = try? FileHandle(forWritingTo: url) {
                h.seekToEndOfFile()
                h.write(Data(line.utf8))
                try? h.close()
            }
        }
        if UpdateTest.enabled || CommandLine.arguments.contains("--smoke") { FileHandle.standardError.write(Data(("UPDATE " + s + "\n").utf8)) }
    }

    /// Waits until every queued line is on disk (before exit).
    static func flush() { q.sync {} }
}

/// Runs a command line tool without ever blocking the main thread or deadlocking on a full pipe.
enum Shell {
    struct Result { let status: Int32; let output: String; let timedOut: Bool }

    private final class Buffer: @unchecked Sendable {
        private let lock = NSLock()
        private var data = Data()
        func append(_ d: Data) { lock.lock(); data.append(d); lock.unlock() }
        var text: String { lock.lock(); defer { lock.unlock() }; return String(decoding: data, as: UTF8.self) }
    }

    static func run(_ exe: String, _ args: [String], timeout: TimeInterval) async -> Result {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async { cont.resume(returning: runBlocking(exe, args, timeout: timeout)) }
        }
    }

    /// Call off the main thread only. Output is drained as it arrives; the tool is killed after `timeout`.
    static func runBlocking(_ exe: String, _ args: [String], timeout: TimeInterval) -> Result {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: exe)
        p.arguments = args
        p.standardInput = FileHandle.nullDevice
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        let buf = Buffer()
        pipe.fileHandleForReading.readabilityHandler = { h in
            let d = h.availableData
            if d.isEmpty { h.readabilityHandler = nil } else { buf.append(d) }
        }
        let done = DispatchSemaphore(value: 0)
        p.terminationHandler = { _ in done.signal() }
        do { try p.run() } catch {
            pipe.fileHandleForReading.readabilityHandler = nil
            return Result(status: -1, output: "\(error)", timedOut: false)
        }
        var timedOut = false
        if done.wait(timeout: .now() + timeout) == .timedOut {
            timedOut = true
            p.terminate()
            if done.wait(timeout: .now() + 3) == .timedOut {
                kill(p.processIdentifier, SIGKILL)
                _ = done.wait(timeout: .now() + 3)
            }
        }
        usleep(50_000) // let the last chunk arrive
        pipe.fileHandleForReading.readabilityHandler = nil
        return Result(status: timedOut ? -2 : p.terminationStatus, output: buf.text, timedOut: timedOut)
    }
}

/// Headless check of the whole update path (CI): `iTune --update-test=<dmg> --update-test-version=<x.y.z>`.
/// Presents the real update sheet, then installs from the local DMG exactly like a download would.
enum UpdateTest {
    static func arg(_ name: String) -> String? {
        CommandLine.arguments.first { $0.hasPrefix("--\(name)=") }.map { String($0.dropFirst(name.count + 3)) }
    }
    static var dmg: String? { arg("update-test") }
    static var enabled: Bool { dmg != nil }

    @MainActor static func run() async {
        guard let path = dmg else { return }
        let version = arg("update-test-version") ?? "9.9.9"
        UpdateLog.write("update test: \(Updater.shared.currentVersion) -> \(version) from \(path), app at \(Bundle.main.bundlePath)")
        let info = UpdateInfo(version: version, tag: "v" + version, notes: "Update test", newer: true,
                              assets: [ReleaseAsset(name: (path as NSString).lastPathComponent, apiUrl: "", browserUrl: "", size: 0)],
                              publishedAt: "")
        // the real sheet is up while installing, as for a user (the condition that swallowed terminate in 0.2.1)
        Nav.shared.updateInfo = info
        try? await Task.sleep(nanoseconds: 2_500_000_000)
        UpdateLog.write("update test: sheet attached = \(NSApp.windows.contains { $0.attachedSheet != nil })")
        Updater.shared.installLocal(URL(fileURLWithPath: path), info: info)
    }
}

struct PreparedUpdate: Sendable {
    let staged: String   // verified copy next to the target ("<target>.v4new")
    let target: String   // bundle path to replace
    let version: String
}

struct UpdateFailure: Error, Sendable { let message: String }

/// The blocking part of an install. Runs on a background thread.
enum UpdatePrep {
    static let quarantine = "com.apple.quarantine"

    static func stripQuarantine(_ path: String) {
        if removexattr(path, quarantine, 0) == 0 { UpdateLog.write("removed quarantine from \((path as NSString).lastPathComponent)") }
    }

    static func setQuarantineForTest(_ path: String) {
        let v = String(format: "0083;%08x;Safari;", Int(Date().timeIntervalSince1970))
        _ = v.withCString { setxattr(path, quarantine, $0, strlen($0), 0, 0) }
    }

    static func normalized(_ v: String) -> [Int] {
        let core = v.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
            .split(whereSeparator: { "-+ ".contains($0) }).first.map(String.init) ?? ""
        var parts = core.split(separator: ".").map { Int($0) ?? 0 }
        while parts.count > 1 && parts.last == 0 { parts.removeLast() }
        return parts
    }

    static func isNewer(_ a: String, than b: String) -> Bool {
        let x = normalized(a), y = normalized(b)
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p > q }
        }
        return false
    }

    /// Where the new bundle goes: in place when the running copy can be replaced (/Applications, ~/Downloads, …);
    /// /Applications (or ~/Applications) when it runs translocated, from a disk image or a read-only folder.
    static func chooseTarget(running: String, appName: String) -> String {
        let fm = FileManager.default
        let parent = (running as NSString).deletingLastPathComponent
        let translocated = running.contains("/AppTranslocation/")
        let readOnly = (try? URL(fileURLWithPath: running).resourceValues(forKeys: [.volumeIsReadOnlyKey]).volumeIsReadOnly) ?? false
        if !translocated && !readOnly && !running.hasPrefix("/Volumes/") && fm.isWritableFile(atPath: parent) { return running }
        if fm.isWritableFile(atPath: "/Applications") { return "/Applications/\(appName)" }
        let user = fm.homeDirectoryForCurrentUser.appendingPathComponent("Applications")
        try? fm.createDirectory(at: user, withIntermediateDirectories: true)
        return user.appendingPathComponent(appName).path
    }

    static func prepare(dmg: String, expect: String, current: String, bundleId: String, running: String) -> Result<PreparedUpdate, UpdateFailure> {
        let fm = FileManager.default
        stripQuarantine(dmg)
        let mount = (NSTemporaryDirectory() as NSString).appendingPathComponent("v4-mount-\(getpid())-\(Int(Date().timeIntervalSince1970))")
        try? fm.createDirectory(atPath: mount, withIntermediateDirectories: true)
        let detach = {
            let d = Shell.runBlocking("/usr/bin/hdiutil", ["detach", mount, "-force"], timeout: 20)
            UpdateLog.write("detach \(d.status == 0 ? "ok" : "failed: \(d.output.prefix(200))")")
            try? fm.removeItem(atPath: mount)
        }
        let attachArgs = ["attach", dmg, "-nobrowse", "-readonly", "-noautoopen", "-noverify", "-mountpoint", mount]
        var r = Shell.runBlocking("/usr/bin/hdiutil", attachArgs, timeout: 30)
        if r.status != 0 && !r.timedOut {
            UpdateLog.write("attach failed (\(r.status)): \(r.output.prefix(300)); retrying")
            sleep(2)
            r = Shell.runBlocking("/usr/bin/hdiutil", attachArgs, timeout: 20)
        }
        guard r.status == 0 else {
            UpdateLog.write("attach failed (\(r.timedOut ? "timed out" : "status \(r.status)")): \(r.output.prefix(300))")
            if r.timedOut { detach() } else { try? fm.removeItem(atPath: mount) }
            return .failure(UpdateFailure(message: r.timedOut ? "Opening the disk image timed out." : "The disk image couldn't be opened."))
        }
        UpdateLog.write("mounted at \(mount)")
        defer { detach() }

        guard let appName = (try? fm.contentsOfDirectory(atPath: mount))?.first(where: { $0.hasSuffix(".app") }) else {
            return .failure(UpdateFailure(message: "The disk image has no app in it."))
        }
        let src = (mount as NSString).appendingPathComponent(appName)
        guard let plist = NSDictionary(contentsOfFile: src + "/Contents/Info.plist"),
              let bid = plist["CFBundleIdentifier"] as? String,
              let ver = plist["CFBundleShortVersionString"] as? String else {
            return .failure(UpdateFailure(message: "The new app is damaged (no Info.plist)."))
        }
        UpdateLog.write("new bundle \(bid) \(ver)")
        guard bid == bundleId else { return .failure(UpdateFailure(message: "The disk image holds a different app (\(bid)).")) }
        let versionOK = expect.isEmpty ? isNewer(ver, than: current) : normalized(ver) == normalized(expect)
        guard versionOK else { return .failure(UpdateFailure(message: "The new app reports version \(ver), expected \(expect.isEmpty ? "newer than \(current)" : expect).")) }

        let target = chooseTarget(running: running, appName: appName)
        let parent = (target as NSString).deletingLastPathComponent
        guard fm.isWritableFile(atPath: parent) else { return .failure(UpdateFailure(message: "No permission to replace the app in \(parent).")) }
        // stage a copy beside the target (same volume -> the swap is two renames) and verify THAT copy
        let staged = target + ".v4new"
        try? fm.removeItem(atPath: staged)
        let cp = Shell.runBlocking("/usr/bin/ditto", [src, staged], timeout: 45)
        guard cp.status == 0 else {
            try? fm.removeItem(atPath: staged)
            return .failure(UpdateFailure(message: cp.timedOut ? "Copying the new app timed out." : "Copying the new app failed: \(cp.output.prefix(160))"))
        }
        _ = Shell.runBlocking("/usr/bin/xattr", ["-dr", quarantine, staged], timeout: 15)
        let exe = staged + "/Contents/MacOS/" + ((plist["CFBundleExecutable"] as? String) ?? "iTune")
        guard fm.isExecutableFile(atPath: exe) else {
            try? fm.removeItem(atPath: staged)
            return .failure(UpdateFailure(message: "The new app has no executable."))
        }
        let cs = Shell.runBlocking("/usr/bin/codesign", ["--verify", "--deep", "--strict", staged], timeout: 30)
        guard cs.status == 0 else {
            UpdateLog.write("codesign verify failed: \(cs.output.prefix(300))")
            try? fm.removeItem(atPath: staged)
            return .failure(UpdateFailure(message: "The new app's signature didn't verify."))
        }
        UpdateLog.write("staged \(ver) at \(staged) (codesign ok), target \(target)")
        return .success(PreparedUpdate(staged: staged, target: target, version: ver))
    }

    /// Writes the helper and starts it in its own session (survives this process, ignores SIGHUP).
    static func launchHelper(_ u: PreparedUpdate, dmg: String, oldVersion: String, pid: Int32) throws {
        let q = { (s: String) in "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }
        let script = """
        #!/bin/sh
        trap '' HUP INT
        LOG=\(q(UpdateLog.url.path))
        log() { echo "$(date '+%Y-%m-%dT%H:%M:%S%z') [helper $$] $*" >> "$LOG"; }
        PID=\(pid); STAGED=\(q(u.staged)); TARGET=\(q(u.target)); DMG=\(q(dmg)); OLDV=\(q(oldVersion)); NEWV=\(q(u.version))
        failed() {
          log "FAILED: $1"
          if [ ! -e "$TARGET" ] && [ -e "$TARGET.old" ]; then mv "$TARGET.old" "$TARGET"; fi
          rm -rf "$STAGED"
          /usr/bin/open "$DMG"
          [ -e "$TARGET" ] && /usr/bin/open -n "$TARGET" --args "--update-failed=$DMG"
          exit 1
        }
        log "waiting for pid $PID to exit"
        i=0
        while kill -0 "$PID" 2>/dev/null; do
          i=$((i+1))
          if [ "$i" -ge 150 ]; then log "app still running after 30 s, killing it"; kill -9 "$PID" 2>/dev/null; sleep 1; break; fi
          sleep 0.2
        done
        log "app exited, installing $NEWV at $TARGET"
        [ -d "$STAGED" ] || failed "staged copy missing"
        rm -rf "$TARGET.old"
        if [ -e "$TARGET" ]; then mv "$TARGET" "$TARGET.old" || failed "could not move the old app aside"; fi
        if mv "$STAGED" "$TARGET"; then rm -rf "$TARGET.old"; else failed "could not move the new app in place"; fi
        V=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$TARGET/Contents/Info.plist" 2>/dev/null)
        log "installed version $V"
        rm -f "$DMG"
        if /usr/bin/open -n "$TARGET" --args "--updated-from=$OLDV"; then log "relaunch requested"; else
          sleep 1; /usr/bin/open "$TARGET" --args "--updated-from=$OLDV" && log "relaunch requested (retry)" || log "FAILED: relaunch"
        fi
        rm -f "$0"
        """
        let path = Updater.workDir.appendingPathComponent("v4-update-helper.sh").path
        try script.write(toFile: path, atomically: true, encoding: .utf8)
        chmod(path, 0o755)

        // posix_spawn with POSIX_SPAWN_SETSID: the helper gets its own session, so nothing that happens to this
        // process (quit, crash, forced exit) reaches it. stdin /dev/null, stdout+stderr to the log.
        var fa: posix_spawn_file_actions_t? = nil
        var attr: posix_spawnattr_t? = nil
        posix_spawn_file_actions_init(&fa)
        posix_spawnattr_init(&attr)
        defer { posix_spawn_file_actions_destroy(&fa); posix_spawnattr_destroy(&attr) }
        posix_spawn_file_actions_addopen(&fa, 0, "/dev/null", O_RDONLY, 0)
        posix_spawn_file_actions_addopen(&fa, 1, UpdateLog.url.path, O_WRONLY | O_APPEND | O_CREAT, 0o644)
        posix_spawn_file_actions_adddup2(&fa, 1, 2)
        posix_spawnattr_setflags(&attr, Int16(0x0400)) // POSIX_SPAWN_SETSID
        let argv: [UnsafeMutablePointer<CChar>?] = [strdup("/bin/sh"), strdup(path), nil]
        let envp: [UnsafeMutablePointer<CChar>?] = ProcessInfo.processInfo.environment.map { strdup("\($0.key)=\($0.value)") } + [nil]
        defer { argv.forEach { free($0) }; envp.forEach { free($0) } }
        var child: pid_t = 0
        let rc = posix_spawn(&child, "/bin/sh", &fa, &attr, argv, envp)
        if rc == 0 {
            UpdateLog.write("helper started, pid \(child) (own session)")
            return
        }
        // fallback: background job under nohup (reparented to launchd when the outer shell exits at once)
        UpdateLog.write("posix_spawn failed (\(rc)), starting helper with nohup")
        let r = Shell.runBlocking("/bin/sh", ["-c", "/usr/bin/nohup /bin/sh \"$0\" </dev/null >>\"$1\" 2>&1 &", path, UpdateLog.url.path], timeout: 10)
        if r.status != 0 { throw UpdateFailure(message: "The installer helper didn't start.") }
    }
}

@MainActor
final class Updater: ObservableObject {
    static let shared = Updater()

    enum Stage: String { case idle, downloading, verifying, installing, restarting, failed }

    @Published var checking = false
    @Published var status: String?
    @Published var progress: Double = 0
    @Published var stage: Stage = .idle
    /// Shown when an install failed; with `fallbackDMG` the sheet offers "Show in Finder".
    @Published var failure: String?
    @Published var fallbackDMG: URL?
    /// The releases API said 401/404 and no key is saved: show the small "Add access key" row.
    @AppStorage("updateNeedsKey") var needsKey = false
    private var generation = 0
    private(set) var quitting = false

    var installing: Bool { stage != .idle && stage != .failed }
    var stageText: String {
        switch stage {
        case .idle: return ""
        case .downloading: return "Downloading \(Int(progress * 100))%"
        case .verifying: return "Verifying…"
        case .installing: return "Installing…"
        case .restarting: return "Restarting…"
        case .failed: return failure ?? "The update couldn't be installed."
        }
    }

    var currentVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0" }

    nonisolated static var workDir: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        let d = base.appendingPathComponent((Bundle.main.bundleIdentifier ?? "in.benfranklin.itune") + "/Updates", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    func checkOnLaunch() async {
        let last = UserDefaults.standard.double(forKey: "lastUpdateCheck")
        guard Date().timeIntervalSince1970 - last > 3600 * 6 else { return }
        await check(manual: false)
    }

    func check(manual: Bool) async {
        checking = true
        defer { checking = false }
        do {
            let info = try await Engine.core.checkUpdate(repo: Engine.updateRepo, currentVersion: currentVersion, token: Keychain.get())
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "lastUpdateCheck")
            needsKey = false
            if info.newer {
                if stage == .failed { stage = .idle; failure = nil; fallbackDMG = nil }
                Nav.shared.updateInfo = info
                status = "Version \(info.version) available"
            } else {
                status = manual ? "You're up to date" : nil
            }
        } catch {
            if Engine.isAuth(error) {
                needsKey = !Keychain.has
                status = Keychain.has ? "The access key didn't work" : nil
            } else {
                status = manual ? "Couldn't check: \(Engine.message(error))" : nil
            }
        }
    }

    private func begin() -> Int {
        generation += 1
        failure = nil
        fallbackDMG = nil
        progress = 0
        stage = .downloading
        return generation
    }

    func install(_ info: UpdateInfo) {
        guard !installing else { return }
        guard let asset = info.assets.first(where: { $0.name.lowercased().hasSuffix(".dmg") }) else {
            status = "No Mac build in this release"; return
        }
        let gen = begin()
        let dmg = Updater.workDir.appendingPathComponent(asset.name)
        try? FileManager.default.removeItem(at: dmg)
        UpdateLog.write("update \(currentVersion) -> \(info.version): downloading \(asset.name); app at \(Bundle.main.bundlePath)")
        let poll = Task { @MainActor in
            var last: Float = -1
            var changed = Date()
            while !Task.isCancelled && self.generation == gen && self.stage == .downloading {
                if let p = Engine.core.downloadProgress(id: "update"), p != last {
                    last = p; changed = Date()
                    withAnimation(.linear(duration: 0.2)) { self.progress = Double(p) }
                }
                if Date().timeIntervalSince(changed) > 60 {
                    self.fail(gen, "The download stalled.", dmg: nil, page: asset.browserUrl)
                    break
                }
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
        }
        Task { @MainActor in
            defer { poll.cancel() }
            do {
                _ = try await Engine.core.downloadUpdate(assetApiUrl: asset.apiUrl, token: Keychain.get(), dest: dmg.path)
            } catch {
                guard self.generation == gen, self.stage == .downloading else { return }
                self.fail(gen, "The download failed (\(Engine.message(error))).", dmg: nil, page: asset.browserUrl)
                return
            }
            guard self.generation == gen, self.stage == .downloading else { return }
            withAnimation(.linear(duration: 0.2)) { self.progress = 1 }
            UpdateLog.write("downloaded \((try? FileManager.default.attributesOfItem(atPath: dmg.path)[.size] as? Int) ?? 0) bytes")
            await self.installDMG(dmg, expect: info.version, gen: gen)
        }
    }

    /// Test path (CI): same as `install` after the download, from a local DMG that gets a quarantine flag first.
    func installLocal(_ src: URL, info: UpdateInfo) {
        guard !installing else { return }
        let gen = begin()
        let dmg = Updater.workDir.appendingPathComponent(src.lastPathComponent)
        try? FileManager.default.removeItem(at: dmg)
        do { try FileManager.default.copyItem(at: src, to: dmg) } catch {
            fail(gen, "Couldn't copy the test image (\(error.localizedDescription)).", dmg: nil, page: nil); return
        }
        UpdatePrep.setQuarantineForTest(dmg.path)
        Task { @MainActor in
            for i in 1...10 { withAnimation(.linear(duration: 0.1)) { self.progress = Double(i) / 10 }; try? await Task.sleep(nanoseconds: 80_000_000) }
            await self.installDMG(dmg, expect: info.version, gen: gen)
        }
    }

    private func installDMG(_ dmg: URL, expect: String, gen: Int) async {
        stage = .verifying
        let current = currentVersion
        let bundleId = Bundle.main.bundleIdentifier ?? "in.benfranklin.itune"
        let running = Bundle.main.bundlePath
        let watchdog = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 60_000_000_000)
            guard !Task.isCancelled, self.generation == gen, self.stage == .verifying || self.stage == .installing else { return }
            UpdateLog.write("timed out after 60 s in stage \(self.stage.rawValue)")
            self.fail(gen, "Installing took too long.", dmg: dmg, page: nil)
        }
        let result = await Task.detached(priority: .userInitiated) {
            UpdatePrep.prepare(dmg: dmg.path, expect: expect, current: current, bundleId: bundleId, running: running)
        }.value
        guard generation == gen, stage == .verifying else {
            if case .success(let u) = result { try? FileManager.default.removeItem(atPath: u.staged) }
            return
        }
        switch result {
        case .failure(let e):
            watchdog.cancel()
            fail(gen, e.message, dmg: dmg, page: nil)
        case .success(let u):
            stage = .installing
            let old = current
            let pid = getpid()
            let started = await Task.detached(priority: .userInitiated) { () -> String? in
                do { try UpdatePrep.launchHelper(u, dmg: dmg.path, oldVersion: old, pid: pid); return nil }
                catch { return (error as? UpdateFailure)?.message ?? "\(error)" }
            }.value
            watchdog.cancel()
            guard generation == gen, stage == .installing else { try? FileManager.default.removeItem(atPath: u.staged); return }
            if let err = started {
                try? FileManager.default.removeItem(atPath: u.staged)
                fail(gen, err, dmg: dmg, page: nil)
                return
            }
            stage = .restarting
            quitForUpdate()
        }
    }

    /// Saves state, ends every sheet (AppKit ignores terminate while one is attached), asks to quit, and exits
    /// on its own after 3 s if the quit was refused or deferred.
    private func quitForUpdate() {
        quitting = true
        UpdateLog.write("saving session and quitting")
        Nav.shared.flushPending()
        Player.shared.saveSession(sync: true)
        UpdateLog.flush()
        DispatchQueue.global().asyncAfter(deadline: .now() + 3) {
            UpdateLog.write("still running 3 s after terminate, exiting")
            UpdateLog.flush()
            exit(0)
        }
        // give "Restarting…" a moment on screen, then take the sheets down and quit on the next turn
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            for w in NSApp.windows { if let s = w.attachedSheet { w.endSheet(s) } }
            Nav.shared.updateInfo = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                UpdateLog.write("terminate")
                UpdateLog.flush()
                NSApp.terminate(nil)
            }
        }
    }

    private func fail(_ gen: Int, _ msg: String, dmg: URL?, page: String?) {
        guard generation == gen else { return }
        generation += 1 // anything still running for this attempt is ignored
        stage = .failed
        if let dmg, FileManager.default.fileExists(atPath: dmg.path) {
            fallbackDMG = dmg
            failure = "\(msg) The installer is open in Finder: drag iTune to Applications to finish."
            UpdatePrep.stripQuarantine(dmg.path)
            if !UpdateTest.enabled { NSWorkspace.shared.open(dmg) }
        } else {
            failure = page == nil ? msg : "\(msg) Opened the download page instead."
            if !UpdateTest.enabled, let page, let u = URL(string: page) { NSWorkspace.shared.open(u) }
        }
        status = failure
        UpdateLog.write("failed: \(failure ?? msg)")
        if UpdateTest.enabled { UpdateLog.flush(); exit(3) }
    }

    func showInFinder() {
        guard let d = fallbackDMG else { return }
        NSWorkspace.shared.activateFileViewerSelecting([d])
    }

    func reset() {
        guard !installing else { return }
        stage = .idle; failure = nil; fallbackDMG = nil; progress = 0
    }

    /// Launch arguments from the helper: `--updated-from=<old>` after a swap, `--update-failed=<dmg>` after a failed one.
    func handleLaunchArguments() {
        if let old = UpdateTest.arg("updated-from") {
            UpdateLog.write("relaunched version \(currentVersion) (from \(old)) at \(Bundle.main.bundlePath)")
            UpdateLog.flush()
            status = "Updated to \(currentVersion)"
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { Nav.shared.show("Updated to \(self.currentVersion)") }
        }
        if let dmg = UpdateTest.arg("update-failed") {
            UpdateLog.write("relaunched after a failed install (\(dmg))")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                let a = NSAlert()
                a.messageText = "The update couldn't be installed"
                a.informativeText = "The installer is open in Finder: drag iTune to Applications to finish updating."
                a.addButton(withTitle: "Show in Finder")
                a.addButton(withTitle: "OK")
                if a.runModal() == .alertFirstButtonReturn {
                    if FileManager.default.fileExists(atPath: dmg) {
                        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: dmg)])
                    } else {
                        NSWorkspace.shared.open(URL(fileURLWithPath: "/Applications"))
                    }
                }
            }
        }
    }
}

/// The update sheet must never block quitting (macOS 15.4+ API; older systems rely on endSheet before terminate).
private struct AllowsTermination: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 15.4, *) { content.presentationPreventsAppTermination(false) } else { content }
    }
}

/// Animated update sheet: version, notes, Update / Later, real stage text, fallback with Show in Finder.
struct UpdateSheet: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject var up = Updater.shared
    @Environment(\.dismiss) private var dismiss
    let info: UpdateInfo
    @State private var appear = false

    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                PulseMark().frame(width: 34, height: 34).foregroundStyle(p.txt)
                    .scaleEffect(appear ? 1 : 0.6).rotationEffect(.degrees(appear ? 0 : -30))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Update available").font(theme.ui(17, .bold)).foregroundStyle(p.txt)
                    Text("\(up.currentVersion)  →  \(info.version)").font(theme.num(12)).foregroundStyle(p.t2)
                }
            }
            ScrollView {
                Text(info.notes.isEmpty ? "Improvements and fixes." : info.notes)
                    .font(theme.ui(12.5)).foregroundStyle(p.t2).frame(maxWidth: .infinity, alignment: .leading)
            }.frame(maxHeight: 160)
            if up.installing {
                VStack(alignment: .leading, spacing: 6) {
                    GeometryReader { g in
                        Capsule().fill(p.surf3).overlay(alignment: .leading) {
                            Capsule().fill(theme.accent).frame(width: g.size.width * (up.stage == .downloading ? up.progress : 1))
                                .opacity(up.stage == .downloading ? 1 : 0.75)
                        }
                    }.frame(height: 5)
                    HStack(spacing: 6) {
                        if up.stage != .downloading { ProgressView().controlSize(.mini) }
                        Text(up.stageText).font(theme.num(11)).foregroundStyle(p.t3)
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            } else if up.stage == .failed {
                VStack(alignment: .leading, spacing: 10) {
                    Text(up.stageText).font(theme.ui(12)).foregroundStyle(p.t2).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Spacer()
                        Button("Close") { up.reset(); dismiss() }.buttonStyle(.plain).font(theme.ui(13, .semibold)).foregroundStyle(p.t2).padding(.horizontal, 12)
                        if up.fallbackDMG != nil {
                            Button { up.showInFinder() } label: {
                                Text("Show in Finder").font(theme.ui(13, .bold)).foregroundStyle(p.light ? .white : p.bg)
                                    .padding(.horizontal, 20).frame(height: 34).background(Capsule().fill(theme.accent))
                            }.buttonStyle(PressStyle())
                        }
                    }
                }
                .transition(.opacity)
            } else {
                HStack {
                    if let s = up.status, s.hasPrefix("No Mac build") { Text(s).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(2) }
                    Spacer()
                    Button("Later") { dismiss() }.buttonStyle(.plain).font(theme.ui(13, .semibold)).foregroundStyle(p.t2).padding(.horizontal, 12)
                    Button { withAnimation(Motion.spring) { up.install(info) } } label: {
                        Text("Update").font(theme.ui(13, .bold)).foregroundStyle(p.light ? .white : p.bg)
                            .padding(.horizontal, 20).frame(height: 34).background(Capsule().fill(theme.accent))
                    }.buttonStyle(PressStyle())
                }
            }
        }
        .padding(24)
        .frame(width: 420)
        .background(p.surf)
        .opacity(appear ? 1 : 0).offset(y: appear ? 0 : 14)
        .onAppear { withAnimation(Motion.bouncy) { appear = true } }
        .animation(Motion.spring, value: up.stage)
        .modifier(AllowsTermination())
    }
}

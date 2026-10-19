import AppKit
import SwiftUI

/// In-app updates from the GitHub releases of `Engine.updateRepo`.
/// Download DMG -> mount -> replace the running app -> relaunch. Fallback: open the DMG in Finder.
@MainActor
final class Updater: ObservableObject {
    static let shared = Updater()

    @Published var checking = false
    @Published var status: String?
    @Published var progress: Double = 0
    @Published var installing = false
    /// The releases API said 401/404 and no key is saved: show the small "Add access key" row.
    @AppStorage("updateNeedsKey") var needsKey = false

    var currentVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0" }

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

    func install(_ info: UpdateInfo) {
        guard let asset = info.assets.first(where: { $0.name.lowercased().hasSuffix(".dmg") }) else {
            status = "No Mac build in this release"; return
        }
        installing = true
        progress = 0
        let dmg = FileManager.default.temporaryDirectory.appendingPathComponent(asset.name)
        try? FileManager.default.removeItem(at: dmg)
        let poll = Task { @MainActor in
            while !Task.isCancelled {
                if let p = Engine.core.downloadProgress(id: "update") { withAnimation(.linear(duration: 0.2)) { self.progress = Double(p) } }
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
        }
        Task {
            defer { poll.cancel() }
            do {
                _ = try await Engine.core.downloadUpdate(assetApiUrl: asset.apiUrl, token: Keychain.get(), dest: dmg.path)
                progress = 1
                try replaceAndRelaunch(dmg: dmg)
            } catch {
                installing = false
                if FileManager.default.fileExists(atPath: dmg.path) {
                    status = "Opened the installer"
                    NSWorkspace.shared.open(dmg)
                } else {
                    status = "Update failed: \(Engine.message(error))"
                    if let u = URL(string: asset.browserUrl) { NSWorkspace.shared.open(u) }
                }
            }
        }
    }

    /// Mounts the DMG, then a detached helper waits for this process to exit, swaps the bundle and relaunches.
    private func replaceAndRelaunch(dmg: URL) throws {
        let mount = FileManager.default.temporaryDirectory.appendingPathComponent("v4-mount-\(Int(Date().timeIntervalSince1970))")
        try FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
        try run("/usr/bin/hdiutil", ["attach", dmg.path, "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path])
        let apps = (try? FileManager.default.contentsOfDirectory(atPath: mount.path))?.filter { $0.hasSuffix(".app") } ?? []
        guard let appName = apps.first else {
            _ = try? run("/usr/bin/hdiutil", ["detach", mount.path, "-force"])
            throw CoreError.Invalid(msg: "no app in disk image")
        }
        let src = mount.appendingPathComponent(appName).path
        var target = Bundle.main.bundlePath
        if target.contains("/AppTranslocation/") || !FileManager.default.isWritableFile(atPath: (target as NSString).deletingLastPathComponent) {
            target = "/Applications/\(appName)"
        }
        let pid = ProcessInfo.processInfo.processIdentifier
        let script = """
        while kill -0 \(pid) 2>/dev/null; do sleep 0.2; done
        rm -rf "\(target).old"
        if [ -d "\(target)" ]; then mv "\(target)" "\(target).old"; fi
        if /usr/bin/ditto "\(src)" "\(target)"; then rm -rf "\(target).old"; else mv "\(target).old" "\(target)"; fi
        /usr/bin/xattr -dr com.apple.quarantine "\(target)" 2>/dev/null
        /usr/bin/hdiutil detach "\(mount.path)" -force >/dev/null 2>&1
        /usr/bin/open "\(target)"
        """
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", script]
        try p.run()
        NSApp.terminate(nil)
    }

    @discardableResult
    private func run(_ exe: String, _ args: [String]) throws -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: exe)
        p.arguments = args
        let out = Pipe()
        p.standardOutput = out
        p.standardError = out
        try p.run()
        p.waitUntilExit()
        let s = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        if p.terminationStatus != 0 { throw CoreError.Invalid(msg: s) }
        return s
    }
}

/// Animated update sheet: version, notes, Update / Later, progress.
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
                            Capsule().fill(theme.accent).frame(width: g.size.width * up.progress)
                        }
                    }.frame(height: 5)
                    Text(up.progress >= 1 ? "Installing…" : "\(Int(up.progress * 100))%").font(theme.num(11)).foregroundStyle(p.t3)
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            } else {
                HStack {
                    if let s = up.status, s.hasPrefix("Update failed") { Text(s).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(2) }
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
        .animation(Motion.spring, value: up.installing)
    }
}

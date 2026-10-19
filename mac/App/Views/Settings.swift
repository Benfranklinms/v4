import AppKit
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @ObservedObject var up = Updater.shared
    @State private var health: StreamingHealth?
    @State private var testing = false
    @State private var cacheMb: UInt32 = 256
    @State private var keyEntry = false
    @State private var key = ""
    @State private var cookie = ""
    @State private var signingIn = false
    @State private var lastfm: LastfmState?
    @State private var lfToken: String?
    @State private var sizes: (cache: UInt64, images: UInt64, downloads: UInt64, data: UInt64) = (0, 0, 0, 0)
    private let cacheSteps: [UInt32] = [0, 128, 256, 512, 1024]

    var body: some View {
        let p = theme.p
        Page {
            Text("Settings").font(theme.ui(30, .bold)).foregroundStyle(p.txt).padding(.bottom, 6)

            group("Appearance") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Theme").font(theme.ui(12, .semibold)).foregroundStyle(p.t2)
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(96), spacing: 10), count: 6), alignment: .leading, spacing: 10) {
                        ForEach(Palette.all) { pal in
                            Button { withAnimation(.easeInOut(duration: 0.5)) { theme.themeId = pal.id } } label: {
                                VStack(spacing: 6) {
                                    ZStack {
                                        RoundedRectangle(cornerRadius: 10).fill(pal.bg)
                                        HStack(spacing: 4) { Circle().fill(pal.txt).frame(width: 10); Circle().fill(pal.acc).frame(width: 10); Circle().fill(pal.surf4).frame(width: 10) }
                                    }
                                    .frame(width: 96, height: 54)
                                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(theme.themeId == pal.id ? p.txt : p.line3, lineWidth: theme.themeId == pal.id ? 2 : 0.6))
                                    Text(pal.name).font(theme.ui(11)).foregroundStyle(p.t2).lineLimit(1)
                                }
                            }.buttonStyle(PressStyle(scale: 0.96))
                        }
                    }
                }
                row("Follow system appearance") { Toggle2(on: $theme.followSystem) }
                row("Pure black") { Toggle2(on: $theme.pureBlack) }
                slider("Text size", value: $theme.textScale, range: 0.85...1.30)
                Text("The quick brown fox").font(theme.ui(15, .semibold)).foregroundStyle(p.txt).animation(Motion.spring, value: theme.textScale)
                slider("Lyrics size", value: $theme.lyricsScale, range: 0.8...1.5)
                VStack(alignment: .leading, spacing: 8) {
                    Text("Font").font(theme.ui(12, .semibold)).foregroundStyle(p.t2)
                    FlowChips(items: fontItems,
                              selected: theme.fontId) { id in withAnimation(.easeInOut(duration: 0.35)) { theme.fontId = id } }
                    HStack {
                        Chip(label: "Import font…", icon: "plus") { importFont() }
                        if theme.fontId == "custom" {
                            Text("Apply to numbers").font(theme.ui(12)).foregroundStyle(p.t2).padding(.leading, 12)
                            Toggle2(on: $theme.customNumbers)
                        }
                    }
                }
            }

            group("Playback") {
                row("Normalise loudness") { Toggle2(on: $player.normalize) }
                row("Autoplay similar when the queue ends") { Toggle2(on: Binding(get: { player.autoplay }, set: { player.autoplay = $0; Engine.core.setAutoplay(on: $0) })) }
            }

            group("Storage") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Stream cache").font(theme.ui(13, .medium)).foregroundStyle(p.txt)
                        Spacer()
                        Text(cacheMb == 0 ? "Off" : cacheMb >= 1024 ? "1 GB" : "\(cacheMb) MB").font(theme.num(12)).foregroundStyle(p.t2)
                    }
                    Slider(value: Binding(get: { Double(cacheSteps.firstIndex(of: cacheMb) ?? 2) },
                                          set: { cacheMb = cacheSteps[Int($0.rounded())]; try? Engine.core.setCacheLimitMb(mb: cacheMb); refreshSizes() }),
                           in: 0...4, step: 1).tint(theme.accent)
                }
                HStack(spacing: 18) {
                    stat("Cache", sizes.cache); stat("Images", sizes.images); stat("Downloads", sizes.downloads); stat("Data", sizes.data)
                    Spacer()
                    Chip(label: "Clear cache", icon: "trash") {
                        Engine.core.clearCache(); URLCache.shared.removeAllCachedResponses(); refreshSizes(); nav.show("Cache cleared")
                    }
                }
            }

            group("Streaming") {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Streaming health").font(theme.ui(13, .medium)).foregroundStyle(p.txt)
                        Text(healthText).font(theme.ui(11.5)).foregroundStyle(p.t2).lineLimit(2)
                    }
                    Spacer()
                    Button { retry() } label: {
                        Group { if testing { ProgressView().controlSize(.small) } else { Text("Retry").font(theme.ui(12, .semibold)) } }
                            .foregroundStyle(p.txt).padding(.horizontal, 14).frame(height: 28).background(Capsule().fill(p.surf3))
                    }.buttonStyle(PressStyle())
                }
                if Engine.core.isSignedIn() {
                    row("Signed in") { Chip(label: "Sign out") { Task { try? await Engine.core.signOut(); nav.show("Signed out") } } }
                } else {
                    HStack {
                        SecureField("Optional: paste a music.youtube.com cookie to sync your library", text: $cookie).textFieldStyle(.plain)
                            .font(theme.ui(12)).padding(.horizontal, 12).frame(height: 30).background(Capsule().fill(p.surf2))
                        Chip(label: signingIn ? "…" : "Sign in") { signIn() }
                    }
                }
            }

            if let lf = lastfm, lf.available {
                group("Last.fm") {
                    if lf.username.isEmpty {
                        row(lfToken == nil ? "Scrobble what you play" : "Approved it in the browser?") {
                            Chip(label: lfToken == nil ? "Connect" : "Finish", selected: true) { lastfmStep() }
                        }
                    } else {
                        row("Scrobbling as \(lf.username)\(lf.pending > 0 ? " · \(lf.pending) queued" : "")") {
                            Toggle2(on: Binding(get: { lf.enabled }, set: { try? Engine.core.lastfmSetEnabled(on: $0); lastfm = Engine.core.lastfmState() }))
                        }
                        row("") { Chip(label: "Disconnect") { try? Engine.core.lastfmSignOut(); lastfm = Engine.core.lastfmState() } }
                    }
                }
            }

            group("About") {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("iTune").font(theme.ui(13, .semibold)).foregroundStyle(p.txt)
                        Text("Version \(up.currentVersion) · core \(Engine.core.version())").font(theme.num(11.5)).foregroundStyle(p.t2)
                    }
                    Spacer()
                    Button { Task { await up.check(manual: true) } } label: {
                        Group { if up.checking { ProgressView().controlSize(.small) } else { Text("Check for updates").font(theme.ui(12, .semibold)) } }
                            .foregroundStyle(p.txt).padding(.horizontal, 14).frame(height: 28).background(Capsule().fill(p.surf3))
                    }.buttonStyle(PressStyle())
                }
                .contentShape(Rectangle())
                .contextMenu {
                    Button(Keychain.has ? "Replace access key" : "Set access key") { keyEntry = true }
                    if Keychain.has { Button("Remove access key") { Keychain.set(nil) } }
                }
                if let s = up.status { Text(s).font(theme.ui(11.5)).foregroundStyle(p.t3).transition(.opacity) }
                if up.needsKey && !Keychain.has {
                    Button { keyEntry = true } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Add access key").font(theme.ui(13, .medium)).foregroundStyle(p.txt)
                                Text("Needed to keep receiving updates").font(theme.ui(11.5)).foregroundStyle(p.t2)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(p.t3)
                        }
                    }.buttonStyle(PressStyle(scale: 0.99)).transition(.opacity)
                }
            }
        }
        .task { health = Engine.core.streamingHealth(); cacheMb = Engine.core.cacheLimitMb(); lastfm = Engine.core.lastfmState(); refreshSizes() }
        .sheet(isPresented: $keyEntry) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Access key").font(theme.ui(16, .bold)).foregroundStyle(theme.p.txt)
                SecureField("Key", text: $key).textFieldStyle(.roundedBorder)
                HStack {
                    Spacer()
                    Button("Cancel") { keyEntry = false; key = "" }
                    Button("Save") {
                        Keychain.set(key); key = ""; keyEntry = false
                        Task { await up.check(manual: true) }
                    }.keyboardShortcut(.defaultAction)
                }
            }.padding(22).frame(width: 380).background(theme.p.surf)
        }
        .animation(Motion.spring, value: up.needsKey)
        .animation(Motion.spring, value: up.status)
    }

    private var fontItems: [(String, String)] {
        var items: [(String, String)] = Theme.fonts
        if !theme.customFamily.isEmpty { items.append(("custom", theme.customFamily)) }
        return items
    }

    private var healthText: String {
        guard let h = health else { return "—" }
        if h.failures == 0 { return "Working · \(h.workingClient)" }
        return "\(h.failures) recent failures · \(h.lastError)"
    }

    private func lastfmStep() {
        Task {
            if let tok = lfToken {
                do { let n = try await Engine.core.lastfmCompleteAuth(token: tok); lfToken = nil; nav.show("Scrobbling as \(n)") }
                catch { nav.show("Not approved yet") }
            } else {
                do {
                    let a = try await Engine.core.lastfmBeginAuth()
                    lfToken = a.token
                    if let u = URL(string: a.url) { NSWorkspace.shared.open(u) }
                } catch { nav.show("Couldn't reach Last.fm") }
            }
            lastfm = Engine.core.lastfmState()
        }
    }

    private func retry() {
        testing = true
        Task { health = (try? await Engine.core.streamingSelftest()) ?? Engine.core.streamingHealth(); testing = false }
    }

    private func signIn() {
        let c = cookie.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !c.isEmpty else { return }
        signingIn = true
        Task {
            do { try await Engine.core.signInCookie(cookie: c); _ = try? await Engine.core.syncLibrary(); cookie = ""; nav.show("Signed in") }
            catch { nav.show(Engine.message(error)) }
            signingIn = false
        }
    }

    private func importFont() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "ttf")!, UTType(filenameExtension: "otf")!]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let u = panel.url {
            if let fam = theme.importFont(from: u) { nav.show("Using \(fam)") } else { nav.show("Couldn't read that font") }
        }
    }

    private func refreshSizes() {
        let h = Engine.core.streamingHealth()
        let dl = ((try? Engine.core.downloads()) ?? []).reduce(UInt64(0)) { $0 + $1.bytes }
        let imgs = UInt64(URLCache.shared.currentDiskUsage)
        let data = dirSize(Engine.supportDir.appendingPathComponent("core"))
        sizes = (h.cacheBytes, imgs, dl, data > h.cacheBytes + dl ? data - h.cacheBytes - dl : data)
    }

    private func dirSize(_ u: URL) -> UInt64 {
        guard let e = FileManager.default.enumerator(at: u, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var n: UInt64 = 0
        for case let f as URL in e { n += UInt64((try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        return n
    }

    private func stat(_ label: String, _ bytes: UInt64) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)).font(theme.num(13)).foregroundStyle(theme.p.txt)
            Text(label).font(theme.ui(11)).foregroundStyle(theme.p.t3)
        }
    }

    @ViewBuilder private func group<C: View>(_ title: String, @ViewBuilder _ c: () -> C) -> some View {
        Text(title.uppercased()).font(theme.ui(10.5, .bold)).tracking(1).foregroundStyle(theme.p.t3).padding(.top, 26).padding(.bottom, 8)
        VStack(alignment: .leading, spacing: 14) { c() }
            .padding(18).frame(maxWidth: 760, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(theme.p.surf))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.p.line2, lineWidth: 0.6))
    }

    private func row<R: View>(_ title: String, @ViewBuilder _ r: () -> R) -> some View {
        HStack { Text(title).font(theme.ui(13, .medium)).foregroundStyle(theme.p.txt); Spacer(); r() }
    }

    private func slider(_ title: String, value: Binding<Double>, range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).font(theme.ui(13, .medium)).foregroundStyle(theme.p.txt)
                Spacer()
                Text("\(Int((value.wrappedValue * 100).rounded()))%").font(theme.num(12)).foregroundStyle(theme.p.t2)
            }
            Slider(value: value, in: range, step: 0.05).tint(theme.accent)
        }
    }
}

struct FlowChips: View {
    let items: [(String, String)]
    let selected: String
    let onPick: (String) -> Void
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(items, id: \.0) { it in Chip(label: it.1, selected: it.0 == selected) { onPick(it.0) } }
        }
    }
}

import AppKit
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var lib: LibraryStore
    @EnvironmentObject var prefs: Prefs
    @ObservedObject var up = Updater.shared
    @AppStorage("themesExpanded") private var themesExpanded = false
    @State private var nameDraft = ""
    @State private var loaded = false
    @State private var signedIn = false
    @State private var showDiag = false
    @State private var confirmRemoveAll = false
    @State private var hasKey = false
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
            LargeTitle(text: "Settings").padding(.bottom, 6)

            group("Account") {
                Button { nav.sheet = .account } label: {
                    HStack(spacing: 12) {
                        ZStack {
                            Circle().fill(p.surf3)
                            if let a = account.info?.avatar, !a.isEmpty { ArtView(url: a, size: 36, circle: true) }
                            else { Image(systemName: "person.fill").foregroundStyle(p.t2) }
                        }.frame(width: 36, height: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(account.info?.name ?? (account.signedIn ? "Google account" : "Sign in with Google")).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt)
                            Text(account.signedIn ? (account.info?.handle ?? "Signed in") : "Sync likes and playlists. Optional.").font(theme.ui(11.5)).foregroundStyle(p.t2)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(p.t3)
                    }.contentShape(Rectangle())
                }.buttonStyle(PressStyle(scale: 0.99)).accessibilityIdentifier("settings_account")
            }

            group("Profile") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("What should I call you?").font(theme.ui(13, .medium)).foregroundStyle(p.txt)
                    HStack(spacing: 10) {
                        TextField(prefs.firstName(account: account.info) == "there" ? "Your name" : prefs.firstName(account: account.info), text: $nameDraft)
                            .textFieldStyle(.plain).font(theme.ui(14)).foregroundStyle(p.txt)
                            .padding(.horizontal, 12).frame(height: 34).frame(maxWidth: 320)
                            .background(RoundedRectangle(cornerRadius: 9).fill(p.surf2))
                            .overlay(RoundedRectangle(cornerRadius: 9).stroke(p.line3, lineWidth: 0.6))
                            .onSubmit { prefs.setDisplayName(nameDraft); nav.show(nameDraft.isEmpty ? "Using your account name" : "Hi, \(nameDraft)") }
                            .accessibilityIdentifier("settings_name")
                        if nameDraft != prefs.displayName {
                            Chip(label: "Save", selected: true) { prefs.setDisplayName(nameDraft); nav.show(nameDraft.isEmpty ? "Using your account name" : "Hi, \(nameDraft)") }
                                .transition(.opacity)
                        }
                    }
                    Text("Used in the Home greeting. Leave empty to use your account name.").font(theme.ui(11.5)).foregroundStyle(p.t2)
                }
                .animation(Motion.spring, value: nameDraft != prefs.displayName)
            }

            group("Appearance") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Theme").font(theme.ui(12, .semibold)).foregroundStyle(p.t2)
                        Spacer()
                        Button { withAnimation(Motion.spring) { themesExpanded.toggle() } } label: {
                            HStack(spacing: 4) {
                                Text(themesExpanded ? "Show less" : "See all \(Palette.all.count)").font(theme.ui(12, .semibold)).lineLimit(1).fixedSize()
                                Image(systemName: "chevron.down").font(.system(size: 10, weight: .bold))
                                    .rotationEffect(.degrees(themesExpanded ? 180 : 0))
                            }.foregroundStyle(p.txt)
                        }
                        .buttonStyle(PressStyle()).accessibilityIdentifier("themes_toggle")
                    }
                    // adaptive grid; collapsed shows the first row (6), "See all" springs open the rest
                    let shown = themesExpanded ? Palette.all : Array(Palette.all.prefix(6)) + (Palette.all.prefix(6).contains { $0.id == theme.themeId } ? [] : Palette.all.filter { $0.id == theme.themeId })
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 140), spacing: 10)], alignment: .leading, spacing: 12) {
                        ForEach(shown) { pal in
                            ThemeTile(pal: pal, selected: theme.themeId == pal.id) { withAnimation(.easeInOut(duration: 0.5)) { theme.themeId = pal.id } }
                                .transition(.scale(scale: 0.9).combined(with: .opacity))
                        }
                    }
                    .accessibilityIdentifier("themes_grid")
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
                row("Autoplay similar when the queue ends") { Toggle2(on: Binding(get: { player.autoplay }, set: { player.setAutoplay($0) })) }
            }

            group("Storage") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Stream cache").font(theme.ui(13, .medium)).foregroundStyle(p.txt)
                        Spacer()
                        Text(cacheMb == 0 ? "Off" : cacheMb >= 1024 ? "1 GB" : "\(cacheMb) MB").font(theme.num(12)).foregroundStyle(p.t2)
                    }
                    Slider(value: Binding(get: { Double(cacheSteps.firstIndex(of: cacheMb) ?? 2) },
                                          set: { cacheMb = cacheSteps[Int($0.rounded())]; let mb = cacheMb; Task { _ = await Engine.off { try Engine.core.setCacheLimitMb(mb: mb) }; await refreshSizes() } }),
                           in: 0...4, step: 1).tint(theme.accent)
                }
                HStack(spacing: 18) {
                    stat("Cache", sizes.cache); stat("Images", sizes.images); stat("Downloads", sizes.downloads); stat("Data", sizes.data)
                    Spacer()
                    Chip(label: "Clear cache", icon: "trash") {
                        URLCache.shared.removeAllCachedResponses()
                        Task { _ = await Engine.off { Engine.core.clearCache() }; await refreshSizes(); nav.show("Cache cleared") }
                    }
                }
                row("Downloads (\(lib.downloaded.count) songs)") {
                    Chip(label: "Remove all downloads", icon: "trash") { confirmRemoveAll = true }
                        .disabled(lib.downloaded.isEmpty)
                }
            }

            group("Playback health") {
                HStack(spacing: 10) {
                    Circle().fill(healthDot).frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(healthTitle).font(theme.ui(13, .medium)).foregroundStyle(p.txt)
                        if healthBroken { Text("Tap Fix to try again").font(theme.ui(11.5)).foregroundStyle(p.t2) }
                    }
                    Spacer()
                    if healthBroken || testing {
                        Button { retry() } label: {
                            Group { if testing { ProgressView().controlSize(.small) } else { Text("Fix").font(theme.ui(12, .semibold)) } }
                                .foregroundStyle(p.txt).padding(.horizontal, 14).frame(height: 28).background(Capsule().fill(p.surf3))
                        }.buttonStyle(PressStyle()).accessibilityIdentifier("health_fix")
                    }
                }
                DisclosureGroup(isExpanded: $showDiag) {
                    VStack(alignment: .leading, spacing: 4) {
                        if let h = health {
                            Text("Client: \(h.workingClient.isEmpty ? "—" : h.workingClient) · recent failures: \(h.failures)").font(theme.num(11)).foregroundStyle(p.t3)
                            if !h.lastError.isEmpty { Text(h.lastError).font(theme.num(11)).foregroundStyle(p.t3).lineLimit(3) }
                        }
                        Button("Open Diagnostics") { nav.go(.diagnostics) }.buttonStyle(.plain).font(theme.ui(12, .semibold)).foregroundStyle(p.t2)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6)
                } label: { Text("Diagnostics").font(theme.ui(12, .medium)).foregroundStyle(p.t2) }
            }

            if let lf = lastfm, lf.available {
                group("Last.fm") {
                    if lf.username.isEmpty {
                        row(lfToken == nil ? "Scrobble what you play" : "Approved it in the browser?") {
                            Chip(label: lfToken == nil ? "Connect" : "Finish", selected: true) { lastfmStep() }
                        }
                    } else {
                        row("Scrobbling as \(lf.username)\(lf.pending > 0 ? " · \(lf.pending) queued" : "")") {
                            Toggle2(on: Binding(get: { lf.enabled }, set: { v in Task { lastfm = await Engine.off { try Engine.core.lastfmSetEnabled(on: v); return Engine.core.lastfmState() } ?? lastfm } }))
                        }
                        row("") { Chip(label: "Disconnect") { Task { lastfm = await Engine.off { try Engine.core.lastfmSignOut(); return Engine.core.lastfmState() } ?? lastfm } } }
                    }
                }
            }

            group("About") {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("iTune").font(theme.ui(13, .semibold)).foregroundStyle(p.txt)
                        Text("Version \(up.currentVersion) · core \(Engine.coreVersion)").font(theme.num(11.5)).foregroundStyle(p.t2)
                    }
                    Spacer()
                    Button { Task { await up.check(manual: true) } } label: {
                        Group { if up.checking { ProgressView().controlSize(.small) } else { Text("Check for updates").font(theme.ui(12, .semibold)).lineLimit(1).fixedSize() } }
                            .foregroundStyle(p.txt).padding(.horizontal, 14).frame(height: 28).background(Capsule().fill(p.surf3))
                    }.buttonStyle(PressStyle())
                }
                .contentShape(Rectangle())
                .contextMenu {
                    Button(hasKey ? "Replace access key" : "Set access key") { keyEntry = true }
                    if hasKey { Button("Remove access key") { Keychain.set(nil); hasKey = false } }
                }
                if let s = up.status { Text(s).font(theme.ui(11.5)).foregroundStyle(p.t3).transition(.opacity) }
                if up.needsKey && !hasKey {
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
        // Everything the page shows is read off the main thread (the page renders at once, values fade in).
        .onAppear { nameDraft = prefs.displayName }
        .task {
            let r = await Engine.off { (Engine.core.streamingHealth(), Engine.core.cacheLimitMb(), Engine.core.lastfmState(), Engine.core.isSignedIn()) }
            if let r { health = r.0; cacheMb = r.1; lastfm = r.2; signedIn = r.3 }
            loaded = true
            hasKey = await Engine.off { Keychain.has } ?? false
            await refreshSizes()
        }
        .confirmationDialog("Remove all downloads?", isPresented: $confirmRemoveAll) {
            Button("Remove all", role: .destructive) { lib.removeAllDownloads() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("You can undo this for a few seconds.") }
        .accessibilityIdentifier("settings")
        .sheet(isPresented: $keyEntry) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Access key").font(theme.ui(16, .bold)).foregroundStyle(theme.p.txt)
                SecureField("Key", text: $key).textFieldStyle(.roundedBorder)
                HStack {
                    Spacer()
                    Button("Cancel") { keyEntry = false; key = "" }
                    Button("Save") {
                        Keychain.set(key); hasKey = !key.isEmpty; key = ""; keyEntry = false
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

    private var healthBroken: Bool { (health?.failures ?? 0) >= 3 && (health?.lastErrorAt ?? 0) > (health?.lastOkAt ?? 0) }
    private var healthTitle: String { !loaded || testing ? "Checking…" : healthBroken ? "Can't reach streams, tap to fix" : "Playback ready" }
    private var healthDot: Color { !loaded || testing ? theme.p.t3 : healthBroken ? theme.p.t2.opacity(0.5) : theme.p.txt }

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
            lastfm = await Engine.off { Engine.core.lastfmState() } ?? lastfm
        }
    }

    private func retry() {
        testing = true
        Task {
            if let h = try? await Engine.core.streamingSelftest() { health = h } else { health = await Engine.off { Engine.core.streamingHealth() } ?? health }
            testing = false
        }
    }

    private func signIn() {
        let c = cookie.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !c.isEmpty else { return }
        signingIn = true
        Task {
            if await account.signIn(cookie: c) { cookie = ""; nav.show("Signed in") } else { nav.show("Couldn't sign in with that cookie") }
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

    /// Directory sizes are walked on a background thread.
    private func refreshSizes() async {
        let imgs = UInt64(URLCache.shared.currentDiskUsage)
        let r = await Engine.off { () -> (UInt64, UInt64, UInt64) in
            let h = Engine.core.streamingHealth()
            let dl = ((try? Engine.core.downloads()) ?? []).reduce(UInt64(0)) { $0 + $1.bytes }
            let data = SettingsView.dirSize(Engine.supportDir.appendingPathComponent("core"))
            return (h.cacheBytes, dl, data)
        }
        if let r { sizes = (r.0, imgs, r.1, r.2 > r.0 + r.1 ? r.2 - r.0 - r.1 : r.2) }
    }

    nonisolated static func dirSize(_ u: URL) -> UInt64 {
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
        HStack { Text(title).font(theme.ui(13, .medium)).foregroundStyle(theme.p.txt).fixedSize(horizontal: false, vertical: true); Spacer(minLength: 12); r() }
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

/// Theme swatch with its name (label always shown, never clipped).
struct ThemeTile: View {
    @EnvironmentObject var theme: Theme
    let pal: Palette
    let selected: Bool
    let action: () -> Void
    var body: some View {
        let p = theme.p
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10).fill(pal.bg)
                    HStack(spacing: 4) { Circle().fill(pal.txt).frame(width: 10); Circle().fill(pal.acc).frame(width: 10); Circle().fill(pal.surf4).frame(width: 10) }
                }
                .frame(height: 54)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? p.txt : p.line3, lineWidth: selected ? 2 : 0.6))
                Text(pal.name).font(theme.ui(11)).foregroundStyle(selected ? p.txt : p.t2).lineLimit(1).minimumScaleFactor(0.85)
                    .frame(height: 16)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(scale: 0.96))
        .accessibilityLabel(pal.name)
        .accessibilityIdentifier("theme_\(pal.id)")
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

import AppKit
import AVKit
import SwiftUI

@main
struct ItuneApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var theme = Theme.shared
    @StateObject private var player = Player.shared
    @StateObject private var nav = Nav.shared
    @StateObject private var lib = LibraryStore.shared
    @StateObject private var account = AccountStore.shared

    var body: some Scene {
        WindowGroup("iTune", id: "main") {
            RootView()
                .environmentObject(theme)
                .environmentObject(player)
                .environmentObject(nav)
                .environmentObject(lib)
                .environmentObject(account)
                .frame(minWidth: 800, minHeight: 560)
                .preferredColorScheme(theme.p.light ? .light : .dark)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button("Settings…") { nav.go(.settings) }.keyboardShortcut(",", modifiers: .command)
            }
            CommandMenu("Controls") {
                Button(player.isPlaying ? "Pause" : "Play") { player.toggle() }.keyboardShortcut(.space, modifiers: [])
                Button("Next") { player.next() }.keyboardShortcut(.rightArrow, modifiers: .command)
                Button("Previous") { player.prev() }.keyboardShortcut(.leftArrow, modifiers: .command)
                Divider()
                Button("Now Playing") { nav.toggleNowPlaying() }.keyboardShortcut("p", modifiers: [.command, .shift])
                Button("Queue") { nav.toggleQueue() }.keyboardShortcut("u", modifiers: [.command, .option])
                Button("Lyrics") { nav.showLyrics.toggle(); if nav.showLyrics { nav.openNowPlaying() } }.keyboardShortcut("l", modifiers: [.command, .option])
                Button("Ask Isa") { nav.go(.home); nav.focusIsa += 1 }.keyboardShortcut("k", modifiers: .command)
                Button("Mini Player") { MiniPlayerPanel.shared.toggle() }.keyboardShortcut("m", modifiers: [.command, .option])
                Divider()
                Button("Account") { nav.sheet = .account }
            }
        }

        MenuBarExtra {
            MenuBarView()
                .environmentObject(theme)
                .environmentObject(player)
        } label: {
            Image("MenuGlyph")
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        CrashReporter.install()
        Theme.registerBundledFonts()
        // Lean storage: images capped at 64 MB on disk / 32 MB in memory; stream cache LRU-trimmed by the core.
        URLCache.shared = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 64 * 1024 * 1024)
        Task.detached(priority: .userInitiated) {
            _ = Engine.core
            try? Engine.core.setCacheLimitMb(mb: Engine.core.cacheLimitMb())
            await MainActor.run {
                LibraryStore.shared.refresh()
                if AccountStore.shared.signedIn { AccountStore.shared.load() }
                if let c = CrashReporter.takePending() { Nav.shared.pendingCrash = c }
            }
        }
        if Smoke.enabled {
            Task { @MainActor in await Smoke.run() }
        } else {
            Task { @MainActor in await Updater.shared.checkOnLaunch() }
        }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationWillTerminate(_ notification: Notification) { Nav.shared.flushPending() }
}

enum Section: Hashable {
    case home, search, library, stats, downloads, importer, settings, diagnostics
}

enum Route: Hashable {
    case album(String), artist(String), playlist(String), isa(String), mix(String)
}

enum SheetKind: Identifiable, Equatable {
    case account
    case newPlaylist([Track])
    case rename(Playlist)
    case crash(String)
    var id: String {
        switch self {
        case .account: return "account"
        case .newPlaylist: return "new"
        case .rename(let p): return "rename-\(p.id)"
        case .crash: return "crash"
        }
    }
    static func == (a: SheetKind, b: SheetKind) -> Bool { a.id == b.id }
}

struct Snack: Equatable {
    let id = UUID()
    let text: String
    var action: String? = nil
    static func == (a: Snack, b: Snack) -> Bool { a.id == b.id }
}

/// Navigation state (sidebar section + push stack), overlays, sheets and toasts.
@MainActor
final class Nav: ObservableObject {
    static let shared = Nav()
    @Published var section: Section = .home
    @Published var path: [Route] = []
    @Published var snack: Snack?
    @Published var updateInfo: UpdateInfo?
    @Published var sheet: SheetKind?
    @Published var showNowPlaying = false
    @Published var showLyrics = false
    @Published var queueOpen = UserDefaults.standard.bool(forKey: "queueOpen") { didSet { UserDefaults.standard.set(queueOpen, forKey: "queueOpen") } }
    @Published var queueWidth: Double = (UserDefaults.standard.object(forKey: "queueWidth") as? Double) ?? 320 { didSet { UserDefaults.standard.set(queueWidth, forKey: "queueWidth") } }
    @Published var focusIsa = 0
    /// Page scroll requests (anchor ids such as "shelf_1"), used by keyboard nav and the smoke test.
    @Published var scrollTarget: String?
    @Published var searchToken = 0
    var lastSearch: SearchResults?
    /// Import link handed to the Import page (smoke test / drag-and-drop).
    var pendingImportURL: String?
    @Published var pendingCrash: String? { didSet { if let c = pendingCrash { sheet = .crash(c) } } }
    var mixes: [String: [Track]] = [:]
    private var undoAction: (() -> Void)?
    private var pendingCommit: (() -> Void)?
    private var commitTask: Task<Void, Never>?

    func go(_ s: Section) { withAnimation(Motion.spring) { closeNowPlaying(); section = s; path = [] } }
    /// Navigation always happens in the main window: Now Playing collapses first (no overlay pushes).
    func push(_ r: Route) {
        if showNowPlaying {
            withAnimation(Motion.spring) { showNowPlaying = false }
            Task { try? await Task.sleep(nanoseconds: 180_000_000); withAnimation(Motion.spring) { self.path.append(r) } }
        } else {
            withAnimation(Motion.spring) { path.append(r) }
        }
    }
    func pop() { withAnimation(Motion.spring) { _ = path.popLast() } }
    func openNowPlaying() { guard Player.shared.current != nil else { return }; withAnimation(Motion.spring) { showNowPlaying = true } }
    func closeNowPlaying() { if showNowPlaying { withAnimation(Motion.spring) { showNowPlaying = false } } }
    func toggleNowPlaying() { showNowPlaying ? closeNowPlaying() : openNowPlaying() }
    func toggleQueue() { withAnimation(Motion.spring) { queueOpen.toggle() } }

    func show(_ m: String) {
        undoAction = nil
        let s = Snack(text: m)
        withAnimation(Motion.spring) { snack = s }
        Task { try? await Task.sleep(nanoseconds: 2_400_000_000); if self.snack == s { withAnimation(Motion.spring) { self.snack = nil } } }
    }

    /// Destructive action with Undo: `apply` now, `commit` after 5 s (or when the next undoable starts).
    func undoable(_ msg: String, apply: () -> Void, undo: @escaping () -> Void, commit: @escaping () -> Void) {
        commitTask?.cancel()
        pendingCommit?()
        apply()
        var done = false
        let c = { if !done { done = true; commit() } }
        pendingCommit = c
        undoAction = { if !done { done = true; undo() } }
        let s = Snack(text: msg, action: "Undo")
        withAnimation(Motion.spring) { snack = s }
        commitTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            if Task.isCancelled { return }
            c()
            self.pendingCommit = nil
            if self.snack == s { withAnimation(Motion.spring) { self.snack = nil } }
        }
    }

    func performSnackAction() {
        commitTask?.cancel()
        undoAction?()
        undoAction = nil
        pendingCommit = nil
        withAnimation(Motion.spring) { snack = nil }
    }

    func flushPending() { commitTask?.cancel(); pendingCommit?(); pendingCommit = nil }
}

/// Layout: content runs full-bleed under a floating glass sidebar (left) and a floating glass player bar (bottom);
/// the queue panel docks on the right; Now Playing covers the content area below the title bar.
struct RootView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav

    static let sidebarWidth: CGFloat = 232
    static let barHeight: CGFloat = 72

    var body: some View {
        let p = theme.p
        GeometryReader { geo in
            let narrow = geo.size.width < 1000
            let sideW: CGFloat = narrow ? 200 : RootView.sidebarWidth
            HStack(spacing: 0) {
                ZStack(alignment: .bottom) {
                    // content: scrolls under the sidebar and player glass
                    ZStack {
                        content
                            .transition(.opacity.combined(with: .offset(y: 8)))
                            .id(contentKey)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .environment(\.contentInsets, EdgeInsets(top: 0, leading: sideW + 20, bottom: player.current == nil ? 24 : RootView.barHeight + 28, trailing: 0))

                    HStack(spacing: 0) {
                        Sidebar().frame(width: sideW)
                            .glassCard(18)
                            .padding(.leading, 10).padding(.vertical, 10)
                            .padding(.top, 28)
                        Spacer(minLength: 0)
                    }

                    if nav.showNowPlaying && player.current != nil {
                        NowPlayingView()
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                            .zIndex(3)
                    }

                    if player.current != nil && !nav.showNowPlaying {
                        PlayerBar()
                            .frame(height: RootView.barHeight)
                            .padding(.horizontal, 12).padding(.bottom, 12)
                            .padding(.leading, sideW + 20)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                            .zIndex(4)
                    }

                    if let s = nav.snack {
                        SnackView(snack: s)
                            .padding(.bottom, (player.current == nil || nav.showNowPlaying ? 24 : RootView.barHeight + 28))
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                            .zIndex(5)
                    }
                }
                if nav.queueOpen {
                    QueueSidePanel()
                        .frame(width: CGFloat(min(420, max(280, nav.queueWidth))))
                        .glassCard(18)
                        .padding(.vertical, 10).padding(.trailing, 10).padding(.top, 28)
                        .padding(.bottom, player.current == nil ? 10 : RootView.barHeight + 22)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .background(ArtBackdrop(strength: 0.5))
        }
        .background(p.bg.ignoresSafeArea())
        .animation(Motion.spring, value: player.current?.id)
        .animation(Motion.spring, value: nav.showNowPlaying)
        .animation(Motion.spring, value: nav.queueOpen)
        .animation(Motion.spring, value: contentKey)
        .animation(.easeInOut(duration: 0.5), value: theme.themeId)
        .onExitCommand { nav.closeNowPlaying() }
        .sheet(item: Binding(get: { nav.updateInfo.map(UpdateBox.init) }, set: { nav.updateInfo = $0?.info })) { box in
            UpdateSheet(info: box.info).environmentObject(theme)
        }
        .sheet(item: $nav.sheet) { kind in
            Group {
                switch kind {
                case .account: AccountSheet()
                case .newPlaylist(let tracks): NamePlaylistSheet(tracks: tracks, edit: nil)
                case .rename(let p): NamePlaylistSheet(tracks: [], edit: p)
                case .crash(let text): CrashSheet(text: text)
                }
            }
            .environmentObject(theme).environmentObject(player).environmentObject(nav)
            .environmentObject(LibraryStore.shared).environmentObject(AccountStore.shared)
        }
    }

    private var contentKey: String { "\(nav.section)-\(nav.path.count)" }

    @ViewBuilder private var content: some View {
        if let r = nav.path.last {
            switch r {
            case .album(let id): AlbumView(id: id)
            case .artist(let id): ArtistView(id: id)
            case .playlist(let id): PlaylistView(id: id)
            case .isa(let prompt): IsaView(prompt: prompt)
            case .mix(let title): TrackListPage(title: title, subtitle: "Mix", art: nav.mixes[title]?.first?.thumb ?? "", tracks: nav.mixes[title], source: title)
            }
        } else {
            switch nav.section {
            case .home: HomeView()
            case .search: SearchView()
            case .library: LibraryView()
            case .stats: StatsView()
            case .downloads: DownloadsView()
            case .importer: ImportView()
            case .settings: SettingsView()
            case .diagnostics: DiagnosticsView()
            }
        }
    }
}

private struct ContentInsetsKey: EnvironmentKey { static let defaultValue = EdgeInsets() }
extension EnvironmentValues {
    /// Space the floating glass sidebar / player bar cover; pages pad their scroll content by it.
    var contentInsets: EdgeInsets {
        get { self[ContentInsetsKey.self] }
        set { self[ContentInsetsKey.self] = newValue }
    }
}

struct UpdateBox: Identifiable { let info: UpdateInfo; var id: String { info.tag } }

struct SnackView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let snack: Snack
    var body: some View {
        let p = theme.p
        HStack(spacing: 14) {
            Text(snack.text).font(theme.ui(13, .semibold)).foregroundStyle(p.bg).lineLimit(1)
            if let a = snack.action {
                Button(a) { nav.performSnackAction() }.buttonStyle(.plain)
                    .font(theme.ui(13, .bold)).foregroundStyle(p.bg)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Capsule().fill(p.bg.opacity(0.12)))
                    .accessibilityIdentifier("snack_action")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Capsule().fill(p.txt))
        .shadow(color: .black.opacity(0.3), radius: 12, y: 6)
    }
}

struct Sidebar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    @EnvironmentObject var account: AccountStore
    @State private var query = ""

    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                PulseMark().frame(width: 16, height: 16).foregroundStyle(p.txt)
                Text("iTune").font(theme.ui(14, .bold)).foregroundStyle(p.txt)
                Spacer()
                Button { nav.sheet = .account } label: {
                    ZStack {
                        Circle().fill(p.surf3)
                        if let a = account.info?.avatar, !a.isEmpty { ArtView(url: a, size: 24, circle: true) }
                        else { Text(String((account.info?.name ?? "B").prefix(1)).uppercased()).font(theme.ui(11, .bold)).foregroundStyle(p.txt) }
                    }.frame(width: 24, height: 24)
                }.buttonStyle(PressStyle()).help("Account").accessibilityLabel("Account")
            }
            .padding(.top, 14).padding(.horizontal, 14).padding(.bottom, 10)
            // search field
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").font(.system(size: 11, weight: .semibold)).foregroundStyle(p.t3)
                TextField("Search", text: $query).textFieldStyle(.plain).font(theme.ui(12.5))
                    .onSubmit { SearchCache.pending = query; nav.go(.search); nav.searchToken += 1; query = "" }
            }
            .padding(.horizontal, 10).frame(height: 28)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.06)))
            .padding(.horizontal, 10).padding(.bottom, 8)
            item("Home", "house", .home)
            item("Search", "magnifyingglass", .search)
            item("Stats", "chart.bar", .stats)
            Text("LIBRARY").font(theme.ui(10, .bold)).tracking(1).foregroundStyle(p.t3).padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 4)
            item("Library", "books.vertical", .library)
            item("Downloads", "arrow.down.circle", .downloads)
            item("Import", "square.and.arrow.down", .importer)
            HStack {
                Text("PLAYLISTS").font(theme.ui(10, .bold)).tracking(1).foregroundStyle(p.t3)
                Spacer()
                Button { nav.sheet = .newPlaylist([]) } label: { Image(systemName: "plus").font(.system(size: 11, weight: .bold)).foregroundStyle(p.t2) }
                    .buttonStyle(PressStyle()).help("New playlist").accessibilityLabel("New playlist")
            }.padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 4)
            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    row("Liked Songs", "heart.fill") { nav.push(.playlist("liked")) }
                    ForEach(Array(lib.visiblePlaylists.enumerated()), id: \.element.id) { i, pl in
                        row(pl.title, lib.pinned.contains(pl.id) ? "pin.fill" : "music.note.list") { nav.push(.playlist(pl.id)) }
                            .contextMenu { PlaylistMenuItems(p: pl) }
                            .enter(i)
                    }
                }
            }
            Spacer(minLength: 6)
            item("Settings", "gearshape", .settings)
                .padding(.bottom, 10)
        }
    }

    private func item(_ title: String, _ icon: String, _ s: Section) -> some View {
        let p = theme.p
        let on = nav.section == s && nav.path.isEmpty
        return Button { nav.go(s) } label: {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 13, weight: .medium)).frame(width: 18)
                Text(title).font(theme.ui(13, on ? .semibold : .medium))
                Spacer()
            }
            .foregroundStyle(on ? p.txt : p.t2)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 8).fill(on ? Color.white.opacity(0.09) : .clear))
            .padding(.horizontal, 8)
        }
        .buttonStyle(PressStyle(scale: 0.97))
        .accessibilityIdentifier("side_\(title)")
    }

    private func row(_ title: String, _ icon: String, _ a: @escaping () -> Void) -> some View {
        let p = theme.p
        return Button(action: a) {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 11)).frame(width: 18).foregroundStyle(p.t3)
                Text(title).font(theme.ui(12.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                Spacer()
            }.padding(.horizontal, 18).padding(.vertical, 5).contentShape(Rectangle())
        }.buttonStyle(PressStyle(scale: 0.98))
    }
}

/// Floating glass player bar at the bottom (content scrolls underneath it).
struct PlayerBar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav

    var body: some View {
        let p = theme.p
        GeometryReader { g in
            let compact = g.size.width < 760
            HStack(spacing: 12) {
                if let t = player.current {
                    Button { nav.toggleNowPlaying() } label: {
                        HStack(spacing: 10) {
                            ArtView(url: t.thumb, image: player.artwork, size: 46, radius: 8)
                            VStack(alignment: .leading, spacing: 2) {
                                Marquee(text: t.title, font: theme.ui(13, .semibold), color: p.txt)
                                Text(t.artists).font(theme.ui(11.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                            }
                        }
                        .frame(width: compact ? 170 : 240, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle(scale: 0.98))
                    .help("Now Playing")
                    .accessibilityIdentifier("player_bar")
                    Spacer(minLength: 8)
                    VStack(spacing: 2) {
                        Transport(size: 14)
                        Scrubber(width: compact ? 200 : min(420, g.size.width * 0.34))
                    }
                    Spacer(minLength: 8)
                    HStack(spacing: 4) {
                        if !compact { VolumeSlider().frame(width: 90) }
                        GlassIconButton(name: "quote.bubble", size: 13, on: nav.showLyrics, help: "Lyrics") {
                            nav.showLyrics.toggle()
                            if nav.showLyrics { nav.openNowPlaying() }
                        }
                        GlassIconButton(name: "list.bullet", size: 13, on: nav.queueOpen, help: "Queue (⌥⌘U)") { nav.toggleQueue() }
                        AirPlayButton().frame(width: 26, height: 26).help("AirPlay")
                    }
                }
            }
            .padding(.horizontal, 14)
            .frame(width: g.size.width, height: g.size.height)
        }
        .glassSurface(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// Title that scrolls when it doesn't fit (no truncated "…re to the Rain").
struct Marquee: View {
    let text: String
    let font: Font
    let color: Color
    var height: CGFloat = 18
    @State private var w: CGFloat = 0
    @State private var box: CGFloat = 0
    @State private var go = false
    var body: some View {
        GeometryReader { g in
            let over = w > g.size.width + 1
            Text(text).font(font).foregroundStyle(color).lineLimit(1).fixedSize()
                .background(GeometryReader { t in Color.clear.onAppear { w = t.size.width }.onChange(of: text) { _, _ in w = t.size.width } })
                .offset(x: over && go && !Motion.reduce ? -(w - g.size.width) : 0)
                .animation(over && !Motion.reduce ? .linear(duration: Double(max(1, w - g.size.width)) / 25).delay(1.5).repeatForever(autoreverses: true) : .default, value: go)
                .onAppear { box = g.size.width; go = true }
                .onChange(of: text) { _, _ in go = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { go = true } }
        }
        .frame(height: height)
        .clipped()
    }
}

struct VolumeSlider: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: player.volume < 0.01 ? "speaker.slash.fill" : "speaker.wave.2.fill").font(.system(size: 10)).foregroundStyle(theme.p.t3)
            Slider(value: Binding(get: { Double(player.volume) }, set: { player.setVolume(Float($0)) }), in: 0...1)
                .controlSize(.mini).tint(theme.p.txt)
        }.help("Volume")
    }
}

struct AirPlayButton: NSViewRepresentable {
    func makeNSView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.isRoutePickerButtonBordered = false
        return v
    }
    func updateNSView(_ nsView: AVRoutePickerView, context: Context) {}
}

struct CrashSheet: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let text: String
    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 12) {
            Text("iTune closed unexpectedly last time").font(theme.ui(17, .bold)).foregroundStyle(p.txt)
            Text("Copy this and send it over so it can be fixed.").font(theme.ui(12.5)).foregroundStyle(p.t2)
            ScrollView { Text(text).font(.system(size: 10.5, design: .monospaced)).foregroundStyle(p.t2).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
                .frame(height: 260).padding(10).background(RoundedRectangle(cornerRadius: 10).fill(p.surf2))
            HStack {
                Spacer()
                SecondaryButton(title: "Close") { nav.sheet = nil }
                PrimaryButton(title: "Copy diagnostics", icon: "doc.on.doc") {
                    NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
                    nav.sheet = nil; nav.show("Copied")
                }
            }
        }
        .padding(22).frame(width: 560).background(p.bg)
    }
}

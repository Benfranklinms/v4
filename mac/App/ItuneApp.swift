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
                .environmentObject(Prefs.shared)
                .frame(minWidth: 760, minHeight: 560)
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
                Button("Lyrics") { nav.toggleLyrics() }.keyboardShortcut("l", modifiers: [.command, .option])
                Button("Ask Isa") { nav.toggleIsa() }.keyboardShortcut("k", modifiers: .command)
                Button("Search") { nav.focusSearchField() }.keyboardShortcut("f", modifiers: .command)
                Button("Go to Search") { nav.focusSearchField() }.keyboardShortcut("l", modifiers: .command)
                Divider()
                // (labels must not read Nav state: the scene body would re-render the window with stale values)
                Button("Back") { nav.back() }.keyboardShortcut("[", modifiers: .command)
                Button("Forward") { nav.forward() }.keyboardShortcut("]", modifiers: .command)
                Button("Toggle Sidebar") { nav.toggleSidebar() }.keyboardShortcut("s", modifiers: [.command, .control])
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
    func applicationWillFinishLaunching(_ notification: Notification) {
        // Cold-launch fast path: read the saved Home feed straight from the DB file while the core starts.
        let dir = Engine.supportDir.appendingPathComponent("core").path
        let tz = Engine.tzMinutes
        HomeCache.peekTask = Task.detached(priority: .userInitiated) { homeCachedPeek(dataDir: dir, tzOffsetMin: tz) }
    }
    func applicationDidFinishLaunching(_ notification: Notification) {
        CrashReporter.install()
        Theme.registerBundledFonts()
        MenuTracker.install()
        LibraryStore.shared.startWatching()
        Launch.finishedAt = CACurrentMediaTime()
        DispatchQueue.global(qos: .utility).async { ArtDisk.trim() }
        // Esc closes Now Playing, then the queue (not while typing or a sheet is up)
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
            // ↑ / ↓ move through the search dropdown
            if e.keyCode == 125 || e.keyCode == 126 {
                return MainActor.assumeIsolated { () -> NSEvent? in
                    guard SearchModel.shared.dropdown, Nav.shared.sheet == nil else { return e }
                    SearchModel.shared.move(e.keyCode == 125 ? 1 : -1)
                    return nil
                }
            }
            guard e.keyCode == 53 else { return e }
            return MainActor.assumeIsolated { () -> NSEvent? in
                let nav = Nav.shared
                // Isa card first, then the search dropdown (both while typing in their fields)
                if nav.sheet == nil && nav.isaOpen { nav.closeIsa(); return nil }
                if nav.sheet == nil && SearchModel.shared.dropdown { SearchModel.shared.closeDropdown(); return nil }
                guard nav.sheet == nil, nav.showNowPlaying || nav.queueOpen,
                      !(NSApp.keyWindow?.firstResponder is NSTextView) else { return e }
                nav.closeTop()
                return nil
            }
        }
        // Reduce Transparency / Reduce Motion changes re-render the glass and motion right away.
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification, object: nil, queue: .main) { _ in
            Theme.shared.objectWillChange.send()
        }
        // Lean storage: images capped at 64 MB on disk / 32 MB in memory; stream cache LRU-trimmed by the core.
        URLCache.shared = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 64 * 1024 * 1024)
        Task.detached(priority: .userInitiated) {
            _ = Engine.core
            try? Engine.core.setCacheLimitMb(mb: Engine.core.cacheLimitMb())
            let session = Engine.core.restoreSession()
            let name = Engine.core.getSetting(key: "displayName") ?? ""
            await MainActor.run {
                if let s = session { Player.shared.restore(s) }
                Prefs.shared.displayName = name
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
    func applicationWillTerminate(_ notification: Notification) {
        Nav.shared.flushPending()
        Player.shared.saveSession(sync: true)
    }
}

enum Launch { nonisolated(unsafe) static var finishedAt: CFTimeInterval = 0 }

/// Small app prefs kept in the core settings (shared with Android's data model).
@MainActor
final class Prefs: ObservableObject {
    static let shared = Prefs()
    /// "What should I call you?" (empty = use the account name).
    @Published var displayName = ""
    func setDisplayName(_ n: String) {
        let v = n.trimmingCharacters(in: .whitespacesAndNewlines)
        displayName = v
        Engine.bg { try Engine.core.setSetting(key: "displayName", value: v) }
    }
    /// First name for greetings: the chosen name, else the account's first name.
    func firstName(account: AccountInfo?) -> String {
        if !displayName.isEmpty { return displayName }
        let n = account?.name ?? ""
        return n.isEmpty ? "there" : String(n.split(separator: " ").first ?? "there")
    }
}

enum Section: Hashable, CaseIterable {
    case home, search, library, stats, downloads, importer, settings, diagnostics
}

enum Route: Hashable {
    case album(String), artist(String), playlist(String), isa(String), mix(String)
}

struct NavSnap: Equatable { let section: Section; let path: [Route] }

struct CarouselTarget: Equatable { let shelf: String; let index: Int }

/// Header shown at once when a tile is opened (before the page loads).
struct RouteSeed { let title: String; let subtitle: String; let thumb: String }

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

/// Navigation state: sidebar section + one push stack per section (each section's stack and scroll state
/// survive navigation), overlays, sheets and toasts.
@MainActor
final class Nav: ObservableObject {
    static let shared = Nav()
    @Published var section: Section = .home
    @Published var stacks: [Section: [Route]] = [:]
    @Published var snack: Snack?
    @Published var updateInfo: UpdateInfo?
    @Published var sheet: SheetKind?
    @Published var showNowPlaying = false
    @Published var showLyrics = false
    @Published var queueOpen = UserDefaults.standard.bool(forKey: "queueOpen") { didSet { UserDefaults.standard.set(queueOpen, forKey: "queueOpen") } }
    @Published var queueWidth: Double = (UserDefaults.standard.object(forKey: "queueWidth") as? Double) ?? 320 { didSet { UserDefaults.standard.set(queueWidth, forKey: "queueWidth") } }
    @Published var focusIsa = 0
    @Published var focusSearch = 0
    /// Isa Command card, centred over a blurred backdrop.
    @Published var isaOpen = false
    /// Narrow icon rail instead of the full sidebar (remembered).
    @Published var sidebarCollapsed = UserDefaults.standard.bool(forKey: "sidebarCollapsed") { didSet { UserDefaults.standard.set(sidebarCollapsed, forKey: "sidebarCollapsed") } }
    /// Back / forward history (section + that section's stack).
    @Published private(set) var backStack: [NavSnap] = []
    @Published private(set) var fwdStack: [NavSnap] = []
    /// Page scroll requests (anchor ids such as "shelf_1"), used by keyboard nav and the smoke test.
    @Published var scrollTarget: String?
    @Published var searchToken = 0
    /// Horizontal carousel scroll request (smoke / keyboard).
    @Published var carouselTarget: CarouselTarget?
    var lastSearch: SearchResults?
    /// Import link handed to the Import page (smoke test / drag-and-drop).
    var pendingImportURL: String?
    @Published var pendingCrash: String? { didSet { if let c = pendingCrash { sheet = .crash(c) } } }
    var mixes: [String: [Track]] = [:]
    var seeds: [String: RouteSeed] = [:]
    private var undoAction: (() -> Void)?
    private var pendingCommit: (() -> Void)?
    private var commitTask: Task<Void, Never>?

    /// The current section's stack.
    var path: [Route] {
        get { stacks[section] ?? [] }
        set { stacks[section] = newValue }
    }

    /// Lyrics are "open" only while Now Playing shows them (the player-bar button reflects exactly this).
    var lyricsOpen: Bool { showNowPlaying && showLyrics }

    // MARK: history
    var snap: NavSnap { NavSnap(section: section, path: path) }
    var canBack: Bool { !backStack.isEmpty }
    var canForward: Bool { !fwdStack.isEmpty }
    /// Call before a navigation change: the current place goes on the back stack, forward is cleared.
    private func record() {
        let s = snap
        if backStack.last != s { backStack.append(s) }
        if backStack.count > 60 { backStack.removeFirst(backStack.count - 60) }
        fwdStack = []
    }
    // Every navigation mutates history and section in ONE animated transaction: withAnimation flushes
    // changes made just before it, which rendered the sidebar with the previous page.
    func back() {
        guard !backStack.isEmpty else { return }
        apply { let s = self.backStack.removeLast(); self.fwdStack.append(self.snap); return s }
    }
    func forward() {
        guard !fwdStack.isEmpty else { return }
        apply { let s = self.fwdStack.removeLast(); self.backStack.append(self.snap); return s }
    }
    private func apply(_ pick: @escaping () -> NavSnap) {
        Enter.navigatedAt = Date()
        withAnimation(Motion.spring) {
            if showNowPlaying { showNowPlaying = false }
            let s = pick()
            stacks[s.section] = s.path
            section = s.section
        }
    }

    func go(_ s: Section) {
        Enter.navigatedAt = Date()
        withAnimation(Motion.spring) {
            if section != s || !(stacks[s] ?? []).isEmpty { record() }
            if showNowPlaying { showNowPlaying = false }
            if section == s { stacks[s] = [] } else { section = s }
        }
    }
    /// Sidebar playlist: Library section with that playlist on top.
    func openFromSidebar(playlist id: String) {
        Enter.navigatedAt = Date()
        if showNowPlaying { withAnimation(Motion.spring) { showNowPlaying = false } }
        record()
        section = .library
        stacks[.library] = [.playlist(id)]
    }
    func toggleSidebar() { withAnimation(Motion.spring) { sidebarCollapsed.toggle() } }

    // MARK: Isa card + search
    func openIsa() {
        closeNowPlaying()
        SearchModel.shared.closeDropdown()
        withAnimation(Motion.bouncy) { isaOpen = true }
        focusIsa += 1
    }
    func closeIsa() { if isaOpen { withAnimation(Motion.spring) { isaOpen = false } } }
    func toggleIsa() { isaOpen ? closeIsa() : openIsa() }
    /// ⌘F / ⌘L: focus the top-bar search field.
    func focusSearchField() {
        closeIsa()
        closeNowPlaying()
        focusSearch += 1
    }
    /// Navigation always happens in the main window: Now Playing collapses first (no overlay pushes).
    func push(_ r: Route) {
        Enter.navigatedAt = Date()
        record()
        if showNowPlaying {
            withAnimation(Motion.spring) { showNowPlaying = false }
            Task { try? await Task.sleep(nanoseconds: 180_000_000); self.path.append(r) }
        } else {
            path.append(r)
        }
    }
    func pop() { guard !path.isEmpty else { return }; record(); _ = path.popLast() }
    func openNowPlaying() { guard Player.shared.current != nil else { return }; withAnimation(Motion.spring) { showNowPlaying = true } }
    func closeNowPlaying() { if showNowPlaying { withAnimation(Motion.spring) { showNowPlaying = false } } }
    func toggleNowPlaying() { showNowPlaying ? closeNowPlaying() : openNowPlaying() }
    func toggleQueue() { withAnimation(Motion.spring) { queueOpen.toggle() } }
    func closeQueue() { if queueOpen { withAnimation(Motion.spring) { queueOpen = false } } }
    /// Player-bar lyrics button: opens Now Playing on the lyrics, or closes them.
    func toggleLyrics() {
        if lyricsOpen {
            withAnimation(Motion.spring) { showLyrics = false; showNowPlaying = false }
        } else {
            withAnimation(Motion.spring) { showLyrics = true }
            openNowPlaying()
        }
    }
    /// Esc: the Isa card, then Now Playing, then the queue.
    func closeTop() { if isaOpen { closeIsa() } else if showNowPlaying { closeNowPlaying() } else { closeQueue() } }

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

    /// An Undo window is open (its change is applied in the UI but not written yet).
    var undoPending: Bool { pendingCommit != nil }

    func flushPending() { commitTask?.cancel(); pendingCommit?(); pendingCommit = nil }
}

/// Measured layout (smoke checks: player bar centred, queue inside the window).
enum LayoutProbe {
    nonisolated(unsafe) static var playerBar: CGRect = .zero
    nonisolated(unsafe) static var detail: CGRect = .zero
    nonisolated(unsafe) static var queue: CGRect = .zero
    nonisolated(unsafe) static var lyricsButton: CGRect = .zero
    nonisolated(unsafe) static var sidebar: CGRect = .zero
    nonisolated(unsafe) static var toggle: CGRect = .zero
    nonisolated(unsafe) static var topBar: CGRect = .zero
    nonisolated(unsafe) static var searchGroup: CGRect = .zero
    nonisolated(unsafe) static var searchPill: CGRect = .zero
    nonisolated(unsafe) static var isaButton: CGRect = .zero
    nonisolated(unsafe) static var isaCard: CGRect = .zero
}

struct FrameProbe: View {
    let set: (CGRect) -> Void
    var body: some View {
        GeometryReader { g in
            Color.clear
                .onAppear { set(g.frame(in: .global)) }
                .onChange(of: g.frame(in: .global)) { _, f in set(f) }
        }
    }
}

/// One section's push stack. The root and every page below the top stay alive (hidden, not torn down), so
/// Back returns to the same vertical scroll position, carousel offsets and loaded data.
struct SectionStack: View {
    @EnvironmentObject var nav: Nav
    let section: Section

    var body: some View {
        let path = nav.stacks[section] ?? []
        ZStack {
            root
                .opacity(path.isEmpty ? 1 : 0)
                .allowsHitTesting(path.isEmpty)
                .accessibilityHidden(!path.isEmpty)
            ForEach(Array(path.enumerated()), id: \.offset) { i, r in
                let top = i == path.count - 1
                destination(r)
                    .id(r)
                    .opacity(top ? 1 : 0)
                    .allowsHitTesting(top)
                    .accessibilityHidden(!top)
                    .transition(.opacity.combined(with: .offset(x: Motion.reduce ? 0 : 28)))
            }
        }
        .animation(Motion.spring, value: path.count)
    }

    @ViewBuilder private var root: some View {
        switch section {
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

    @ViewBuilder private func destination(_ r: Route) -> some View {
        switch r {
        case .album(let id): AlbumView(id: id)
        case .artist(let id): ArtistView(id: id)
        case .playlist(let id): PlaylistView(id: id)
        case .isa(let prompt): IsaView(prompt: prompt)
        case .mix(let title): TrackListPage(title: title, subtitle: "Mix", art: nav.mixes[title]?.first?.thumb ?? "", tracks: nav.mixes[title], source: title)
        }
    }
}

private struct ContentInsetsKey: EnvironmentKey { static let defaultValue = EdgeInsets() }
extension EnvironmentValues {
    /// Extra page padding (kept for pages; the split view and safe-area padding handle sidebar / player bar).
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

/// Floating glass player bar at the bottom of the detail column (content scrolls underneath it).
struct PlayerBar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav

    var body: some View {
        let p = theme.p
        GeometryReader { g in
            let compact = g.size.width < 700
            ZStack {
                // centred transport + progress, independent of the side groups' widths
                VStack(spacing: 2) {
                    Transport(size: 14)
                    Scrubber(width: compact ? max(140, g.size.width - 420) : min(420, g.size.width * 0.34))
                }
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
                            .frame(width: compact ? 150 : 220, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressStyle(scale: 0.98))
                        .help("Now Playing")
                        .accessibilityIdentifier("player_bar")
                        Spacer(minLength: 8)
                        HStack(spacing: 4) {
                            if !compact { VolumeSlider().frame(width: 84) }
                            GlassIconButton(name: "quote.bubble", size: 13, on: nav.lyricsOpen, help: "Lyrics") { nav.toggleLyrics() }
                                .background(FrameProbe { LayoutProbe.lyricsButton = $0 })
                                .accessibilityIdentifier("bar_lyrics")
                            GlassIconButton(name: "list.bullet", size: 13, on: nav.queueOpen, help: "Queue (⌥⌘U)") { nav.toggleQueue() }
                                .accessibilityIdentifier("bar_queue")
                            AirPlayButton().frame(width: 26, height: 26).help("AirPlay")
                        }
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

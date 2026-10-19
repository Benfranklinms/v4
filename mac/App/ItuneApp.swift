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
                Button("Ask Isa") { nav.go(.home); nav.focusIsa += 1 }.keyboardShortcut("k", modifiers: .command)
                Button("Search") { nav.focusSearch += 1 }.keyboardShortcut("f", modifiers: .command)
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
        Launch.finishedAt = CACurrentMediaTime()
        DispatchQueue.global(qos: .utility).async { ArtDisk.trim() }
        // Esc closes Now Playing, then the queue (not while typing or a sheet is up)
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
            guard e.keyCode == 53 else { return e }
            return MainActor.assumeIsolated { () -> NSEvent? in
                let nav = Nav.shared
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

    func go(_ s: Section) {
        Enter.navigatedAt = Date()
        withAnimation(Motion.spring) {
            closeNowPlaying()
            if section == s { stacks[s] = [] } else { section = s }
        }
    }
    /// Sidebar playlist: Library section with that playlist on top.
    func openFromSidebar(playlist id: String) {
        Enter.navigatedAt = Date()
        closeNowPlaying()
        section = .library
        stacks[.library] = [.playlist(id)]
    }
    /// Navigation always happens in the main window: Now Playing collapses first (no overlay pushes).
    func push(_ r: Route) {
        Enter.navigatedAt = Date()
        if showNowPlaying {
            withAnimation(Motion.spring) { showNowPlaying = false }
            Task { try? await Task.sleep(nanoseconds: 180_000_000); self.path.append(r) }
        } else {
            path.append(r)
        }
    }
    func pop() { _ = path.popLast() }
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
    /// Esc: Now Playing first, then the queue.
    func closeTop() { if showNowPlaying { closeNowPlaying() } else { closeQueue() } }

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

/// Measured layout (smoke checks: player bar centred, queue inside the window).
enum LayoutProbe {
    nonisolated(unsafe) static var playerBar: CGRect = .zero
    nonisolated(unsafe) static var detail: CGRect = .zero
    nonisolated(unsafe) static var queue: CGRect = .zero
    nonisolated(unsafe) static var lyricsButton: CGRect = .zero
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

/// Apple Music layout: native NavigationSplitView sidebar (system sidebar glass); the detail column starts at
/// the sidebar's trailing edge and clips its content. The queue is an inspector column when the window is wide
/// (>= 1100 pt) and an overlay panel when narrower; the sidebar auto-collapses below 800 pt. The floating glass
/// player bar sits over the bottom of the detail and every scroll view gets a bottom inset of its height.
struct RootView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav

    static let barHeight: CGFloat = 72
    static let inspectorMinWidth: CGFloat = 1100
    static let sidebarCollapseWidth: CGFloat = 800
    @State private var split: NavigationSplitViewVisibility = .all
    @State private var width: CGFloat = 1280

    var body: some View {
        let p = theme.p
        let wide = width >= RootView.inspectorMinWidth
        ZStack {
            NavigationSplitView(columnVisibility: $split) {
                Sidebar()
                    .navigationSplitViewColumnWidth(min: 200, ideal: 224, max: 280)
            } detail: {
                DetailArea(wide: wide)
                    .inspector(isPresented: Binding(get: { nav.queueOpen && wide },
                                                    set: { v in if !v && wide { nav.queueOpen = false } })) {
                        QueueSidePanel()
                            .background(FrameProbe { LayoutProbe.queue = $0 })
                            .inspectorColumnWidth(min: 280, ideal: CGFloat(min(420, max(280, nav.queueWidth))), max: 420)
                    }
            }
            .navigationSplitViewStyle(.balanced)

            if nav.showNowPlaying && player.current != nil {
                NowPlayingView()
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(3)
            }
        }
        .background(GeometryReader { g in
            Color.clear
                .onAppear { width = g.size.width; adapt(g.size.width, initial: true) }
                .onChange(of: g.size.width) { _, w in let old = width; width = w; adapt(w, from: old) }
        })
        .background(p.bg.ignoresSafeArea())
        .animation(Motion.spring, value: player.current?.id)
        .animation(Motion.spring, value: nav.showNowPlaying)
        .animation(Motion.spring, value: nav.queueOpen)
        .animation(.easeInOut(duration: 0.5), value: theme.themeId)
        .onExitCommand { nav.closeTop() }
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
            .environmentObject(LibraryStore.shared).environmentObject(AccountStore.shared).environmentObject(Prefs.shared)
        }
    }

    /// Auto-collapse the sidebar when the window gets narrow (and bring it back when it widens again).
    private func adapt(_ w: CGFloat, from old: CGFloat = 0, initial: Bool = false) {
        let t = RootView.sidebarCollapseWidth
        if (initial || old >= t) && w < t { withAnimation(Motion.spring) { split = .detailOnly } }
        else if !initial && old < t && w >= t { withAnimation(Motion.spring) { split = .all } }
    }
}

/// Detail column: the section stacks, the floating player bar, the narrow-window queue overlay and toasts.
struct DetailArea: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    let wide: Bool
    /// Sections whose stack stays alive while another section is shown (state + scroll survive).
    private static let kept: [Section] = [.home, .search, .library]

    var body: some View {
        let bar = player.current != nil
        ZStack(alignment: .bottom) {
            ZStack {
                ForEach(DetailArea.kept, id: \.self) { s in
                    SectionStack(section: s)
                        .opacity(nav.section == s ? 1 : 0)
                        .allowsHitTesting(nav.section == s)
                        .accessibilityHidden(nav.section != s)
                }
                if !DetailArea.kept.contains(nav.section) {
                    SectionStack(section: nav.section).id(nav.section)
                        .transition(.opacity)
                }
            }
            .safeAreaPadding(.bottom, bar ? RootView.barHeight + 24 : 12)
            .background(FrameProbe { LayoutProbe.detail = $0 })

            if bar && !nav.showNowPlaying {
                PlayerBar()
                    .frame(height: RootView.barHeight)
                    .frame(maxWidth: 1180)
                    .padding(.horizontal, 14).padding(.bottom, 12)
                    .background(FrameProbe { LayoutProbe.playerBar = $0 }.padding(.horizontal, 14).padding(.bottom, 12))
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(4)
            }

            if nav.queueOpen && !wide {
                Color.black.opacity(0.28)
                    .contentShape(Rectangle())
                    .onTapGesture { nav.closeQueue() }
                    .transition(.opacity)
                    .zIndex(5)
                    .accessibilityIdentifier("queue_scrim")
                GeometryReader { g in
                    HStack {
                        Spacer(minLength: 0)
                        QueueSidePanel()
                            .frame(width: min(380, g.size.width - 40))
                            .glassCard(18)
                            .background(FrameProbe { LayoutProbe.queue = $0 })
                            .padding(.top, 10).padding(.trailing, 10)
                            .padding(.bottom, bar ? RootView.barHeight + 22 : 10)
                    }
                }
                .transition(.move(edge: .trailing).combined(with: .opacity))
                .zIndex(6)
            }

            if let s = nav.snack {
                SnackView(snack: s)
                    .padding(.bottom, bar && !nav.showNowPlaying ? RootView.barHeight + 28 : 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(7)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(ArtBackdrop(strength: 0.5))
        .clipped()
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

enum SideItem: Hashable { case section(Section), playlist(String) }

/// Native sidebar (List .sidebar: system glass + selection pill). Search field on top like Apple Music
/// (⌘F focuses it; no separate Search row).
struct Sidebar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    @EnvironmentObject var account: AccountStore
    @State private var query = ""
    @FocusState private var searchFocused: Bool

    private var selection: Binding<SideItem?> {
        Binding(get: {
            if nav.section == .library, case .playlist(let id)? = nav.stacks[.library]?.first { return .playlist(id) }
            return nav.section == .search ? nil : .section(nav.section)
        }, set: { v in
            switch v {
            case .section(let s)?: nav.go(s)
            case .playlist(let id)?: nav.openFromSidebar(playlist: id)
            case nil: break
            }
        })
    }

    var body: some View {
        let p = theme.p
        List(selection: selection) {
            SwiftUI.Section {
                item("Home", "house", .home)
                item("Stats", "chart.bar", .stats)
            }
            SwiftUI.Section {
                item("Library", "books.vertical", .library)
                item("Downloads", "arrow.down.circle", .downloads)
                item("Import", "square.and.arrow.down", .importer)
            } header: { header("Library") }
            SwiftUI.Section {
                Label { Text("Liked Songs").font(theme.ui(13, .medium)).lineLimit(1) } icon: { Image(systemName: "heart.fill") }
                    .tag(SideItem.playlist("liked"))
                ForEach(lib.visiblePlaylists, id: \.id) { pl in
                    Label { Text(pl.title).font(theme.ui(13, .medium)).lineLimit(1) } icon: { Image(systemName: lib.pinned.contains(pl.id) ? "pin.fill" : "music.note.list") }
                        .tag(SideItem.playlist(pl.id))
                        .contextMenu { PlaylistMenuItems(p: pl) }
                }
            } header: {
                HStack {
                    header("Playlists")
                    Spacer()
                    Button { nav.sheet = .newPlaylist([]) } label: { Image(systemName: "plus").font(.system(size: 11, weight: .bold)).foregroundStyle(p.t2) }
                        .buttonStyle(PressStyle()).help("New playlist").accessibilityLabel("New playlist")
                }
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 8) {
                HStack(spacing: 8) {
                    Spacer()
                    Button { nav.sheet = .account } label: {
                        ZStack {
                            Circle().fill(p.surf3)
                            if let a = account.info?.avatar, !a.isEmpty { ArtView(url: a, size: 24, circle: true) }
                            else { Text(String((account.info?.name ?? "B").prefix(1)).uppercased()).font(theme.ui(11, .bold)).foregroundStyle(p.txt) }
                        }.frame(width: 24, height: 24)
                    }.buttonStyle(PressStyle()).help("Account").accessibilityLabel("Account")
                }
                .frame(height: 24)
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").font(.system(size: 11, weight: .semibold)).foregroundStyle(p.t2)
                    TextField("Search", text: $query).textFieldStyle(.plain).font(theme.ui(12.5)).focused($searchFocused)
                        .onSubmit { submit() }
                        .accessibilityIdentifier("sidebar_search")
                    if !query.isEmpty {
                        Button { query = "" } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 11)).foregroundStyle(p.t2) }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 9).frame(height: 28)
                .background(RoundedRectangle(cornerRadius: 8).fill(p.light ? Color.black.opacity(0.06) : Color.white.opacity(0.08)))
            }
            .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 6)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button { nav.go(.settings) } label: {
                Label { Text("Settings").font(theme.ui(13, nav.section == .settings ? .semibold : .medium)) } icon: { Image(systemName: "gearshape") }
                    .foregroundStyle(nav.section == .settings ? p.txt : p.t2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 7).fill(nav.section == .settings ? Color.primary.opacity(0.1) : .clear))
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle(scale: 0.98))
            .padding(.horizontal, 10).padding(.bottom, 10)
            .accessibilityIdentifier("side_Settings")
        }
        .onChange(of: nav.focusSearch) { _, _ in searchFocused = true }
    }

    private func submit() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        SearchCache.pending = q
        if nav.section != .search { nav.go(.search) } else { nav.stacks[.search] = [] }
        nav.searchToken += 1
    }

    /// Apple sidebar section header, in a colour that reads at >= 4.5:1 on every theme.
    private func header(_ t: String) -> some View {
        Text(t).font(theme.ui(11, .semibold)).foregroundStyle(theme.p.t2)
    }

    private func item(_ title: String, _ icon: String, _ s: Section) -> some View {
        Label { Text(title).font(theme.ui(13, .medium)) } icon: { Image(systemName: icon) }
            .tag(SideItem.section(s))
            .accessibilityIdentifier("side_\(title)")
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

import AppKit
import SwiftUI

/// Window layout (0.2.1): an inset glass sidebar (full or a narrow icon rail) on the left; on the right a
/// Spotify-style top bar (back / forward, round Home, centred search pill, Isa orb + avatar) above the
/// section stacks. The queue is a glass column on wide windows (>= 1100 pt) and an overlay when narrower;
/// the sidebar collapses to the rail below 800 pt. Traffic lights, the sidebar toggle and the top bar share
/// one centre line (WindowChrome.centerY). Now Playing and the Isa card cover the whole window.
struct RootView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav

    static let barHeight: CGFloat = 72
    static let topBarHeight: CGFloat = WindowChrome.centerY * 2
    static let inspectorMinWidth: CGFloat = 1100
    static let sidebarCollapseWidth: CGFloat = 800
    static let inset: CGFloat = 8
    @State private var width: CGFloat = 1280

    var body: some View {
        let p = theme.p
        let wide = width >= RootView.inspectorMinWidth
        ZStack {
            HStack(spacing: 0) {
                Sidebar(selected: Sidebar.selection(nav))
                    .frame(width: nav.sidebarCollapsed ? Sidebar.railWidth : Sidebar.fullWidth)
                    .padding(.leading, RootView.inset).padding(.vertical, RootView.inset)
                    .background(FrameProbe { LayoutProbe.sidebar = $0 })
                ZStack(alignment: .top) {
                    HStack(spacing: 0) {
                        DetailArea(wide: wide)
                        if nav.queueOpen && wide {
                            QueueSidePanel()
                                .frame(width: CGFloat(min(420, max(280, nav.queueWidth))))
                                .glassCard(18)
                                .background(FrameProbe { LayoutProbe.queue = $0 })
                                .padding(.trailing, RootView.inset).padding(.bottom, RootView.inset)
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                    .padding(.top, RootView.topBarHeight)
                    // tap outside the search dropdown closes it (above the content, below the bar)
                    DropdownScrim().padding(.top, RootView.topBarHeight)
                    TopBar()
                        .frame(height: RootView.topBarHeight)
                        .zIndex(2)
                }
            }
            .background(ArtBackdrop(strength: 0.5).ignoresSafeArea())
            .allowsHitTesting(!nav.isaOpen)
            .accessibilityHidden(nav.isaOpen)

            if nav.showNowPlaying && player.current != nil {
                NowPlayingView()
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(3)
            }

            if nav.isaOpen {
                IsaOverlay()
                    .zIndex(4)
            }
        }
        .ignoresSafeArea()
        .tint(theme.accent)
        .background(WindowChrome.Accessor())
        .background(GeometryReader { g in
            Color.clear
                .onAppear { width = g.size.width; adapt(g.size.width, initial: true) }
                .onChange(of: g.size.width) { _, w in let old = width; width = w; adapt(w, from: old) }
        })
        .background(p.bg.ignoresSafeArea())
        .animation(Motion.spring, value: player.current?.id)
        .animation(Motion.spring, value: nav.showNowPlaying)
        .animation(Motion.spring, value: nav.queueOpen)
        .animation(Motion.spring, value: nav.sidebarCollapsed)
        .animation(Motion.bouncy, value: nav.isaOpen)
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
            .tint(theme.accent)
        }
    }

    /// Collapse the sidebar to the rail when the window gets narrow (and bring it back when it widens again).
    private func adapt(_ w: CGFloat, from old: CGFloat = 0, initial: Bool = false) {
        let t = RootView.sidebarCollapseWidth
        if (initial || old >= t) && w < t { if !nav.sidebarCollapsed { autoCollapsed = true; nav.toggleSidebar() } }
        else if !initial && old < t && w >= t { if nav.sidebarCollapsed && autoCollapsed { autoCollapsed = false; nav.toggleSidebar() } }
    }
    @State private var autoCollapsed = false
}

/// Invisible layer under the search dropdown: a click anywhere else closes it.
struct DropdownScrim: View {
    @ObservedObject private var search = SearchModel.shared
    var body: some View {
        if search.dropdown {
            Color.black.opacity(0.001)
                .contentShape(Rectangle())
                .onTapGesture { search.closeDropdown() }
                .accessibilityHidden(true)
        }
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
                            .padding(.top, 4).padding(.trailing, 10)
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
        .clipped()
    }
}

/// What the sidebar / top bar bodies last rendered (smoke: they follow navigation).
enum RenderProbe {
    nonisolated(unsafe) static var sidebar = ""
    nonisolated(unsafe) static var topBar = ""
}

// MARK: - Sidebar

enum SideItem: Hashable { case section(Section), playlist(String) }

/// Inset glass sidebar with mono line icons tinted by the theme (selected = accent, others = muted grey mixed
/// with the accent). The selected row is a subtle glass pill; collapsed it is a rail of centred icons with the
/// selected one in a glass circle. Settings sits at the bottom. No search field (search lives in the top bar).
struct Sidebar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore

    static let fullWidth: CGFloat = 224
    static let railWidth: CGFloat = 72

    /// Passed in by RootView (an explicit input, so the selection always re-renders with navigation).
    let selected: SideItem?

    @MainActor static func selection(_ nav: Nav) -> SideItem? {
        if nav.section == .library, case .playlist(let id)? = nav.stacks[.library]?.first { return .playlist(id) }
        return nav.section == .search || nav.section == .diagnostics ? nil : .section(nav.section)
    }

    var body: some View {
        let p = theme.p
        let rail = nav.sidebarCollapsed
        let _ = RenderProbe.sidebar = "\(selected.map { "\($0)" } ?? "none")"
        VStack(spacing: 0) {
            // traffic-light row; the toggle sits on the same centre line (moves to the top bar when collapsed)
            HStack {
                Spacer()
                if !rail {
                    SidebarToggle()
                        .padding(.trailing, 10)
                        .transition(.opacity)
                }
            }
            .frame(height: (WindowChrome.centerY - RootView.inset) * 2)
            ScrollView {
                VStack(alignment: rail ? .center : .leading, spacing: 2) {
                    row("Home", "house", .section(.home))
                    row("Stats", "chart.bar", .section(.stats))
                    header("Library")
                    row("Library", "books.vertical", .section(.library))
                    row("Downloads", "arrow.down.circle", .section(.downloads))
                    row("Import", "tray.and.arrow.down", .section(.importer))
                    if rail {
                        groupDivider
                    } else {
                        HStack {
                            Text("Playlists").font(theme.ui(11, .semibold)).foregroundStyle(p.t2)
                            Spacer()
                            Button { nav.sheet = .newPlaylist([]) } label: {
                                Image(systemName: "plus").font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.sideMuted)
                                    .frame(width: 22, height: 22).contentShape(Rectangle())
                            }
                            .buttonStyle(PressStyle()).help("New playlist").accessibilityLabel("New playlist")
                        }
                        .padding(.leading, 12).padding(.trailing, 4).padding(.top, 14).padding(.bottom, 4)
                    }
                    row("Liked Songs", "heart", .playlist("liked"))
                    ForEach(lib.visiblePlaylists, id: \.id) { pl in
                        row(pl.title, lib.pinned.contains(pl.id) ? "pin" : "music.note.list", .playlist(pl.id))
                            .contextMenu { PlaylistMenuItems(p: pl) }
                    }
                }
                .padding(.horizontal, rail ? 0 : 8)
                .frame(maxWidth: .infinity, alignment: rail ? .center : .leading)
                .padding(.bottom, 8)
            }
            .scrollIndicators(.never)
            Rectangle().fill(p.line3.opacity(0.6)).frame(height: 0.6).padding(.horizontal, rail ? 14 : 14)
            row("Settings", "gearshape", .section(.settings))
                .padding(.horizontal, rail ? 0 : 8)
                .frame(maxWidth: .infinity, alignment: rail ? .center : .leading)
                .padding(.vertical, 8)
        }
        .glassCard(18)
        .accessibilityIdentifier("sidebar")
    }

    private var groupDivider: some View {
        Rectangle().fill(theme.p.line3.opacity(0.6)).frame(width: 28, height: 0.6).padding(.vertical, 8)
    }

    @ViewBuilder private func header(_ t: String) -> some View {
        if nav.sidebarCollapsed {
            groupDivider
        } else {
            Text(t).font(theme.ui(11, .semibold)).foregroundStyle(theme.p.t2)
                .padding(.leading, 12).padding(.top, 14).padding(.bottom, 4)
        }
    }

    private func row(_ title: String, _ icon: String, _ item: SideItem) -> some View {
        SidebarRow(title: title, icon: icon, selected: selected == item, rail: nav.sidebarCollapsed) {
            switch item {
            case .section(let s): nav.go(s)
            case .playlist(let id): nav.openFromSidebar(playlist: id)
            }
        }
        .accessibilityIdentifier("side_\(title)")
    }
}

struct SidebarRow: View {
    @EnvironmentObject var theme: Theme
    let title: String
    let icon: String
    let selected: Bool
    let rail: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        let p = theme.p
        let iconColor = selected ? theme.accent : (hover ? Theme.mix(theme.sideMuted, theme.accent, 0.5) : theme.sideMuted)
        Button(action: { Haptics.tap(); action() }) {
            Group {
                if rail {
                    Image(systemName: icon)
                        .font(.system(size: 15, weight: .regular))
                        .foregroundStyle(iconColor)
                        .frame(width: 40, height: 40)
                        .background { if selected { SelectionGlass(shape: Circle()) } else if hover { Circle().fill(Color.white.opacity(0.05)) } }
                        .contentShape(Circle())
                } else {
                    HStack(spacing: 11) {
                        Image(systemName: icon)
                            .font(.system(size: 14, weight: .regular))
                            .foregroundStyle(iconColor)
                            .frame(width: 20)
                        Text(title).font(theme.ui(13, selected ? .semibold : .medium))
                            .foregroundStyle(selected ? p.txt : p.txt.opacity(0.86)).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 10).frame(height: 32)
                    .background {
                        if selected { SelectionGlass(shape: RoundedRectangle(cornerRadius: 10, style: .continuous)) }
                        else if hover { RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.white.opacity(p.light ? 0.0 : 0.04)) }
                    }
                    .contentShape(Rectangle())
                }
            }
        }
        .buttonStyle(PressStyle(scale: 0.97))
        .help(rail ? title : "")
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
    }
}

/// Subtle glass pill / circle behind the selected sidebar item (never filled with an accent colour).
struct SelectionGlass<S: Shape>: View {
    @EnvironmentObject var theme: Theme
    let shape: S
    var body: some View {
        let p = theme.p
        shape
            .fill(p.light ? Color.black.opacity(0.07) : Color.white.opacity(0.09))
            .overlay(shape.stroke(LinearGradient(colors: [Color.white.opacity(p.light ? 0.5 : 0.16), Color.white.opacity(0.03)], startPoint: .top, endPoint: .bottom), lineWidth: 0.7))
            .shadow(color: .black.opacity(p.light ? 0.06 : 0.25), radius: 4, y: 2)
    }
}

/// Sidebar show / collapse button (on the traffic-light line).
struct SidebarToggle: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    var body: some View {
        Button { nav.toggleSidebar() } label: {
            Image(systemName: "sidebar.left")
                .font(.system(size: 14, weight: .regular))
                .foregroundStyle(theme.sideMuted)
                .frame(width: 30, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(scale: 0.9))
        .help(nav.sidebarCollapsed ? "Show sidebar (⌃⌘S)" : "Collapse sidebar (⌃⌘S)")
        .accessibilityLabel(nav.sidebarCollapsed ? "Show sidebar" : "Collapse sidebar")
        .accessibilityIdentifier("sidebar_toggle")
        .background(FrameProbe { LayoutProbe.toggle = $0 })
    }
}

// MARK: - Top bar

/// Back / forward, round Home, centred search pill (magnifier, field, clear, divider, browse) and on the right
/// the Isa orb and the account avatar. Empty space drags the window.
struct TopBar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var account: AccountStore

    var body: some View {
        let p = theme.p
        let _ = RenderProbe.topBar = "\(nav.section)"
        ZStack {
            WindowDragArea()
            HStack(spacing: 6) {
                if nav.sidebarCollapsed {
                    SidebarToggle().transition(.opacity)
                }
                chevron("chevron.left", enabled: nav.canBack, help: "Back (⌘[)") { nav.back() }
                    .accessibilityIdentifier("top_back")
                chevron("chevron.right", enabled: nav.canForward, help: "Forward (⌘])") { nav.forward() }
                    .accessibilityIdentifier("top_forward")
                Spacer(minLength: 12)
                HStack(spacing: 8) {
                    Button { nav.go(.home) } label: {
                        Image(systemName: nav.section == .home && nav.path.isEmpty ? "house.fill" : "house")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(nav.section == .home ? p.txt : p.t2)
                            .frame(width: 36, height: 36)
                            .background(Circle().fill(p.light ? Color.black.opacity(0.06) : Color.white.opacity(0.07)))
                            .contentShape(Circle())
                    }
                    .buttonStyle(PressStyle(scale: 0.92))
                    .help("Home").accessibilityLabel("Home").accessibilityIdentifier("top_home")
                    SearchPill()
                }
                .frame(maxWidth: 440)
                .background(FrameProbe { LayoutProbe.searchGroup = $0 })
                Spacer(minLength: 12)
                IsaOrbButton()
                Button { nav.sheet = .account } label: {
                    ZStack {
                        Circle().fill(p.light ? Color.black.opacity(0.07) : Color.white.opacity(0.08))
                        if let a = account.info?.avatar, !a.isEmpty { ArtView(url: a, size: 30, circle: true) }
                        else { Text(String((account.info?.name ?? "B").prefix(1)).uppercased()).font(theme.ui(12.5, .bold)).foregroundStyle(p.txt) }
                    }
                    .frame(width: 32, height: 32)
                    .overlay(Circle().stroke(Color.white.opacity(0.08), lineWidth: 0.6))
                    .contentShape(Circle())
                }
                .buttonStyle(PressStyle(scale: 0.92)).help("Account").accessibilityLabel("Account").accessibilityIdentifier("top_avatar")
            }
            .padding(.leading, nav.sidebarCollapsed ? 10 : 18).padding(.trailing, 16)
        }
        .background(FrameProbe { LayoutProbe.topBar = $0 })
    }

    private func chevron(_ name: String, enabled: Bool, help: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(enabled ? theme.p.txt : theme.p.t3)
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(scale: 0.9))
        .disabled(!enabled)
        .help(help)
        .accessibilityLabel(help)
    }
}

/// Round Isa orb button next to the avatar: opens the Isa Command card in the middle of the window.
struct IsaOrbButton: View {
    @EnvironmentObject var nav: Nav
    @State private var hover = false
    var body: some View {
        Button { nav.toggleIsa() } label: {
            IsaOrb(active: false)
                .frame(width: 20, height: 20)
                .frame(width: 32, height: 32)
                .background(Circle().fill(Color.white.opacity(hover ? 0.12 : 0.07)))
                .overlay(Circle().stroke(Color.white.opacity(0.1), lineWidth: 0.6))
                .contentShape(Circle())
        }
        .buttonStyle(PressStyle(scale: 0.9))
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
        .help("Ask Isa (⌘K)")
        .accessibilityLabel("Ask Isa")
        .accessibilityIdentifier("top_isa")
        .background(FrameProbe { LayoutProbe.isaButton = $0 })
    }
}

/// Isa Command card centred in the window over a blurred, dimmed backdrop. Esc, × or a click outside closes it.
struct IsaOverlay: View {
    @EnvironmentObject var nav: Nav
    @State private var shown = false
    var body: some View {
        ZStack {
            // blurred, dimmed backdrop (a material blurs what's under it; solid with Reduce Transparency)
            ZStack {
                if GlassEnv.reduceTransparency {
                    Color.black.opacity(0.6)
                } else {
                    Rectangle().fill(.thinMaterial)
                    Color.black.opacity(0.28)
                }
            }
                .opacity(shown ? 1 : 0)
                .contentShape(Rectangle())
                .onTapGesture { nav.closeIsa() }
                .accessibilityLabel("Close Isa")
            GeometryReader { g in
                IsaCommandCard(popup: true) { nav.closeIsa() }
                    .frame(width: min(520, g.size.width - 48))
                    .background(FrameProbe { LayoutProbe.isaCard = $0 })
                    .shadow(color: .black.opacity(0.45), radius: 40, y: 18)
                    .scaleEffect(shown || Motion.reduce ? 1 : 0.9)
                    .opacity(shown ? 1 : 0)
                    .position(x: g.size.width / 2, y: g.size.height / 2)
            }
        }
        .ignoresSafeArea()
        .onAppear { withAnimation(Motion.bouncy) { shown = true } }
        .transition(.opacity)
        .accessibilityIdentifier("isa_overlay")
    }
}

/// Empty top-bar space moves the window (double-click zooms), like a title bar.
struct WindowDragArea: NSViewRepresentable {
    final class DragView: NSView {
        override var mouseDownCanMoveWindow: Bool { true }
        override func mouseDown(with event: NSEvent) {
            if event.clickCount == 2 { window?.performZoom(nil); return }
            window?.performDrag(with: event)
        }
    }
    func makeNSView(context: Context) -> NSView { DragView() }
    func updateNSView(_ nsView: NSView, context: Context) {}
}

/// Puts the traffic lights on the top bar's centre line (y = 26 pt), clear of the inset sidebar, and keeps them
/// there across resizes and full screen.
enum WindowChrome {
    static let centerY: CGFloat = 26
    /// Centre x of the close button; the others follow at the system spacing.
    static let firstX: CGFloat = 22

    @MainActor static func layout(_ w: NSWindow) {
        guard !w.styleMask.contains(.fullScreen),
              let close = w.standardWindowButton(.closeButton),
              let mini = w.standardWindowButton(.miniaturizeButton),
              let zoom = w.standardWindowButton(.zoomButton),
              let container = close.superview?.superview else { return }
        let h = centerY * 2
        var f = container.frame
        if abs(f.height - h) > 0.5 || abs(f.minY - (w.frame.height - h)) > 0.5 {
            f.size.height = h
            f.origin.y = w.frame.height - h
            container.frame = f
        }
        let step: CGFloat = 20
        for (i, b) in [close, mini, zoom].enumerated() {
            let o = NSPoint(x: (firstX + CGFloat(i) * step - b.frame.width / 2).rounded(), y: ((h - b.frame.height) / 2).rounded())
            if b.frame.origin != o { b.setFrameOrigin(o) }
        }
    }

    /// Hooks the hosting window: lays the buttons out now and after every resize / full-screen change.
    struct Accessor: NSViewRepresentable {
        final class V: NSView {
            private var tokens: [NSObjectProtocol] = []
            override func viewDidMoveToWindow() {
                super.viewDidMoveToWindow()
                tokens.forEach { NotificationCenter.default.removeObserver($0) }
                tokens = []
                guard let w = window else { return }
                let relayout: (Notification) -> Void = { [weak w] _ in
                    guard let w else { return }
                    MainActor.assumeIsolated { WindowChrome.layout(w) }
                    DispatchQueue.main.async { MainActor.assumeIsolated { WindowChrome.layout(w) } }
                }
                for n in [NSWindow.didResizeNotification, NSWindow.didEndLiveResizeNotification, NSWindow.didExitFullScreenNotification,
                          NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification, NSWindow.didBecomeMainNotification] {
                    tokens.append(NotificationCenter.default.addObserver(forName: n, object: w, queue: .main, using: relayout))
                }
                DispatchQueue.main.async { [weak w] in if let w { MainActor.assumeIsolated { WindowChrome.layout(w) } } }
            }
            override func layout() {
                super.layout()
                if let w = window { MainActor.assumeIsolated { WindowChrome.layout(w) } }
            }
            deinit { tokens.forEach { NotificationCenter.default.removeObserver($0) } }
        }
        func makeNSView(context: Context) -> NSView { V() }
        func updateNSView(_ nsView: NSView, context: Context) {}
    }
}

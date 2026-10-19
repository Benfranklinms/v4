import AppKit
import AVKit
import SwiftUI

@main
struct ItuneApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var theme = Theme.shared
    @StateObject private var player = Player.shared
    @StateObject private var nav = Nav.shared

    var body: some Scene {
        WindowGroup("iTune", id: "main") {
            RootView()
                .environmentObject(theme)
                .environmentObject(player)
                .environmentObject(nav)
                .frame(minWidth: 980, minHeight: 640)
                .preferredColorScheme(theme.p.light ? .light : .dark)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandMenu("Controls") {
                Button(player.isPlaying ? "Pause" : "Play") { player.toggle() }.keyboardShortcut(.space, modifiers: [])
                Button("Next") { player.next() }.keyboardShortcut(.rightArrow, modifiers: .command)
                Button("Previous") { player.prev() }.keyboardShortcut(.leftArrow, modifiers: .command)
                Divider()
                Button("Now Playing") { withAnimation(Motion.spring) { player.showNowPlaying.toggle() } }.keyboardShortcut("p", modifiers: [.command, .shift])
                Button("Mini Player") { MiniPlayerPanel.shared.toggle() }.keyboardShortcut("m", modifiers: [.command, .option])
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
        Theme.registerBundledFonts()
        _ = Engine.core
        Task { @MainActor in await Updater.shared.checkOnLaunch() }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

enum Section: Hashable {
    case home, search, library, stats, downloads, importer, settings
}

enum Route: Hashable {
    case album(String), artist(String), playlist(String), isa(String), mix(String)
}

/// Navigation state (sidebar section + push stack).
@MainActor
final class Nav: ObservableObject {
    static let shared = Nav()
    @Published var section: Section = .home
    @Published var path: [Route] = []
    @Published var toast: String?
    @Published var updateInfo: UpdateInfo?
    var mixes: [String: [Track]] = [:]

    func go(_ s: Section) { withAnimation(Motion.spring) { section = s; path = [] } }
    func push(_ r: Route) { withAnimation(Motion.spring) { path.append(r); Player.shared.showNowPlaying = false } }
    func pop() { withAnimation(Motion.spring) { _ = path.popLast() } }
    func show(_ m: String) {
        withAnimation(Motion.spring) { toast = m }
        Task { try? await Task.sleep(nanoseconds: 2_400_000_000); await MainActor.run { withAnimation(Motion.spring) { if self.toast == m { self.toast = nil } } } }
    }
}

struct RootView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @Namespace private var ns

    var body: some View {
        let p = theme.p
        ZStack(alignment: .bottom) {
            p.bg.ignoresSafeArea()
            HStack(spacing: 0) {
                Sidebar().frame(width: 220)
                Rectangle().fill(p.line).frame(width: 0.5)
                ZStack {
                    content
                        .transition(.opacity.combined(with: .offset(y: 8)))
                        .id(contentKey)
                    if player.showNowPlaying && player.current != nil {
                        NowPlayingView(ns: ns)
                            .transition(.asymmetric(insertion: .opacity, removal: .opacity))
                            .zIndex(2)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.bottom, player.current == nil ? 0 : 76)
            }
            if player.current != nil {
                PlayerBar(ns: ns)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if let t = nav.toast {
                Text(t).font(theme.ui(13, .semibold)).foregroundStyle(p.bg)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(p.txt))
                    .padding(.bottom, 100)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(Motion.spring, value: player.current?.id)
        .animation(Motion.spring, value: player.showNowPlaying)
        .animation(Motion.spring, value: contentKey)
        .animation(.easeInOut(duration: 0.5), value: theme.themeId)
        .sheet(item: Binding(get: { nav.updateInfo.map(UpdateBox.init) }, set: { nav.updateInfo = $0?.info })) { box in
            UpdateSheet(info: box.info).environmentObject(theme)
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
            case .mix(let title): TrackListPage(title: title, subtitle: "Daily Mix", art: nav.mixes[title]?.first?.thumb ?? "", tracks: nav.mixes[title], source: title)
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
            }
        }
    }
}

struct UpdateBox: Identifiable { let info: UpdateInfo; var id: String { info.tag } }

struct Sidebar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @State private var playlists: [Playlist] = []

    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                PulseMark().frame(width: 16, height: 16).foregroundStyle(p.txt)
                Text("iTune").font(theme.ui(14, .bold)).foregroundStyle(p.txt)
            }
            .padding(.top, 40).padding(.horizontal, 16).padding(.bottom, 16)
            item("Home", "house", .home)
            item("Search", "magnifyingglass", .search)
            item("Library", "books.vertical", .library)
            item("Stats", "chart.bar", .stats)
            item("Downloads", "arrow.down.circle", .downloads)
            Text("PLAYLISTS").font(theme.ui(10, .bold)).tracking(1).foregroundStyle(p.t3).padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 6)
            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    row("Liked Songs", "heart.fill") { nav.push(.playlist("liked")) }
                    ForEach(Array(playlists.enumerated()), id: \.element.id) { i, pl in
                        row(pl.title, "music.note.list") { nav.push(.playlist(pl.id)) }.enter(i)
                    }
                }
            }
            Spacer()
            item("Import", "square.and.arrow.down", .importer)
            item("Settings", "gearshape", .settings)
                .padding(.bottom, 12)
        }
        .background(p.side)
        .task { playlists = (try? Engine.core.playlists()) ?? [] }
        .onChange(of: nav.toast) { _, _ in playlists = (try? Engine.core.playlists()) ?? [] }
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
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 8).fill(on ? p.surf3 : .clear))
            .padding(.horizontal, 8)
        }
        .buttonStyle(PressStyle(scale: 0.97))
    }

    private func row(_ title: String, _ icon: String, _ a: @escaping () -> Void) -> some View {
        let p = theme.p
        return Button(action: a) {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 11)).frame(width: 18).foregroundStyle(p.t3)
                Text(title).font(theme.ui(12.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                Spacer()
            }.padding(.horizontal, 18).padding(.vertical, 5)
        }.buttonStyle(PressStyle(scale: 0.98))
    }
}

/// Bottom player bar; the artwork morphs into Now Playing (matchedGeometryEffect).
struct PlayerBar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    let ns: Namespace.ID

    var body: some View {
        let p = theme.p
        HStack(spacing: 14) {
            if let t = player.current {
                Button { withAnimation(Motion.spring) { player.showNowPlaying.toggle() } } label: {
                    HStack(spacing: 12) {
                        if !player.showNowPlaying {
                            ArtView(url: t.thumb, image: player.artwork, size: 46, radius: 8)
                                .matchedGeometryEffect(id: "art", in: ns)
                        } else {
                            Color.clear.frame(width: 46, height: 46)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                            Text(t.artists).font(theme.ui(11.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                        }.frame(maxWidth: 240, alignment: .leading)
                    }
                }.buttonStyle(PressStyle(scale: 0.98))
                Spacer()
                VStack(spacing: 4) {
                    Transport(size: 15)
                    Scrubber(width: 420)
                }
                Spacer()
                HStack(spacing: 14) {
                    AirPlayButton().frame(width: 22, height: 22)
                    IconButton("pip") { MiniPlayerPanel.shared.toggle() }
                    IconButton("list.bullet") { withAnimation(Motion.spring) { player.showNowPlaying = true } }
                }.foregroundStyle(p.t2)
            }
        }
        .padding(.horizontal, 18)
        .frame(height: 76)
        .background(
            ZStack {
                p.side
                LinearGradient(colors: [theme.glow.opacity(0.16), .clear], startPoint: .leading, endPoint: .center)
            }
        )
        .overlay(Rectangle().fill(p.line2).frame(height: 0.5), alignment: .top)
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

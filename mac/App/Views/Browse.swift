import AppKit
import SwiftUI

/// Scrolling page (lazy: long lists only build the rows on screen). The detail column already starts at the
/// sidebar's edge and gets a bottom safe-area inset for the floating player bar.
struct Page<Content: View>: View {
    @EnvironmentObject var nav: Nav
    @Environment(\.contentInsets) var insets
    /// Names the page for the scroll-offset probe (smoke: Back restores the same offset).
    var name: String? = nil
    /// Lazy for long lists; Home uses an eager stack so every carousel keeps its offset while scrolled away.
    var lazy = true
    @ViewBuilder let content: () -> Content
    var body: some View {
        GeometryReader { geo in
        ScrollViewReader { proxy in
            ScrollView {
                Group {
                    if lazy {
                        LazyVStack(alignment: .leading, spacing: 0) { inner }
                    } else {
                        VStack(alignment: .leading, spacing: 0) { inner }
                    }
                }
                .padding(.leading, 32 + insets.leading).padding(.trailing, 32 + insets.trailing)
                .padding(.top, 14 + insets.top).padding(.bottom, 32 + insets.bottom)
                // exactly the column width: a wide row can never widen the detail column (that pushed the
                // queue overlay and player bar past the window edge)
                .frame(width: max(0, geo.size.width), alignment: .leading)
            }
            .coordinateSpace(name: "page_scroll")
            .scrollIndicators(.never)
            .onChange(of: nav.scrollTarget) { _, t in
                guard let t else { return }
                withAnimation(Motion.spring) { proxy.scrollTo(t, anchor: .top) }
            }
        }
        }
    }
}

extension Page {
    @ViewBuilder var inner: some View {
        Color.clear.frame(height: 0).id("page_top")
            .background(name.map { n in GeometryReader { g in
                Color.clear.onChange(of: g.frame(in: .named("page_scroll")).minY) { _, y in ScrollProbe.offsets[n] = -y }
            } })
        content()
        Color.clear.frame(height: 1).id("page_end")
    }
}

/// Large page title (Apple Music style).
struct LargeTitle: View {
    @EnvironmentObject var theme: Theme
    let text: String
    var body: some View {
        Text(text).font(theme.ui(30, .bold)).foregroundStyle(theme.p.txt)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Scroll offsets reported by named pages and carousels (smoke checks).
enum ScrollProbe { nonisolated(unsafe) static var offsets: [String: CGFloat] = [:] }

enum HomeCache {
    @MainActor static var feed: HomeFeed?
    /// Last successful network refresh.
    @MainActor static var at = Date.distantPast
    /// When the cached feed was first on screen (CACurrentMediaTime), for the cold-launch smoke check.
    @MainActor static var cachedShownAt: CFTimeInterval = 0
    @MainActor static var source = "none"
    /// Started in applicationWillFinishLaunching (DB read without constructing the core).
    @MainActor static var peekTask: Task<HomeFeed?, Never>?
}

struct HomeView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var prefs: Prefs
    @State private var feed: HomeFeed? = HomeCache.feed
    @State private var failed = false
    @State private var loading = false
    @State private var localRecent: [Track] = []

    var body: some View {
        let p = theme.p
        Page(name: "home", lazy: false) {
            LargeTitle(text: "Home").enter(0)
            Text("\(feed?.greeting ?? greetingNow), \(firstName)").font(theme.ui(14, .medium)).foregroundStyle(p.t2).padding(.top, 2).enter(0)
            if let f = feed {
                feedSections(f)
            } else if failed {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Can't load your feed right now.").font(theme.ui(15, .semibold)).foregroundStyle(p.txt)
                    Text("Check your connection. Your library and downloads still work.").font(theme.ui(12.5)).foregroundStyle(p.t2)
                    SecondaryButton(title: loading ? "Retrying…" : "Retry", icon: "arrow.clockwise") { Task { await load(force: true) } }
                        .accessibilityIdentifier("home_retry")
                }.padding(.top, 30)
                if !localRecent.isEmpty {
                    CardShelf(title: "Recently played", items: localRecent.map(HomeItemBox.init(track:))).id("shelf_0")
                }
            } else {
                ForEach(0..<3, id: \.self) { i in
                    SkeletonShelf().padding(.top, 26).id("shelf_\(i)")
                }
            }
        }
        .task { await load(force: false) }
        .accessibilityIdentifier("home")
    }

    private var greetingNow: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 5 ? "Late night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : h < 22 ? "Good evening" : "Late night"
    }
    private var firstName: String { prefs.firstName(account: account.info) }

    @ViewBuilder private func feedSections(_ f: HomeFeed) -> some View {
        let shelves = HomeView.sections(f)
        ForEach(Array(shelves.enumerated()), id: \.offset) { i, s in
            Group {
                if s.songs { SongShelf(title: s.title, tracks: s.items.compactMap { $0.track }) }
                else { CardShelf(title: s.title, items: s.items) }
            }
            .id("shelf_\(i)")
            .enter(min(i + 2, 10))
        }
    }

    struct Section { let title: String; let songs: Bool; let items: [HomeItemBox] }

    /// Home is never empty: YouTube Music shelves, then mixes / on repeat / discover / recent / trending fallbacks.
    static func sections(_ f: HomeFeed) -> [Section] {
        var out: [Section] = []
        if !f.mixes.isEmpty {
            out.append(Section(title: "Top Picks for You", songs: false, items: f.mixes.map(HomeItemBox.init(mix:))))
        }
        if !f.recent.isEmpty {
            out.append(Section(title: "Recently played", songs: false, items: f.recent.map(HomeItemBox.init(track:))))
        }
        for s in f.shelves where !s.items.isEmpty {
            out.append(Section(title: s.title, songs: s.layout == "songs", items: s.items.map(HomeItemBox.init(item:))))
        }
        if !f.onRepeat.isEmpty { out.append(Section(title: "On Repeat", songs: true, items: f.onRepeat.map(HomeItemBox.init(track:)))) }
        if !f.discover.isEmpty { out.append(Section(title: "Discover", songs: true, items: f.discover.map(HomeItemBox.init(track:)))) }
        if f.shelves.isEmpty && !f.trending.isEmpty {
            out.append(Section(title: "Trending", songs: true, items: f.trending.map(HomeItemBox.init(track:))))
        }
        return out
    }

    /// Stale-while-revalidate: the last saved feed renders at once (no network), then the fresh feed is
    /// swapped in without animation (same section ids, so nothing jumps). Skeletons only with no cache at all.
    private func load(force: Bool) async {
        if feed == nil, let t = HomeCache.peekTask, let c = await t.value, !HomeView.sections(c).isEmpty {
            feed = c
            HomeCache.feed = c
            HomeCache.source = "cache"
            if HomeCache.cachedShownAt == 0 { HomeCache.cachedShownAt = CACurrentMediaTime() }
            HomeView.prefetchArt(c)
        }
        if feed == nil, let c = await Engine.off({ Engine.core.homeCached(tzOffsetMin: Engine.tzMinutes) }) ?? nil, !HomeView.sections(c).isEmpty {
            feed = c
            HomeCache.feed = c
            HomeCache.source = "cache"
            if HomeCache.cachedShownAt == 0 { HomeCache.cachedShownAt = CACurrentMediaTime() }
            HomeView.prefetchArt(c)
        }
        if !force, feed != nil, Date().timeIntervalSince(HomeCache.at) < 1200 { return }
        loading = true
        let r: Result<HomeFeed, Error>
        do { r = .success(try await Engine.core.home(tzOffsetMin: Engine.tzMinutes)) } catch { r = .failure(error) }
        loading = false
        switch r {
        case .success(let f) where !HomeView.sections(f).isEmpty:
            if feed == nil {
                withAnimation(Motion.spring) { feed = f; failed = false }
            } else {
                var t = Transaction(); t.disablesAnimations = true
                withTransaction(t) { feed = f; failed = false }
            }
            HomeCache.feed = f; HomeCache.at = Date(); HomeCache.source = "network"
            HomeView.prefetchArt(f)
        default:
            if feed == nil {
                localRecent = await Engine.off { try Engine.core.recent(limit: 20) } ?? []
                withAnimation(Motion.spring) { failed = true }
            }
        }
    }
}

extension HomeView {
    /// Warm the image cache with the first visible shelves' covers.
    @MainActor static func prefetchArt(_ f: HomeFeed) {
        for s in sections(f).prefix(2) {
            ArtCache.shared.prefetch(s.items.prefix(8).map(\.thumb), points: s.songs ? 44 : (s.items.first?.kind == "mix" ? 196 : 168))
        }
    }
}

/// Page prefetch on hover: album / playlist pages land in the core page cache before the click.
@MainActor
enum PagePrefetch {
    private static var done = Set<String>()
    static func hover(_ it: HomeItemBox) {
        let id = it.rawId
        guard ["album", "playlist"].contains(it.kind), done.insert(it.id).inserted else { return }
        ArtCache.shared.prefetch([it.thumb], points: 200)
        Task.detached(priority: .utility) {
            if it.kind == "album" { _ = try? await Engine.core.album(id: id) } else { _ = try? await Engine.core.playlist(id: id) }
        }
    }
}

/// One card on a home shelf (track, album, playlist, artist, mix).
struct HomeItemBox: Identifiable {
    let id: String
    let kind: String
    let title: String
    let subtitle: String
    let thumb: String
    var track: Track? = nil
    var mix: Mix? = nil

    init(item i: HomeItem) {
        id = i.kind + ":" + i.id; kind = i.kind; title = i.title; subtitle = i.subtitle; thumb = i.thumb; track = i.track
    }
    init(track t: Track) {
        id = "song:" + t.id; kind = "song"; title = t.title; subtitle = t.artists; thumb = t.thumb; track = t
    }
    init(mix m: Mix) {
        id = "mix:" + m.id; kind = "mix"; title = m.title; subtitle = m.subtitle; thumb = m.thumb; mix = m
    }
    var rawId: String { String(id.drop(while: { $0 != ":" }).dropFirst()) }
}

struct ShelfHeader: View {
    @EnvironmentObject var theme: Theme
    let title: String
    var body: some View {
        HStack(spacing: 4) {
            Text(title).font(theme.ui(20, .bold)).foregroundStyle(theme.p.txt)
            Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundStyle(theme.p.t3)
        }
        .padding(.top, 28).padding(.bottom, 12)
        .accessibilityAddTraits(.isHeader)
    }
}

struct CardShelf: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    let title: String
    let items: [HomeItemBox]
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ShelfHeader(title: title)
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 18) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, it in
                            HomeCard(item: it, size: it.kind == "mix" ? 196 : 168) { open(it, index: i) }
                                .id("card_\(i)")
                        }
                    }
                    .padding(.vertical, 8)
                    .background(GeometryReader { g in
                        Color.clear.onChange(of: g.frame(in: .named("carousel_\(title)")).minX) { _, x in ScrollProbe.offsets["carousel_\(title)"] = -x }
                    })
                }
                .coordinateSpace(name: "carousel_\(title)")
                .carousel()
                .onChange(of: nav.carouselTarget) { _, t in
                    guard let t, t.shelf == title else { return }
                    withAnimation(Motion.spring) { proxy.scrollTo("card_\(t.index)", anchor: .leading) }
                }
            }
        }
    }

    private func open(_ it: HomeItemBox, index: Int) {
        switch it.kind {
        case "song", "video":
            let tracks = items.compactMap { $0.track }
            if let t = it.track { player.play(tracks, index: tracks.firstIndex { $0.id == t.id } ?? 0, source: title) }
        case "album", "playlist", "artist":
            nav.seeds[it.rawId] = RouteSeed(title: it.title, subtitle: it.subtitle, thumb: it.thumb)
            nav.push(it.kind == "album" ? .album(it.rawId) : it.kind == "playlist" ? .playlist(it.rawId) : .artist(it.rawId))
        case "mix":
            if let m = it.mix { nav.mixes[m.title] = m.tracks; nav.push(.mix(m.title)) }
        default: break
        }
    }
}

struct HomeCard: View {
    @EnvironmentObject var theme: Theme
    let item: HomeItemBox
    var size: CGFloat = 168
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        let p = theme.p
        let circle = item.kind == "artist"
        VStack(alignment: circle ? .center : .leading, spacing: 3) {
            ZStack(alignment: .bottomTrailing) {
                ArtView(url: item.thumb, size: size, radius: 12, circle: circle)
                    .shadow(color: .black.opacity(0.25), radius: 8, y: 5)
                if !circle {
                    Image(systemName: "play.fill").font(.system(size: 13, weight: .bold)).foregroundStyle(Color(red: 0.035, green: 0.035, blue: 0.04))
                        .frame(width: 34, height: 34).background(Circle().fill(Color(red: 0.91, green: 0.91, blue: 0.91)))
                        .padding(10).opacity(hover ? 1 : 0).offset(y: hover ? 0 : 6)
                }
            }
            Text(item.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1).padding(.top, 6)
            Text(item.subtitle).font(theme.ui(11.5, .medium)).foregroundStyle(p.t3).lineLimit(1)
        }
        .frame(width: size)
        .contentShape(Rectangle())
        .scaleEffect(hover && !Motion.reduce ? 1.02 : 1)
        .onHover { h in withAnimation(Motion.spring) { hover = h }; if h { PagePrefetch.hover(item) } }
        .onTapGesture(perform: action)
        .contextMenu { if let t = item.track { TrackMenuItems(t: t) } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Quick-picks style shelf: columns of four compact rows, scrolled horizontally.
struct SongShelf: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    let title: String
    let tracks: [Track]
    var body: some View {
        let cols = stride(from: 0, to: tracks.count, by: 4).map { Array(tracks[$0..<min($0 + 4, tracks.count)]) }
        VStack(alignment: .leading, spacing: 0) {
            ShelfHeader(title: title)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 18) {
                    ForEach(Array(cols.enumerated()), id: \.offset) { c, col in
                        VStack(spacing: 4) {
                            ForEach(Array(col.enumerated()), id: \.element.id) { r, t in
                                CompactTrackRow(t: t) { player.play(tracks, index: c * 4 + r, source: title) }
                            }
                        }.frame(width: 300)
                    }
                }
            }
            .carousel()
        }
    }
}

struct CompactTrackRow: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    let t: Track
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        let p = theme.p
        HStack(spacing: 10) {
            ArtView(url: t.thumb, size: 44, radius: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.title).font(theme.ui(13, .semibold)).foregroundStyle(player.current?.id == t.id ? theme.accent : p.txt).lineLimit(1)
                Text(t.artists).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1)
            }
            Spacer(minLength: 4)
            TrackMenu(t: t).opacity(hover ? 1 : 0)
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 8).fill(hover ? Color.white.opacity(0.06) : .clear))
        .contentShape(Rectangle())
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
        .onTapGesture(perform: action)
        .contextMenu { TrackMenuItems(t: t) }
    }
}

struct SkeletonShelf: View {
    @EnvironmentObject var theme: Theme
    @State private var on = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            RoundedRectangle(cornerRadius: 6).fill(theme.p.surf3).frame(width: 180, height: 18)
            HStack(spacing: 18) {
                ForEach(0..<6, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 6) {
                        RoundedRectangle(cornerRadius: 12).fill(theme.p.surf3).frame(width: 168, height: 168)
                        RoundedRectangle(cornerRadius: 4).fill(theme.p.surf3).frame(width: 120, height: 10)
                    }
                }
            }
        }
        .opacity(on ? 0.9 : 0.4)
        .onAppear { if !Motion.reduce { withAnimation(.easeInOut(duration: 0.8).repeatForever()) { on = true } } }
        .accessibilityLabel("Loading")
    }
}

struct AlbumStrip: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let albums: [Album]
    var body: some View {
        let p = theme.p
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 16) {
                ForEach(Array(albums.enumerated()), id: \.element.id) { i, a in
                    VStack(alignment: .leading, spacing: 3) {
                        ArtView(url: a.thumb, size: 150, radius: 10)
                        Text(a.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1).padding(.top, 5)
                        Text([a.artists, a.year.map(String.init)].compactMap { $0 }.joined(separator: " · ")).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1)
                    }.frame(width: 150).contentShape(Rectangle()).onTapGesture {
                        nav.seeds[a.id] = RouteSeed(title: a.title, subtitle: a.artists, thumb: a.thumb)
                        nav.push(.album(a.id))
                    }
                        .contextMenu { AlbumMenuItems(a: a) }
                        .enter(i)
                }
            }
        }.carousel()
    }
}

extension View {
    /// Horizontal carousel: runs edge to edge of the detail column (clipped there, never under the sidebar)
    /// with the page's 32 pt margins as content margins.
    func carousel() -> some View {
        self.scrollIndicators(.never)
            .contentMargins(.horizontal, 32, for: .scrollContent)
            .padding(.horizontal, -32)
    }
}

struct LibraryView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    @AppStorage("libFilter") private var filter = "Playlists"
    @AppStorage("libSort") private var sort = "Recent"
    @State private var selecting = false
    @State private var selected: Set<String> = []

    var body: some View {
        let p = theme.p
        Page {
            HStack {
                LargeTitle(text: "Library")
                Spacer()
                if filter == "Playlists" {
                    if selecting {
                        SecondaryButton(title: "Cancel") { selecting = false; selected = [] }
                        PrimaryButton(title: selected.isEmpty ? "Delete" : "Delete (\(selected.count))", icon: "trash", enabled: !selected.isEmpty) {
                            lib.delete(lib.visiblePlaylists.filter { selected.contains($0.id) }); selecting = false; selected = []
                        }.accessibilityIdentifier("lib_delete_selected")
                    } else {
                        Chip(label: "Select", icon: "checkmark.circle") { selecting = true }
                        Chip(label: "Import", icon: "square.and.arrow.down") { nav.go(.importer) }
                        Chip(label: "New playlist", icon: "plus") { nav.sheet = .newPlaylist([]) }.accessibilityIdentifier("lib_new_playlist")
                    }
                }
            }
            HStack(spacing: 8) {
                ForEach(["Playlists", "Albums", "Artists"], id: \.self) { f in Chip(label: f, selected: filter == f) { withAnimation(Motion.spring) { filter = f; selecting = false } } }
                Spacer()
                Menu {
                    ForEach(["Recent", "A–Z"], id: \.self) { s in Button(s) { sort = s } }
                } label: { Label(sort, systemImage: "arrow.up.arrow.down").font(theme.ui(12, .medium)) }
                    .menuStyle(.borderlessButton).frame(width: 100)
            }.padding(.top, 14)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 168), spacing: 18)], alignment: .leading, spacing: 20) {
                if filter == "Playlists" {
                    if !selecting {
                        Tile(title: "Liked Songs", sub: lib.liked.count == 1 ? "1 song" : "\(lib.liked.count) songs", art: "", icon: "heart.fill", i: 0) { nav.push(.playlist("liked")) }
                        Tile(title: "Downloads", sub: "\(lib.downloaded.count) songs", art: "", icon: "arrow.down.circle.fill", i: 1) { nav.go(.downloads) }
                    }
                    ForEach(Array(sorted(lib.visiblePlaylists, \.title).enumerated()), id: \.element.id) { i, pl in
                        Tile(title: pl.title, sub: playlistSubtitle(pl.subtitle, count: pl.trackCount.map { Int($0) }), art: pl.thumb, i: i + 2,
                             selected: selecting ? selected.contains(pl.id) : nil) {
                            if selecting { if selected.contains(pl.id) { selected.remove(pl.id) } else { selected.insert(pl.id) } }
                            else { nav.push(.playlist(pl.id)) }
                        }
                        .contextMenu { PlaylistMenuItems(p: pl) }
                    }
                } else if filter == "Albums" {
                    ForEach(Array(sorted(lib.albums, \.title).enumerated()), id: \.element.id) { i, a in
                        Tile(title: a.title, sub: a.artists, art: a.thumb, i: i) { nav.push(.album(a.id)) }.contextMenu { AlbumMenuItems(a: a) }
                    }
                    ForEach(Array(sorted(lib.savedPlaylists, \.title).enumerated()), id: \.element.id) { i, pl in
                        Tile(title: pl.title, sub: pl.subtitle, art: pl.thumb, i: i + lib.albums.count) { nav.push(.playlist(pl.id)) }.contextMenu { PlaylistMenuItems(p: pl) }
                    }
                } else {
                    ForEach(Array(sorted(lib.artists, \.name).enumerated()), id: \.element.id) { i, a in
                        Tile(title: a.name, sub: "Artist", art: a.thumb, circle: true, i: i) { nav.push(.artist(a.id)) }
                            .contextMenu { Button("Remove from Library") { lib.toggleSaved(artist: a) } }
                    }
                }
            }.padding(.top, 20)
            if filter == "Albums" && lib.albums.isEmpty && lib.savedPlaylists.isEmpty {
                EmptyHint(icon: "bookmark", text: "Albums and playlists you save show up here.")
            } else if filter == "Artists" && lib.artists.isEmpty {
                EmptyHint(icon: "music.mic", text: "Artists you save show up here.")
            }
        }
        .onAppear { lib.refresh() }
        .accessibilityIdentifier("library")
    }

    private func sorted<T>(_ xs: [T], _ key: KeyPath<T, String>) -> [T] {
        sort == "A–Z" ? xs.sorted { $0[keyPath: key].localizedCaseInsensitiveCompare($1[keyPath: key]) == .orderedAscending } : xs
    }
}

struct EmptyHint: View {
    @EnvironmentObject var theme: Theme
    let icon: String
    let text: String
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon).foregroundStyle(theme.p.t3)
            Text(text).font(theme.ui(13)).foregroundStyle(theme.p.t2)
        }.padding(.top, 24)
    }
}

struct Tile: View {
    @EnvironmentObject var theme: Theme
    let title: String
    let sub: String
    let art: String
    var icon: String? = nil
    var circle = false
    let i: Int
    var selected: Bool? = nil
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 3) {
            ZStack(alignment: .topTrailing) {
                if let icon {
                    ZStack { RoundedRectangle(cornerRadius: 12).fill(p.surf3); Image(systemName: icon).font(.system(size: 34)).foregroundStyle(p.txt) }.frame(width: 168, height: 168)
                } else {
                    ArtView(url: art, size: 168, radius: 12, circle: circle)
                }
                if let s = selected {
                    Image(systemName: s ? "checkmark.circle.fill" : "circle").font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(s ? p.txt : p.t2).background(Circle().fill(p.bg.opacity(0.6))).padding(8)
                }
            }
            Text(title).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt).lineLimit(1).padding(.top, 6)
            Text(sub).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1)
        }
        .frame(width: 168)
        .contentShape(Rectangle())
        .scaleEffect(hover && !Motion.reduce ? 1.02 : 1)
        .onHover { h in withAnimation(Motion.spring) { hover = h } }
        .onTapGesture(perform: action)
        .enter(i)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Album / playlist / artist / mix page: art, title, Play / Shuffle, bookmark and menu.
struct TrackListPage: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    let title: String
    let subtitle: String
    let art: String
    let tracks: [Track]?
    let source: String
    var circle = false
    /// Artist pages (a handful of top songs) have no in-list search.
    var searchable = true
    /// Album pages hide the album column (it would repeat the page title on every row).
    var showAlbum = true
    var playlistId: String? = nil
    var actions: AnyView? = nil
    var extra: AnyView? = nil
    var empty: String = "Nothing here yet."
    @State private var query = ""
    @State private var hits: ListHits?

    var body: some View {
        let p = theme.p
        let filtering = !ListFilter.isBlank(query)
        Page {
            Button { Nav.shared.pop() } label: { Label("Back", systemImage: "chevron.left").font(theme.ui(12.5, .semibold)).foregroundStyle(p.t2) }
                .buttonStyle(PressStyle()).accessibilityIdentifier("back")
            HStack(alignment: .bottom, spacing: 24) {
                ArtView(url: art, size: 200, radius: 14, circle: circle).shadow(color: .black.opacity(0.35), radius: 24, y: 10)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(theme.ui(30, .bold)).foregroundStyle(p.txt).lineLimit(2)
                    Text(subtitle).font(theme.ui(13)).foregroundStyle(p.t2).lineLimit(2)
                    HStack(spacing: 10) {
                        PrimaryButton(title: "Play", icon: "play.fill", enabled: !(tracks ?? []).isEmpty) {
                            if let t = tracks, !t.isEmpty { player.setShuffle(false); player.play(t, index: 0, source: source) }
                        }
                        SecondaryButton(title: "Shuffle", icon: "shuffle") { if let t = tracks, !t.isEmpty { player.playShuffled(t, source: source) } }
                        if let a = actions { a }
                    }.padding(.top, 10)
                }
            }.padding(.top, 10).enter(0)
            if searchable, let ts = tracks, !ts.isEmpty {
                // own row under the header: always on screen, however narrow the window or open the queue
                HStack { Spacer(minLength: 0); ListSearchField(text: $query) }.padding(.top, 14).enter(1)
                Spacer().frame(height: 10)
            } else {
                Spacer().frame(height: 24)
            }
            if let ts = tracks {
                if ts.isEmpty {
                    EmptyHint(icon: "music.note", text: empty)
                }
                let albumCol = showAlbum && Set(ts.compactMap { $0.album }).count > 1
                if filtering, let h = hits {
                    // filtered: no remove-from-playlist (positions belong to the full list); a tap plays the full list
                    if h.idx.isEmpty { NoMatches() }
                    ForEach(h.idx, id: \.self) { i in
                        TrackRow(t: h.list[i], index: i, number: i + 1, showAlbum: albumCol) { player.play(h.list, index: i, source: source) }
                            .id("row_\(i)")
                    }
                } else {
                    ForEach(Array(ts.enumerated()), id: \.offset) { i, t in
                        TrackRow(t: t, index: i, number: i + 1, showAlbum: albumCol, playlistId: playlistId, position: i) { player.play(ts, index: i, source: source) }
                            .id("row_\(i)")
                    }
                }
            } else {
                Shimmer(count: 3)
            }
            if let e = extra { e }
        }
        .listFilter(searchable ? tracks : nil, query, $hits)
    }
}

/// "N songs" once: strips any count already in the source subtitle (Liked Songs said "0 songs · 0 songs").
func playlistSubtitle(_ sub: String?, count: Int?) -> String {
    var parts = (sub ?? "").components(separatedBy: " · ").map { $0.trimmingCharacters(in: .whitespaces) }
    parts.removeAll { $0.isEmpty || $0.range(of: #"^\d[\d,.]*\s*(songs?|tracks?)$"#, options: [.regularExpression, .caseInsensitive]) != nil }
    if let n = count { parts.append(n == 1 ? "1 song" : "\(n) songs") }
    return parts.joined(separator: " · ")
}

/// First rows on screen per page id (smoke timing).
enum PageTiming {
    @MainActor static var firstRows: [String: CFTimeInterval] = [:]
    /// Track ids currently shown per playlist page (smoke: likes show up live in Liked Songs).
    @MainActor static var rows: [String: [String]] = [:]
}

struct AlbumView: View {
    @EnvironmentObject var lib: LibraryStore
    let id: String
    @State private var page: AlbumPage?
    var body: some View {
        let seed = Nav.shared.seeds[id]
        TrackListPage(title: page?.album.title ?? seed?.title ?? "", subtitle: page.map { pg in [pg.album.artists, pg.album.year.map(String.init), pg.album.kind].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ") } ?? seed?.subtitle ?? "",
                      art: page?.album.thumb ?? seed?.thumb ?? "", tracks: page?.tracks, source: page?.album.title ?? "Album", showAlbum: false,
                      actions: AnyView(HStack(spacing: 10) {
                          if let a = page?.album {
                              BookmarkButton(saved: lib.isSaved(album: a.id)) { lib.toggleSaved(album: a) }
                              if let ts = page?.tracks { DownloadAllButton(tracks: ts, label: a.title) }
                          }
                      }))
            .task(id: id) {
                // cached page at once, then revalidate (the core serves it without network while fresh)
                if let c = await Engine.off({ Engine.core.albumCached(id: id) }) ?? nil { page = c; PageTiming.firstRows[id] = CACurrentMediaTime() }
                if let n = try? await Engine.core.album(id: id) { page = n; if PageTiming.firstRows[id] == nil { PageTiming.firstRows[id] = CACurrentMediaTime() } }
            }
            .accessibilityIdentifier("album_page")
    }
}

struct PlaylistView: View {
    @EnvironmentObject var lib: LibraryStore
    let id: String
    @State private var page: PlaylistPage?
    @State private var tracks: [Track]?
    var body: some View {
        let local = LibraryStore.isLocal(id) || id == "liked"
        let seed = Nav.shared.seeds[id]
        let pl = page?.playlist ?? lib.visiblePlaylists.first { $0.id == id }
        let title = pl?.title ?? seed?.title ?? ""
        TrackListPage(title: title,
                      subtitle: playlistSubtitle(pl?.subtitle ?? seed?.subtitle, count: tracks?.count),
                      art: pl?.thumb ?? tracks?.first?.thumb ?? seed?.thumb ?? "", tracks: tracks, source: pl?.title ?? "Playlist",
                      playlistId: LibraryStore.isLocal(id) ? id : nil,
                      actions: AnyView(HStack(spacing: 10) {
                          if let pl {
                              if !local { BookmarkButton(saved: lib.isSaved(playlist: pl.id)) { lib.toggleSaved(playlist: pl) } }
                              if let ts = tracks { DownloadAllButton(tracks: ts, label: pl.title) }
                              if id != "liked" {
                                  Menu { PlaylistMenuItems(p: pl) } label: { Image(systemName: "ellipsis").font(.system(size: 14, weight: .bold)) }
                                      .menuStyle(.borderlessButton).menuIndicator(.hidden).frame(width: 38, height: 38)
                                      .background(Circle().fill(Color.white.opacity(0.08))).help("More")
                              }
                          }
                      }),
                      empty: "Add songs from any ⋯ menu.")
            // own playlists and Liked Songs reload whenever the library changes (likes from the player, menus, NP)
            .task(id: "\(id)-\(lib.trackChanges)-\(local ? lib.rev : 0)") { await load() }
            .accessibilityIdentifier("playlist_page")
    }

    private func show(_ p: PlaylistPage) {
        page = p
        tracks = p.tracks
        PageTiming.rows[id] = p.tracks.map(\.id)
        if PageTiming.firstRows[id] == nil, !p.tracks.isEmpty { PageTiming.firstRows[id] = CACurrentMediaTime() }
    }

    /// Cached page at once -> first network page -> the rest appended page by page (queue extended too).
    private func load() async {
        if page == nil, let c = await Engine.off({ Engine.core.playlistCached(id: id) }) ?? nil { show(c) }
        guard let p = try? await Engine.core.playlist(id: id) else { return }
        if Task.isCancelled { return }
        show(p)
        let source = p.playlist.title
        while !Task.isCancelled, Engine.core.playlistHasMore(id: id) {
            guard let more = try? await Engine.core.playlistMore(id: id, count: 100) else { break }
            let have = Set((tracks ?? []).map(\.id))
            let new = more.tracks.filter { !have.contains($0.id) }
            if !new.isEmpty {
                tracks = (tracks ?? []) + new
                Player.shared.extendQueue(new, source: source)
            }
            if more.done { break }
        }
    }
}

struct ArtistView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var lib: LibraryStore
    let id: String
    @State private var page: ArtistPage?
    var body: some View {
        let p = theme.p
        let seed = Nav.shared.seeds[id]
        let sub = page?.artist.subtitle ?? ""
        TrackListPage(title: page?.artist.name ?? seed?.title ?? "", subtitle: sub.lowercased().contains("subscriber") || sub.isEmpty ? "Artist" : sub,
                      art: page?.artist.thumb ?? seed?.thumb ?? "", tracks: page?.topTracks,
                      source: page?.artist.name ?? "Artist", circle: true, searchable: false,
                      actions: AnyView(Group {
                          if let a = page?.artist { BookmarkButton(saved: lib.isSaved(artist: a.id)) { lib.toggleSaved(artist: a) } }
                      }),
                      extra: AnyView(VStack(alignment: .leading) {
                          if let a = page, !a.albums.isEmpty { SectionTitle(text: "Albums & singles"); AlbumStrip(albums: a.albums) }
                          if let a = page, !a.similar.isEmpty {
                              SectionTitle(text: "Fans also like")
                              ScrollView(.horizontal) {
                                  LazyHStack(spacing: 18) {
                                      ForEach(Array(a.similar.prefix(12).enumerated()), id: \.element.id) { i, x in
                                          VStack { ArtView(url: x.thumb, size: 110, circle: true); Text(x.name).font(theme.ui(12, .semibold)).foregroundStyle(p.txt).lineLimit(1) }
                                              .frame(width: 116).contentShape(Rectangle())
                                              .onTapGesture { Nav.shared.seeds[x.id] = RouteSeed(title: x.name, subtitle: "Artist", thumb: x.thumb); Nav.shared.push(.artist(x.id)) }
                                              .enter(i)
                                      }
                                  }
                              }.carousel()
                          }
                          if let d = page?.description { Text(d).font(theme.ui(12.5)).foregroundStyle(p.t2).lineLimit(8).padding(.top, 20).frame(maxWidth: 720, alignment: .leading) }
                      }))
            .task(id: id) {
                if let c = await Engine.off({ Engine.core.artistCached(id: id) }) ?? nil { page = c }
                if let n = try? await Engine.core.artist(id: id) { page = n }
            }
            .accessibilityIdentifier("artist_page")
    }
}

/// Save to Library (bookmark): optimistic toggle with spring + haptic; toast with Undo comes from the store.
struct BookmarkButton: View {
    @EnvironmentObject var theme: Theme
    let saved: Bool
    let toggle: () -> Void
    var body: some View {
        Button { Haptics.tap(); withAnimation(Motion.bouncy) { toggle() } } label: {
            Image(systemName: saved ? "bookmark.fill" : "bookmark").font(.system(size: 14, weight: .semibold)).foregroundStyle(theme.p.txt)
                .frame(width: 38, height: 38).background(Circle().fill(Color.white.opacity(saved ? 0.16 : 0.08)))
                .symbolEffect(.bounce, value: saved)
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(PressStyle())
        .help(saved ? "Remove from Library" : "Save to Library")
        .accessibilityLabel(saved ? "Remove from Library" : "Save to Library")
        .accessibilityIdentifier("bookmark")
    }
}

/// Download every track / remove the downloads of a whole album or playlist.
struct DownloadAllButton: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var lib: LibraryStore
    let tracks: [Track]
    let label: String
    var body: some View {
        let streamable = tracks.filter { !$0.id.hasPrefix("local:") }
        let all = !streamable.isEmpty && streamable.allSatisfy { lib.downloaded.contains($0.id) }
        Button {
            if all { lib.removeDownloads(streamable.map(\.id)) } else { lib.download(streamable.filter { !lib.downloaded.contains($0.id) }, label: label) }
        } label: {
            Image(systemName: all ? "arrow.down.circle.fill" : "arrow.down.circle").font(.system(size: 15, weight: .semibold)).foregroundStyle(theme.p.txt)
                .frame(width: 38, height: 38).background(Circle().fill(Color.white.opacity(0.08)))
        }
        .buttonStyle(PressStyle())
        .help(all ? "Remove download" : "Download")
        .accessibilityLabel(all ? "Remove download" : "Download")
        .disabled(streamable.isEmpty)
    }
}

struct DownloadsView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var lib: LibraryStore
    @State private var items: [DownloadItem]?
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var confirmAll = false
    @State private var query = ""
    @State private var hits: ListHits?

    var body: some View {
        let p = theme.p
        let shown = (items ?? []).filter { lib.downloaded.contains($0.track.id) }
        let tracks = shown.map(\.track)
        let filtering = !selecting && !ListFilter.isBlank(query)
        Page {
            HStack {
                LargeTitle(text: "Downloads")
                Spacer()
                if !shown.isEmpty && !selecting { ListSearchField(text: $query) }
                if !shown.isEmpty {
                    if selecting {
                        SecondaryButton(title: "Cancel") { selecting = false; selected = [] }
                        PrimaryButton(title: selected.isEmpty ? "Remove" : "Remove (\(selected.count))", icon: "trash", enabled: !selected.isEmpty) {
                            lib.removeDownloads(Array(selected)); selecting = false; selected = []
                        }
                    } else {
                        Chip(label: "Select", icon: "checkmark.circle") { selecting = true }
                        Chip(label: "Remove all", icon: "trash") { confirmAll = true }
                    }
                }
            }
            Text("\(shown.count) songs · \(ByteCountFormatter.string(fromByteCount: Int64(shown.reduce(0) { $0 + $1.bytes }), countStyle: .file)) · play offline")
                .font(theme.ui(12.5)).foregroundStyle(p.t2).padding(.top, 2)
            if !shown.isEmpty && !selecting {
                HStack(spacing: 10) {
                    PrimaryButton(title: "Play", icon: "play.fill") { player.play(tracks, index: 0, source: "Downloads") }
                    SecondaryButton(title: "Shuffle", icon: "shuffle") { player.playShuffled(tracks, source: "Downloads") }
                }.padding(.top, 14)
            }
            Spacer().frame(height: 18)
            if items == nil { Shimmer(count: 3) }
            else if shown.isEmpty { EmptyHint(icon: "arrow.down.circle", text: "Download songs from any ⋯ menu to play them offline.") }
            if filtering, let h = hits {
                if h.idx.isEmpty { NoMatches() }
                ForEach(h.idx, id: \.self) { i in
                    TrackRow(t: h.list[i], index: i, number: i + 1) { player.play(h.list, index: i, source: "Downloads") }
                }
            } else {
            ForEach(Array(shown.enumerated()), id: \.element.track.id) { i, d in
                HStack(spacing: 8) {
                    if selecting {
                        Image(systemName: selected.contains(d.track.id) ? "checkmark.circle.fill" : "circle").font(.system(size: 17))
                            .foregroundStyle(selected.contains(d.track.id) ? p.txt : p.t3)
                    }
                    TrackRow(t: d.track, index: i, number: selecting ? nil : i + 1) {
                        if selecting { if selected.contains(d.track.id) { selected.remove(d.track.id) } else { selected.insert(d.track.id) } }
                        else { player.play(tracks, index: i, source: "Downloads") }
                    }
                }
            }
            }
        }
        .listFilter(tracks, selecting ? "" : query, $hits)
        .confirmationDialog("Remove all downloads?", isPresented: $confirmAll) {
            Button("Remove all", role: .destructive) { lib.removeAllDownloads() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Songs stay in your library and can be downloaded again.") }
        .task { items = await Engine.off { try Engine.core.downloads() } ?? [] }
        .onChange(of: lib.downloaded) { _, _ in Task { items = await Engine.off { try Engine.core.downloads() } ?? items } }
        .accessibilityIdentifier("downloads")
    }
}

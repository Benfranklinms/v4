import AppKit
import SwiftUI

struct Page<Content: View>: View {
    @EnvironmentObject var theme: Theme
    @ViewBuilder let content: () -> Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) { content() }
                .padding(.horizontal, 32).padding(.top, 36).padding(.bottom, 40)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollIndicators(.never)
    }
}

enum HomeCache { @MainActor static var feed: HomeFeed?; @MainActor static var at = Date.distantPast }

struct HomeView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @State private var feed: HomeFeed? = HomeCache.feed
    @State private var failed = false

    var body: some View {
        let p = theme.p
        Page {
            Text("\(feed?.greeting ?? "Hello"), Ben").font(theme.ui(26, .bold)).foregroundStyle(p.txt).enter(0)
            IsaBar().padding(.top, 18).frame(maxWidth: 620).enter(1)
            HStack(spacing: 8) {
                ForEach(Array((feed?.suggestions ?? ["rainy night drive, Malayalam + lo-fi", "focus, no vocals"]).enumerated()), id: \.offset) { i, s in
                    Chip(label: s, icon: "sparkles") { nav.push(.isa(s)) }.enter(i + 2)
                }
            }.padding(.top, 12)
            if let f = feed {
                if !f.mixes.isEmpty {
                    SectionTitle(text: "Daily Mixes")
                    ScrollView(.horizontal) {
                        HStack(spacing: 16) {
                            ForEach(Array(f.mixes.enumerated()), id: \.element.id) { i, m in
                                MixCard(m: m, i: i)
                            }
                        }
                    }.scrollIndicators(.never)
                }
                if !f.recent.isEmpty {
                    SectionTitle(text: "Recently played")
                    ForEach(Array(f.recent.prefix(8).enumerated()), id: \.offset) { i, t in
                        TrackRow(t: t, index: i) { player.play(f.recent, index: i, source: "Recently played") }
                    }
                }
                if !f.trending.isEmpty {
                    SectionTitle(text: "Trending")
                    ForEach(Array(f.trending.prefix(12).enumerated()), id: \.offset) { i, t in
                        TrackRow(t: t, index: i, number: i + 1) { player.play(f.trending, index: i, source: "Trending") }
                    }
                }
            } else if failed {
                Text("Can't reach YouTube Music right now.").font(theme.ui(13)).foregroundStyle(p.t3).padding(.top, 40)
            } else {
                SectionTitle(text: "Daily Mixes")
                Shimmer()
            }
        }
        .task {
            if feed == nil || Date().timeIntervalSince(HomeCache.at) > 1200 {
                do {
                    let f = try await Engine.core.home(tzOffsetMin: Engine.tzMinutes)
                    withAnimation(Motion.spring) { feed = f }
                    HomeCache.feed = f; HomeCache.at = Date()
                } catch { failed = true }
            }
        }
    }
}

struct MixCard: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    let m: Mix
    let i: Int
    @State private var hover = false
    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomTrailing) {
                ArtView(url: m.thumb, size: 160, radius: 12)
                Button { player.play(m.tracks, index: 0, source: m.title) } label: {
                    Image(systemName: "play.fill").font(.system(size: 14, weight: .bold)).foregroundStyle(p.bg)
                        .frame(width: 36, height: 36).background(Circle().fill(p.txt))
                }
                .buttonStyle(PressStyle()).padding(10)
                .opacity(hover ? 1 : 0).offset(y: hover ? 0 : 8)
            }
            Text(m.title).font(theme.ui(14, .semibold)).foregroundStyle(p.txt).padding(.top, 6)
            Text(m.subtitle).font(theme.ui(11.5, .medium)).foregroundStyle(p.t3).lineLimit(1)
        }
        .frame(width: 160)
        .scaleEffect(hover && !Motion.reduce ? 1.02 : 1)
        .onHover { h in withAnimation(Motion.spring) { hover = h } }
        .onTapGesture { nav.mixes[m.title] = m.tracks; nav.push(.mix(m.title)) }
        .enter(i)
    }
}

struct IsaBar: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @State private var text = ""
    var body: some View {
        let p = theme.p
        HStack(spacing: 10) {
            Image(systemName: "sparkles").foregroundStyle(p.txt)
            Text("Isa").font(theme.ui(13.5, .bold)).foregroundStyle(p.txt)
            Rectangle().fill(p.line3).frame(width: 0.75, height: 16)
            TextField("Ask Isa…", text: $text).textFieldStyle(.plain).font(theme.ui(13.5)).foregroundStyle(p.txt)
                .onSubmit(go)
            Button(action: go) {
                Image(systemName: "arrow.up").font(.system(size: 13, weight: .bold)).foregroundStyle(p.light ? .white : p.bg)
                    .frame(width: 30, height: 30).background(Circle().fill(theme.accent))
            }.buttonStyle(PressStyle())
        }
        .padding(.leading, 16).padding(.trailing, 6).frame(height: 44)
        .background(Capsule().fill(p.surf))
        .overlay(Capsule().stroke(p.line3, lineWidth: 0.6))
    }
    func go() {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        text = ""
        nav.push(.isa(t))
    }
}

enum SearchCache { @MainActor static var q = ""; @MainActor static var res: SearchResults? }

struct SearchView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @State private var q = SearchCache.q
    @State private var res: SearchResults? = SearchCache.res
    @State private var sugg: [String] = []
    @State private var loading = false
    @FocusState private var focused: Bool

    var body: some View {
        let p = theme.p
        Page {
            Text("Search").font(theme.ui(26, .bold)).foregroundStyle(p.txt)
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(p.t2)
                TextField("Songs, artists, albums", text: $q).textFieldStyle(.plain).font(theme.ui(14)).focused($focused).onSubmit { run(q) }
                if !q.isEmpty { IconButton("xmark.circle.fill", size: 12) { q = ""; sugg = [] }.foregroundStyle(p.t3) }
            }
            .padding(.horizontal, 16).frame(height: 44).frame(maxWidth: 620)
            .background(Capsule().fill(p.surf)).overlay(Capsule().stroke(p.line3, lineWidth: 0.6))
            .padding(.top, 16)
            if !sugg.isEmpty && focused {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(sugg.enumerated()), id: \.offset) { i, s in
                        Button { q = s; run(s) } label: {
                            HStack { Image(systemName: "magnifyingglass").foregroundStyle(p.t3); Text(s).font(theme.ui(13.5)).foregroundStyle(p.txt); Spacer() }
                                .padding(.vertical, 8).padding(.horizontal, 18)
                        }.buttonStyle(PressStyle(scale: 0.99)).enter(i)
                    }
                }.frame(maxWidth: 620).padding(.top, 6)
            }
            if loading { Shimmer().padding(.top, 24) }
            if let r = res, !loading {
                if !r.artists.isEmpty {
                    SectionTitle(text: "Artists")
                    ScrollView(.horizontal) {
                        HStack(spacing: 18) {
                            ForEach(Array(r.artists.enumerated()), id: \.element.id) { i, a in
                                VStack(spacing: 8) {
                                    ArtView(url: a.thumb, size: 110, circle: true)
                                    Text(a.name).font(theme.ui(12.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                                }.frame(width: 116).onTapGesture { nav.push(.artist(a.id)) }.enter(i)
                            }
                        }
                    }.scrollIndicators(.never)
                }
                if !r.tracks.isEmpty {
                    SectionTitle(text: "Songs")
                    ForEach(Array(r.tracks.enumerated()), id: \.offset) { i, t in
                        TrackRow(t: t, index: i) { player.play(r.tracks, index: i, source: "Search · \(q)") }
                    }
                }
                if !r.albums.isEmpty {
                    SectionTitle(text: "Albums")
                    AlbumStrip(albums: r.albums)
                }
                if !r.playlists.isEmpty {
                    SectionTitle(text: "Playlists")
                    ForEach(Array(r.playlists.enumerated()), id: \.element.id) { i, pl in
                        HStack(spacing: 12) {
                            ArtView(url: pl.thumb, size: 44, radius: 6)
                            VStack(alignment: .leading) {
                                Text(pl.title).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt)
                                Text(pl.subtitle).font(theme.ui(11.5)).foregroundStyle(p.t3)
                            }
                            Spacer()
                        }.padding(.vertical, 5).contentShape(Rectangle()).onTapGesture { nav.push(.playlist(pl.id)) }.enter(i)
                    }
                }
            }
        }
        .onAppear { focused = true }
        .task(id: q) {
            guard !q.isEmpty, q != SearchCache.q else { sugg = []; return }
            try? await Task.sleep(nanoseconds: 220_000_000)
            if Task.isCancelled { return }
            sugg = Array(((try? await Engine.core.searchSuggestions(query: q)) ?? []).prefix(6))
        }
    }

    func run(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        sugg = []
        loading = true
        focused = false
        Task {
            let r = try? await Engine.core.search(query: t)
            withAnimation(Motion.spring) { res = r; loading = false }
            SearchCache.q = t; SearchCache.res = r
        }
    }
}

struct AlbumStrip: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let albums: [Album]
    var body: some View {
        let p = theme.p
        ScrollView(.horizontal) {
            HStack(spacing: 16) {
                ForEach(Array(albums.enumerated()), id: \.element.id) { i, a in
                    VStack(alignment: .leading, spacing: 3) {
                        ArtView(url: a.thumb, size: 150, radius: 10)
                        Text(a.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1).padding(.top, 5)
                        Text([a.artists, a.year.map(String.init)].compactMap { $0 }.joined(separator: " · ")).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1)
                    }.frame(width: 150).onTapGesture { nav.push(.album(a.id)) }.enter(i)
                }
            }
        }.scrollIndicators(.never)
    }
}

struct LibraryView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @State private var filter = "Playlists"
    @State private var pls: [Playlist] = []
    @State private var albums: [Album] = []
    @State private var artists: [Artist] = []
    @State private var counts: LibraryCounts?

    var body: some View {
        let p = theme.p
        Page {
            HStack {
                Text("Library").font(theme.ui(26, .bold)).foregroundStyle(p.txt)
                Spacer()
                Chip(label: "Import", icon: "square.and.arrow.down") { nav.go(.importer) }
                Chip(label: "New playlist", icon: "plus") { _ = try? Engine.core.createPlaylist(title: "New playlist", tracks: []); reload(); nav.show("Playlist created") }
            }
            HStack(spacing: 8) {
                ForEach(["Playlists", "Albums", "Artists"], id: \.self) { f in Chip(label: f, selected: filter == f) { withAnimation(Motion.spring) { filter = f } } }
            }.padding(.top, 14)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 18)], alignment: .leading, spacing: 20) {
                if filter == "Playlists" {
                    Tile(title: "Liked Songs", sub: "\(counts?.liked ?? 0) songs", art: "", icon: "heart.fill", i: 0) { nav.push(.playlist("liked")) }
                    Tile(title: "Downloads", sub: "\(counts?.downloads ?? 0) songs", art: "", icon: "arrow.down.circle.fill", i: 1) { nav.go(.downloads) }
                    ForEach(Array(pls.enumerated()), id: \.element.id) { i, pl in
                        Tile(title: pl.title, sub: pl.subtitle, art: pl.thumb, i: i + 2) { nav.push(.playlist(pl.id)) }
                    }
                } else if filter == "Albums" {
                    ForEach(Array(albums.enumerated()), id: \.element.id) { i, a in Tile(title: a.title, sub: a.artists, art: a.thumb, i: i) { nav.push(.album(a.id)) } }
                } else {
                    ForEach(Array(artists.enumerated()), id: \.element.id) { i, a in Tile(title: a.name, sub: "Artist", art: a.thumb, circle: true, i: i) { nav.push(.artist(a.id)) } }
                }
            }.padding(.top, 20)
        }
        .task { reload() }
    }

    func reload() {
        let c = Engine.core
        counts = try? c.libraryCounts()
        pls = ((try? c.playlists()) ?? []) + ((try? c.savedPlaylists()) ?? [])
        albums = (try? c.savedAlbums()) ?? []
        artists = (try? c.savedArtists()) ?? []
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
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 3) {
            if let icon {
                ZStack { RoundedRectangle(cornerRadius: 12).fill(p.surf3); Image(systemName: icon).font(.system(size: 34)).foregroundStyle(p.txt) }.frame(width: 160, height: 160)
            } else {
                ArtView(url: art, size: 160, radius: 12, circle: circle)
            }
            Text(title).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt).lineLimit(1).padding(.top, 6)
            Text(sub).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1)
        }
        .frame(width: 160)
        .scaleEffect(hover && !Motion.reduce ? 1.02 : 1)
        .onHover { h in withAnimation(Motion.spring) { hover = h } }
        .onTapGesture(perform: action)
        .enter(i)
    }
}

/// Album / playlist / mix page with art, glow, play + shuffle.
struct TrackListPage: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    let title: String
    let subtitle: String
    let art: String
    let tracks: [Track]?
    let source: String
    var circle = false
    var actions: AnyView? = nil
    var extra: AnyView? = nil

    var body: some View {
        let p = theme.p
        ZStack(alignment: .top) {
            GlowView(color: theme.glow, opacity: 0.25, center: UnitPoint(x: 0.2, y: 0.0), radius: 600).frame(height: 420)
            Page {
                Button { nav.pop() } label: { Label("Back", systemImage: "chevron.left").font(theme.ui(12.5, .semibold)).foregroundStyle(p.t2) }
                    .buttonStyle(PressStyle()).opacity(nav.path.isEmpty ? 0 : 1)
                HStack(alignment: .bottom, spacing: 24) {
                    ArtView(url: art, size: 200, radius: 14, circle: circle).shadow(color: theme.glow.opacity(0.4), radius: 30)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(title).font(theme.ui(30, .bold)).foregroundStyle(p.txt).lineLimit(2)
                        Text(subtitle).font(theme.ui(13)).foregroundStyle(p.t2).lineLimit(2)
                        HStack(spacing: 12) {
                            Button { if let t = tracks, !t.isEmpty { Engine.core.setShuffle(on: false); player.play(t, index: 0, source: source) } } label: {
                                Label("Play", systemImage: "play.fill").font(theme.ui(13.5, .bold)).foregroundStyle(p.light ? .white : p.bg)
                                    .padding(.horizontal, 20).frame(height: 38).background(Capsule().fill(theme.accent))
                            }.buttonStyle(PressStyle())
                            Button { if let t = tracks, !t.isEmpty { player.playShuffled(t, source: source) } } label: {
                                Label("Shuffle", systemImage: "shuffle").font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt)
                                    .padding(.horizontal, 18).frame(height: 38).background(Capsule().fill(p.surf3))
                            }.buttonStyle(PressStyle())
                            if let a = actions { a }
                        }.padding(.top, 10)
                    }
                }.padding(.top, 10).enter(0)
                Spacer().frame(height: 24)
                if let ts = tracks {
                    ForEach(Array(ts.enumerated()), id: \.offset) { i, t in
                        TrackRow(t: t, index: i, number: i + 1) { player.play(ts, index: i, source: source) }
                    }
                } else {
                    Shimmer(count: 3)
                }
                if let e = extra { e }
            }
        }
    }
}

struct AlbumView: View {
    let id: String
    @State private var page: AlbumPage?
    @State private var saved = false
    var body: some View {
        TrackListPage(title: page?.album.title ?? "", subtitle: [page?.album.artists, page?.album.year.map(String.init), page?.album.kind].compactMap { $0 }.joined(separator: " · "),
                      art: page?.album.thumb ?? "", tracks: page?.tracks, source: page?.album.title ?? "Album",
                      actions: AnyView(SaveButton(saved: $saved) { if let a = page?.album { try? Engine.core.saveAlbum(album: a, saved: saved) } }))
            .task(id: id) {
                saved = (try? Engine.core.isSaved(kind: "album", id: id)) ?? false
                page = try? await Engine.core.album(id: id)
            }
    }
}

struct PlaylistView: View {
    let id: String
    @State private var page: PlaylistPage?
    @State private var saved = false
    @EnvironmentObject var nav: Nav
    var body: some View {
        let local = id.hasPrefix("local:") || id.hasPrefix("import:") || id == "liked"
        TrackListPage(title: page?.playlist.title ?? "", subtitle: [page?.playlist.subtitle, page?.playlist.trackCount.map { "\($0) songs" }].compactMap { $0 }.joined(separator: " · "),
                      art: page?.playlist.thumb ?? "", tracks: page?.tracks, source: page?.playlist.title ?? "Playlist",
                      actions: local ? (id == "liked" ? nil : AnyView(Chip(label: "Delete", icon: "trash") { try? Engine.core.deletePlaylist(playlistId: id); nav.pop(); nav.show("Playlist deleted") }))
                          : AnyView(SaveButton(saved: $saved) { if let pl = page?.playlist { try? Engine.core.savePlaylist(playlist: pl, saved: saved) } }))
            .task(id: id) {
                saved = (try? Engine.core.isSaved(kind: "playlist", id: id)) ?? false
                page = try? await Engine.core.playlist(id: id)
            }
    }
}

struct ArtistView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let id: String
    @State private var page: ArtistPage?
    @State private var saved = false
    var body: some View {
        let p = theme.p
        TrackListPage(title: page?.artist.name ?? "", subtitle: page?.artist.subtitle ?? "", art: page?.artist.thumb ?? "", tracks: page?.topTracks,
                      source: page?.artist.name ?? "Artist", circle: true,
                      actions: AnyView(SaveButton(saved: $saved) { if let a = page?.artist { try? Engine.core.saveArtist(artist: a, saved: saved) } }),
                      extra: AnyView(VStack(alignment: .leading) {
                          if let a = page, !a.albums.isEmpty { SectionTitle(text: "Albums & singles"); AlbumStrip(albums: a.albums) }
                          if let a = page, !a.similar.isEmpty {
                              SectionTitle(text: "Fans also like")
                              HStack(spacing: 18) {
                                  ForEach(Array(a.similar.prefix(8).enumerated()), id: \.element.id) { i, x in
                                      VStack { ArtView(url: x.thumb, size: 96, circle: true); Text(x.name).font(theme.ui(12, .semibold)).foregroundStyle(p.txt).lineLimit(1) }
                                          .frame(width: 100).onTapGesture { nav.push(.artist(x.id)) }.enter(i)
                                  }
                              }
                          }
                          if let d = page?.description { Text(d).font(theme.ui(12.5)).foregroundStyle(p.t2).lineLimit(8).padding(.top, 20).frame(maxWidth: 720, alignment: .leading) }
                      }))
            .task(id: id) {
                saved = (try? Engine.core.isSaved(kind: "artist", id: id)) ?? false
                page = try? await Engine.core.artist(id: id)
            }
    }
}

struct SaveButton: View {
    @EnvironmentObject var theme: Theme
    @Binding var saved: Bool
    let onChange: () -> Void
    var body: some View {
        Button { saved.toggle(); onChange() } label: {
            Image(systemName: saved ? "bookmark.fill" : "bookmark").font(.system(size: 14, weight: .semibold)).foregroundStyle(theme.p.txt)
                .frame(width: 38, height: 38).background(Circle().fill(theme.p.surf3))
                .symbolEffect(.bounce, value: saved)
        }.buttonStyle(PressStyle())
    }
}

struct DownloadsView: View {
    @State private var items: [Track]?
    var body: some View {
        TrackListPage(title: "Downloads", subtitle: "\(items?.count ?? 0) songs · play offline", art: items?.first?.thumb ?? "", tracks: items, source: "Downloads")
            .task { items = ((try? Engine.core.downloads()) ?? []).map { $0.track } }
    }
}

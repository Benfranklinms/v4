import AppKit
import SwiftUI

/// Top-bar search: field text, the glass dropdown (matching recent searches + live suggestions), the last
/// query and its results, and the Browse all page. Shared by the pill, the dropdown, the Search page,
/// keyboard shortcuts and the smoke test.
@MainActor
final class SearchModel: ObservableObject {
    static let shared = SearchModel()
    @Published var text = ""
    @Published var dropdown = false
    @Published var suggestions: [String] = []
    @Published var recents: [String] = []
    /// Keyboard selection in the dropdown (index into `rows`).
    @Published var highlight: Int?
    /// Last submitted query ("" = Browse all).
    @Published var query = ""
    @Published var results: SearchResults?
    @Published var loading = false
    @Published var failed = false
    @Published var filter = "All"
    private(set) var fieldFocused = false
    private var programmatic = false
    private var suggestTask: Task<Void, Never>?
    /// The window may hand the first text field focus on launch; that must not open Search.
    static let launchedAt = Date()

    static let filters = ["All", "Songs", "Artists", "Albums", "Playlists"]

    var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    /// Recent searches containing the typed text (at most 3).
    var recentMatches: [String] {
        let q = trimmed.lowercased()
        guard !q.isEmpty else { return [] }
        return Array(recents.filter { $0.lowercased().contains(q) }.prefix(3))
    }
    var suggestionRows: [String] {
        let r = Set(recentMatches.map { $0.lowercased() })
        return Array(suggestions.filter { !r.contains($0.lowercased()) }.prefix(6))
    }
    /// Every pickable dropdown row, in order (recents, then suggestions).
    var rows: [String] { recentMatches + suggestionRows }

    func loadRecents() {
        Task { recents = await Engine.off { Engine.core.recentSearches(limit: 20) } ?? recents }
    }

    /// Returns false when the focus should be refused (launch auto-focus).
    @discardableResult func focusChanged(_ f: Bool, user: Bool) -> Bool {
        if f && !user && Date().timeIntervalSince(SearchModel.launchedAt) < 2.5 { return false }
        fieldFocused = f
        if f {
            loadRecents()
            if trimmed.isEmpty { showBrowse() } else { openDropdown() }
        }
        return true
    }

    func typed(_ t: String) {
        if programmatic { return }
        suggestTask?.cancel()
        highlight = nil
        let q = t.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty {
            suggestions = []
            withAnimation(Motion.spring) { dropdown = false }
            if Nav.shared.section == .search && Nav.shared.path.isEmpty { query = ""; results = nil; loading = false; failed = false }
            return
        }
        if fieldFocused || dropdown { openDropdown() }
        suggestTask = Task {
            try? await Task.sleep(nanoseconds: 150_000_000)
            if Task.isCancelled { return }
            let s = (try? await Engine.core.searchSuggestions(query: q)) ?? []
            if Task.isCancelled || self.trimmed != q { return }
            self.suggestions = s
        }
    }

    func openDropdown() {
        guard !trimmed.isEmpty else { return }
        if !dropdown { withAnimation(Motion.spring) { dropdown = true } }
    }
    func closeDropdown() {
        highlight = nil
        if dropdown { withAnimation(Motion.spring) { dropdown = false } }
    }

    /// Arrow keys in the dropdown.
    func move(_ d: Int) {
        let n = rows.count
        guard dropdown, n > 0 else { return }
        if let h = highlight { highlight = (h + d + n) % n } else { highlight = d > 0 ? 0 : n - 1 }
    }

    /// Enter: the highlighted row, else the typed text.
    func submit() {
        if let h = highlight, h < rows.count { run(rows[h]) } else { run(text) }
    }

    /// Empty focus or the browse icon: the Browse all page.
    func showBrowse() {
        closeDropdown()
        if !trimmed.isEmpty { set(text: "") }
        query = ""; results = nil; loading = false; failed = false
        let nav = Nav.shared
        if nav.section != .search || !nav.path.isEmpty { nav.go(.search) }
    }

    func set(text t: String) {
        programmatic = true
        text = t
        programmatic = false
    }

    /// Opens results for `q` on the Search page.
    func run(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        suggestTask?.cancel()
        set(text: t)
        closeDropdown()
        suggestions = []
        query = t
        filter = "All"
        failed = false
        withAnimation(Motion.spring) { loading = true }
        recents.removeAll { $0.lowercased() == t.lowercased() }
        recents.insert(t, at: 0)
        Engine.bg { try Engine.core.addRecentSearch(query: t) }
        let nav = Nav.shared
        if nav.section != .search || !nav.path.isEmpty { nav.go(.search) }
        Task {
            let r = try? await Engine.core.search(query: t)
            guard self.query == t else { return }
            withAnimation(Motion.spring) { self.results = r; self.loading = false; self.failed = r == nil }
            nav.lastSearch = r
        }
    }

    func removeRecent(_ r: String) {
        withAnimation(Motion.spring) { recents.removeAll { $0 == r } }
        highlight = nil
        Engine.bg { try Engine.core.removeRecentSearch(query: r) }
    }

    enum TopHit { case artist(Artist), track(Track), album(Album) }
    /// Spotify-style Top result: the artist when the query names one, else the best song, else an album.
    static func top(_ r: SearchResults, query: String) -> TopHit? {
        let q = query.lowercased().trimmingCharacters(in: .whitespaces)
        if let a = r.artists.first {
            let n = a.name.lowercased()
            if n == q || q.contains(n) || n.hasPrefix(q) { return .artist(a) }
        }
        if let t = r.top ?? r.tracks.first { return .track(t) }
        if let a = r.artists.first { return .artist(a) }
        if let al = r.albums.first { return .album(al) }
        return nil
    }
}

// MARK: - pill

/// Centred pill: magnifier, "What do you want to play?", clear ×, divider, browse icon. Click to focus.
struct SearchPill: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @ObservedObject private var m = SearchModel.shared
    @FocusState private var focused: Bool
    @State private var hover = false
    @State private var userFocus = false

    var body: some View {
        let p = theme.p
        let on = focused || m.dropdown
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(on ? p.txt : p.t2)
            TextField("", text: $m.text, prompt: Text("What do you want to play?").foregroundColor(p.t2))
                .textFieldStyle(.plain)
                .font(theme.ui(14, .medium))
                .foregroundStyle(p.txt)
                .focused($focused)
                .focusEffectDisabled()
                .onSubmit { m.submit() }
                .accessibilityLabel("Search")
                .accessibilityIdentifier("search_field")
            if !m.text.isEmpty {
                Button { m.set(text: ""); m.typed(""); userFocus = true; focused = true } label: {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .semibold)).foregroundStyle(p.t2)
                        .frame(width: 24, height: 24).contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(scale: 0.9)).help("Clear").accessibilityLabel("Clear search")
                .transition(.opacity)
            }
            Rectangle().fill(p.light ? Color.black.opacity(0.14) : Color.white.opacity(0.16)).frame(width: 1, height: 20)
            Button { m.showBrowse() } label: {
                Image(systemName: "archivebox")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundStyle(nav.section == .search && m.query.isEmpty ? p.txt : p.t2)
                    .frame(width: 30, height: 30).contentShape(Rectangle())
            }
            .buttonStyle(PressStyle(scale: 0.9)).help("Browse all").accessibilityLabel("Browse all").accessibilityIdentifier("search_browse")
        }
        .padding(.leading, 14).padding(.trailing, 6)
        .frame(height: 40)
        .frame(maxWidth: .infinity)
        .background(Capsule().fill(p.light ? Color.black.opacity(on || hover ? 0.08 : 0.05) : Color.white.opacity(on || hover ? 0.11 : 0.075)))
        .overlay(Capsule().stroke(on ? p.txt.opacity(0.92) : Color.white.opacity(hover ? 0.14 : 0.06), lineWidth: on ? 1.5 : 0.7))
        .contentShape(Capsule())
        .onTapGesture { userFocus = true; focused = true; _ = m.focusChanged(true, user: true) }
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
        .overlay(alignment: .top) {
            if m.dropdown {
                SearchDropdown()
                    .fixedSize(horizontal: false, vertical: true)
                    .offset(y: 48)
                    .transition(.opacity.combined(with: .offset(y: -6)))
            }
        }
        .background(FrameProbe { LayoutProbe.searchPill = $0 })
        .onChange(of: focused) { _, f in
            let user = userFocus
            userFocus = false
            if !m.focusChanged(f, user: user) { focused = false }
        }
        .onChange(of: nav.focusSearch) { _, _ in userFocus = true; focused = true; _ = m.focusChanged(true, user: true) }
        .onChange(of: m.text) { _, t in m.typed(t) }
        .animation(Motion.spring, value: m.text.isEmpty)
    }
}

/// Glass dropdown under the pill: "Recent searches" (clock, ×) and "Suggestions" (typed part muted, the rest bold).
struct SearchDropdown: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject private var m = SearchModel.shared

    var body: some View {
        let p = theme.p
        let rec = m.recentMatches
        let sug = m.suggestionRows
        VStack(alignment: .leading, spacing: 0) {
            if !rec.isEmpty {
                header("Recent searches")
                ForEach(Array(rec.enumerated()), id: \.element) { i, r in
                    row(i, icon: "clock", text: Text(r).foregroundColor(p.txt), value: r, removable: true)
                }
            }
            if !rec.isEmpty && !sug.isEmpty {
                Rectangle().fill(p.light ? Color.black.opacity(0.08) : Color.white.opacity(0.08)).frame(height: 0.7)
                    .padding(.horizontal, 14).padding(.vertical, 6)
            }
            if !sug.isEmpty {
                header("Suggestions")
                ForEach(Array(sug.enumerated()), id: \.element) { i, s in
                    row(rec.count + i, icon: "magnifyingglass", text: emphasised(s), value: s, removable: false)
                }
            }
            if rec.isEmpty && sug.isEmpty {
                row(-1, icon: "magnifyingglass", text: Text("Search for “\(m.trimmed)”").foregroundColor(p.txt), value: m.trimmed, removable: false)
            }
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(16)
        .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
        .accessibilityIdentifier("search_dropdown")
    }

    private func header(_ t: String) -> some View {
        Text(t).font(theme.ui(11.5, .semibold)).foregroundStyle(theme.p.t2)
            .padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 6)
    }

    /// The typed prefix muted, the rest of the suggestion bold.
    private func emphasised(_ s: String) -> Text {
        let p = theme.p
        let q = m.trimmed
        if !q.isEmpty, s.lowercased().hasPrefix(q.lowercased()) {
            let head = String(s.prefix(q.count)), tail = String(s.dropFirst(q.count))
            return Text("\(Text(head).foregroundColor(p.t2))\(Text(tail).fontWeight(.bold).foregroundColor(p.txt))")
        }
        return Text(s).foregroundColor(p.txt)
    }

    private func row(_ i: Int, icon: String, text: Text, value: String, removable: Bool) -> some View {
        DropdownRow(index: i, icon: icon, text: text, removable: removable, pick: { m.run(value) }, remove: { m.removeRecent(value) })
    }
}

struct DropdownRow: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject private var m = SearchModel.shared
    let index: Int
    let icon: String
    let text: Text
    let removable: Bool
    let pick: () -> Void
    let remove: () -> Void
    @State private var hover = false

    var body: some View {
        let p = theme.p
        let lit = hover || (index >= 0 && m.highlight == index)
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 13, weight: .regular)).foregroundStyle(p.t2).frame(width: 18)
            text.font(theme.ui(13.5, .medium)).lineLimit(1)
            Spacer(minLength: 6)
            if removable {
                Button(action: remove) {
                    Image(systemName: "xmark").font(.system(size: 10.5, weight: .semibold)).foregroundStyle(p.t2)
                        .frame(width: 22, height: 22).contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(scale: 0.9)).help("Remove").accessibilityLabel("Remove from recent searches")
            }
        }
        .padding(.horizontal, 12).frame(height: 34)
        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(lit ? (p.light ? Color.black.opacity(0.06) : Color.white.opacity(0.08)) : .clear))
        .padding(.horizontal, 6)
        .contentShape(Rectangle())
        .onTapGesture(perform: pick)
        .onHover { h in hover = h; if h && index >= 0 { m.highlight = index } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Search page (results / Browse all)

struct SearchView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @ObservedObject private var m = SearchModel.shared

    var body: some View {
        let p = theme.p
        Page(name: "search") {
            if m.query.isEmpty {
                BrowseAll()
            } else {
                HStack(spacing: 8) {
                    ForEach(SearchModel.filters, id: \.self) { f in
                        FilterChip(label: f, selected: m.filter == f) { withAnimation(Motion.spring) { m.filter = f } }
                    }
                }
                .padding(.top, 4)
                if m.loading {
                    Shimmer().padding(.top, 24)
                } else if let r = m.results, !(r.tracks.isEmpty && r.artists.isEmpty && r.albums.isEmpty && r.playlists.isEmpty) {
                    results(r)
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(m.failed ? "Can't search right now." : "No results for “\(m.query)”").font(theme.ui(16, .semibold)).foregroundStyle(p.txt)
                        Text(m.failed ? "Check your connection and try again." : "Check the spelling or try other words.").font(theme.ui(12.5)).foregroundStyle(p.t2)
                        if m.failed { SecondaryButton(title: "Retry", icon: "arrow.clockwise") { m.run(m.query) } }
                    }.padding(.top, 30)
                }
            }
        }
        .accessibilityIdentifier("search_page")
    }

    @ViewBuilder private func results(_ r: SearchResults) -> some View {
        switch m.filter {
        case "Songs":
            ForEach(Array(r.tracks.enumerated()), id: \.offset) { i, t in
                TrackRow(t: t, index: i, number: i + 1) { player.play(r.tracks, index: i, source: "Search · \(m.query)") }
            }.padding(.top, 14)
        case "Artists":
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 18)], alignment: .leading, spacing: 20) {
                ForEach(Array(r.artists.enumerated()), id: \.element.id) { i, a in artistCell(a, size: 150, i: i) }
            }.padding(.top, 18)
        case "Albums":
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 18)], alignment: .leading, spacing: 20) {
                ForEach(Array(r.albums.enumerated()), id: \.element.id) { i, a in albumCell(a, size: 150, i: i) }
            }.padding(.top, 18)
        case "Playlists":
            playlistRows(r.playlists).padding(.top, 14)
        default:
            allResults(r)
        }
    }

    @ViewBuilder private func allResults(_ r: SearchResults) -> some View {
        let p = theme.p
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 24) {
                topResult(r).frame(width: 360)
                songs(r).frame(minWidth: 380)
            }
            VStack(alignment: .leading, spacing: 0) {
                topResult(r)
                songs(r)
            }
        }
        .padding(.top, 18)
        if !r.artists.isEmpty {
            Text("Artists").font(theme.ui(20, .bold)).foregroundStyle(p.txt).padding(.top, 28).padding(.bottom, 10)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 18) {
                    ForEach(Array(r.artists.prefix(10).enumerated()), id: \.element.id) { i, a in artistCell(a, size: 132, i: i) }
                }
            }.carousel()
        }
        if !r.albums.isEmpty {
            Text("Albums").font(theme.ui(20, .bold)).foregroundStyle(p.txt).padding(.top, 24).padding(.bottom, 10)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 18) {
                    ForEach(Array(r.albums.prefix(10).enumerated()), id: \.element.id) { i, a in albumCell(a, size: 132, i: i) }
                }
            }.carousel()
        }
        if !r.playlists.isEmpty {
            Text("Playlists").font(theme.ui(20, .bold)).foregroundStyle(p.txt).padding(.top, 24).padding(.bottom, 6)
            playlistRows(Array(r.playlists.prefix(6)))
        }
    }

    @ViewBuilder private func topResult(_ r: SearchResults) -> some View {
        let p = theme.p
        if let hit = SearchModel.top(r, query: m.query) {
            VStack(alignment: .leading, spacing: 10) {
                Text("Top result").font(theme.ui(20, .bold)).foregroundStyle(p.txt)
                TopResultCard(hit: hit, tracks: r.tracks, query: m.query)
            }
        }
    }

    @ViewBuilder private func songs(_ r: SearchResults) -> some View {
        let p = theme.p
        if !r.tracks.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Songs").font(theme.ui(20, .bold)).foregroundStyle(p.txt).padding(.bottom, 6)
                ForEach(Array(r.tracks.prefix(4).enumerated()), id: \.offset) { i, t in
                    TrackRow(t: t, index: i, showAlbum: false) { player.play(r.tracks, index: i, source: "Search · \(m.query)") }
                }
            }
        }
    }

    private func artistCell(_ a: Artist, size: CGFloat, i: Int) -> some View {
        VStack(spacing: 8) {
            ArtView(url: a.thumb, size: size, circle: true)
            Text(a.name).font(theme.ui(13, .semibold)).foregroundStyle(theme.p.txt).lineLimit(1)
        }
        .frame(width: size + 6).contentShape(Rectangle())
        .onTapGesture {
            nav.seeds[a.id] = RouteSeed(title: a.name, subtitle: "Artist", thumb: a.thumb)
            nav.push(.artist(a.id))
        }
        .enter(i)
        .accessibilityElement(children: .combine).accessibilityAddTraits(.isButton)
    }

    private func albumCell(_ a: Album, size: CGFloat, i: Int) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            ArtView(url: a.thumb, size: size, radius: 10)
            Text(a.title).font(theme.ui(13, .semibold)).foregroundStyle(theme.p.txt).lineLimit(1).padding(.top, 5)
            Text([a.artists, a.year.map(String.init)].compactMap { $0 }.joined(separator: " · ")).font(theme.ui(11.5)).foregroundStyle(theme.p.t3).lineLimit(1)
        }
        .frame(width: size).contentShape(Rectangle())
        .onTapGesture {
            nav.seeds[a.id] = RouteSeed(title: a.title, subtitle: a.artists, thumb: a.thumb)
            nav.push(.album(a.id))
        }
        .contextMenu { AlbumMenuItems(a: a) }
        .enter(i)
    }

    private func playlistRows(_ pls: [Playlist]) -> some View {
        let p = theme.p
        return ForEach(Array(pls.enumerated()), id: \.element.id) { i, pl in
            HStack(spacing: 12) {
                ArtView(url: pl.thumb, size: 44, radius: 6)
                VStack(alignment: .leading) {
                    Text(pl.title).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                    Text(pl.subtitle).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1)
                }
                Spacer()
            }
            .padding(.vertical, 5).contentShape(Rectangle())
            .onTapGesture {
                nav.seeds[pl.id] = RouteSeed(title: pl.title, subtitle: pl.subtitle, thumb: pl.thumb)
                nav.push(.playlist(pl.id))
            }
            .contextMenu { PlaylistMenuItems(p: pl) }
            .enter(i)
        }
    }
}

/// Filter chip: selected = light fill with dark text, others = soft grey.
struct FilterChip: View {
    @EnvironmentObject var theme: Theme
    let label: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        let p = theme.p
        Button(action: { Haptics.tap(); action() }) {
            Text(label).font(theme.ui(13, .semibold))
                .foregroundStyle(selected ? p.bg : p.txt)
                .padding(.horizontal, 14).frame(height: 32)
                .background(Capsule().fill(selected ? p.txt : (p.light ? Color.black.opacity(0.06) : Color.white.opacity(0.08))))
        }
        .buttonStyle(PressStyle(scale: 0.95))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("search_filter_\(label)")
    }
}

/// Big Top result card: art (round for artists), name, kind, white play button.
struct TopResultCard: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    let hit: SearchModel.TopHit
    let tracks: [Track]
    let query: String
    @State private var hover = false

    var body: some View {
        let p = theme.p
        let (title, kind, thumb, circle): (String, String, String, Bool) = {
            switch hit {
            case .artist(let a): return (a.name, "Artist", a.thumb, true)
            case .track(let t): return (t.title, "Song · \(t.artists)", t.thumb, false)
            case .album(let a): return (a.title, "Album · \(a.artists)", a.thumb, false)
            }
        }()
        VStack(alignment: .leading, spacing: 0) {
            ArtView(url: thumb, size: 92, radius: 8, circle: circle)
                .shadow(color: .black.opacity(0.35), radius: 10, y: 5)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(theme.ui(26, .bold)).foregroundStyle(p.txt).lineLimit(1)
                    Text(kind).font(theme.ui(12.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                }
                Spacer(minLength: 10)
                Button { play() } label: {
                    Image(systemName: "play.fill").font(.system(size: 17, weight: .bold))
                        .foregroundStyle(Color(red: 0.035, green: 0.035, blue: 0.04))
                        .frame(width: 48, height: 48)
                        .background(Circle().fill(Color(red: 0.95, green: 0.95, blue: 0.95)))
                        .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
                }
                .buttonStyle(PressStyle(scale: 0.92))
                .opacity(hover || Motion.reduce ? 1 : 0.92)
                .help("Play").accessibilityLabel("Play \(title)").accessibilityIdentifier("top_result_play")
            }
            .padding(.top, 16)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(p.light ? Color.black.opacity(hover ? 0.07 : 0.05) : Color.white.opacity(hover ? 0.09 : 0.06)))
        .contentShape(Rectangle())
        .onHover { h in withAnimation(.easeOut(duration: 0.15)) { hover = h } }
        .onTapGesture { open() }
        .accessibilityIdentifier("top_result")
    }

    private func open() {
        switch hit {
        case .artist(let a): nav.seeds[a.id] = RouteSeed(title: a.name, subtitle: "Artist", thumb: a.thumb); nav.push(.artist(a.id))
        case .album(let a): nav.seeds[a.id] = RouteSeed(title: a.title, subtitle: a.artists, thumb: a.thumb); nav.push(.album(a.id))
        case .track: play()
        }
    }

    private func play() {
        switch hit {
        case .track(let t):
            let i = tracks.firstIndex { $0.id == t.id }
            if let i { player.play(tracks, index: i, source: "Search · \(query)") } else { player.play([t] + tracks, index: 0, source: "Search · \(query)") }
        case .artist(let a):
            Task {
                if let pg = try? await Engine.core.artist(id: a.id), !pg.topTracks.isEmpty { player.play(pg.topTracks, index: 0, source: a.name) }
                else if !tracks.isEmpty { player.play(tracks, index: 0, source: "Search · \(query)") }
            }
        case .album(let a):
            Task { if let pg = try? await Engine.core.album(id: a.id), !pg.tracks.isEmpty { player.play(pg.tracks, index: 0, source: a.title) } }
        }
    }
}

// MARK: - Browse all

/// Browse all: grey-gradient genre / mood tiles with a tilted mono pattern square; a tap searches it.
struct BrowseAll: View {
    @EnvironmentObject var theme: Theme
    static let tiles: [(String, BrowseArt.Kind)] = [
        ("Malayalam", .orb), ("Tamil", .waves), ("Hindi", .blobs), ("Lo-fi", .stripes), ("Focus", .rings),
        ("Chill", .half), ("Workout", .dots), ("Indie", .target), ("Hip-Hop", .bars), ("Rock", .orb),
        ("Jazz", .waves), ("Classical", .blobs), ("Sleep", .stripes), ("Party", .rings), ("Romance", .half),
        ("Drive", .dots), ("Rain", .target), ("Acoustic", .bars), ("Electronic", .orb), ("Devotional", .waves),
    ]

    var body: some View {
        Text("Browse all").font(theme.ui(26, .bold)).foregroundStyle(theme.p.txt)
            .padding(.top, 6).padding(.bottom, 16)
            .accessibilityAddTraits(.isHeader)
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 260), spacing: 16)], alignment: .leading, spacing: 16) {
            ForEach(Array(BrowseAll.tiles.enumerated()), id: \.offset) { i, t in
                BrowseTile(title: t.0, kind: t.1, seed: i) { SearchModel.shared.run(BrowseAll.query(t.0)) }
                    .enter(i)
            }
        }
        .accessibilityIdentifier("browse_all")
    }

    static func query(_ title: String) -> String {
        switch title {
        case "Focus", "Chill", "Workout", "Sleep", "Party", "Drive", "Rain", "Romance": return "\(title.lowercased()) music"
        default: return "\(title) songs"
        }
    }
}

struct BrowseTile: View {
    @EnvironmentObject var theme: Theme
    let title: String
    let kind: BrowseArt.Kind
    let seed: Int
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .topLeading) {
                LinearGradient(colors: [Color(white: 0.17 + Double(seed % 3) * 0.015), Color(white: 0.27), Color(white: 0.45 + Double(seed % 4) * 0.02)],
                               startPoint: .bottomLeading, endPoint: .topTrailing)
                BrowseArt(kind: kind)
                    .frame(width: g.size.height * 0.62, height: g.size.height * 0.62)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .shadow(color: .black.opacity(0.4), radius: 8, x: -2, y: 4)
                    .rotationEffect(.degrees(25))
                    .position(x: g.size.width - g.size.height * 0.24, y: g.size.height * 0.72)
                    .scaleEffect(hover && !Motion.reduce ? 1.04 : 1, anchor: .bottomTrailing)
                Text(title).font(theme.ui(16, .bold)).foregroundStyle(.white).lineLimit(1)
                    .padding(.leading, 14).padding(.top, 12)
            }
        }
        .aspectRatio(1.67, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Color.white.opacity(hover ? 0.18 : 0.06), lineWidth: 0.7))
        .contentShape(Rectangle())
        .onHover { h in withAnimation(Motion.spring) { hover = h } }
        .onTapGesture(perform: action)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityAddTraits(.isButton)
    }
}

/// Mono pattern squares for Browse all tiles (drawn, no images).
struct BrowseArt: View {
    enum Kind { case orb, waves, blobs, stripes, rings, half, dots, target, bars }
    let kind: Kind
    var body: some View {
        Canvas { g, s in
            let c = CGPoint(x: s.width / 2, y: s.height / 2)
            let w = s.width
            g.fill(Path(CGRect(origin: .zero, size: s)), with: .color(Color(white: 0.1)))
            switch kind {
            case .orb:
                g.fill(Path(ellipseIn: CGRect(x: c.x - w * 0.42, y: c.y - w * 0.42, width: w * 0.84, height: w * 0.84)),
                       with: .radialGradient(Gradient(colors: [.white, Color(white: 0.75), Color(white: 0.1).opacity(0)]), center: c, startRadius: 0, endRadius: w * 0.42))
            case .waves:
                g.fill(Path(CGRect(x: 0, y: 0, width: w, height: s.height * 0.4)), with: .linearGradient(Gradient(colors: [Color(white: 0.75), Color(white: 0.45)]), startPoint: .zero, endPoint: CGPoint(x: 0, y: s.height * 0.4)))
                for j in 0..<4 {
                    var p = Path()
                    let base = s.height * (0.32 + CGFloat(j) * 0.17)
                    p.move(to: CGPoint(x: 0, y: s.height))
                    for i in 0...16 { p.addLine(to: CGPoint(x: CGFloat(i) / 16 * w, y: base + 5 * sin(Double(i) * 0.8 + Double(j)))) }
                    p.addLine(to: CGPoint(x: w, y: s.height)); p.closeSubpath()
                    g.fill(p, with: .color(Color(white: 0.22 + Double(j) * 0.06)))
                }
            case .blobs:
                for (x, y, r, a) in [(0.3, 0.35, 0.32, 0.55), (0.72, 0.4, 0.28, 0.75), (0.5, 0.78, 0.3, 0.45)] as [(CGFloat, CGFloat, CGFloat, Double)] {
                    g.fill(Path(ellipseIn: CGRect(x: w * (x - r), y: s.height * y - w * r, width: 2 * w * r, height: 2 * w * r)),
                           with: .radialGradient(Gradient(colors: [Color(white: 0.85).opacity(a), Color(white: 0.85).opacity(0)]), center: CGPoint(x: w * x, y: s.height * y), startRadius: 0, endRadius: w * r))
                }
            case .stripes:
                for i in 0..<9 {
                    var p = Path()
                    let x = CGFloat(i) * w / 6 - w * 0.4
                    p.move(to: CGPoint(x: x, y: s.height)); p.addLine(to: CGPoint(x: x + w * 0.5, y: 0))
                    g.stroke(p, with: .color(Color(white: [0.25, 0.55, 0.85, 0.4][i % 4])), lineWidth: w / 9)
                }
            case .rings:
                for i in stride(from: 9, through: 1, by: -1) {
                    let r = CGFloat(i) * w * 0.055
                    g.fill(Path(ellipseIn: CGRect(x: w * 0.75 - r, y: s.height * 0.8 - r, width: 2 * r, height: 2 * r)), with: .color(Color(white: i % 2 == 0 ? 0.2 : 0.55 + Double(9 - i) * 0.04)))
                }
            case .half:
                g.fill(Path(CGRect(x: 0, y: s.height * 0.55, width: w, height: s.height * 0.45)), with: .color(Color(white: 0.85)))
                g.fill(Path(ellipseIn: CGRect(x: c.x - w * 0.3, y: c.y - w * 0.3, width: w * 0.6, height: w * 0.6)), with: .color(Color(white: 0.92)))
                var half = Path()
                half.addArc(center: c, radius: w * 0.3, startAngle: .degrees(180), endAngle: .degrees(360), clockwise: true)
                half.closeSubpath()
                g.fill(half, with: .color(Color(white: 0.08)))
            case .dots:
                for i in 0..<6 {
                    for j in 0..<6 {
                        let big = i == 1 && j == 3
                        let r: CGFloat = big ? w * 0.045 : w * 0.022
                        let x = CGFloat(i) * w / 6 + w / 12, y = CGFloat(j) * s.height / 6 + s.height / 12
                        g.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(.white.opacity(big ? 0.95 : 0.4)))
                    }
                }
            case .target:
                for i in 1...10 {
                    let r = CGFloat(i) * w * 0.05
                    g.stroke(Path(ellipseIn: CGRect(x: w * 0.65 - r, y: s.height * 0.65 - r, width: 2 * r, height: 2 * r)), with: .color(.white.opacity(0.35)), lineWidth: 0.7)
                }
            case .bars:
                g.fill(Path(CGRect(origin: .zero, size: s)), with: .color(Color(white: 0.88)))
                for i in 0..<5 {
                    let y = s.height * (0.2 + CGFloat(i) * 0.15)
                    g.fill(Path(roundedRect: CGRect(x: w * 0.15, y: y, width: w * (i % 2 == 0 ? 0.7 : 0.55), height: s.height * 0.07), cornerRadius: 1), with: .color(Color(white: 0.12)))
                }
            }
        }
        .accessibilityHidden(true)
    }
}

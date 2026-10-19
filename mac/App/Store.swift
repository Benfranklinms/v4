import AppKit
import SwiftUI

/// Library state shared by the sidebar, Library page, menus and playlist pages.
/// Mutations update the published lists first (optimistic), then persist in the Rust core off the main thread.
@MainActor
final class LibraryStore: ObservableObject {
    static let shared = LibraryStore()
    @Published var playlists: [Playlist]? = nil
    @Published var savedPlaylists: [Playlist] = []
    @Published var albums: [Album] = []
    @Published var artists: [Artist] = []
    @Published var liked: Set<String> = []
    @Published var downloaded: Set<String> = []
    @Published var hidden: Set<String> = []
    @Published var pinned: Set<String> = []
    @Published var trackChanges = 0
    /// Core library revision last seen (bumped by every like / playlist / save / download change, from any
    /// view or the core itself). Pages showing library data reload when it moves.
    @Published private(set) var rev: UInt64 = 0
    /// Writes queued on the core and not finished yet (a refresh never overwrites optimistic state meanwhile).
    private var pendingWrites = 0
    private var watchTimer: Timer?

    /// Persist a library change in the core, then publish the new revision (pages and counts refresh live).
    func write(_ f: @escaping () throws -> Void) {
        pendingWrites += 1
        Engine.bg {
            try f()
        } done: {
            let r = Engine.core.libraryRevision()
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self.pendingWrites = max(0, self.pendingWrites - 1)
                    MenuTracker.run {
                        if r != self.rev { self.rev = r }
                        if self.pendingWrites == 0 { self.refreshLive() }
                    }
                }
            }
        }
    }

    /// Cheap revision poll (an atomic read in the core, off the main thread) so changes made anywhere
    /// (imports, Isa saves, downloads finishing) show up without a relaunch. Publishes only on change.
    func startWatching() {
        guard watchTimer == nil else { return }
        let t = Timer(timeInterval: 2, repeats: true) { _ in
            Engine.work.async {
                let r = Engine.core.libraryRevision()
                DispatchQueue.main.async { MainActor.assumeIsolated { LibraryStore.shared.observe(r) } }
            }
        }
        t.tolerance = 0.5
        RunLoop.main.add(t, forMode: .common)
        watchTimer = t
    }

    private func observe(_ r: UInt64) {
        guard r != rev else { return }
        MenuTracker.run {
            guard r != self.rev else { return }
            self.rev = r
            self.refreshLive()
        }
    }

    /// Reloads likes, playlists (with counts) and downloads after a change, unless writes are still queued
    /// or an Undo window is open (those publish again when they land).
    private var liveTask: Task<Void, Never>?
    private func refreshLive() {
        liveTask?.cancel()
        liveTask = Task {
            try? await Task.sleep(nanoseconds: 60_000_000)
            if Task.isCancelled || pendingWrites > 0 || Nav.shared.undoPending { return }
            async let pl = Engine.off { try Engine.core.playlists() }
            async let lk = Engine.off { try Engine.core.liked().map(\.id) }
            async let dl = Engine.off { try Engine.core.downloads().map(\.track.id) }
            let (a, b, c) = await (pl, lk, dl)
            if Task.isCancelled || pendingWrites > 0 || Nav.shared.undoPending { return }
            MenuTracker.run {
                if let a, LibraryStore.sig(a) != LibraryStore.sig(self.playlists ?? []) { self.playlists = a }
                if let b { let s = Set(b); if s != self.liked { self.liked = s } }
                if let c { let s = Set(c); if s != self.downloaded { self.downloaded = s } }
            }
        }
    }

    static func sig(_ xs: [Playlist]) -> [String] { xs.map { "\($0.id)|\($0.title)|\($0.subtitle)|\($0.thumb)|\($0.trackCount ?? 0)" } }

    func refresh() {
        Task {
            async let pl = Engine.off { try Engine.core.playlists() }
            async let sp = Engine.off { try Engine.core.savedPlaylists() }
            async let al = Engine.off { try Engine.core.savedAlbums() }
            async let ar = Engine.off { try Engine.core.savedArtists() }
            async let lk = Engine.off { try Engine.core.liked().map(\.id) }
            async let dl = Engine.off { try Engine.core.downloads().map(\.track.id) }
            async let pn = Engine.off { Engine.core.getSetting(key: "pinned") ?? "" }
            let (a, b, c, d, e, f, g) = await (pl, sp, al, ar, lk, dl, pn)
            MenuTracker.run {
                self.playlists = a ?? self.playlists ?? []
                self.savedPlaylists = b ?? []
                self.albums = c ?? []
                self.artists = d ?? []
                self.liked = Set(e ?? [])
                self.downloaded = Set(f ?? [])
                self.pinned = Set((g ?? "").split(separator: "\n").map(String.init))
            }
        }
    }

    var visiblePlaylists: [Playlist] {
        let all = (playlists ?? []) + savedPlaylists
        return all.filter { !hidden.contains($0.id) }.sorted { pinned.contains($0.id) && !pinned.contains($1.id) }
    }

    static func isLocal(_ id: String) -> Bool { id.hasPrefix("local:") || id.hasPrefix("import:") || id.hasPrefix("isa:") }

    // MARK: likes
    func isLiked(_ id: String) -> Bool { liked.contains(id) }
    @discardableResult func toggleLike(_ t: Track) -> Bool {
        let now = !liked.contains(t.id)
        if now { liked.insert(t.id) } else { liked.remove(t.id) }
        write { try Engine.core.setLiked(track: t, liked: now) }
        Haptics.tap()
        return now
    }

    // MARK: playlists
    func create(name: String, description: String, tracks: [Track]) async -> Playlist? {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return nil }
        guard let p = await Engine.off({ try Engine.core.createPlaylistNamed(title: n, description: description, cover: "", tracks: tracks) }) else { return nil }
        playlists = [p] + (playlists ?? []).filter { $0.id != p.id }
        return p
    }

    func rename(_ p: Playlist, to name: String, description: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        let sub = description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? p.subtitle : description
        var q = p; q.title = n; q.subtitle = sub
        playlists = (playlists ?? []).map { $0.id == p.id ? q : $0 }
        self.write { try Engine.core.editPlaylist(playlistId: p.id, title: n, description: sub, cover: "") }
    }

    func add(_ t: Track, to p: Playlist) {
        playlists = (playlists ?? []).map { x in
            guard x.id == p.id else { return x }
            var y = x; y.trackCount = (x.trackCount ?? 0) + 1; return y
        }
        self.write { try Engine.core.addToPlaylist(playlistId: p.id, track: t) }
        trackChanges += 1
        Nav.shared.show("Added to \(p.title)")
    }

    /// Delete with undo: hidden now, committed after the undo window (one transaction in the core).
    func delete(_ lists: [Playlist]) {
        guard !lists.isEmpty else { return }
        let ids = lists.map(\.id)
        if case .playlist(let id) = Nav.shared.path.last, ids.contains(id) { Nav.shared.pop() }
        let msg = lists.count == 1 ? "Deleted \(lists[0].title)" : "Deleted \(lists.count) playlists"
        Nav.shared.undoable(msg, apply: { self.hidden.formUnion(ids) }, undo: { self.hidden.subtract(ids) }, commit: {
            self.playlists = (self.playlists ?? []).filter { !ids.contains($0.id) }
            let saved = self.savedPlaylists.filter { ids.contains($0.id) }
            self.savedPlaylists.removeAll { ids.contains($0.id) }
            self.hidden.subtract(ids)
            let local = ids.filter(LibraryStore.isLocal)
            self.write {
                _ = try Engine.core.deletePlaylists(ids: local)
                for s in saved { try Engine.core.savePlaylist(playlist: s, saved: false) }
            }
        })
    }

    func togglePin(_ id: String) {
        if pinned.contains(id) { pinned.remove(id) } else { pinned.insert(id) }
        let v = pinned.joined(separator: "\n")
        self.write { try Engine.core.setSetting(key: "pinned", value: v) }
    }

    // MARK: saving albums / playlists / artists
    func setSaved(album a: Album, _ on: Bool) {
        if on { albums = [a] + albums.filter { $0.id != a.id } } else { albums.removeAll { $0.id == a.id } }
        self.write { try Engine.core.saveAlbum(album: a, saved: on) }
    }
    func setSaved(playlist p: Playlist, _ on: Bool) {
        if on { savedPlaylists = [p] + savedPlaylists.filter { $0.id != p.id } } else { savedPlaylists.removeAll { $0.id == p.id } }
        self.write { try Engine.core.savePlaylist(playlist: p, saved: on) }
    }
    func setSaved(artist a: Artist, _ on: Bool) {
        if on { artists = [a] + artists.filter { $0.id != a.id } } else { artists.removeAll { $0.id == a.id } }
        self.write { try Engine.core.saveArtist(artist: a, saved: on) }
    }

    func isSaved(album id: String) -> Bool { albums.contains { $0.id == id } }
    func isSaved(playlist id: String) -> Bool { savedPlaylists.contains { $0.id == id } }
    func isSaved(artist id: String) -> Bool { artists.contains { $0.id == id } }

    /// Bookmark toggle: optimistic, persisted in the background, toast with Undo.
    func toggleSaved(album a: Album) {
        let on = !isSaved(album: a.id)
        let before = albums
        Nav.shared.undoable(on ? "Saved to Library" : "Removed from Library",
                            apply: { if on { self.albums = [a] + self.albums.filter { $0.id != a.id } } else { self.albums.removeAll { $0.id == a.id } } },
                            undo: { self.albums = before },
                            commit: { self.write { try Engine.core.saveAlbum(album: a, saved: on) } })
    }
    func toggleSaved(playlist pl: Playlist) {
        let on = !isSaved(playlist: pl.id)
        let before = savedPlaylists
        Nav.shared.undoable(on ? "Saved to Library" : "Removed from Library",
                            apply: { if on { self.savedPlaylists = [pl] + self.savedPlaylists.filter { $0.id != pl.id } } else { self.savedPlaylists.removeAll { $0.id == pl.id } } },
                            undo: { self.savedPlaylists = before },
                            commit: { self.write { try Engine.core.savePlaylist(playlist: pl, saved: on) } })
    }
    func toggleSaved(artist a: Artist) {
        let on = !isSaved(artist: a.id)
        let before = artists
        Nav.shared.undoable(on ? "Saved to Library" : "Removed from Library",
                            apply: { if on { self.artists = [a] + self.artists.filter { $0.id != a.id } } else { self.artists.removeAll { $0.id == a.id } } },
                            undo: { self.artists = before },
                            commit: { self.write { try Engine.core.saveArtist(artist: a, saved: on) } })
    }

    func removeFromPlaylist(_ id: String, position: Int, track: Track) {
        self.write { try Engine.core.removeFromPlaylist(playlistId: id, position: UInt32(position)) }
        playlists = (playlists ?? []).map { x in
            guard x.id == id else { return x }
            var y = x; y.trackCount = max(0, (x.trackCount ?? 1) - 1); return y
        }
        Task { try? await Task.sleep(nanoseconds: 150_000_000); self.trackChanges += 1 }
        Nav.shared.show("Removed from playlist")
    }

    // MARK: downloads
    func download(_ tracks: [Track], label: String) {
        Nav.shared.show(tracks.count == 1 ? "Downloading…" : "Downloading \(tracks.count) songs…")
        Task {
            var ok = 0
            for t in tracks {
                if await (try? Engine.core.download(track: t)) != nil { ok += 1; MenuTracker.run { self.downloaded.insert(t.id) } }
                self.observe(Engine.core.libraryRevision())
            }
            Nav.shared.show(ok == tracks.count ? "Downloaded \(label)" : "Downloaded \(ok) of \(tracks.count)")
        }
    }

    func removeDownloads(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        let set = Set(ids)
        Nav.shared.undoable(ids.count == 1 ? "Removed download" : "Removed \(ids.count) downloads",
                            apply: { self.downloaded.subtract(set) },
                            undo: { self.downloaded.formUnion(set) },
                            commit: { self.write { _ = try Engine.core.removeDownloads(ids: ids) } })
    }

    func removeAllDownloads() {
        let all = downloaded
        Nav.shared.undoable("Removed all downloads", apply: { self.downloaded = [] }, undo: { self.downloaded = all }, commit: {
            self.write { _ = try Engine.core.removeAllDownloads() }
        })
    }
}

/// While an NSMenu (context / "…" menu) is open, store updates wait until it closes, so SwiftUI doesn't rebuild
/// the menu under the pointer (that flickered and reset the Add to Playlist submenu).
@MainActor
enum MenuTracker {
    private(set) static var open = false
    private static var pending: [() -> Void] = []
    static func install() {
        let nc = NotificationCenter.default
        nc.addObserver(forName: NSMenu.didBeginTrackingNotification, object: nil, queue: .main) { _ in MainActor.assumeIsolated { open = true } }
        nc.addObserver(forName: NSMenu.didEndTrackingNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                open = false
                let p = pending; pending = []
                p.forEach { $0() }
            }
        }
    }
    static func run(_ f: @escaping () -> Void) { if open { pending.append(f) } else { f() } }
}

/// Google account shown in the Account sheet.
@MainActor
final class AccountStore: ObservableObject {
    static let shared = AccountStore()
    @Published var info: AccountInfo?
    @Published var signedIn = UserDefaults.standard.bool(forKey: "signedIn") { didSet { UserDefaults.standard.set(signedIn, forKey: "signedIn") } }

    func load(refresh: Bool = false) {
        Task {
            if let a = await (try? Engine.core.accountInfo(refresh: false)) ?? nil { info = a }
            if refresh || info == nil, let a = await (try? Engine.core.accountInfo(refresh: true)) ?? nil { info = a }
        }
    }

    func signIn(cookie: String) async -> Bool {
        do {
            try await Engine.core.signInCookie(cookie: cookie)
            signedIn = true
            load(refresh: true)
            return true
        } catch { return false }
    }

    func signOut() {
        signedIn = false
        info = nil
        Task { try? await Engine.core.signOut() }
        Nav.shared.show("Signed out")
    }
}

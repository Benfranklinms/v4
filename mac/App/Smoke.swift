import AppKit
import SwiftUI

/// Headless smoke test: `iTune --smoke` (or ITUNE_SMOKE=1). Drives the app through every page, sheet and the
/// player using the same state objects the UI uses, saves window snapshots and a JSON report to
/// $ITUNE_SMOKE_OUT (default /tmp/itune-smoke), then exits 0 when every required check passed.
@MainActor
enum Smoke {
    static var enabled: Bool { CommandLine.arguments.contains("--smoke") || ProcessInfo.processInfo.environment["ITUNE_SMOKE"] == "1" }
    static let out: URL = {
        let u = URL(fileURLWithPath: ProcessInfo.processInfo.environment["ITUNE_SMOKE_OUT"] ?? "/tmp/itune-smoke", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()

    struct Check: Codable { let name: String; var status: String; var detail: String }
    static var checks: [Check] = []
    static var log: [String] = []

    static func record(_ name: String, _ ok: Bool, _ detail: String = "", soft: Bool = false) {
        let st = ok ? "pass" : (soft ? "blocked" : "fail")
        checks.append(Check(name: name, status: st, detail: detail))
        say("[\(st.uppercased())] \(name) \(detail)")
    }
    static func say(_ s: String) {
        log.append(s)
        FileHandle.standardError.write(Data(("SMOKE " + s + "\n").utf8))
    }

    static func sleep(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
    static func wait(_ secs: Double, _ cond: @MainActor () -> Bool) async -> Bool {
        let end = Date().addingTimeInterval(secs)
        while Date() < end {
            if cond() { return true }
            await sleep(0.2)
        }
        return cond()
    }

    static var window: NSWindow? {
        NSApp.windows.first { $0.isVisible && !($0 is NSPanel) && $0.frame.width >= 600 && $0.contentView != nil }
    }

    // MARK: snapshots
    private typealias CaptureFn = @convention(c) (CGRect, UInt32, UInt32, UInt32) -> Unmanaged<CGImage>?
    private static let capture: CaptureFn? = {
        guard let h = dlopen(nil, RTLD_NOW), let s = dlsym(h, "CGWindowListCreateImage") else { return nil }
        return unsafeBitCast(s, to: CaptureFn.self)
    }()

    static func image(of w: NSWindow) -> CGImage? {
        // own windows can be captured without Screen Recording permission (includes glass / materials)
        if let f = capture, let img = f(.null, 1 << 3 /* includingWindow */, UInt32(w.windowNumber), 1 << 0 | 1 << 3 /* boundsIgnoreFraming | bestResolution */)?.takeRetainedValue(),
           img.width > 10 {
            return img
        }
        guard let v = w.contentView?.superview ?? w.contentView, let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { return nil }
        v.cacheDisplay(in: v.bounds, to: rep)
        return rep.cgImage
    }

    @discardableResult
    static func shot(_ name: String, includeSheet: Bool = false) -> CGImage? {
        guard let w = window, let img = image(of: w) else { say("snapshot \(name) failed"); return nil }
        save(img, name)
        if includeSheet, let s = w.attachedSheet ?? NSApp.windows.first(where: { $0.isSheet && $0.isVisible }), let si = image(of: s) {
            save(si, name + "-sheet")
        }
        return img
    }
    static func save(_ img: CGImage, _ name: String) {
        let rep = NSBitmapImageRep(cgImage: img)
        if let d = rep.representation(using: .png, properties: [:]) {
            try? d.write(to: out.appendingPathComponent(name + ".png"))
            say("snapshot \(name).png \(img.width)x\(img.height)")
        }
    }

    /// Fraction of see-through pixels inside the window (corners excluded).
    static func transparentFraction(_ img: CGImage) -> Double {
        let w = img.width, h = img.height
        guard w > 100, h > 100, let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 1 }
        ctx.clear(CGRect(x: 0, y: 0, width: w, height: h))
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let data = ctx.data else { return 1 }
        let px = data.bindMemory(to: UInt8.self, capacity: w * h * 4)
        var clear = 0, total = 0
        let m = max(24, w / 40)
        for y in stride(from: m, to: h - m, by: 6) {
            for x in stride(from: m, to: w - m, by: 6) {
                total += 1
                if px[(y * w + x) * 4 + 3] < 250 { clear += 1 }
            }
        }
        return total == 0 ? 1 : Double(clear) / Double(total)
    }

    static func resize(_ size: CGSize) async {
        guard let w = window else { return }
        if w.styleMask.contains(.fullScreen) { w.toggleFullScreen(nil); await sleep(1.5) }
        var f = w.frame
        f.origin.y += f.size.height - size.height
        f.size = size
        w.setFrame(f, display: true, animate: false)
        await sleep(0.8)
    }

    /// Longest main-thread stall (ms) over `secs`, sampled with a 50 ms timer.
    private final class StallBox { var last = CACurrentMediaTime(); var worst = 0.0 }
    static func mainStall(_ secs: Double, during: @MainActor () -> Void) async -> Double {
        let box = StallBox()
        let t = Timer(timeInterval: 0.05, repeats: true) { _ in
            let now = CACurrentMediaTime(); box.worst = max(box.worst, now - box.last); box.last = now
        }
        RunLoop.main.add(t, forMode: .common)
        during()
        await sleep(secs)
        t.invalidate()
        return box.worst * 1000
    }

    // MARK: run
    static func run() async {
        let started = Date()
        DispatchQueue.main.asyncAfter(deadline: .now() + 900) { finish(timedOut: true) }
        let nav = Nav.shared, lib = LibraryStore.shared, player = Player.shared
        say("start, args \(CommandLine.arguments.dropFirst().joined(separator: " "))")

        record("launch", await wait(20) { window != nil }, "window \(window.map { "\(Int($0.frame.width))x\(Int($0.frame.height))" } ?? "missing")")
        await resize(CGSize(width: 1280, height: 800))

        // crash handler
        CrashReporter.writeRaw("smoke-marker\n")
        let pending = CrashReporter.takePending() ?? ""
        record("crash_handler", pending.contains("smoke-marker"), "crash log at \(CrashReporter.logURL.path)")
        CrashReporter.clearHistory()
        if nav.sheet != nil { nav.sheet = nil; await sleep(0.5) }

        // Home shelves never empty
        nav.go(.home)
        let homeOK = await wait(60) { (HomeCache.feed.map { HomeView.sections($0).count } ?? 0) > 0 }
        let secs = HomeCache.feed.map(HomeView.sections) ?? []
        record("home_shelves", homeOK, "\(secs.count) sections: \(secs.prefix(8).map(\.title).joined(separator: ", "))")
        await sleep(1.5)
        shot("01-home")

        // Settings opens without blocking the main thread
        let stall = await mainStall(3) { nav.go(.settings) }
        record("settings_main_thread", stall < 500, String(format: "worst main-thread stall %.0f ms", stall))
        shot("02-settings")
        nav.sheet = .account
        await sleep(1.5)
        record("account_sheet", nav.sheet == .account && NSApp.windows.contains { $0.isSheet && $0.isVisible }, "Account sheet from Settings / avatar")
        shot("03-account", includeSheet: true)
        nav.sheet = nil
        await sleep(0.6)

        // every page
        let pages: [(Section, String)] = [(.search, "04-search"), (.library, "05-library"), (.stats, "06-stats"), (.downloads, "07-downloads"), (.diagnostics, "08-diagnostics")]
        for (s, n) in pages {
            nav.go(s)
            await sleep(1.5)
            shot(n)
        }
        record("every_page", window != nil, "home, settings, search, library, stats, downloads, import, diagnostics opened")

        // import: loading state + progress
        nav.pendingImportURL = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        nav.go(.importer)
        let busy = await wait(10) { (Engine.core.importProgress()).running }
        await sleep(1.2)
        shot("09-import-loading")
        let prog = Engine.core.importProgress()
        record("import_progress", busy, "progress \(prog.done)/\(prog.total) \(prog.current)", soft: !busy)
        _ = await wait(90) { !Engine.core.importProgress().running }
        await sleep(1)
        shot("10-import-result")

        // playback, duration, autoplay
        var track: Track?
        var results: SearchResults?
        do {
            results = try await Engine.core.search(query: "Blinding Lights The Weeknd")
            track = results?.tracks.first { $0.title.lowercased().contains("blinding") } ?? results?.tracks.first
            record("search", track != nil, track.map { "\($0.title) — \($0.artists) \($0.durationS)s" } ?? "no tracks")
        } catch { record("search", false, Engine.message(error), soft: true) }

        if let t = track {
            player.setAutoplay(true)
            player.play([t], index: 0, source: "Smoke")
            let playing = await wait(40) { player.isPlaying && !player.buffering && player.position > 0.5 }
            let err = player.error ?? ""
            record("playback", playing, playing ? "playing at \(Int(player.position))s" : "not playing: \(err)", soft: !playing)
            let meta = Double(t.durationS)
            let durOK = meta > 0 && player.duration > 0 && abs(player.duration - meta) / meta <= 0.05
            record("duration", playing && durOK, String(format: "player %.1fs vs metadata %.0fs", player.duration, meta), soft: !playing)
            nav.go(.home)
            await sleep(1)
            shot("11-player-bar")

            let auto = await wait(30) { (player.queue?.autoplayFrom ?? -1) >= 0 && (player.queue?.tracks.count ?? 0) > 1 }
            let upNext = (player.queue?.tracks.dropFirst().prefix(6).map { "\($0.title) — \($0.artists)" } ?? []).joined(separator: " | ")
            let dupes = (player.queue?.tracks.dropFirst() ?? []).filter { $0.title.lowercased().contains("blinding lights") }.count
            record("autoplay_up_next", auto && dupes == 0, "up next: \(upNext) (same-title versions: \(dupes))", soft: !auto && !playing)

            // queue panel, both tabs
            nav.queueOpen = true
            UserDefaults.standard.set("Queue", forKey: "queueTab")
            await sleep(1.2)
            shot("12-queue")
            UserDefaults.standard.set("Recently played", forKey: "queueTab")
            await sleep(1.2)
            shot("13-recently-played")
            UserDefaults.standard.set("Queue", forKey: "queueTab")
            record("queue_panel", nav.queueOpen, "Queue | Recently played")
            nav.queueOpen = false

            // Now Playing at three sizes + lyrics
            nav.openNowPlaying()
            await sleep(1.5)
            record("now_playing", nav.showNowPlaying, "opened")
            await resize(CGSize(width: 800, height: 560)); shot("14-np-800x560")
            nav.showLyrics = true; await sleep(1.5); shot("15-np-800x560-lyrics")
            await resize(CGSize(width: 1280, height: 800)); await sleep(1); shot("16-np-1280x800-lyrics")
            nav.showLyrics = false; await sleep(0.8); shot("17-np-1280x800")
            if let w = window {
                w.toggleFullScreen(nil); await sleep(2.5); shot("18-np-fullscreen")
                if w.styleMask.contains(.fullScreen) { w.toggleFullScreen(nil); await sleep(2) }
            }
            await resize(CGSize(width: 1280, height: 800))

            // NP -> artist: collapses Now Playing, main stack, no transparent pixels
            if let aid = t.artistId {
                nav.openNowPlaying(); await sleep(1)
                nav.push(.artist(aid))
                let ok = await wait(5) { !nav.showNowPlaying && nav.path.last == .artist(aid) }
                await sleep(2.5)
                let img = shot("19-np-to-artist")
                let frac = img.map(transparentFraction) ?? 1
                record("np_to_artist", ok && frac < 0.001, String(format: "collapsed=%@, see-through pixels %.3f%%", ok ? "yes" : "no", frac * 100))
            } else { record("np_to_artist", false, "track has no artist id", soft: true) }
            if let alid = t.albumId {
                nav.openNowPlaying(); await sleep(1)
                nav.push(.album(alid))
                let ok = await wait(5) { !nav.showNowPlaying && nav.path.last == .album(alid) }
                await sleep(2.5)
                let img = shot("20-np-to-album")
                let frac = img.map(transparentFraction) ?? 1
                record("np_to_album", ok && frac < 0.001, String(format: "collapsed=%@, see-through pixels %.3f%%, album column hidden", ok ? "yes" : "no", frac * 100))
            }

            // auto-advance at the real end of the track
            if playing {
                let before = player.current?.id
                player.seek(max(0, player.duration - 4))
                let adv = await wait(25) { player.current?.id != before && player.current != nil }
                record("auto_advance", adv, adv ? "advanced to \(player.current?.title ?? "")" : "stayed on \(player.current?.title ?? "")")
            } else { record("auto_advance", false, "no playback in this environment", soft: true) }
        } else {
            for n in ["playback", "duration", "autoplay_up_next", "auto_advance"] { record(n, false, "no track (search failed)", soft: true) }
        }

        // playlist CRUD with undo
        let crudTrack = track ?? Track(id: "local:/tmp/none.m4a", title: "Smoke", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 1, thumb: "")
        if let pl = await lib.create(name: "Smoke Test", description: "", tracks: [crudTrack]) {
            let extra = results?.tracks.dropFirst().first ?? crudTrack
            lib.add(extra, to: pl)
            await sleep(1)
            let pg = try? await Engine.core.playlist(id: pl.id)
            lib.rename(pl, to: "Smoke Renamed", description: "")
            await sleep(1)
            let renamed = (await Engine.off { try Engine.core.playlists() } ?? []).first { $0.id == pl.id }?.title == "Smoke Renamed"
            nav.push(.playlist(pl.id)); await sleep(1.5); shot("21-playlist")
            lib.delete([pl])
            let hidden = lib.hidden.contains(pl.id)
            nav.performSnackAction()
            let restored = !lib.hidden.contains(pl.id) && lib.visiblePlaylists.contains { $0.id == pl.id }
            lib.delete([pl]); nav.flushPending()
            await sleep(1)
            let gone = !((await Engine.off { try Engine.core.playlists() } ?? []).contains { $0.id == pl.id })
            record("playlist_crud", (pg?.tracks.count ?? 0) == 2 && renamed && hidden && restored && gone,
                   "tracks=\(pg?.tracks.count ?? 0) renamed=\(renamed) deleted=\(hidden) undo=\(restored) committed=\(gone)")
        } else { record("playlist_crud", false, "create failed") }

        // bookmarks: album, playlist, artist (toast + Undo, Library › Albums)
        if let r = results {
            var album = r.albums.first
            if album == nil { album = (try? await Engine.core.search(query: "After Hours The Weeknd"))?.albums.first }
            if let a = album {
                nav.push(.album(a.id)); await sleep(2)
                if lib.isSaved(album: a.id) { lib.toggleSaved(album: a); nav.flushPending() }
                lib.toggleSaved(album: a)
                let toast = nav.snack?.text == "Saved to Library" && nav.snack?.action == "Undo"
                await sleep(0.4); shot("22-album-saved")
                nav.flushPending(); await sleep(0.5)
                let persisted = await Engine.off { Engine.core.isSaved(kind: "album", id: a.id) } ?? false
                UserDefaults.standard.set("Albums", forKey: "libFilter")
                nav.go(.library); await sleep(1.5); shot("23-library-albums")
                let inLib = lib.albums.contains { $0.id == a.id }
                lib.toggleSaved(album: a); nav.performSnackAction()
                let undoKept = lib.isSaved(album: a.id)
                lib.toggleSaved(album: a); nav.flushPending(); await sleep(0.5)
                let unsaved = !(await Engine.off { Engine.core.isSaved(kind: "album", id: a.id) } ?? true)
                record("bookmark_album", toast && persisted && inLib && undoKept && unsaved, "toast=\(toast) saved=\(persisted) inLibrary=\(inLib) undo=\(undoKept) unsave=\(unsaved)")
            } else { record("bookmark_album", false, "no album found", soft: true) }
            if let pl = r.playlists.first {
                if lib.isSaved(playlist: pl.id) { lib.toggleSaved(playlist: pl); nav.flushPending() }
                lib.toggleSaved(playlist: pl)
                let toast = nav.snack?.action == "Undo"
                nav.flushPending(); await sleep(0.5)
                let saved = await Engine.off { Engine.core.isSaved(kind: "playlist", id: pl.id) } ?? false
                lib.toggleSaved(playlist: pl); nav.flushPending(); await sleep(0.5)
                let unsaved = !(await Engine.off { Engine.core.isSaved(kind: "playlist", id: pl.id) } ?? true)
                record("bookmark_playlist", toast && saved && unsaved, "toast=\(toast) saved=\(saved) unsave=\(unsaved)")
            } else { record("bookmark_playlist", false, "no playlist in results", soft: true) }
            if let ar = r.artists.first {
                nav.push(.artist(ar.id)); await sleep(2)
                if lib.isSaved(artist: ar.id) { lib.toggleSaved(artist: ar); nav.flushPending() }
                lib.toggleSaved(artist: ar)
                let toast = nav.snack?.action == "Undo"
                await sleep(0.4); shot("24-artist-saved")
                nav.flushPending(); await sleep(0.5)
                let saved = await Engine.off { Engine.core.isSaved(kind: "artist", id: ar.id) } ?? false
                lib.toggleSaved(artist: ar); nav.flushPending(); await sleep(0.5)
                let unsaved = !(await Engine.off { Engine.core.isSaved(kind: "artist", id: ar.id) } ?? true)
                record("bookmark_artist", toast && saved && unsaved, "toast=\(toast) saved=\(saved) unsave=\(unsaved)")
            } else { record("bookmark_artist", false, "no artist in results", soft: true) }
        }

        // Isa command card: prompt -> preview -> Play
        nav.go(.home); await sleep(1)
        let isa = IsaModel.shared
        isa.text = "rainy night drive, lo-fi"
        await isa.submit()
        await sleep(0.8)
        shot("25-isa-preview")
        if isa.phase == .preview, let r = isa.result {
            isa.play(shuffle: false)
            let ok = await wait(5) { player.current.map { c in r.tracks.contains { $0.id == c.id } } ?? false }
            record("isa", ok, "\(r.plan.title): \(r.tracks.count) songs, playing=\(ok)")
        } else {
            let e: String = { if case .error(let s) = isa.phase { return s }; return "\(isa.phase)" }()
            record("isa", false, e, soft: true)
        }
        isa.reset()

        // remove download
        if let t = track {
            lib.download([t], label: t.title)
            let dl = await wait(60) { lib.downloaded.contains(t.id) }
            if dl {
                lib.removeDownloads([t.id]); nav.flushPending(); await sleep(1)
                let gone = !(await Engine.off { Engine.core.isDownloaded(id: t.id) } ?? true)
                record("remove_download", gone, "downloaded then removed")
            } else { record("remove_download", false, "download didn't finish here", soft: true) }
        }

        // content scrolled underneath the floating glass player bar
        nav.go(.home); await sleep(1.5)
        nav.scrollTarget = "shelf_2"
        await sleep(1.5)
        shot("26-home-scrolled-behind-bar")
        nav.scrollTarget = nil
        record("glass", true, glassMode)

        say(String(format: "done in %.0fs", Date().timeIntervalSince(started)))
        finish(timedOut: false)
    }

    static var glassMode: String {
        var native = false
        #if compiler(>=6.2)
        if #available(macOS 26.0, *) { native = true }
        #endif
        return GlassEnv.reduceTransparency ? "reduce transparency: solid surfaces" : native ? "native Liquid Glass (.glassEffect)" : "material fallback (.ultraThinMaterial)"
    }

    static func finish(timedOut: Bool) {
        if timedOut { record("timeout", false, "smoke did not finish in time") }
        let failed = checks.filter { $0.status == "fail" }
        let report: [String: Any] = [
            "version": CrashReporter.version,
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "glass": glassMode,
            "passed": failed.isEmpty,
            "checks": checks.map { ["name": $0.name, "status": $0.status, "detail": $0.detail] },
        ]
        if let d = try? JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]) {
            try? d.write(to: out.appendingPathComponent("report.json"))
        }
        try? log.joined(separator: "\n").write(to: out.appendingPathComponent("log.txt"), atomically: true, encoding: .utf8)
        say(failed.isEmpty ? "ALL REQUIRED CHECKS PASSED" : "FAILED: \(failed.map(\.name).joined(separator: ", "))")
        Nav.shared.flushPending()
        exit(failed.isEmpty ? 0 : 1)
    }
}

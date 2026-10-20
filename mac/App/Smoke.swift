import AppKit
import AVFoundation
import Combine
import SwiftUI

/// Headless smoke test: `iTune --smoke` (or ITUNE_SMOKE=1). Drives the app through every page, sheet and the
/// player using the same state objects the UI uses, saves window snapshots and a JSON report to
/// $ITUNE_SMOKE_OUT (default /tmp/itune-smoke), then exits 0 when every required check passed.
@MainActor
enum Smoke {
    static var enabled: Bool { CommandLine.arguments.contains("--smoke") || ProcessInfo.processInfo.environment["ITUNE_SMOKE"] == "1" }
    /// `--smoke-phase=session`: first launch that plays, pauses at a known position, caches Home and quits.
    static var sessionPhase: Bool { CommandLine.arguments.contains("--smoke-phase=session") }
    static var expectedURL: URL { out.appendingPathComponent("session-expected.json") }
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

    static func trace(_ s: String) { if enabled { say("trace: " + s) } }

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

    /// Writes a sine-tone AAC file for local playback tests.
    static func makeTone(seconds: Double, name: String) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name + ".m4a")
        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000]
        do {
            let f = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
            let fmt = f.processingFormat
            let frames = AVAudioFrameCount(seconds * fmt.sampleRate)
            guard let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: frames), let ch = buf.floatChannelData else { return nil }
            buf.frameLength = frames
            for i in 0..<Int(frames) { ch[0][i] = 0.15 * sin(2 * Float.pi * 440 * Float(i) / Float(fmt.sampleRate)) }
            try f.write(from: buf)
        } catch { say("tone: \(error)"); return nil }
        return url
    }

    // MARK: phase 1 (session): play, pause at ~6 s, cache Home, quit through the normal terminate path
    static func runSessionPhase() async {
        DispatchQueue.main.asyncAfter(deadline: .now() + 240) { exit(3) }
        let player = Player.shared
        say("session phase: start")
        _ = await wait(20) { window != nil }
        // Home feed fetched once so the next (cold) launch can show it from the cache
        Nav.shared.go(.home)
        let home = await wait(90) { HomeCache.source == "network" }
        say("session phase: home cached from network=\(home)")
        guard let a = makeTone(seconds: 40, name: "session-a"), let b = makeTone(seconds: 40, name: "session-b") else { say("no tone"); exit(4) }
        let ta = Track(id: "local:" + a.absoluteString, title: "Session tone A", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 40, thumb: "")
        let tb = Track(id: "local:" + b.absoluteString, title: "Session tone B", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 40, thumb: "")
        player.setAutoplay(false)
        player.play([ta, tb], index: 0, source: "Session smoke")
        _ = await wait(20) { player.startedId == ta.id && player.isPlaying && player.position > 0.3 }
        player.seek(6)
        _ = await wait(10) { player.position >= 7 }
        player.toggle()  // pause (saves)
        await sleep(0.5)
        let exp: [String: Any] = ["id": ta.id, "title": ta.title, "position": player.position, "source": "Session smoke", "queue": 2]
        if let d = try? JSONSerialization.data(withJSONObject: exp) { try? d.write(to: expectedURL) }
        say(String(format: "session phase: paused %@ at %.2fs, quitting", ta.title, player.position))
        NSApp.terminate(nil)
    }

    // MARK: run
    static func run() async {
        if sessionPhase { await runSessionPhase(); return }
        let started = Date()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1200) { finish(timedOut: true) }
        let nav = Nav.shared, lib = LibraryStore.shared, player = Player.shared
        say("start, args \(CommandLine.arguments.dropFirst().joined(separator: " "))")
        let systemRT = NSWorkspace.shared.accessibilityDisplayShouldReduceTransparency
        // CI VMs report Reduce Transparency (no GPU); snapshots use glass, one extra snapshot checks the solid path.
        if ProcessInfo.processInfo.environment["ITUNE_FORCE_GLASS"] != "0" { GlassEnv.forceGlass = true; Theme.shared.objectWillChange.send() }
        say("system reduce transparency: \(systemRT), reduce motion: \(Motion.reduce)")

        record("launch", await wait(20) { window != nil }, "window \(window.map { "\(Int($0.frame.width))x\(Int($0.frame.height))" } ?? "missing")")

        // cold launch: cached Home on screen within 300 ms of launch (stale-while-revalidate)
        _ = await wait(5) { HomeCache.cachedShownAt > 0 }
        let coldMs = HomeCache.cachedShownAt > 0 ? max(0, HomeCache.cachedShownAt - Launch.finishedAt) * 1000 : -1
        record("home_cached_cold_launch", coldMs >= 0 && coldMs < 300, coldMs < 0 ? "no cached feed shown (source \(HomeCache.source))" : String(format: "cached shelves on screen %.0f ms after launch", coldMs))
        shot("00-cold-launch-home")

        // session restored paused at the saved position, Play resumes there
        if let d = try? Data(contentsOf: expectedURL), let exp = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
           let eid = exp["id"] as? String, let epos = exp["position"] as? Double {
            let restored = await wait(5) { player.current?.id == eid }
            let posOK = abs(player.position - epos) < 1.5
            let paused = !player.isPlaying && player.startedId == nil
            let qOK = (player.queue?.tracks.count ?? 0) == (exp["queue"] as? Int ?? 2) && player.queue?.sourceTitle == (exp["source"] as? String)
            shot("00b-session-restored")
            player.toggle()
            let resumed = await wait(15) { player.startedId == eid && player.isPlaying && player.position >= epos - 0.6 }
            let at = player.position
            player.toggle()
            record("session_restore", restored && posOK && paused && qOK && resumed,
                   String(format: "track=%@ restored=%@ position %.2f (saved %.2f) paused=%@ queue=%@ resumed=%@ at %.2fs", player.current?.title ?? "-", restored ? "yes" : "no", player.position, epos, paused ? "yes" : "no", qOK ? "yes" : "no", resumed ? "yes" : "no", at))
        } else {
            record("session_restore", false, "phase-1 expectation missing (\(expectedURL.path))")
        }
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
        let base = await mainStall(3) { nav.go(.library) }
        nav.go(.home); await sleep(1)
        let stall = await mainStall(3) { nav.go(.settings) }
        // beachball shows after ~2 s; CI VMs render on the CPU, so the bar is 1.5 s with Library as a baseline
        record("settings_main_thread", stall < 1500, String(format: "worst main-thread stall opening Settings %.0f ms (Library baseline %.0f ms)", stall, base))
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
        // sidebar selection + top bar follow navigation (they used to show the previous page)
        nav.go(.library); await sleep(1.2)
        nav.go(.stats); await sleep(1.5)
        let sb = RenderProbe.sidebar, tb = RenderProbe.topBar
        shot("05b-stats-selection")
        if let w = window { w.contentView?.needsDisplay = true; w.displayIfNeeded() }
        await sleep(0.5)
        shot("05c-stats-selection-redrawn")
        record("chrome_follows_nav", sb.contains("stats") && tb.contains("stats"), "after Library -> Stats: sidebar rendered \(sb), top bar rendered \(tb)")

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

        // local playback: duration within 5 % + auto-advance (works without network)
        if let a = makeTone(seconds: 12, name: "tone-a"), let b = makeTone(seconds: 12, name: "tone-b") {
            let ta = Track(id: "local:" + a.absoluteString, title: "Smoke tone A", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 12, thumb: "")
            let tb = Track(id: "local:" + b.absoluteString, title: "Smoke tone B", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 12, thumb: "")
            player.setAutoplay(false)
            player.play([ta, tb], index: 0, source: "Smoke tones")
            let ok = await wait(20) { player.startedId == ta.id && player.isPlaying && player.position > 0.3 }
            record("local_playback", ok, ok ? "playing local file at \(String(format: "%.1f", player.position))s" : "not playing: \(player.error ?? "-")")
            let durOK = abs(player.duration - 12) / 12 <= 0.05
            record("duration_local", ok && durOK, String(format: "shown %.2fs, AVPlayer item %.2fs, file 12s", player.duration, player.itemDuration))
            if ok {
                player.seek(8)
                let adv = await wait(20) { player.current?.id == tb.id }
                record("auto_advance_local", adv, adv ? "advanced A -> B at the end" : "stayed on \(player.current?.title ?? "-") at \(Int(player.position))s")
            } else { record("auto_advance_local", false, "local playback did not start") }
            player.toggle()
            player.setAutoplay(true)
        } else { record("local_playback", false, "couldn't write test audio") }

        // playback, duration, autoplay
        var track: Track?
        var results: SearchResults?
        var npAlbum: String?
        do {
            results = try await Engine.core.search(query: "Blinding Lights The Weeknd")
            track = results?.tracks.first { $0.title.lowercased().contains("blinding") } ?? results?.tracks.first
            npAlbum = track?.albumId ?? results?.albums.first?.id
            if npAlbum == nil { npAlbum = (try? await Engine.core.search(query: "After Hours The Weeknd"))?.albums.first?.id }
            record("search", track != nil, track.map { "\($0.title) — \($0.artists) \($0.durationS)s" } ?? "no tracks")
        } catch { record("search", false, Engine.message(error), soft: true) }

        if let t = track {
            say("step: autoplay on")
            player.setAutoplay(true)
            say("step: play")
            player.play([t], index: 0, source: "Smoke")
            say("step: waiting for playback")
            // only counts when this track's own stream started (not leftover audio)
            let playing = await wait(40) { player.startedId == t.id && player.isPlaying && !player.buffering && player.position > 0.5 }
            let err = player.error ?? ""
            record("playback", playing, playing ? "streaming \(t.title) at \(Int(player.position))s" : "stream not available here: \(err.prefix(200))", soft: !playing)
            let meta = Double(t.durationS)
            let streamS = Double(player.lastStreamMs) / 1000
            let durOK = meta > 0 && abs(player.duration - meta) / meta <= 0.05 && (streamS == 0 || abs(streamS - meta) / meta <= 0.05)
            record("duration", playing && durOK, String(format: "shown %.1fs, stream %.1fs, AVPlayer item %.1fs, metadata %.0fs", player.duration, streamS, player.itemDuration, meta), soft: !playing)
            nav.go(.home)
            await sleep(1)
            shot("11-player-bar")
            say("step: waiting for autoplay")

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
            if let alid = npAlbum {
                nav.openNowPlaying(); await sleep(1)
                nav.push(.album(alid))
                let ok = await wait(5) { !nav.showNowPlaying && nav.path.last == .album(alid) }
                await sleep(2.5)
                let img = shot("20-np-to-album")
                let frac = img.map(transparentFraction) ?? 1
                record("np_to_album", ok && frac < 0.001, String(format: "collapsed=%@, see-through pixels %.3f%%, album column hidden", ok ? "yes" : "no", frac * 100))
            }

            // auto-advance at the real end of the track
            // fresh start of the searched track, then jump near its real end
            player.play([t], index: 0, source: "Smoke advance")
            let again = await wait(40) { player.startedId == t.id && player.isPlaying && player.position > 0.5 }
            if again {
                _ = await wait(20) { (player.queue?.tracks.count ?? 0) > 1 }
                player.seek(max(0, player.duration - 4))
                let adv = await wait(30) { player.current?.id != t.id && player.current != nil }
                record("auto_advance", adv, adv ? "advanced to \(player.current?.title ?? "")" : "stayed on \(player.current?.title ?? "") at \(Int(player.position))s/\(Int(player.duration))s")
            } else { record("auto_advance", false, "stream not available here: \(player.error ?? "-")", soft: true) }
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
            let r2 = try? await Engine.core.search(query: "The Weeknd")
            var plCand = r.playlists.first ?? r2?.playlists.first
            if plCand == nil, let it = HomeCache.feed?.shelves.flatMap(\.items).first(where: { $0.kind == "playlist" }) {
                plCand = Playlist(id: it.id, title: it.title, subtitle: it.subtitle, thumb: it.thumb, trackCount: nil, source: "yt")
            }
            if let pl = plCand {
                if lib.isSaved(playlist: pl.id) { lib.toggleSaved(playlist: pl); nav.flushPending() }
                lib.toggleSaved(playlist: pl)
                let toast = nav.snack?.action == "Undo"
                nav.flushPending(); await sleep(0.5)
                let saved = await Engine.off { Engine.core.isSaved(kind: "playlist", id: pl.id) } ?? false
                lib.toggleSaved(playlist: pl); nav.flushPending(); await sleep(0.5)
                let unsaved = !(await Engine.off { Engine.core.isSaved(kind: "playlist", id: pl.id) } ?? true)
                record("bookmark_playlist", toast && saved && unsaved, "toast=\(toast) saved=\(saved) unsave=\(unsaved)")
            } else { record("bookmark_playlist", false, "no playlist in results", soft: true) }
            if let ar = r.artists.first ?? r2?.artists.first {
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

        // Isa command card (centred pop-up): prompt -> preview -> Play
        nav.go(.home); await sleep(1)
        let isa = IsaModel.shared
        nav.openIsa(); await sleep(0.8)
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
        nav.closeIsa(); await sleep(0.5)

        // remove download: UI path with Undo (file deletion is covered by the core test)
        do {
            let fake = "smoke-download-test"
            lib.downloaded.insert(fake)
            lib.removeDownloads([fake])
            let toast = nav.snack?.action == "Undo" && !lib.downloaded.contains(fake)
            nav.performSnackAction()
            let undone = lib.downloaded.contains(fake)
            lib.removeDownloads([fake]); nav.flushPending()
            record("remove_download_ui", toast && undone && !lib.downloaded.contains(fake), "toast=\(toast) undo=\(undone)")
        }
        // remove a real download
        if let t = track {
            lib.download([t], label: t.title)
            let dl = await wait(60) { lib.downloaded.contains(t.id) }
            if dl {
                lib.removeDownloads([t.id]); nav.flushPending(); await sleep(1)
                let gone = !(await Engine.off { Engine.core.isDownloaded(id: t.id) } ?? true)
                record("remove_download", gone, "downloaded then removed")
            } else { record("remove_download", false, "download didn't finish here", soft: true) }
        }


        // ---------------------------------------------------------------- 0.2.0 fix checks
        await fixChecks(track: track, results: results)

        // ---------------------------------------------------------------- 0.2.1 checks
        await checks021(track: track, results: results)

        // ---------------------------------------------------------------- 0.2.5 in-list search
        await listSearchChecks()

        // content scrolled underneath the floating glass player bar
        nav.go(.home); await sleep(1.5)
        nav.scrollTarget = "shelf_2"
        await sleep(1.5)
        shot("26-home-scrolled-behind-bar")
        nav.scrollTarget = nil
        record("glass", true, glassMode + (GlassEnv.forceGlass == true ? " (forced on for snapshots; system RT=\(systemRT))" : ""))
        GlassEnv.forceGlass = false
        Theme.shared.objectWillChange.send()
        await sleep(1.2)
        shot("27-reduce-transparency-solid")
        GlassEnv.forceGlass = nil
        Theme.shared.objectWillChange.send()

        say(String(format: "done in %.0fs", Date().timeIntervalSince(started)))
        finish(timedOut: false)
    }

    // MARK: 0.2.0 fix checks
    static func crop(_ img: CGImage, _ r: CGRect, in w: NSWindow) -> CGImage? {
        let sc = CGFloat(img.width) / max(1, w.frame.width)
        let rect = CGRect(x: r.minX * sc, y: r.minY * sc, width: r.width * sc, height: r.height * sc).integral
        return img.cropping(to: rect)
    }
    static func meanDiff(_ a: CGImage, _ b: CGImage) -> Double {
        let w = min(a.width, b.width), h = min(a.height, b.height)
        guard w > 2, h > 2 else { return 255 }
        func px(_ i: CGImage) -> [UInt8] {
            var d = [UInt8](repeating: 0, count: w * h * 4)
            let ctx = CGContext(data: &d, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            ctx?.draw(i, in: CGRect(x: 0, y: 0, width: w, height: h))
            return d
        }
        let x = px(a), y = px(b)
        var sum = 0
        for i in 0..<x.count { sum += abs(Int(x[i]) - Int(y[i])) }
        return Double(sum) / Double(x.count)
    }

    /// Animated scroll through page anchors while sampling main-thread stalls.
    static func scrollStall(_ targets: [String]) async -> Double {
        let nav = Nav.shared
        return await mainStall(Double(targets.count) * 0.7 + 0.3) {
            for (i, t) in targets.enumerated() {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.7) { nav.scrollTarget = nil; nav.scrollTarget = t }
            }
        }
    }

    /// Bar centred in the detail column and the queue fully inside the window.
    static func layoutOK(_ label: String) -> (Bool, String) {
        guard let w = window else { return (false, "no window") }
        let bar = LayoutProbe.playerBar, det = LayoutProbe.detail, q = LayoutProbe.queue
        let barCentred = Player.shared.current == nil || bar.width == 0 || abs(bar.midX - det.midX) < 3
        let winW = w.contentView?.bounds.width ?? w.frame.width
        let queueInside = !Nav.shared.queueOpen || (q.width > 200 && q.minX >= -1 && q.maxX <= winW + 1)
        let barInside = bar.width == 0 || (bar.minX >= det.minX - 1 && bar.maxX <= det.maxX + 1)
        return (barCentred && queueInside && barInside,
                String(format: "%@: bar mid %.0f vs detail mid %.0f, queue %.0f–%.0f of %.0f", label, bar.midX, det.midX, q.minX, q.maxX, winW))
    }

    static func fixChecks(track: Track?, results: SearchResults?) async {
        let nav = Nav.shared, player = Player.shared, lib = LibraryStore.shared, theme = Theme.shared
        nav.closeNowPlaying(); nav.queueOpen = false
        await resize(CGSize(width: 1280, height: 800))

        // a playing item for the bar (local tone, works offline)
        if let a = makeTone(seconds: 60, name: "fix-a") {
            player.setAutoplay(false)
            player.play([Track(id: "local:" + a.absoluteString, title: "Fix tone", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 60, thumb: "")], index: 0, source: "Fix smoke")
            _ = await wait(10) { player.isPlaying }
        }

        // 1. lag: Player must not publish on clock ticks (that re-rendered the window and open menus 4x/s)
        _ = await wait(10) { player.isPlaying && player.position > 0.5 }
        await sleep(0.5)
        var publishes = 0
        let sub = player.objectWillChange.sink { _ in publishes += 1 }
        await sleep(2.5)
        sub.cancel()
        let ticks = player.clock.position
        record("no_tick_rerender", publishes <= 1, "Player publishes during 2.5 s of playback: \(publishes) (clock at \(String(format: "%.1f", ticks))s)")

        // 1b. main-thread stalls while scrolling Home and a long playlist
        nav.go(.home); await sleep(1.5)
        let homeTargets = (1..<min(8, max(2, (HomeCache.feed.map { HomeView.sections($0).count } ?? 2)))).map { "shelf_\($0)" } + ["page_top"]
        let homeStall = await scrollStall(homeTargets)
        record("scroll_home_stall", homeStall < 600, String(format: "worst main-thread stall scrolling Home %.0f ms", homeStall))
        let pool = (results?.tracks ?? []) + (HomeCache.feed?.shelves.flatMap { $0.items.compactMap(\.track) } ?? [])
        if !pool.isEmpty {
            let long = (0..<400).map { i -> Track in let t = pool[i % pool.count]; return Track(id: t.id + "#\(i)", title: t.title, artists: t.artists, artistId: t.artistId, album: t.album, albumId: t.albumId, durationS: t.durationS, thumb: t.thumb) }
            nav.mixes["Smoke long list"] = long
            nav.push(.mix("Smoke long list")); await sleep(1.5)
            let lstall = await scrollStall(["row_40", "row_120", "row_220", "row_320", "row_399", "page_top"])
            shot("30-long-playlist")
            record("scroll_playlist_stall", lstall < 600, String(format: "worst main-thread stall scrolling a 400-row playlist %.0f ms", lstall))
            nav.pop(); await sleep(0.8)
        } else { record("scroll_playlist_stall", false, "no tracks to build a long list", soft: true) }

        // 2. adaptive contrast with dark art (dark teal)
        let prevTheme = theme.themeId
        theme.themeId = "adaptive"
        let side = 600
        if let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
            ctx.setFillColor(CGColor(srgbRed: 0.03, green: 0.20, blue: 0.22, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
            ctx.setFillColor(CGColor(srgbRed: 0.05, green: 0.28, blue: 0.30, alpha: 1)); ctx.fill(CGRect(x: 150, y: 150, width: 300, height: 300))
            if let cg = ctx.makeImage() {
                player.applyArtwork(ArtDecode.processed(cg), for: nil)
                theme.glow = Color(.sRGB, red: 0.04, green: 0.30, blue: 0.33, opacity: 1)  // the dark teal Ben saw
            }
        }
        await sleep(1.2)
        let acc = theme.accent
        let rBg = Contrast.ratio(acc, theme.p.bg)
        let rBd = theme.artAverage.map { Contrast.ratio(acc, Theme.backdropEstimate(art: $0, bg: theme.p.bg)) } ?? 99
        shot("31-adaptive-dark-art-bar")
        nav.queueOpen = true; await sleep(1.2); shot("32-adaptive-dark-art-queue"); nav.queueOpen = false
        nav.openNowPlaying(); await sleep(1.2); shot("33-adaptive-dark-art-np"); nav.closeNowPlaying(); await sleep(0.6)
        record("adaptive_contrast", rBg >= 4.5 && rBd >= 4.5, String(format: "accent %@ vs background %.2f:1, vs art backdrop %.2f:1 (raw teal %.2f:1)", Theme.hexOf(acc), rBg, rBd, Contrast.ratio(theme.glow, theme.p.bg)))
        theme.themeId = prevTheme

        // sidebar header contrast on every theme (t2 vs background / sidebar / surface)
        let worst = Palette.all.map { pal in (pal.name, min(Contrast.ratio(pal.t2, pal.bg), Contrast.ratio(pal.t2, pal.side), Contrast.ratio(pal.t2, pal.surf))) }.min { $0.1 < $1.1 }!
        record("sidebar_header_contrast", worst.1 >= 4.5, String(format: "lowest header contrast %.2f:1 (%@)", worst.1, worst.0))

        // 3. Now Playing scrolls (art, controls, then Up next + Lyrics)
        for (sz, n) in [(CGSize(width: 800, height: 560), "800x560"), (CGSize(width: 1440, height: 900), "1440x900")] {
            await resize(sz)
            nav.showLyrics = false
            nav.openNowPlaying(); await sleep(1.2)
            shot("34-np-\(n)-top")
            nav.showLyrics = true; await sleep(1.4)
            shot("35-np-\(n)-scrolled-lyrics")
            nav.showLyrics = false; nav.closeNowPlaying(); await sleep(0.6)
        }
        record("np_scroll", true, "Now Playing column scrolls to Up next / Lyrics (snapshots 34/35)")

        // lyrics button: on -> off returns to the idle look
        await resize(CGSize(width: 1280, height: 800))
        nav.go(.home); await sleep(1)
        if let w = window, let base = image(of: w).flatMap({ crop($0, LayoutProbe.lyricsButton.insetBy(dx: -4, dy: -4), in: w) }) {
            save(base, "36-lyrics-button-idle")
            nav.toggleLyrics(); await sleep(1.0)
            let wasOn = nav.lyricsOpen
            nav.toggleLyrics(); await sleep(1.2)
            if let after = image(of: w).flatMap({ crop($0, LayoutProbe.lyricsButton.insetBy(dx: -4, dy: -4), in: w) }) {
                save(after, "37-lyrics-button-after-toggle")
                let d = meanDiff(base, after)
                record("lyrics_button_resets", wasOn && !nav.lyricsOpen && d < 6, String(format: "opened=%@ closed=%@ pixel diff vs idle %.2f", wasOn ? "yes" : "no", !nav.lyricsOpen ? "yes" : "no", d))
            } else { record("lyrics_button_resets", false, "capture failed") }
        } else { record("lyrics_button_resets", false, "no player bar") }

        // 4. queue panel: open / close at 900x600 and 1440x900, layout stays centred, close button + Esc + click-outside
        for (sz, n) in [(CGSize(width: 900, height: 600), "900x600"), (CGSize(width: 1440, height: 900), "1440x900")] {
            await resize(sz)
            nav.queueOpen = false; await sleep(0.8)
            shot("38-queue-closed-\(n)")
            let (ok0, d0) = layoutOK("closed \(n)")
            nav.queueOpen = true; await sleep(1.4)
            shot("39-queue-open-\(n)")
            let (ok1, d1) = layoutOK("open \(n)")
            nav.closeTop(); await sleep(0.8)   // Esc path
            let escClosed = !nav.queueOpen
            record("queue_layout_\(n)", ok0 && ok1 && escClosed, "\(d0); \(d1); Esc closes=\(escClosed)")
        }

        // 5. responsive: Home, Settings, Album at 800 / 1100 / 1600, queue closed and open
        var albumId = results?.albums.first?.id ?? track?.albumId ?? HomeCache.feed?.shelves.flatMap(\.items).first { $0.kind == "album" }?.id
        if albumId == nil { albumId = (try? await Engine.core.search(query: "After Hours The Weeknd"))?.albums.first?.id }
        var respFails: [String] = []
        for wdt in [800, 1100, 1600] {
            await resize(CGSize(width: CGFloat(wdt), height: 760))
            for (name, go) in [("home", { nav.go(.home) }), ("settings", { nav.go(.settings) }), ("album", { if let a = albumId { nav.go(.home); nav.push(.album(a)) } })] as [(String, () -> Void)] {
                go(); await sleep(1.4)
                for q in [false, true] {
                    nav.queueOpen = q; await sleep(1.0)
                    shot("40-\(name)-\(wdt)-queue-\(q ? "open" : "closed")")
                    let (ok, d) = layoutOK("\(name) \(wdt) q=\(q)")
                    if !ok { respFails.append(d) }
                }
                nav.queueOpen = false
            }
            nav.go(.home)
        }
        record("responsive_layout", respFails.isEmpty, respFails.isEmpty ? "18 layouts: bar centred, queue inside the window" : respFails.joined(separator: " | "))
        await resize(CGSize(width: 1280, height: 800))

        // 6. account sheet (labels on one line)
        nav.sheet = .account; await sleep(1.3)
        shot("41-account-sheet", includeSheet: true)
        record("account_sheet_labels", nav.sheet == .account, "Settings / Stats / Switch account / Sign out with fixed-size labels; row wraps to two rows when narrow")
        nav.sheet = nil; await sleep(0.6)

        // 7. Settings › Appearance collapsed / expanded, name field
        UserDefaults.standard.set(false, forKey: "themesExpanded")
        nav.go(.settings); await sleep(1.3)
        nav.scrollTarget = "page_top"; await sleep(0.5)
        shot("42-settings-appearance-collapsed")
        UserDefaults.standard.set(true, forKey: "themesExpanded"); await sleep(1.0)
        shot("43-settings-appearance-expanded")
        UserDefaults.standard.set(false, forKey: "themesExpanded")
        let prevName = Prefs.shared.displayName
        Prefs.shared.setDisplayName("Smokey")
        await sleep(0.8)
        let saved = await Engine.off { Engine.core.getSetting(key: "displayName") } ?? nil
        shot("44-settings-name")
        nav.go(.home); await sleep(1.2)
        shot("45-home-greeting-name")
        let greetOK = Prefs.shared.firstName(account: AccountStore.shared.info) == "Smokey"
        record("settings_name", saved == "Smokey" && greetOK, "stored=\(saved ?? "nil") greeting uses it=\(greetOK)")
        Prefs.shared.setDisplayName(prevName)
        record("themes_collapse", true, "collapsed shows the first row, See all expands (snapshots 42/43)")

        // 8. playlist header subtitle (no "0 songs · 0 songs")
        let s1 = playlistSubtitle("0 songs", count: 0), s2 = playlistSubtitle("Lana Del Rey · 12 songs", count: 12), s3 = playlistSubtitle("YouTube Music", count: 1)
        nav.go(.library); nav.push(.playlist("liked")); await sleep(1.5)
        shot("46-liked-songs-header")
        record("playlist_subtitle", s1 == "0 songs" && s2 == "Lana Del Rey · 12 songs" && s3 == "YouTube Music · 1 song", "\(s1) | \(s2) | \(s3)")

        // 9. context menu: Add to Playlist (menu doesn't observe Player; add + New Playlist… paths)
        if let t = track ?? results?.tracks.first, let pl = await lib.create(name: "Smoke Menu", description: "", tracks: []) {
            lib.add(t, to: pl)
            await sleep(0.8)
            let pg = try? await Engine.core.playlist(id: pl.id)
            nav.sheet = .newPlaylist([t]); await sleep(1.0)
            let sheetUp = nav.sheet == .newPlaylist([t]) && NSApp.windows.contains { $0.isSheet && $0.isVisible }
            shot("47-new-playlist-from-menu", includeSheet: true)
            nav.sheet = nil; await sleep(0.4)
            lib.delete([pl]); nav.flushPending()
            record("menu_add_to_playlist", (pg?.tracks.first?.id == t.id) && sheetUp && publishes <= 1, "added=\(pg?.tracks.count ?? 0) newPlaylistSheet=\(sheetUp) (menu items read Player.shared / Nav.shared; store publishes deferred while a menu tracks)")
        } else { record("menu_add_to_playlist", false, "no track", soft: true) }

        // 10. Back keeps Home's scroll + carousel offsets
        nav.go(.home); await sleep(1.2)
        nav.scrollTarget = nil; nav.scrollTarget = "shelf_0"; await sleep(1.2)
        let firstShelf = HomeCache.feed.map { HomeView.sections($0) }?.first { !$0.songs }
        if let fs = firstShelf { nav.carouselTarget = CarouselTarget(shelf: fs.title, index: min(5, fs.items.count - 1)); await sleep(1.4) }
        shot("48-home-scrolled-sideways-clean-sidebar")
        nav.scrollTarget = "shelf_2"; await sleep(1.2)
        let vBefore = ScrollProbe.offsets["home"] ?? -1
        let hBefore = firstShelf.flatMap { ScrollProbe.offsets["carousel_\($0.title)"] } ?? -1
        if let a = albumId {
            nav.push(.album(a)); await sleep(1.5)
            nav.pop(); await sleep(1.2)
            let vAfter = ScrollProbe.offsets["home"] ?? -2
            let hAfter = firstShelf.flatMap { ScrollProbe.offsets["carousel_\($0.title)"] } ?? -2
            shot("49-home-after-back")
            record("home_back_keeps_offset", vBefore > 20 && hBefore > 20 && abs(vAfter - vBefore) < 3 && abs(hAfter - hBefore) < 3,
                   String(format: "vertical %.0f -> %.0f, carousel %.0f -> %.0f", vBefore, vAfter, hBefore, hAfter))
        } else { record("home_back_keeps_offset", false, "no album to open", soft: true) }

        // 11. open a mix / playlist: first rows < 1 s with a warm cache
        let plId = HomeCache.feed?.shelves.flatMap(\.items).first { $0.kind == "playlist" }?.id ?? results?.playlists.first?.id
        if let id = plId {
            _ = try? await Engine.core.playlist(id: id)  // warm
            PageTiming.firstRows[id] = nil
            nav.go(.home); await sleep(0.6)
            let t0 = CACurrentMediaTime()
            nav.push(.playlist(id))
            let ok = await wait(5) { PageTiming.firstRows[id] != nil }
            let ms = ((PageTiming.firstRows[id] ?? CACurrentMediaTime()) - t0) * 1000
            shot("50-mix-open")
            record("mix_open_fast", ok && ms < 1000, String(format: "first rows after %.0f ms (has more pages: %@)", ms, Engine.core.playlistHasMore(id: id) ? "yes" : "no"))
            nav.pop()
        } else { record("mix_open_fast", false, "no playlist found", soft: true) }
        nav.go(.home)
    }

    // MARK: 0.2.1 checks (top bar, search, centred Isa, sidebar, Now Playing, live likes)

    /// Posts a key press through the normal event queue (local monitors see it like a real key).
    static func press(keyCode: UInt16, chars: String) async {
        guard let w = window, let ev = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                                                         windowNumber: w.windowNumber, context: nil, characters: chars, charactersIgnoringModifiers: chars,
                                                         isARepeat: false, keyCode: keyCode) else { return }
        NSApp.postEvent(ev, atStart: false)
        await sleep(0.5)
    }

    /// Does a click at this SwiftUI point (global, top-left origin) reach the app's content (not the title bar)?
    static func reachesContent(_ pt: CGPoint) -> (Bool, String) {
        guard let w = window, let content = w.contentView, let frame = content.superview else { return (false, "no window") }
        let h = frame.bounds.height
        let p = frame.convert(NSPoint(x: pt.x, y: h - pt.y), from: nil)
        guard let hit = frame.hitTest(p) else { return (false, "nothing hit") }
        return (hit.isDescendant(of: content), String(describing: type(of: hit)))
    }

    /// In-list search: case/accent-insensitive, title/artist/album, every word; fast off the main actor.
    static func listSearchChecks() async {
        func t(_ title: String, _ artists: String, _ album: String?) -> Track {
            Track(id: UUID().uuidString, title: title, artists: artists, artistId: nil, album: album, albumId: nil, durationS: 200, thumb: "")
        }
        let ts = [t("Café del Mar", "Energy 52", "Café del Mar"), t("Blinding Lights", "The Weeknd", "After Hours"),
                  t("Señorita", "Shawn Mendes, Camila Cabello", nil), t("Hours", "Beabadoobee", "Beatopia")]
        let keys = ts.map(ListFilter.key)
        let cases: [(String, [Int]?)] = [("", nil), ("  ", nil), ("CAFE", [0]), ("café", [0]), ("senorita", [2]), ("SEÑOR", [2]),
                                         ("weeknd", [1]), ("hours", [1, 3]), ("hours weeknd", [1]), ("camila", [2]), ("zzq", [])]
        let bad = cases.filter { ListFilter.match(keys, $0.0) != $0.1 }.map { "'\($0.0)'" }
        record("list search matches (case/accent, title/artist/album)", bad.isEmpty, bad.isEmpty ? "\(cases.count) cases" : "wrong: " + bad.joined(separator: ", "))
        let big = (0..<5000).map { t("Song \($0) Ünïcödé", "Artist \($0 % 97)", "Album \($0 % 13)") }
        let t0 = CACurrentMediaTime()
        let hits = await ListFilter.run(big, "unicode artist 5")
        let ms = Int((CACurrentMediaTime() - t0) * 1000)
        record("list search 5000 tracks off main", !hits.isEmpty && ms < 1500, "\(hits.count) hits in \(ms) ms")
    }

    static func checks021(track: Track?, results: SearchResults?) async {
        let nav = Nav.shared, lib = LibraryStore.shared, player = Player.shared, theme = Theme.shared
        let m = SearchModel.shared
        nav.closeNowPlaying(); nav.queueOpen = false; nav.closeIsa()
        nav.flushPending(); nav.snack = nil
        await resize(CGSize(width: 1280, height: 800))
        if nav.sidebarCollapsed { nav.toggleSidebar() }
        nav.go(.home); await sleep(1.5)
        guard let w = window else { record("top_bar", false, "no window"); return }

        // 1. top bar + traffic lights / toggle on one centre line, clicks reach the bar
        shot("60-top-bar-home")
        let tb = LayoutProbe.topBar, pill = LayoutProbe.searchPill, grp = LayoutProbe.searchGroup, orb = LayoutProbe.isaButton
        let pillCentred = abs(grp.midX - tb.midX) < 60
        record("top_bar", tb.height > 40 && tb.height < 64 && pill.width >= 300 && pillCentred && orb.maxX > tb.midX && abs(pill.midY - WindowChrome.centerY) < 3,
               String(format: "bar %.0fx%.0f at x %.0f, search pill %.0fx%.0f mid y %.0f, group mid %.0f vs bar mid %.0f, Isa orb at x %.0f", tb.width, tb.height, tb.minX, pill.width, pill.height, pill.midY, grp.midX, tb.midX, orb.midX))
        if let close = w.standardWindowButton(.closeButton), let cv = w.contentView {
            let r = close.convert(close.bounds, to: nil)   // window coordinates (origin bottom-left)
            let lightsY = cv.frame.height - r.midY
            let tog = LayoutProbe.toggle
            record("chrome_alignment", abs(lightsY - WindowChrome.centerY) < 2.5 && abs(tog.midY - WindowChrome.centerY) < 3 && r.maxX < LayoutProbe.sidebar.maxX,
                   String(format: "traffic lights centre y %.1f (x %.0f–%.0f), sidebar toggle centre y %.1f, top bar centre y %.1f", lightsY, r.minX, r.maxX, tog.midY, WindowChrome.centerY))
        } else { record("chrome_alignment", false, "no traffic lights") }
        var hits: [String] = []
        var allHit = true
        for (name, pt) in [("search", CGPoint(x: pill.midX, y: pill.midY)), ("isa", CGPoint(x: orb.midX, y: orb.midY)),
                           ("back", CGPoint(x: tb.minX + 40, y: WindowChrome.centerY))] {
            let (ok, v) = reachesContent(pt); allHit = allHit && ok; hits.append("\(name)=\(ok ? "content" : v)")
        }
        record("topbar_clickable", allHit, hits.joined(separator: ", "))

        // 2. sidebar: mono icons tinted by the theme, no system blue
        let mono = Theme.hexOf(theme.sideMuted), acc = Theme.hexOf(theme.accent)
        let sysBlue = Theme.hexOf(Color(nsColor: .controlAccentColor))
        record("sidebar_icons_mono", theme.themeId != "mono" || (mono == "8A8A8E" && ["E8E8E8", "E7E7E7"].contains(acc)),
               "theme \(theme.themeId): selected icon #\(acc), others #\(mono) (system accent #\(sysBlue) not used; root .tint = theme accent)")

        // 3. search: empty focus -> Browse all, typing -> dropdown, pick -> results, Esc closes the dropdown
        Engine.bg { try Engine.core.addRecentSearch(query: "the weeknd live") }
        Engine.bg { try Engine.core.addRecentSearch(query: "weeknd after hours") }
        await sleep(0.3)
        m.loadRecents()
        nav.focusSearchField(); await sleep(1.2)
        let browseOK = nav.section == .search && m.query.isEmpty
        shot("61-search-browse")
        record("search_browse", browseOK, "focus with empty text -> Browse all (\(BrowseAll.tiles.count) tiles), section=\(nav.section)")
        m.text = "the weeknd"
        await sleep(0.3)
        if !m.dropdown { m.openDropdown() }
        _ = await wait(8) { !m.suggestions.isEmpty }
        await sleep(0.6)
        let rows = m.rows
        shot("62-search-dropdown")
        record("search_dropdown", m.dropdown && !rows.isEmpty, "recent=\(m.recentMatches) suggestions=\(m.suggestionRows.prefix(4))", soft: m.dropdown && rows.isEmpty)
        await press(keyCode: 125, chars: "\u{F701}")
        let hl = m.highlight
        await press(keyCode: 53, chars: "\u{1b}")
        let escClosed = !m.dropdown
        record("search_esc_closes", escClosed && (rows.isEmpty || hl == 0), "↓ highlight=\(hl.map(String.init) ?? "nil"), Esc closed dropdown=\(escClosed)")
        m.openDropdown(); await sleep(0.3)
        // pick the plain-artist suggestion when there is one (shows Top result + Artists / Albums)
        let artistRow = m.rows.firstIndex { $0.lowercased() == "the weeknd" }
        m.highlight = artistRow ?? (rows.isEmpty ? nil : 0)
        m.submit()
        _ = await wait(30) { !m.loading }
        await sleep(1.5)
        shot("63-search-results")
        let r = m.results
        let top = r.flatMap { SearchModel.top($0, query: m.query) }
        record("search_results", !m.dropdown && nav.section == .search && (r?.tracks.isEmpty == false), "query “\(m.query)”: \(r?.tracks.count ?? 0) songs, \(r?.artists.count ?? 0) artists, \(r?.albums.count ?? 0) albums, top result \(top.map { "\($0)".prefix(40) } ?? "none")", soft: r == nil)
        m.filter = "Songs"; await sleep(1.0); shot("64-search-songs-filter"); m.filter = "All"
        nav.back(); await sleep(0.8)
        let backOK = nav.section == .home
        nav.forward(); await sleep(0.8)
        let fwdOK = nav.section == .search
        record("history_back_forward", backOK && fwdOK, "back -> \(backOK ? "home" : "?"), forward -> \(fwdOK ? "search" : "?")")
        m.showBrowse(); m.closeDropdown()

        // 4. Isa: orb opens the Command card centred over a blurred backdrop; Esc closes it
        nav.go(.home); await sleep(1)
        nav.openIsa(); await sleep(1.4)
        let card = LayoutProbe.isaCard
        let win = w.contentView?.bounds ?? .zero
        let centred = abs(card.midX - win.midX) < 4 && abs(card.midY - win.midY) < 4
        shot("65-isa-centred")
        await press(keyCode: 53, chars: "\u{1b}")
        let closed = !nav.isaOpen
        record("isa_centred", centred && closed && card.width > 300,
               String(format: "card %.0fx%.0f centre (%.0f, %.0f) vs window (%.0f, %.0f); Esc closed=%@", card.width, card.height, card.midX, card.midY, win.midX, win.midY, closed ? "yes" : "no"))
        nav.closeIsa(); await sleep(0.5)

        // 5. sidebar expanded / collapsed (rail, toggle on the light line in the top bar, avatar in the top bar)
        nav.go(.library); await sleep(1.2)
        shot("66-sidebar-expanded")
        let wide = LayoutProbe.sidebar.width
        nav.toggleSidebar(); await sleep(1.2)
        shot("67-sidebar-collapsed")
        let rail = LayoutProbe.sidebar.width, tog = LayoutProbe.toggle
        record("sidebar_collapsed", nav.sidebarCollapsed && rail < 100 && wide > 200 && tog.minX > LayoutProbe.sidebar.maxX && abs(tog.midY - WindowChrome.centerY) < 3,
               String(format: "expanded %.0f pt, rail %.0f pt, toggle at x %.0f (rail ends %.0f) centre y %.1f", wide, rail, tog.minX, LayoutProbe.sidebar.maxX, tog.midY))
        nav.toggleSidebar(); await sleep(1.0)

        // 6. Now Playing: clean top row (no sidebar toggle over it)
        if let a = makeTone(seconds: 60, name: "np021") {
            player.setAutoplay(false)
            player.play([Track(id: "local:" + a.absoluteString, title: "Now Playing check", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 60, thumb: "")], index: 0, source: "Smoke 0.2.1")
            _ = await wait(10) { player.current != nil }
        }
        nav.openNowPlaying(); await sleep(1.5)
        shot("68-now-playing")
        record("np_top_row_clean", nav.showNowPlaying, "Now Playing covers the window; sidebar toggle and top bar sit under it (no window toolbar)")
        nav.closeNowPlaying(); await sleep(0.8)

        // 7. live likes: Liked Songs list + count, Library tile count, without a reload
        let likeT = track ?? results?.tracks.first ?? Track(id: "smoke-like-1", title: "Smoke like", artists: "Smoke", artistId: nil, album: nil, albumId: nil, durationS: 100, thumb: "")
        if lib.isLiked(likeT.id) { lib.toggleLike(likeT); await sleep(1) }
        nav.go(.library); await sleep(0.6)
        nav.push(.playlist("liked")); await sleep(1.5)
        let before = PageTiming.rows["liked"]?.count ?? -1
        let countBefore = lib.liked.count
        let t0 = CACurrentMediaTime()
        lib.toggleLike(likeT)
        let shown = await wait(3) { PageTiming.rows["liked"]?.contains(likeT.id) == true }
        let ms = (CACurrentMediaTime() - t0) * 1000
        await sleep(0.6)
        shot("69-like-live-liked-songs")
        let countOK = lib.liked.count == countBefore + 1
        lib.toggleLike(likeT)
        let removed = await wait(3) { PageTiming.rows["liked"]?.contains(likeT.id) == false }
        record("like_live", shown && countOK && removed,
               String(format: "liked rows %d -> shown=%@ after %.0f ms, count %d -> %d, unlike removed=%@ (core revision %llu)", before, shown ? "yes" : "no", ms, countBefore, countBefore + (countOK ? 1 : 0), removed ? "yes" : "no", Engine.core.libraryRevision()))
        nav.go(.library); await sleep(0.8)
        shot("70-library-after-like")
        nav.go(.home)
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

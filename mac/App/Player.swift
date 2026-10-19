import AppKit
import AVFoundation
import Combine
import MediaPlayer
import SwiftUI

/// Playback: AVPlayer driven by the Rust core's queue (shuffle/repeat/autoplay/reranking).
@MainActor
final class Player: ObservableObject {
    static let shared = Player()

    @Published var current: Track?
    @Published var isPlaying = false
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var queue: QueueState?
    @Published var artwork: NSImage?
    @Published var error: String?
    @Published var volume: Float = (UserDefaults.standard.object(forKey: "volume") as? Float) ?? 1
    @Published var rate: Float = 1
    @Published var buffering = false
    @AppStorage("normalize") var normalize = true
    @AppStorage("autoplay") var autoplay = true

    private let av = AVPlayer()
    private var timeObs: Any?
    private var endObs: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var retried = Set<String>()
    private var clientFor: [String: String] = [:]
    private var loadTask: Task<Void, Never>?
    // stats
    private var startedAt: Int64 = 0
    private var listened: Double = 0
    private var lastTick = Date()
    /// Duration we trust (metadata / stream length); AVPlayer misreports some formats (2x).
    private var trustedDuration: Double = 0
    private var stallSince: Date?
    private var loudGain: Float = 1
    private var advancing = false
    /// Consecutive tracks that failed to start; after 3 playback stops instead of skipping through the queue.
    private var failStreak = 0

    private init() {
        av.automaticallyWaitsToMinimizeStalling = true
        timeObs = av.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t.seconds) }
        }
        setupRemote()
        let ap = autoplay
        Engine.bg { Engine.core.setAutoplay(on: ap) }
        NotificationCenter.default.addObserver(forName: AVPlayerItem.playbackStalledNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.stallSince = Date() }
        }
    }

    func setVolume(_ v: Float) {
        volume = max(0, min(1, v))
        UserDefaults.standard.set(volume, forKey: "volume")
        av.volume = volume * loudGain
    }

    private var core: Core { Engine.core }

    // MARK: commands
    func play(_ tracks: [Track], index: Int, source: String) {
        guard !tracks.isEmpty else { return }
        failStreak = 0
        finishPlay(skipped: false)
        if let t = core.queueSet(tracks: tracks, start: Int32(index), source: source) { load(t) }
        publishQueue()
    }

    func playShuffled(_ tracks: [Track], source: String) {
        core.setShuffle(on: true)
        play(tracks, index: Int.random(in: 0..<max(1, tracks.count)), source: source)
    }

    func toggle() {
        if av.timeControlStatus == .playing { av.pause() } else {
            if av.currentItem == nil, let c = current { load(c) } else { av.play() }
        }
        isPlaying = av.rate != 0 || av.timeControlStatus == .waitingToPlayAtSpecifiedRate
        updateNowPlaying()
    }

    func next(user: Bool = true) {
        Smoke.trace("next user=\(user) from \(current?.title ?? "-") at \(Int(position))s/\(Int(duration))s")
        finishPlay(skipped: user)
        if let t = core.queueNext(user: user) { load(t); publishQueue(); return }
        Task {
            _ = try? await core.ensureAutoplay()
            if let t = core.queueNext(user: user) { load(t) } else { av.pause(); isPlaying = false }
            publishQueue()
        }
    }

    func prev() {
        if position > 4 { seek(0); return }
        finishPlay(skipped: true)
        if let t = core.queuePrev() { load(t) }
        publishQueue()
    }

    func jump(_ i: Int) {
        finishPlay(skipped: true)
        if let t = core.queueJump(index: Int32(i)) { load(t) }
        publishQueue()
    }

    func seek(_ s: Double) {
        av.seek(to: CMTime(seconds: s, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        position = s
        updateNowPlaying()
    }

    func setShuffle(_ on: Bool) { core.setShuffle(on: on); publishQueue() }
    func cycleRepeat() { _ = core.cycleRepeat(); publishQueue() }
    func playNext(_ t: Track) { if current == nil { play([t], index: 0, source: "Queue") } else { core.queuePlayNext(track: t); publishQueue() } }
    func addToQueue(_ t: Track) { if current == nil { play([t], index: 0, source: "Queue") } else { core.queueAdd(track: t); publishQueue() } }
    func remove(_ i: Int) { core.queueRemove(index: Int32(i)); publishQueue() }
    func setRate(_ r: Float) { rate = r; av.defaultRate = r; if isPlaying { av.rate = r } }
    func move(_ from: Int, _ to: Int) { core.queueMove(from: Int32(from), to: Int32(to)); publishQueue() }
    func setAutoplay(_ on: Bool) { autoplay = on; core.setAutoplay(on: on); publishQueue(); if on { Task { _ = try? await core.ensureAutoplay(); publishQueue() } } }

    private func publishQueue() { queue = core.queueState() }

    // MARK: loading
    private func load(_ t: Track) {
        loadTask?.cancel()
        current = t
        duration = Double(t.durationS)
        trustedDuration = Double(t.durationS)
        stallSince = nil
        advancing = false
        position = 0
        startedAt = Int64(Date().timeIntervalSince1970)
        listened = 0
        error = nil
        buffering = true
        loadArtwork(t)
        updateNowPlaying()
        Task { await Engine.core.lastfmNowPlaying(track: t) }
        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let (url, headers, loud, client, streamMs) = try await self.resolve(t)
                if Task.isCancelled { return }
                self.clientFor[t.id] = client
                Smoke.trace("resolved \(t.title) via \(client), stream \(streamMs) ms, metadata \(t.durationS) s")
                if streamMs > 0 {
                    let sd = Double(streamMs) / 1000
                    if self.trustedDuration <= 0 || abs(sd - self.trustedDuration) / max(1, self.trustedDuration) < 0.08 { self.trustedDuration = sd }
                    self.duration = self.trustedDuration > 0 ? self.trustedDuration : sd
                }
                self.start(url: url, headers: headers, loudness: loud, track: t)
            } catch {
                self.error = "Couldn't play \(t.title): \(Engine.message(error))"
                Smoke.trace("resolve failed \(t.title): \(Engine.message(error).prefix(160))")
                self.buffering = false
                self.failStreak += 1
                if self.failStreak >= 3 {
                    self.isPlaying = false
                    Nav.shared.show("Can't reach streams right now")
                    return
                }
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                if self.current?.id == t.id { self.next(user: false) }
            }
        }
    }

    private func resolve(_ t: Track) async throws -> (URL, [String: String], Float?, String, UInt64) {
        if t.id.hasPrefix("local:") {
            let s = String(t.id.dropFirst(6))
            return (URL(string: s) ?? URL(fileURLWithPath: s), [:], nil, "local", 0)
        }
        let id = t.id
        if let p = await Engine.off({ Engine.core.offlinePath(id: id) }) ?? nil { return (URL(fileURLWithPath: p), [:], nil, "offline", 0) }
        if let p = await Engine.off({ Engine.core.cachedPath(id: id) }) ?? nil { return (URL(fileURLWithPath: p), [:], nil, "cache", 0) }
        let s = try await core.stream(id: t.id)
        guard let u = URL(string: s.url) else { throw CoreError.Invalid(msg: "bad url") }
        return (u, ["User-Agent": s.userAgent], s.loudnessDb, s.client, s.durationMs)
    }

    private func start(url: URL, headers: [String: String], loudness: Float?, track: Track) {
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            Task { @MainActor in self?.itemStatus(it, track: track) }
        }
        if let e = endObs { NotificationCenter.default.removeObserver(e) }
        endObs = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.advance() }
        }
        av.replaceCurrentItem(with: item)
        // ReplayGain-style: YouTube loudnessDb is relative to its target; attenuate loud tracks.
        if normalize, let db = loudness, db > 0 { loudGain = max(0.2, pow(10, -db / 20)) } else { loudGain = 1 }
        av.volume = volume * loudGain
        av.play()
        if rate != 1 { av.rate = rate }
        isPlaying = true
        lastTick = Date()
        Task { await self.afterStart() }
    }

    private func itemStatus(_ item: AVPlayerItem, track: Track) {
        switch item.status {
        case .readyToPlay:
            buffering = false
            failStreak = 0
            let d = item.duration.seconds
            // Trust metadata / stream length over AVPlayer's value when they disagree by more than 5 %.
            if d.isFinite && d > 0 {
                if trustedDuration <= 0 { trustedDuration = d }
                duration = abs(d - trustedDuration) / max(1, trustedDuration) > 0.05 ? trustedDuration : d
            }
            updateNowPlaying()
        case .failed:
            let msg = item.error?.localizedDescription ?? "playback failed"
            Smoke.trace("item failed \(track.title): \(msg)")
            // 403 / dead URL / throttled: next client in the chain, once per track.
            if !retried.contains(track.id), current?.id == track.id, !track.id.hasPrefix("local:") {
                retried.insert(track.id)
                let pos = position
                Task {
                    do {
                        let s = try await core.streamRetry(id: track.id, failedClient: clientFor[track.id] ?? "visionos", error: msg)
                        guard let u = URL(string: s.url), current?.id == track.id else { return }
                        clientFor[track.id] = s.client
                        start(url: u, headers: ["User-Agent": s.userAgent], loudness: s.loudnessDb, track: track)
                        seek(pos)
                    } catch {
                        self.error = "Couldn't play \(track.title). Skipping."
                        self.next(user: false)
                    }
                }
            } else if current?.id == track.id {
                error = "Couldn't play \(track.title). Skipping."
                next(user: false)
            }
        default: break
        }
    }

    /// Autoplay top-up and pre-caching of the next 2 tracks (survives short outages, instant skips).
    private func afterStart() async {
        if autoplay, let q = queue, q.tracks.count - Int(q.index) <= 5 {
            _ = try? await core.ensureAutoplay()
            publishQueue()
        }
        guard let q = core.queueState() as QueueState? else { return }
        let upcoming = q.tracks.dropFirst(Int(q.index) + 1).prefix(2)
        for t in upcoming where !t.id.hasPrefix("local:") {
            _ = try? await core.prefetch(track: t)
        }
    }

    /// Auto-advance exactly once per track (end notification, watchdog at the real end, or a stall there).
    private func advance(_ why: String = "end") {
        guard !advancing else { return }
        Smoke.trace("auto-advance (\(why)) at \(Int(position))s of \(Int(duration))s")
        advancing = true
        next(user: false)
    }

    private func tick(_ s: Double) {
        guard s.isFinite else { return }
        position = min(s, duration > 0 ? duration : s)
        // watchdog: the real end of the track (AVPlayer may report a longer item and never fire didPlayToEnd)
        if duration > 1, s >= duration - 0.3, current != nil { advance("watchdog") }
        if av.timeControlStatus == .waitingToPlayAtSpecifiedRate, duration > 1, s >= duration - 2 {
            if stallSince == nil { stallSince = Date() }
            if let st = stallSince, Date().timeIntervalSince(st) > 3 { advance("stall") }
        } else if av.timeControlStatus == .playing { stallSince = nil }
        let playing = av.timeControlStatus == .playing
        if playing { listened += min(2, Date().timeIntervalSince(lastTick)) }
        lastTick = Date()
        if isPlaying != (playing || av.timeControlStatus == .waitingToPlayAtSpecifiedRate) {
            isPlaying = playing || av.timeControlStatus == .waitingToPlayAtSpecifiedRate
            updateNowPlaying()
        }
    }

    private func finishPlay(skipped: Bool) {
        guard let t = current, listened >= 1 else { return }
        let ms = UInt64(listened * 1000)
        let dur = max(1, Double(t.durationS))
        let wasSkip = skipped && listened < 30 && listened < dur / 2
        listened = 0
        let st = startedAt
        Engine.bg { try Engine.core.recordPlay(track: t, startedAt: st, listenedMs: ms, skipped: wasSkip, tzOffsetMin: Engine.tzMinutes, source: "macos") }
        Task { _ = try? await Engine.core.lastfmFlush() }
    }

    // MARK: artwork + glow (decoded off-main)
    private func loadArtwork(_ t: Track) {
        guard let u = URL(string: t.thumb), u.scheme?.hasPrefix("http") == true else { artwork = nil; return }
        Task.detached(priority: .userInitiated) {
            guard let (data, _) = try? await URLSession.shared.data(from: u), let img = NSImage(data: data) else { return }
            let glow = Glow.color(of: img)
            await MainActor.run {
                guard Player.shared.current?.id == t.id else { return }
                Player.shared.artwork = img
                if let g = glow { withAnimation(Motion.glow) { Theme.shared.glow = g } }
                Player.shared.updateNowPlaying()
            }
        }
    }

    // MARK: Now Playing + media keys
    private func setupRemote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { _ in Task { @MainActor in if !Player.shared.isPlaying { Player.shared.toggle() } }; return .success }
        c.pauseCommand.addTarget { _ in Task { @MainActor in if Player.shared.isPlaying { Player.shared.toggle() } }; return .success }
        c.togglePlayPauseCommand.addTarget { _ in Task { @MainActor in Player.shared.toggle() }; return .success }
        c.nextTrackCommand.addTarget { _ in Task { @MainActor in Player.shared.next() }; return .success }
        c.previousTrackCommand.addTarget { _ in Task { @MainActor in Player.shared.prev() }; return .success }
        c.changePlaybackPositionCommand.addTarget { e in
            if let e = e as? MPChangePlaybackPositionCommandEvent { Task { @MainActor in Player.shared.seek(e.positionTime) } }
            return .success
        }
    }

    func updateNowPlaying() {
        guard let t = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: t.artists,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0.0,
        ]
        if let a = t.album { info[MPMediaItemPropertyAlbumTitle] = a }
        if let img = artwork {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
    }
}

enum Glow {
    /// Most vivid colour of a downscaled artwork (16x16), like Android's Palette vibrant swatch.
    nonisolated static func color(of img: NSImage) -> Color? {
        guard let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        let w = 16, h = 16
        var px = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var best: (Double, NSColor)? = nil
        var avg = (0.0, 0.0, 0.0)
        for i in stride(from: 0, to: px.count, by: 4) {
            let r = Double(px[i]) / 255, g = Double(px[i + 1]) / 255, b = Double(px[i + 2]) / 255
            avg.0 += r; avg.1 += g; avg.2 += b
            let c = NSColor(srgbRed: r, green: g, blue: b, alpha: 1)
            var hh: CGFloat = 0, s: CGFloat = 0, v: CGFloat = 0, a: CGFloat = 0
            c.getHue(&hh, saturation: &s, brightness: &v, alpha: &a)
            let score = Double(s) * Double(v) * (v > 0.25 ? 1 : 0.3)
            if best == nil || score > best!.0 { best = (score, c) }
        }
        if let b = best, b.0 > 0.12 { return Color(nsColor: b.1) }
        let n = Double(w * h)
        return Color(red: avg.0 / n, green: avg.1 / n, blue: avg.2 / n)
    }
}

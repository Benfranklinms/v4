import AppKit
import SwiftUI

/// Full-window Now Playing over a pre-blurred art backdrop. One centred column you can scroll: art, then
/// title / artist / like and the controls at exactly the art's width, then Up next and Lyrics below.
/// Wide windows with lyrics on: the column on the left and big lyrics beside it.
struct NowPlayingView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore

    var body: some View {
        let p = theme.p
        GeometryReader { g in
            let wide = g.size.width >= 1100
            let beside = wide && nav.showLyrics
            let avail = beside ? g.size.width * 0.44 : g.size.width - 64
            // art is the column width; at least 340 so the controls fit, at most 560
            let col = max(340, min(560, avail, (g.size.height - 56) * 0.62))
            ZStack {
                NowPlayingBackdrop()
                if let t = player.current {
                    VStack(spacing: 0) {
                        topBar(t)
                        HStack(alignment: .top, spacing: 48) {
                            ScrollViewReader { proxy in
                            ScrollView {
                                LazyVStack(spacing: 0) {
                                    ArtView(url: t.thumb, image: player.artwork, size: col, radius: 16)
                                        .scaleEffect(player.isPlaying || Motion.reduce ? 1 : 0.95)
                                        .shadow(color: .black.opacity(0.45), radius: 30, y: 12)
                                        .animation(Motion.bouncy, value: player.isPlaying)
                                        .id(t.id)
                                        .transition(.opacity.combined(with: .scale(scale: 0.97)))
                                        .onTapGesture(count: 2) { lib.toggleLike(t) }
                                        .accessibilityIdentifier("np_art")
                                        .padding(.top, 8)
                                    titleRow(t).frame(width: col).padding(.top, 22)
                                    NPControls().frame(width: col).padding(.top, 14)
                                    secondaryRow(t).frame(width: col).padding(.top, 12)
                                    if !beside {
                                        UpNextList().frame(width: col).padding(.top, 34)
                                        Group {
                                            VStack(alignment: .leading, spacing: 10) {
                                                Text("Lyrics").font(theme.ui(16, .bold)).foregroundStyle(p.txt)
                                                LyricsPanel(t: t).frame(height: 380)
                                                    .padding(.horizontal, 16)
                                                    .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.black.opacity(0.18)))
                                            }
                                            .frame(width: col, alignment: .leading)
                                            .padding(.top, 30)
                                            .id("np_lyrics_section")
                                        }
                                    }
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.bottom, 40)
                            }
                            .scrollIndicators(.automatic)
                            .frame(width: beside ? col + 48 : nil)
                            .frame(maxWidth: beside ? nil : .infinity)
                            .accessibilityIdentifier("np_scroll")
                            .onChange(of: nav.showLyrics) { _, on in
                                if on && !wide { withAnimation(Motion.spring) { proxy.scrollTo("np_lyrics_section", anchor: .top) } }
                            }
                            .onAppear {
                                if nav.showLyrics && !wide {
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { withAnimation(Motion.spring) { proxy.scrollTo("np_lyrics_section", anchor: .top) } }
                                }
                            }
                            }
                            if beside {
                                LyricsPanel(t: t, large: true)
                                    .frame(maxWidth: 640, maxHeight: .infinity)
                                    .transition(.opacity.combined(with: .move(edge: .trailing)))
                            }
                        }
                        .padding(.horizontal, beside ? 40 : 0)
                        .frame(maxWidth: .infinity)
                    }
                    .animation(Motion.spring, value: nav.showLyrics)
                    .animation(Motion.spring, value: t.id)
                }
            }
        }
        .foregroundStyle(p.txt)
        .accessibilityIdentifier("now_playing")
    }

    private func topBar(_ t: Track) -> some View {
        let p = theme.p
        return HStack(spacing: 12) {
            GlassIconButton(name: "chevron.down", size: 13, help: "Close Now Playing (Esc)") { nav.closeNowPlaying() }
                .glassSurface(Circle(), interactive: true)
                .accessibilityIdentifier("np_close")
            VStack(alignment: .leading, spacing: 1) {
                Text("PLAYING FROM").font(theme.ui(9.5, .bold)).tracking(1).foregroundStyle(p.t2)
                Text(player.queue?.sourceTitle ?? "").font(theme.ui(12.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
            }
            Spacer()
            Menu { TrackMenuItems(t: t) } label: { Image(systemName: "ellipsis").font(.system(size: 13, weight: .bold)).frame(width: 31, height: 31) }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).frame(width: 31, height: 31)
                .glassSurface(Circle(), interactive: true)
                .help("More")
        }
        // on the traffic-light centre line, clear of the lights; nothing else floats over this row
        .padding(.leading, 86).padding(.trailing, 18).frame(height: WindowChrome.centerY * 2)
        .frame(maxWidth: .infinity)
    }

    private func titleRow(_ t: Track) -> some View {
        let p = theme.p
        return HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Marquee(text: t.title, font: theme.ui(22, .bold), color: p.txt, height: 30)
                HStack(spacing: 0) {
                    Button { if let a = t.artistId { nav.push(.artist(a)) } } label: {
                        Text(t.artists).font(theme.ui(13.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                    }.buttonStyle(.plain).disabled(t.artistId == nil).accessibilityIdentifier("np_artist")
                    if let al = t.album, !al.isEmpty {
                        Text(" — ").font(theme.ui(13.5)).foregroundStyle(p.t3)
                        Button { if let a = t.albumId { nav.push(.album(a)) } } label: {
                            Text(al).font(theme.ui(13.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                        }.buttonStyle(.plain).disabled(t.albumId == nil).accessibilityIdentifier("np_album")
                    }
                }
            }
            Spacer(minLength: 8)
            LikeButton(t: t)
        }
    }

    private func secondaryRow(_ t: Track) -> some View {
        let p = theme.p
        return HStack(spacing: 6) {
            SpeedMenu()
            Spacer(minLength: 4)
            GlassIconButton(name: "quote.bubble", size: 13, on: nav.showLyrics, help: "Lyrics (⌥⌘L)") { withAnimation(Motion.spring) { nav.showLyrics.toggle() } }
                .accessibilityIdentifier("np_lyrics")
            GlassIconButton(name: "list.bullet", size: 13, on: nav.queueOpen, help: "Queue (⌥⌘U)") { nav.toggleQueue() }
                .accessibilityIdentifier("np_queue")
            if !t.id.hasPrefix("local:") {
                let dl = lib.downloaded.contains(t.id)
                GlassIconButton(name: dl ? "arrow.down.circle.fill" : "arrow.down.circle", size: 13, help: dl ? "Remove download" : "Download") {
                    if dl { lib.removeDownloads([t.id]) } else { lib.download([t], label: t.title) }
                }
            }
            AirPlayButton().frame(width: 28, height: 28).help("AirPlay")
            Spacer(minLength: 4)
            VolumeSlider().frame(minWidth: 60, maxWidth: 110)
        }
        .foregroundStyle(p.t2)
    }
}

/// Pre-blurred album art filling the window (rendered once at 96 px; no blur filter per frame).
struct NowPlayingBackdrop: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    var body: some View {
        let p = theme.p
        ZStack {
            p.bg
            if let img = player.backdrop, !GlassEnv.reduceTransparency {
                Color.clear
                    .overlay(Image(nsImage: img).resizable().interpolation(.high).aspectRatio(contentMode: .fill))
                    .clipped()
                    .opacity(0.85)
                    .transition(.opacity)
                LinearGradient(colors: [.black.opacity(0.25), .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
            } else {
                GlowView(color: theme.glow, opacity: 0.35, center: UnitPoint(x: 0.3, y: 0.35), radius: 640)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .ignoresSafeArea()
        .animation(Motion.glow, value: player.current?.id)
    }
}

/// Next few tracks under the controls (tap to play, the queue panel has the rest).
struct UpNextList: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    var body: some View {
        let p = theme.p
        let q = player.queue
        let idx = Int(q?.index ?? -1)
        let next = Array((q?.tracks ?? []).enumerated().filter { $0.offset > idx }.prefix(8))
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Up next").font(theme.ui(16, .bold)).foregroundStyle(p.txt)
                Spacer()
                Button("Open queue") { nav.toggleQueue() }.buttonStyle(.plain).font(theme.ui(12, .semibold)).foregroundStyle(p.t2)
            }
            if next.isEmpty {
                Text(player.autoplay ? "Finding similar songs…" : "Nothing queued").font(theme.ui(12.5)).foregroundStyle(p.t2).padding(.vertical, 6)
            }
            ForEach(next, id: \.offset) { i, t in
                SimpleRow(t: t) { player.jump(i) }
            }
        }
        .accessibilityIdentifier("np_up_next")
    }
}

/// Glass capsule with the scrubber and the transport row on one axis (symmetric shuffle / repeat).
struct NPControls: View {
    var body: some View {
        VStack(spacing: 10) {
            Scrubber()
            Transport(size: 17)
        }
        .padding(.horizontal, 18).padding(.vertical, 14)
        .glassCard(22)
    }
}

struct LikeButton: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var lib: LibraryStore
    let t: Track
    var body: some View {
        let liked = lib.isLiked(t.id)
        Button { _ = lib.toggleLike(t) } label: {
            Image(systemName: liked ? "heart.fill" : "heart").font(.system(size: 14, weight: .semibold)).foregroundStyle(theme.p.txt)
                .frame(width: 31, height: 31)
                .symbolEffect(.bounce, value: liked)
        }
        .buttonStyle(PressStyle())
        .glassSurface(Circle(), interactive: true)
        .help(liked ? "Remove from Liked" : "Like")
        .accessibilityLabel(liked ? "Remove from Liked" : "Like")
    }
}

/// Compact centred speed pill with a 0.5–2× popover.
struct SpeedMenu: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var theme: Theme
    @State private var open = false
    var body: some View {
        Button { open.toggle() } label: {
            Text(label(player.rate)).font(theme.num(11.5, .medium)).foregroundStyle(theme.p.txt)
                .frame(width: 46, height: 26)
        }
        .buttonStyle(PressStyle())
        .glassSurface(Capsule(), interactive: true)
        .help("Playback speed")
        .popover(isPresented: $open, arrowEdge: .top) {
            HStack(spacing: 4) {
                ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0] as [Float], id: \.self) { r in
                    Button(label(r)) { player.setRate(r); open = false }
                        .buttonStyle(.plain).font(theme.num(12, .medium))
                        .foregroundStyle(player.rate == r ? theme.p.bg : theme.p.txt)
                        .frame(width: 44, height: 28)
                        .background(Capsule().fill(player.rate == r ? theme.p.txt : .clear))
                }
            }.padding(8)
        }
    }
    private func label(_ r: Float) -> String { r == 1 ? "1×" : String(format: "%g×", r) }
}

/// Karaoke lyrics. The active line follows the player clock (4 Hz) interpolated by a 30 fps timeline that
/// only runs while playing and on screen; only the active line receives the time, the other lines are
/// equatable and skip re-rendering.
struct LyricsPanel: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @ObservedObject private var clock = Player.shared.clock
    let t: Track
    var large = false
    @State private var lyrics: Lyrics?
    @State private var loaded = false
    @State private var visible = false
    @State private var base: (pos: Double, at: Date) = (0, Date())

    var body: some View {
        let p = theme.p
        Group {
            if !loaded {
                Shimmer(count: 2)
            } else if let l = lyrics, !l.lines.isEmpty {
                TimelineView(.animation(minimumInterval: 1 / 30, paused: !player.isPlaying || !visible || Motion.reduce)) { ctx in
                    let pos = player.isPlaying ? base.pos + ctx.date.timeIntervalSince(base.at) : clock.position
                    let ms = Int64(pos * 1000)
                    let active = l.synced ? (l.lines.lastIndex { Int64($0.startMs) <= ms + 150 } ?? -1) : -1
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 14) {
                                ForEach(Array(l.lines.enumerated()), id: \.offset) { i, line in
                                    KaraokeLine(line: line, active: i == active, distance: active < 0 ? 0 : abs(i - active), past: i < active, synced: l.synced,
                                                ms: i == active && !line.words.isEmpty ? ms : 0, scale: theme.lyricsScale * (large ? 1.15 : 1))
                                        .equatable()
                                        .id(i)
                                        .onTapGesture { if l.synced { player.seek(Double(line.startMs) / 1000) } }
                                }
                            }.padding(.vertical, large ? 160 : 60).padding(.horizontal, 4)
                        }
                        .scrollIndicators(.never)
                        .mask(LinearGradient(colors: [.clear, .black, .black, .clear], startPoint: .top, endPoint: .bottom))
                        .onChange(of: active) { _, a in
                            guard a >= 0 else { return }
                            withAnimation(Motion.soft) { proxy.scrollTo(a, anchor: UnitPoint(x: 0, y: 0.35)) }
                        }
                    }
                }
            } else {
                Text("No lyrics for this one.").font(theme.ui(14)).foregroundStyle(p.t2).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .onAppear { visible = true }
        .onDisappear { visible = false }
        .task(id: t.id) {
            loaded = false
            lyrics = try? await Engine.core.lyrics(track: t)
            withAnimation(Motion.spring) { loaded = true }
        }
        .onChange(of: clock.position) { _, v in base = (v, Date()) }
    }
}

struct KaraokeLine: View, Equatable {
    @EnvironmentObject var theme: Theme
    let line: LyricLine
    let active: Bool
    let distance: Int
    let past: Bool
    let synced: Bool
    let ms: Int64
    var scale: Double = 1

    static func == (a: KaraokeLine, b: KaraokeLine) -> Bool {
        a.active == b.active && a.distance == b.distance && a.past == b.past && a.ms == b.ms && a.scale == b.scale && a.line.startMs == b.line.startMs && a.line.text == b.line.text
    }

    var body: some View {
        let p = theme.p
        let size = (active ? 26.0 : 22.0) * scale
        Group {
            if active && !line.words.isEmpty {
                line.words.reduce(Text("")) { acc, w in
                    let f = max(0, min(1, Double(ms - Int64(w.startMs)) / Double(max(1, Int64(w.endMs) - Int64(w.startMs)))))
                    return acc + Text(w.text + " ").foregroundColor(Color.lerp(p.t3, p.txt, f))
                }
            } else {
                Text(line.text).foregroundColor(!synced ? p.t2 : active ? p.txt : past ? p.t3.opacity(0.8) : distance == 1 ? p.t3 : p.t4)
            }
        }
        .font(theme.ui(size, .bold))
        .scaleEffect(active || !synced ? 1 : 0.95, anchor: .leading)
        .opacity(active || !synced || Motion.reduce ? 1 : max(0.55, 1 - Double(min(distance, 4)) * 0.1))
        .animation(Motion.spring, value: active)
    }
}

extension Color {
    static func lerp(_ a: Color, _ b: Color, _ t: Double) -> Color {
        let x = NSColor(a).usingColorSpace(.sRGB) ?? .gray
        let y = NSColor(b).usingColorSpace(.sRGB) ?? .white
        return Color(.sRGB, red: Double(x.redComponent + (y.redComponent - x.redComponent) * t),
                     green: Double(x.greenComponent + (y.greenComponent - x.greenComponent) * t),
                     blue: Double(x.blueComponent + (y.blueComponent - x.blueComponent) * t), opacity: 1)
    }
}

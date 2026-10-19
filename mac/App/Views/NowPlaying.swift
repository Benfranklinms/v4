import AppKit
import SwiftUI

/// Full-window Now Playing over a blurred-art backdrop. Art + glass controls on the left; lyrics beside the art
/// when wide, replacing it when narrow. Navigation (artist / album) collapses this first, then uses the main stack.
struct NowPlayingView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore

    var body: some View {
        let p = theme.p
        GeometryReader { g in
            let wide = g.size.width >= 960
            let lyricsBeside = wide && nav.showLyrics
            let lyricsOnly = !wide && nav.showLyrics
            let colW = min(600, lyricsBeside ? g.size.width * 0.42 : g.size.width - 96)
            let artSide = max(120, min(colW, g.size.height * 0.5, g.size.height - 330))
            ZStack {
                NowPlayingBackdrop()
                if let t = player.current {
                    VStack(spacing: 0) {
                        topBar(t)
                        HStack(alignment: .center, spacing: 56) {
                            VStack(alignment: .leading, spacing: 0) {
                                if lyricsOnly {
                                    LyricsPanel(t: t).frame(maxHeight: .infinity)
                                } else {
                                    Spacer(minLength: 8)
                                    ArtView(url: t.thumb, image: player.artwork, size: artSide, radius: 16)
                                        .scaleEffect(player.isPlaying || Motion.reduce ? 1 : 0.94)
                                        .shadow(color: .black.opacity(0.45), radius: 36, y: 14)
                                        .animation(Motion.bouncy, value: player.isPlaying)
                                        .id(t.id)
                                        .transition(.opacity.combined(with: .scale(scale: 0.97)))
                                        .onTapGesture(count: 2) { lib.toggleLike(t) }
                                        .frame(maxWidth: .infinity, alignment: lyricsBeside ? .leading : .center)
                                        .accessibilityIdentifier("np_art")
                                }
                                titleRow(t).padding(.top, 20)
                                NPControls().padding(.top, 14)
                                secondaryRow(t).padding(.top, 14)
                                Spacer(minLength: 8)
                            }
                            .frame(width: colW)
                            if lyricsBeside {
                                LyricsPanel(t: t)
                                    .frame(maxWidth: 620, maxHeight: .infinity)
                                    .transition(.opacity.combined(with: .move(edge: .trailing)))
                            }
                        }
                        .padding(.horizontal, 48)
                        .padding(.bottom, 24)
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
                Text("PLAYING FROM").font(theme.ui(9.5, .bold)).tracking(1).foregroundStyle(p.t3)
                Text(player.queue?.sourceTitle ?? "").font(theme.ui(12.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
            }
            Spacer()
            Menu { TrackMenuItems(t: t) } label: { Image(systemName: "ellipsis").font(.system(size: 13, weight: .bold)).frame(width: 31, height: 31) }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).frame(width: 31, height: 31)
                .glassSurface(Circle(), interactive: true)
                .help("More")
        }
        // clear of the traffic lights (title bar inset)
        .padding(.leading, 84).padding(.trailing, 18).padding(.top, 12).frame(height: 56)
    }

    private func titleRow(_ t: Track) -> some View {
        let p = theme.p
        return HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Marquee(text: t.title, font: theme.ui(22, .bold), color: p.txt).frame(height: 28)
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
            VolumeSlider().frame(width: 110)
        }
        .foregroundStyle(p.t2)
    }
}

/// Blurred, saturated album art filling the window (opaque: falls back to the palette background).
struct NowPlayingBackdrop: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    var body: some View {
        let p = theme.p
        ZStack {
            p.bg
            if let img = player.artwork, !GlassEnv.reduceTransparency {
                Image(nsImage: img).resizable().aspectRatio(contentMode: .fill)
                    .blur(radius: 90, opaque: true).saturation(1.35).opacity(0.85)
                    .transition(.opacity)
                LinearGradient(colors: [.black.opacity(0.25), .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
            } else {
                GlowView(color: theme.glow, opacity: 0.35, center: UnitPoint(x: 0.3, y: 0.35), radius: 640)
            }
        }
        .clipped()
        .ignoresSafeArea()
        .animation(Motion.glow, value: player.current?.id)
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

/// Karaoke lyrics: frame-smooth position via TimelineView; active line fills word by word.
struct LyricsPanel: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    let t: Track
    @State private var lyrics: Lyrics?
    @State private var loaded = false
    @State private var base: (pos: Double, at: Date) = (0, Date())

    var body: some View {
        let p = theme.p
        Group {
            if !loaded {
                Shimmer(count: 2)
            } else if let l = lyrics, !l.lines.isEmpty {
                TimelineView(.animation(minimumInterval: nil, paused: !player.isPlaying)) { ctx in
                    let pos = player.isPlaying ? base.pos + ctx.date.timeIntervalSince(base.at) : player.position
                    let ms = Int64(pos * 1000)
                    let active = l.synced ? (l.lines.lastIndex { Int64($0.startMs) <= ms + 150 } ?? -1) : -1
                    ScrollViewReader { proxy in
                        ScrollView {
                            VStack(alignment: .leading, spacing: 14) {
                                ForEach(Array(l.lines.enumerated()), id: \.offset) { i, line in
                                    KaraokeLine(line: line, active: i == active, distance: active < 0 ? 0 : abs(i - active), past: i < active, synced: l.synced, ms: ms)
                                        .id(i)
                                        .onTapGesture { if l.synced { player.seek(Double(line.startMs) / 1000) } }
                                }
                            }.padding(.vertical, 160).padding(.horizontal, 4)
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
                Text("No lyrics for this one.").font(theme.ui(14)).foregroundStyle(p.t3).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: t.id) {
            loaded = false
            lyrics = try? await Engine.core.lyrics(track: t)
            withAnimation(Motion.spring) { loaded = true }
        }
        .onChange(of: player.position) { _, v in base = (v, Date()) }
    }
}

struct KaraokeLine: View {
    @EnvironmentObject var theme: Theme
    let line: LyricLine
    let active: Bool
    let distance: Int
    let past: Bool
    let synced: Bool
    let ms: Int64

    var body: some View {
        let p = theme.p
        let size = (active ? 26.0 : 22.0) * theme.lyricsScale
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
        .blur(radius: active || !synced || Motion.reduce ? 0 : CGFloat(min(distance, 3)) * 0.9)
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

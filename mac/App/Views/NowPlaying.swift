import AppKit
import SwiftUI

struct NowPlayingView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    let ns: Namespace.ID
    @State private var tab = "Lyrics"

    var body: some View {
        let p = theme.p
        ZStack {
            p.bg
            GlowView(color: theme.glow, opacity: p.light ? 0.3 : 0.55, center: UnitPoint(x: 0.28, y: 0.35), radius: 640)
            GlowView(color: theme.glow, opacity: 0.12, center: UnitPoint(x: 0.8, y: 0.8), radius: 420)
            if let t = player.current {
                HStack(alignment: .center, spacing: 48) {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack {
                            IconButton("chevron.down") { withAnimation(Motion.spring) { player.showNowPlaying = false } }.foregroundStyle(p.txt)
                            VStack(alignment: .leading, spacing: 1) {
                                Text("PLAYING FROM").font(theme.ui(9.5, .bold)).tracking(1).foregroundStyle(p.t3)
                                Text(player.queue?.sourceTitle ?? "").font(theme.ui(12.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                            }
                            Spacer()
                        }
                        Spacer(minLength: 16)
                        ArtView(url: t.thumb, image: player.artwork, size: 360, radius: 18)
                            .matchedGeometryEffect(id: "art", in: ns)
                            .scaleEffect(player.isPlaying || Motion.reduce ? 1 : 0.94)
                            .shadow(color: theme.glow.opacity(0.45), radius: 40, y: 10)
                            .animation(Motion.bouncy, value: player.isPlaying)
                            .id(t.id)
                            .transition(.opacity.combined(with: .scale(scale: 0.97)))
                        HStack(alignment: .center) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(t.title).font(theme.ui(24, .bold)).foregroundStyle(p.txt).lineLimit(1)
                                    .id("t" + t.id).transition(.opacity.combined(with: .offset(y: 8)))
                                Button {
                                    if let a = t.artistId { nav.push(.artist(a)) }
                                } label: {
                                    Text([t.artists, t.album].compactMap { $0 }.joined(separator: " · ")).font(theme.ui(13.5)).foregroundStyle(p.t2).lineLimit(1)
                                }.buttonStyle(.plain)
                            }
                            Spacer()
                            LikeButton(t: t)
                        }
                        .frame(width: 360).padding(.top, 26)
                        Scrubber(width: 260).frame(width: 360).padding(.top, 14)
                        Transport(size: 18).frame(width: 360).padding(.top, 12)
                        HStack(spacing: 8) {
                            SpeedMenu()
                            Chip(label: "Why this song?", icon: "sparkles") { nav.show((Engine.core.isaWhy(track: t))) }
                            Chip(label: "Download", icon: "arrow.down") {
                                Task { do { _ = try await Engine.core.download(track: t); nav.show("Downloaded") } catch { nav.show("Download failed") } }
                            }
                            AirPlayButton().frame(width: 26, height: 26)
                        }.padding(.top, 14)
                        Spacer(minLength: 16)
                    }
                    .frame(width: 380)
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 8) {
                            ForEach(["Lyrics", "Up next"], id: \.self) { x in Chip(label: x, selected: tab == x) { withAnimation(Motion.spring) { tab = x } } }
                        }
                        if tab == "Lyrics" { LyricsPanel(t: t).transition(.opacity) } else { QueuePanel().transition(.opacity) }
                    }
                    .frame(maxWidth: 560, maxHeight: .infinity)
                    .padding(.vertical, 40)
                }
                .padding(.horizontal, 48)
                .animation(Motion.spring, value: t.id)
            }
        }
    }
}

struct LikeButton: View {
    @EnvironmentObject var theme: Theme
    let t: Track
    @State private var liked = false
    var body: some View {
        Button { liked.toggle(); try? Engine.core.setLiked(track: t, liked: liked) } label: {
            Image(systemName: liked ? "heart.fill" : "heart").font(.system(size: 20, weight: .semibold)).foregroundStyle(theme.p.txt)
                .symbolEffect(.bounce, value: liked)
        }
        .buttonStyle(PressStyle())
        .task(id: t.id) { liked = Engine.core.isLiked(id: t.id) }
    }
}

struct SpeedMenu: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var theme: Theme
    @State private var rate: Float = 1
    var body: some View {
        Menu {
            ForEach([0.75, 0.9, 1.0, 1.1, 1.25, 1.5] as [Float], id: \.self) { r in Button("\(String(format: "%.2g", r))×") { rate = r; player.setRate(r) } }
        } label: { Text(String(format: "%.2g×", rate)).font(theme.num(12, .medium)) }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).frame(width: 52, height: 30)
            .background(Capsule().fill(theme.p.surf2)).overlay(Capsule().stroke(theme.p.line3, lineWidth: 0.6))
    }
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
                Text("No English lyrics for this one.").font(theme.ui(14)).foregroundStyle(p.t3).frame(maxWidth: .infinity, maxHeight: .infinity)
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

struct QueuePanel: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    var body: some View {
        let p = theme.p
        if let q = player.queue {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(q.tracks.enumerated()), id: \.offset) { i, t in
                            if i == Int(q.autoplayFrom) && q.autoplayFrom >= 0 {
                                Text("FROM RADIO · TUNED TO YOUR TASTE").font(theme.ui(10, .bold)).tracking(1).foregroundStyle(p.t3).padding(.top, 12).padding(.bottom, 4)
                            }
                            HStack(spacing: 12) {
                                ArtView(url: t.thumb, size: 38, radius: 6)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(t.title).font(theme.ui(13, .semibold)).foregroundStyle(i == Int(q.index) ? theme.accent : i < Int(q.index) ? p.t3 : p.txt).lineLimit(1)
                                    Text(t.artists).font(theme.ui(11)).foregroundStyle(p.t3).lineLimit(1)
                                }
                                Spacer()
                                if i > Int(q.index) { IconButton("xmark", size: 10) { player.remove(i) }.foregroundStyle(p.t3) }
                            }
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(RoundedRectangle(cornerRadius: 8).fill(i == Int(q.index) ? p.surf3 : .clear))
                            .contentShape(Rectangle())
                            .onTapGesture { player.jump(i) }
                            .id(i)
                        }
                    }
                }
                .scrollIndicators(.never)
                .onAppear { proxy.scrollTo(Int(q.index), anchor: .top) }
            }
        }
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

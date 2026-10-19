import AppKit
import SwiftUI

/// Image cache + off-main decoding for artwork.
final class ArtCache {
    static let shared = ArtCache()
    let cache = NSCache<NSString, NSImage>()
    func image(_ url: String) async -> NSImage? {
        if let i = cache.object(forKey: url as NSString) { return i }
        guard let u = URL(string: url) else { return nil }
        let img: NSImage? = await Task.detached(priority: .utility) {
            if u.isFileURL { return NSImage(contentsOf: u) }
            guard let (d, _) = try? await URLSession.shared.data(from: u) else { return nil }
            return NSImage(data: d)
        }.value
        if let img { cache.setObject(img, forKey: url as NSString) }
        return img
    }
}

struct ArtView: View {
    @EnvironmentObject var theme: Theme
    let url: String
    var image: NSImage? = nil
    var size: CGFloat
    var radius: CGFloat = 8
    var circle = false
    @State private var loaded: NSImage?

    var body: some View {
        let p = theme.p
        ZStack {
            p.surf3
            if let img = image ?? loaded {
                Image(nsImage: img).resizable().aspectRatio(contentMode: .fill).transition(.opacity)
            } else {
                PulseMark().foregroundStyle(p.t4).frame(width: size * 0.34, height: size * 0.34)
            }
        }
        .frame(width: size, height: size)
        .clipShape(circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: radius, style: .continuous)))
        .task(id: url) {
            guard image == nil, !url.isEmpty else { return }
            let i = await ArtCache.shared.image(url)
            withAnimation(.easeOut(duration: 0.25)) { loaded = i }
        }
    }
}

/// The app mark: a disc with three bars cut out.
struct PulseMark: View {
    var body: some View {
        Canvas { ctx, size in
            let r = min(size.width, size.height) / 2
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            var path = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
            let w = 0.2 * r
            for (x, h) in [(-0.36, 0.34), (0.0, 0.56), (0.36, 0.42)] {
                path.addPath(Path(roundedRect: CGRect(x: c.x + x * r - w / 2, y: c.y - h * r, width: w, height: 2 * h * r), cornerRadius: w / 2))
            }
            ctx.fill(path, with: .foreground, style: FillStyle(eoFill: true))
        }
    }
}

/// CALayer-backed radial glow (GPU, no relayout); colour crossfades with the theme animation.
struct GlowView: View {
    var color: Color
    var opacity: Double = 0.5
    var center: UnitPoint = UnitPoint(x: 0.5, y: 0.3)
    var radius: CGFloat = 520
    var body: some View {
        RadialGradient(colors: [color.opacity(opacity), color.opacity(opacity * 0.4), .clear], center: center, startRadius: 0, endRadius: radius)
            .drawingGroup()
            .allowsHitTesting(false)
            .animation(Motion.glow, value: color)
    }
}

struct IconButton: View {
    let name: String
    var size: CGFloat = 14
    let action: () -> Void
    init(_ name: String, size: CGFloat = 14, action: @escaping () -> Void) { self.name = name; self.size = size; self.action = action }
    var body: some View {
        Button(action: action) { Image(systemName: name).font(.system(size: size, weight: .semibold)).frame(width: size + 14, height: size + 14) }
            .buttonStyle(PressStyle())
    }
}

struct Transport: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    var size: CGFloat = 16
    var body: some View {
        let p = theme.p
        let q = player.queue
        HStack(spacing: size * 1.2) {
            IconButton("shuffle", size: size * 0.8) { player.setShuffle(!(q?.shuffle ?? false)) }
                .foregroundStyle(q?.shuffle == true ? theme.accent : p.t2)
            IconButton("backward.fill", size: size) { player.prev() }.foregroundStyle(p.txt)
            Button { player.toggle() } label: {
                ZStack {
                    Circle().fill(theme.accent)
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: size, weight: .bold)).foregroundStyle(p.light ? .white : p.bg)
                        .contentTransition(.symbolEffect(.replace))
                }.frame(width: size * 2.4, height: size * 2.4)
            }.buttonStyle(PressStyle(scale: 0.9))
            IconButton("forward.fill", size: size) { player.next() }.foregroundStyle(p.txt)
            IconButton(q?.repeat == 2 ? "repeat.1" : "repeat", size: size * 0.8) { player.cycleRepeat() }
                .foregroundStyle((q?.repeat ?? 0) > 0 ? theme.accent : p.t2)
        }
    }
}

struct Scrubber: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    var width: CGFloat? = nil
    @State private var drag: Double?
    @State private var hover = false

    var body: some View {
        let p = theme.p
        let f = drag ?? (player.duration > 0 ? min(1, player.position / player.duration) : 0)
        HStack(spacing: 10) {
            Text(fmt(drag.map { $0 * player.duration } ?? player.position)).font(theme.num(10.5)).foregroundStyle(p.t3).frame(width: 38, alignment: .trailing)
            GeometryReader { g in
                let h: CGFloat = hover || drag != nil ? 6 : 4
                ZStack(alignment: .leading) {
                    Capsule().fill(p.surf4).frame(height: h)
                    Capsule().fill(theme.glow.opacity(0.55)).frame(width: max(0, g.size.width * f), height: h * 3).blur(radius: 5)
                    Capsule().fill(p.txt).frame(width: max(0, g.size.width * f), height: h)
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in drag = max(0, min(1, v.location.x / g.size.width)) }
                    .onEnded { _ in if let d = drag { player.seek(d * player.duration) }; drag = nil })
                .onHover { h in withAnimation(Motion.spring) { hover = h } }
            }
            .frame(width: width, height: 14)
            Text(fmt(player.duration)).font(theme.num(10.5)).foregroundStyle(p.t3).frame(width: 38, alignment: .leading)
        }
    }
}

func fmt(_ s: Double) -> String {
    guard s.isFinite, s > 0 else { return "0:00" }
    let t = Int(s)
    return String(format: "%d:%02d", t / 60, t % 60)
}

struct TrackRow: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    let t: Track
    let index: Int
    var number: Int? = nil
    var showAlbum = true
    /// Set on the user's own playlists (enables "Remove from this playlist").
    var playlistId: String? = nil
    var position: Int? = nil
    let onPlay: () -> Void
    @State private var hover = false

    var body: some View {
        let p = theme.p
        let playing = player.current?.id == t.id
        HStack(spacing: 12) {
            if let n = number {
                ZStack {
                    if playing { EqBars(on: player.isPlaying).frame(width: 12, height: 12).foregroundStyle(theme.accent) }
                    else if hover { Image(systemName: "play.fill").font(.system(size: 10)).foregroundStyle(p.txt) }
                    else { Text("\(n)").font(theme.num(11)).foregroundStyle(p.t3) }
                }.frame(width: 22)
            }
            ArtView(url: t.thumb, size: 40, radius: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.title).font(theme.ui(13.5, .semibold)).foregroundStyle(playing ? theme.accent : p.txt).lineLimit(1)
                Text(t.artists).font(theme.ui(11.5, .medium)).foregroundStyle(p.t3).lineLimit(1)
            }
            Spacer(minLength: 8)
            if showAlbum, let a = t.album, !a.isEmpty {
                Text(a).font(theme.ui(11.5, .medium)).foregroundStyle(p.t3).lineLimit(1).frame(width: 200, alignment: .leading)
            }
            Text(t.durationS > 0 ? fmt(Double(t.durationS)) : "").font(theme.num(11)).foregroundStyle(p.t3).frame(width: 44, alignment: .trailing)
            TrackMenu(t: t, playlistId: playlistId, position: position).opacity(hover ? 1 : 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8).fill(hover ? Color.white.opacity(0.06) : .clear))
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { onPlay() }
        .onTapGesture { onPlay() }
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
        .contextMenu { TrackMenuItems(t: t, playlistId: playlistId, position: position) }
        .enter(index)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

struct TrackMenu: View {
    @EnvironmentObject var theme: Theme
    let t: Track
    var playlistId: String? = nil
    var position: Int? = nil
    var body: some View {
        Menu { TrackMenuItems(t: t, playlistId: playlistId, position: position) } label: { Image(systemName: "ellipsis").foregroundStyle(theme.p.t2) }
            .menuStyle(.borderlessButton).menuIndicator(.hidden).frame(width: 26)
            .help("More")
    }
}

/// Track context menu. Reads library state from the store (no core calls while the menu builds).
struct TrackMenuItems: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    let t: Track
    var playlistId: String? = nil
    var position: Int? = nil
    var body: some View {
        Button("Play Next") { player.playNext(t); nav.show("Playing next") }
        Button("Add to Queue") { player.addToQueue(t); nav.show("Added to queue") }
        Button(lib.isLiked(t.id) ? "Remove from Liked" : "Like") { lib.toggleLike(t) }
        Menu("Add to Playlist") {
            Button("New Playlist…") { nav.sheet = .newPlaylist([t]) }
            Divider()
            ForEach((lib.playlists ?? []).filter { LibraryStore.isLocal($0.id) && !lib.hidden.contains($0.id) }, id: \.id) { pl in
                Button(pl.title) { lib.add(t, to: pl) }
            }
        }
        if let pid = playlistId, let pos = position {
            Button("Remove from This Playlist") { lib.removeFromPlaylist(pid, position: pos, track: t) }
        }
        if !t.id.hasPrefix("local:") {
            Button("Start Radio") {
                Task { let r = (try? await Engine.core.radio(videoId: t.id)) ?? []; player.play([t] + r, index: 0, source: "\(t.title) radio") }
            }
            if lib.downloaded.contains(t.id) {
                Button("Remove Download") { lib.removeDownloads([t.id]) }
            } else {
                Button("Download") { lib.download([t], label: t.title) }
            }
        }
        Divider()
        if let a = t.artistId { Button("Go to Artist") { nav.push(.artist(a)) } }
        if let a = t.albumId { Button("Go to Album") { nav.push(.album(a)) } }
        Divider()
        Button("Copy Link") {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString("https://music.youtube.com/watch?v=\(t.id)", forType: .string)
            nav.show("Link copied")
        }
    }
}

struct AlbumMenuItems: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    let a: Album
    var body: some View {
        Button("Play") { Task { if let pg = try? await Engine.core.album(id: a.id) { player.play(pg.tracks, index: 0, source: a.title) } } }
        Button("Shuffle") { Task { if let pg = try? await Engine.core.album(id: a.id) { player.playShuffled(pg.tracks, source: a.title) } } }
        Button("Add to Queue") { Task { if let pg = try? await Engine.core.album(id: a.id) { pg.tracks.forEach { player.addToQueue($0) }; nav.show("Added to queue") } } }
        Divider()
        Button(lib.isSaved(album: a.id) ? "Remove from Library" : "Save to Library") { lib.toggleSaved(album: a) }
        Button("Download") { Task { if let pg = try? await Engine.core.album(id: a.id) { lib.download(pg.tracks, label: a.title) } } }
        Button("Remove Download") { Task { if let pg = try? await Engine.core.album(id: a.id) { lib.removeDownloads(pg.tracks.map(\.id).filter { lib.downloaded.contains($0) }) } } }
        Divider()
        Button("Go to Album") { nav.push(.album(a.id)) }
    }
}

/// Animated equaliser bars for the playing row.
struct EqBars: View {
    let on: Bool
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 20, paused: !on || Motion.reduce)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 1.5) {
                ForEach(0..<3, id: \.self) { i in
                    Capsule().frame(width: 2.5, height: on ? 3 + 9 * abs(sin(t * (3 + Double(i)) + Double(i))) : 4)
                }
            }
        }
    }
}

struct Chip: View {
    @EnvironmentObject var theme: Theme
    let label: String
    var icon: String? = nil
    var selected = false
    let action: () -> Void
    var body: some View {
        let p = theme.p
        Button(action: action) {
            HStack(spacing: 6) {
                if let i = icon { Image(systemName: i).font(.system(size: 11, weight: .semibold)) }
                Text(label).font(theme.ui(12, .medium)).lineLimit(1)
            }
            .foregroundStyle(selected ? p.txt : p.t2)
            .padding(.horizontal, 12).frame(height: 30)
            .background(Capsule().fill(selected ? p.surf4 : p.surf2))
            .overlay(Capsule().stroke(p.line3, lineWidth: 0.6))
        }.buttonStyle(PressStyle())
    }
}

struct SectionTitle: View {
    @EnvironmentObject var theme: Theme
    let text: String
    var body: some View {
        Text(text).font(theme.ui(18, .bold)).foregroundStyle(theme.p.txt).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 26).padding(.bottom, 10)
    }
}

/// Shimmer placeholder (keeps layout stable while content loads).
struct Shimmer: View {
    @EnvironmentObject var theme: Theme
    var count = 4
    @State private var on = false
    var body: some View {
        HStack(spacing: 14) {
            ForEach(0..<count, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 12).fill(theme.p.surf3).frame(width: 150, height: 150)
            }
        }
        .opacity(on ? 0.85 : 0.35)
        .onAppear { withAnimation(.easeInOut(duration: 0.8).repeatForever()) { on = true } }
    }
}

/// Number that rolls to its value.
struct RollingNumber: View, Animatable {
    @EnvironmentObject var theme: Theme
    var value: Double
    var size: CGFloat = 40
    var animatableData: Double { get { value } set { value = newValue } }
    var body: some View {
        Text("\(Int(value.rounded()))").font(theme.num(size, .light)).foregroundStyle(theme.p.txt).monospacedDigit()
    }
}

struct RollIn: View {
    let target: Double
    var size: CGFloat = 40
    @State private var v: Double = 0
    var body: some View {
        RollingNumber(value: v, size: size)
            .onAppear { withAnimation(Motion.soft) { v = target } }
            .onChange(of: target) { _, n in withAnimation(Motion.soft) { v = n } }
    }
}

struct Toggle2: View {
    @EnvironmentObject var theme: Theme
    @Binding var on: Bool
    var body: some View {
        let p = theme.p
        Button { withAnimation(Motion.bouncy) { on.toggle() } } label: {
            ZStack(alignment: on ? .trailing : .leading) {
                Capsule().fill(on ? theme.accent : p.surf4).frame(width: 40, height: 24)
                Circle().fill(on ? (p.light ? Color.white : p.bg) : p.t2).frame(width: 18, height: 18).padding(3)
            }
        }.buttonStyle(.plain)
    }
}

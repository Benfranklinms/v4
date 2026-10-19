import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Spotify-style right panel: Queue | Recently played tabs (mono underline), close ×, resizable 280–420 pt.
struct QueueSidePanel: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @AppStorage("queueTab") private var tab = "Queue"
    @State private var recent: [Track]?
    @State private var dragStart: Double?

    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 16) {
                ForEach(["Queue", "Recently played"], id: \.self) { x in
                    Button { withAnimation(Motion.spring) { tab = x } } label: {
                        VStack(spacing: 6) {
                            Text(x).font(theme.ui(13, tab == x ? .bold : .medium)).foregroundStyle(tab == x ? p.txt : p.t2).lineLimit(1)
                            Capsule().fill(tab == x ? p.txt : .clear).frame(height: 2)
                        }.fixedSize()
                    }.buttonStyle(.plain).accessibilityIdentifier("queue_tab_\(x)")
                }
                Spacer(minLength: 8)
                Button { nav.closeQueue() } label: {
                    Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).foregroundStyle(p.txt)
                        .frame(width: 26, height: 26)
                        .background(Circle().fill(p.light ? Color.black.opacity(0.08) : Color.white.opacity(0.12)))
                        .contentShape(Circle())
                }
                .buttonStyle(PressStyle(scale: 0.9))
                .help("Close (Esc)").accessibilityLabel("Close queue").accessibilityIdentifier("queue_close")
            }
            .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 8)
            Divider().opacity(0.4)
            Group { if tab == "Queue" { queueList } else { recentList } }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
        }
        .overlay(alignment: .leading) {
            // resize handle
            Rectangle().fill(Color.clear).frame(width: 6).contentShape(Rectangle())
                .onHover { h in if h { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() } }
                .gesture(DragGesture().onChanged { v in
                    if dragStart == nil { dragStart = nav.queueWidth }
                    nav.queueWidth = min(420, max(280, (dragStart ?? 320) - v.translation.width))
                }.onEnded { _ in dragStart = nil })
        }
        .task(id: "\(tab)-\(player.current?.id ?? "")") {
            if tab != "Queue" { recent = await Engine.off { try Engine.core.recent(limit: 50) } ?? [] }
        }
        .accessibilityIdentifier("queue_panel")
    }

    @ViewBuilder private var queueList: some View {
        let p = theme.p
        if let q = player.queue, !q.tracks.isEmpty {
            let idx = Int(q.index)
            let apFrom = q.autoplayFrom >= 0 ? Int(q.autoplayFrom) : q.tracks.count
            let userNext = Array(q.tracks.indices.filter { $0 > idx && $0 < apFrom })
            let auto = Array(q.tracks.indices.filter { $0 > idx && $0 >= apFrom })
            List {
                if idx >= 0 && idx < q.tracks.count {
                    header("Now playing")
                    QueueRow(t: q.tracks[idx], index: idx, current: true)
                }
                if !userNext.isEmpty {
                    header("Next in queue")
                    ForEach(userNext, id: \.self) { i in QueueRow(t: q.tracks[i], index: i) }
                        .onMove { from, to in move(userNext, from, to) }
                }
                HStack {
                    Text(auto.isEmpty && !player.autoplay ? "Autoplay is off" : "Next up · Autoplay").font(theme.ui(12, .bold)).foregroundStyle(p.t2)
                    Spacer()
                    Toggle2(on: Binding(get: { player.autoplay }, set: { player.setAutoplay($0) }))
                        .scaleEffect(0.8).help("Autoplay similar songs").accessibilityIdentifier("autoplay_toggle")
                }
                .listRowSeparator(.hidden).listRowBackground(Color.clear).padding(.top, 10)
                if player.autoplay {
                    ForEach(auto, id: \.self) { i in QueueRow(t: q.tracks[i], index: i) }
                        .onMove { from, to in move(auto, from, to) }
                    if auto.isEmpty {
                        Text("Finding similar songs…").font(theme.ui(12)).foregroundStyle(p.t3).listRowBackground(Color.clear).listRowSeparator(.hidden)
                    }
                }
                Button { nav.sheet = .newPlaylist(Array(q.tracks.dropFirst(max(0, idx)))) } label: {
                    Label("Save queue as playlist", systemImage: "plus").font(theme.ui(12, .semibold)).foregroundStyle(p.t2)
                }.buttonStyle(.plain).listRowBackground(Color.clear).listRowSeparator(.hidden).padding(.vertical, 10)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 10)
        } else {
            VStack(spacing: 8) {
                Image(systemName: "list.bullet").font(.system(size: 22)).foregroundStyle(p.t3)
                Text("Nothing queued").font(theme.ui(13, .semibold)).foregroundStyle(p.t2)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder private var recentList: some View {
        let p = theme.p
        if let r = recent {
            if r.isEmpty {
                Text("Play something and it shows up here.").font(theme.ui(12.5)).foregroundStyle(p.t3).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(r.enumerated()), id: \.offset) { i, t in
                            SimpleRow(t: t) { player.play(r, index: i, source: "Recently played") }
                                .contextMenu { TrackMenuItems(t: t) }
                        }
                    }.padding(10)
                }.scrollIndicators(.never)
            }
        } else {
            ProgressView().controlSize(.small).frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func header(_ s: String) -> some View {
        Text(s).font(theme.ui(12, .bold)).foregroundStyle(theme.p.t2).padding(.top, 8)
            .listRowSeparator(.hidden).listRowBackground(Color.clear)
    }

    /// Reorder inside a section: translate list offsets to queue indices.
    private func move(_ section: [Int], _ from: IndexSet, _ to: Int) {
        guard let f = from.first, f < section.count else { return }
        let src = section[f]
        let dst = to >= section.count ? (section.last ?? src) : section[to] - (to > f ? 1 : 0)
        if src != dst { player.move(src, dst) }
    }
}

struct QueueRow: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    let t: Track
    let index: Int
    var current = false
    @State private var hover = false
    var body: some View {
        let p = theme.p
        HStack(spacing: 10) {
            ArtView(url: t.thumb, size: 38, radius: 6)
            VStack(alignment: .leading, spacing: 1) {
                Text(t.title).font(theme.ui(12.5, .semibold)).foregroundStyle(current ? theme.accent : p.txt).lineLimit(1)
                Text(t.artists).font(theme.ui(11)).foregroundStyle(p.t3).lineLimit(1)
            }
            Spacer(minLength: 4)
            if !current {
                Button { player.remove(index) } label: { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundStyle(p.t3).frame(width: 20, height: 20) }
                    .buttonStyle(PressStyle()).opacity(hover ? 1 : 0).help("Remove from queue")
            }
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
        .onTapGesture { if !current { player.jump(index) } }
        .onHover { h in hover = h }
        .contextMenu {
            if !current { Button("Play Now") { player.jump(index) }; Button("Remove from Queue") { player.remove(index) } }
            TrackMenuItems(t: t)
        }
        .listRowBackground(RoundedRectangle(cornerRadius: 8).fill(current ? Color.white.opacity(0.08) : hover ? Color.white.opacity(0.05) : .clear))
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 1, leading: 8, bottom: 1, trailing: 8))
    }
}

struct SimpleRow: View {
    @EnvironmentObject var theme: Theme
    let t: Track
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        let p = theme.p
        HStack(spacing: 10) {
            ArtView(url: t.thumb, size: 38, radius: 6)
            VStack(alignment: .leading, spacing: 1) {
                Text(t.title).font(theme.ui(12.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                Text(t.artists).font(theme.ui(11)).foregroundStyle(p.t3).lineLimit(1)
            }
            Spacer()
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 8).fill(hover ? Color.white.opacity(0.05) : .clear))
        .contentShape(Rectangle())
        .onHover { h in hover = h }
        .onTapGesture(perform: action)
    }
}

import SwiftUI

/// Spotify / Apple Music playlist import: paste a link, review matches, save.
struct ImportView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @State private var url = ""
    @State private var busy = false
    @State private var result: ImportResult?
    @State private var error: String?

    var body: some View {
        let p = theme.p
        Page {
            Text("Import").font(theme.ui(30, .bold)).foregroundStyle(p.txt)
            HStack(spacing: 10) {
                Image(systemName: "link").foregroundStyle(p.t3)
                TextField("Spotify or Apple Music playlist link", text: $url).textFieldStyle(.plain)
                    .font(theme.ui(13.5)).foregroundStyle(p.txt).onSubmit(run)
                Button(action: run) {
                    Group {
                        if busy { ProgressView().controlSize(.small) } else { Text("Import").font(theme.ui(12.5, .bold)) }
                    }
                    .foregroundStyle(p.light ? .white : p.bg).padding(.horizontal, 16).frame(height: 32)
                    .background(Capsule().fill(theme.accent))
                }.buttonStyle(PressStyle()).disabled(busy)
            }
            .padding(.leading, 16).padding(.trailing, 6).frame(height: 46)
            .background(Capsule().fill(p.surf)).overlay(Capsule().stroke(p.line3, lineWidth: 0.6))
            .padding(.top, 18)

            if let e = error {
                Text(e).font(theme.ui(12.5)).foregroundStyle(p.t2).padding(.top, 12)
            }

            if let r = result {
                let matched = r.matches.compactMap { $0.matched }
                HStack(spacing: 16) {
                    ArtView(url: r.cover, size: 96, radius: 10)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(r.name).font(theme.ui(22, .bold)).foregroundStyle(p.txt)
                        Text("\(r.service) · \(matched.count) of \(r.matches.count) matched").font(theme.num(12)).foregroundStyle(p.t2)
                        HStack(spacing: 8) {
                            Chip(label: "Save to library", icon: "plus", selected: true) { save(r, matched) }
                            Chip(label: "Play", icon: "play.fill") { Player.shared.play(matched, index: 0, source: r.name) }
                        }
                    }
                }
                .padding(.top, 26)
                SectionTitle(text: "Tracks")
                ForEach(Array(r.matches.enumerated()), id: \.offset) { i, m in
                    HStack(spacing: 12) {
                        Image(systemName: m.status == "matched" ? "checkmark.circle.fill" : m.status == "review" ? "questionmark.circle" : "xmark.circle")
                            .foregroundStyle(m.status == "matched" ? p.txt : p.t3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(m.source.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                            Text(m.source.artists).font(theme.ui(11.5)).foregroundStyle(p.t2).lineLimit(1)
                        }
                        Spacer()
                        if let t = m.matched {
                            Text(t.title).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1).frame(maxWidth: 220, alignment: .trailing)
                        }
                        Text("\(m.confidence)").font(theme.num(11)).foregroundStyle(p.t3).frame(width: 30, alignment: .trailing)
                    }
                    .padding(.vertical, 6)
                    .enter(min(i, 20))
                }
            }
        }
    }

    private func run() {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !u.isEmpty, !busy else { return }
        busy = true; error = nil
        Task {
            do { let r = try await Engine.core.importPlaylist(url: u); withAnimation(Motion.spring) { result = r } }
            catch { self.error = Engine.message(error) }
            busy = false
        }
    }

    private func save(_ r: ImportResult, _ tracks: [Track]) {
        if let pl = try? Engine.core.saveImport(name: r.name, service: r.service, cover: r.cover, tracks: tracks) {
            nav.show("Saved \(pl.title)")
        }
    }
}

/// "Ask Isa": on-device DJ that turns a prompt into a playlist.
struct IsaView: View {
    @EnvironmentObject var theme: Theme
    let prompt: String
    @State private var res: IsaResult?
    @State private var error: String?

    var body: some View {
        let p = theme.p
        Group {
            if let r = res {
                TrackListPage(title: r.plan.title, subtitle: "Isa", art: r.tracks.first?.thumb ?? "", tracks: r.tracks, source: "Isa · \(r.plan.title)",
                              extra: AnyView(
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(r.plan.explanation).font(theme.ui(13)).foregroundStyle(p.t2)
                                    HStack(spacing: 6) { ForEach(r.plan.moods, id: \.self) { m in Chip(label: m) {} } }
                                }.padding(.bottom, 8)))
            } else if let e = error {
                Page {
                    Text("Isa").font(theme.ui(30, .bold)).foregroundStyle(p.txt)
                    Text(e).font(theme.ui(13)).foregroundStyle(p.t2).padding(.top, 10)
                }
            } else {
                VStack(spacing: 14) {
                    Image(systemName: "sparkles").font(.system(size: 28)).foregroundStyle(theme.accent).symbolEffect(.pulse)
                    Text(prompt).font(theme.ui(15, .semibold)).foregroundStyle(p.txt)
                    Text(Engine.core.isaPlan(prompt: prompt).explanation).font(theme.ui(12.5)).foregroundStyle(p.t2)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: prompt) {
            do { let r = try await Engine.core.isa(prompt: prompt); withAnimation(Motion.spring) { res = r } }
            catch { self.error = Engine.message(error) }
        }
    }
}

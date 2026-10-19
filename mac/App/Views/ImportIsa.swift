import SwiftUI

/// Spotify / Apple Music playlist import: paste a link, watch progress (X / Y, current track, Cancel),
/// review low-confidence matches, save. Primary buttons are #09090A on #E8E8E8 (never white-on-white).
struct ImportView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    @State private var url = ""
    @State private var busy = false
    @State private var result: ImportResult?
    @State private var picks: [Int: Track] = [:]
    @State private var error: String?
    @State private var progress: ImportProgress?
    @State private var saved: Playlist?

    var body: some View {
        let p = theme.p
        Page {
            LargeTitle(text: "Import")
            Text("Bring a playlist over from Spotify or Apple Music.").font(theme.ui(13)).foregroundStyle(p.t2).padding(.top, 2)
            HStack(spacing: 10) {
                Image(systemName: "link").foregroundStyle(p.t3)
                TextField("Spotify or Apple Music playlist link", text: $url).textFieldStyle(.plain)
                    .font(theme.ui(13.5)).foregroundStyle(p.txt).onSubmit(run).disabled(busy)
                    .accessibilityIdentifier("import_url")
                PrimaryButton(title: "Import", icon: "square.and.arrow.down", busy: busy, busyTitle: "Importing…",
                              enabled: !url.trimmingCharacters(in: .whitespaces).isEmpty) { run() }
                    .accessibilityIdentifier("import_button")
            }
            .padding(.leading, 16).padding(.trailing, 6).frame(height: 50).frame(maxWidth: 720)
            .glassSurface(Capsule())
            .padding(.top, 18)

            if busy {
                let pr = progress
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(pr.map { $0.total > 0 ? "Matching \($0.done) of \($0.total)" : "Reading the playlist…" } ?? "Reading the playlist…")
                            .font(theme.ui(13, .semibold)).foregroundStyle(p.txt)
                        Spacer()
                        Button("Cancel") { Engine.bg { Engine.core.importCancel() } }.buttonStyle(.plain)
                            .font(theme.ui(12.5, .semibold)).foregroundStyle(p.t2).accessibilityIdentifier("import_cancel")
                    }
                    ProgressView(value: Double(pr?.done ?? 0), total: Double(max(1, pr?.total ?? 1))).tint(p.txt)
                    if let c = pr?.current, !c.isEmpty { Text(c).font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1) }
                }
                .padding(16).frame(maxWidth: 720)
                .background(RoundedRectangle(cornerRadius: 14).fill(p.surf))
                .padding(.top, 14)
                .accessibilityIdentifier("import_progress")
                .transition(.opacity)
            }

            if let e = error {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.circle").foregroundStyle(p.t2)
                    Text(e).font(theme.ui(12.5)).foregroundStyle(p.t2)
                }.padding(.top, 12)
            }

            if let r = result {
                let tracks = finalTracks(r)
                let review = r.matches.enumerated().filter { $0.element.status == "review" }
                let missing = r.matches.filter { $0.status == "missing" }.count
                HStack(spacing: 16) {
                    ArtView(url: r.cover, size: 96, radius: 10)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(r.name).font(theme.ui(22, .bold)).foregroundStyle(p.txt)
                        Text("\(r.service) · \(tracks.count) of \(r.matches.count) matched\(missing > 0 ? " · \(missing) not found" : "")").font(theme.num(12)).foregroundStyle(p.t2)
                        HStack(spacing: 8) {
                            if let s = saved {
                                PrimaryButton(title: "Open", icon: "music.note.list") { nav.push(.playlist(s.id)) }
                            } else {
                                PrimaryButton(title: "Save to Library", icon: "plus", enabled: !tracks.isEmpty) { save(r, tracks) }
                                    .accessibilityIdentifier("import_save")
                            }
                            SecondaryButton(title: "Play", icon: "play.fill") { Player.shared.play(tracks, index: 0, source: r.name) }
                        }
                    }
                }
                .padding(.top, 26)
                if !review.isEmpty {
                    SectionTitle(text: "Check these (\(review.count))")
                    ForEach(review, id: \.offset) { i, m in reviewRow(i, m) }
                }
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
                        if let t = picks[i] ?? m.matched {
                            Text("\(t.title) · \(t.artists)").font(theme.ui(11.5)).foregroundStyle(p.t3).lineLimit(1).frame(maxWidth: 260, alignment: .trailing)
                        } else {
                            Text("Not found").font(theme.ui(11.5, .semibold)).foregroundStyle(p.t3)
                        }
                    }
                    .padding(.vertical, 6)
                    .enter(min(i, 20))
                }
            }
        }
        .animation(Motion.spring, value: busy)
        .onAppear { if let u = nav.pendingImportURL { nav.pendingImportURL = nil; url = u; run() } }
        .task(id: busy) {
            while busy && !Task.isCancelled {
                progress = await Engine.off { Engine.core.importProgress() }
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
        .accessibilityIdentifier("import")
    }

    private func reviewRow(_ i: Int, _ m: ImportMatch) -> some View {
        let p = theme.p
        let options = [m.matched].compactMap { $0 } + m.alternatives
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(m.source.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                Text(m.source.artists).font(theme.ui(11.5)).foregroundStyle(p.t2).lineLimit(1)
            }.frame(width: 240, alignment: .leading)
            Image(systemName: "arrow.right").foregroundStyle(p.t3)
            Menu {
                ForEach(Array(options.enumerated()), id: \.offset) { _, t in
                    Button("\(t.title) · \(t.artists) · \(fmt(Double(t.durationS)))") { picks[i] = t }
                }
            } label: {
                let t = picks[i] ?? m.matched
                Text(t.map { "\($0.title) · \($0.artists)" } ?? "Choose").font(theme.ui(12.5)).lineLimit(1)
            }.menuStyle(.borderlessButton).frame(maxWidth: 320)
            Spacer()
        }.padding(.vertical, 6)
    }

    private func finalTracks(_ r: ImportResult) -> [Track] {
        r.matches.enumerated().compactMap { i, m in picks[i] ?? m.matched }
    }

    private func run() {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !u.isEmpty, !busy else { return }
        busy = true; error = nil; result = nil; picks = [:]; saved = nil; progress = nil
        Task {
            do { let r = try await Engine.core.importPlaylist(url: u); withAnimation(Motion.spring) { result = r } }
            catch {
                let m = Engine.message(error)
                self.error = m.contains("cancelled") ? "Import cancelled." : Engine.friendly(error, "Couldn't read that playlist. Check the link and that it's public.")
            }
            busy = false
        }
    }

    private func save(_ r: ImportResult, _ tracks: [Track]) {
        Task {
            let (n, s, c) = (r.name, r.service, r.cover)
            if let pl = await Engine.off({ try Engine.core.saveImport(name: n, service: s, cover: c, tracks: tracks) }) {
                saved = pl
                lib.refresh()
                nav.show("Saved \(pl.title)")
            } else { nav.show("Couldn't save the playlist") }
        }
    }
}

/// "Ask Isa": on-device DJ that turns a prompt into a playlist.
struct IsaView: View {
    @EnvironmentObject var theme: Theme
    let prompt: String
    @State private var res: IsaResult?
    @State private var error: String?
    @State private var planText = ""

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
                    Text(planText).font(theme.ui(12.5)).foregroundStyle(p.t2)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: prompt) {
            let pr = prompt
            planText = await Engine.off { Engine.core.isaPlan(prompt: pr).explanation } ?? ""
            do { let r = try await Engine.core.isa(prompt: prompt); withAnimation(Motion.spring) { res = r } }
            catch { self.error = Engine.message(error) }
        }
    }
}

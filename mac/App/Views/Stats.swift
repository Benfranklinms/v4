import SwiftUI

/// Always-on listening stats (computed by the core from the local play log).
struct StatsView: View {
    @EnvironmentObject var theme: Theme
    @State private var range = "week"
    @State private var s: StatsSummary?
    @State private var shown = false
    private let ranges = [("week", "Week"), ("month", "Month"), ("year", "Year"), ("all", "All time")]

    var body: some View {
        let p = theme.p
        Page {
            HStack(alignment: .firstTextBaseline) {
                Text("Stats").font(theme.ui(30, .bold)).foregroundStyle(p.txt)
                Spacer()
                HStack(spacing: 6) {
                    ForEach(ranges, id: \.0) { r in
                        Chip(label: r.1, selected: range == r.0) { withAnimation(Motion.spring) { range = r.0 } }
                    }
                }
            }
            if let s {
                HStack(alignment: .top, spacing: 14) {
                    card {
                        Text("MINUTES").font(theme.ui(10, .bold)).tracking(1).foregroundStyle(p.t3)
                        RollIn(target: Double(s.minutes), size: 46)
                        Text(delta(s)).font(theme.num(11.5)).foregroundStyle(p.t2)
                    }
                    card {
                        Text("PLAYS").font(theme.ui(10, .bold)).tracking(1).foregroundStyle(p.t3)
                        RollIn(target: Double(s.plays), size: 46)
                        Text("tracks played").font(theme.ui(11.5)).foregroundStyle(p.t2)
                    }
                    card {
                        Text("STREAK").font(theme.ui(10, .bold)).tracking(1).foregroundStyle(p.t3)
                        RollIn(target: Double(s.streakDays), size: 46)
                        HStack(spacing: 4) {
                            ForEach(Array(s.streakWeek.enumerated()), id: \.offset) { _, on in
                                Circle().fill(on ? theme.accent : p.surf4).frame(width: 8, height: 8)
                            }
                            Text("best \(s.bestStreakDays)").font(theme.num(11)).foregroundStyle(p.t3).padding(.leading, 4)
                        }
                    }
                }
                .padding(.top, 20)

                SectionTitle(text: "Listening")
                Bars(values: s.series, height: 150, shown: shown).frame(height: 150)
                    .padding(18).background(RoundedRectangle(cornerRadius: 14).fill(p.surf))

                HStack(alignment: .top, spacing: 24) {
                    VStack(alignment: .leading, spacing: 0) {
                        SectionTitle(text: "Top artists")
                        ForEach(Array(s.topArtists.prefix(6).enumerated()), id: \.element.id) { i, a in
                            rankRow(i, a, circle: true).enter(i)
                        }
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        SectionTitle(text: "Top tracks")
                        ForEach(Array(s.topTracks.prefix(6).enumerated()), id: \.element.id) { i, t in
                            rankRow(i, t, circle: false).enter(i)
                        }
                    }
                }

                HStack(alignment: .top, spacing: 24) {
                    VStack(alignment: .leading, spacing: 0) {
                        SectionTitle(text: "By hour")
                        Bars(values: s.clock, height: 90, shown: shown).frame(height: 90)
                        HStack { Text("0").font(theme.num(10)); Spacer(); Text("12").font(theme.num(10)); Spacer(); Text("23").font(theme.num(10)) }
                            .foregroundStyle(p.t3)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        SectionTitle(text: "Genres")
                        ForEach(s.genres.prefix(5), id: \.name) { g in
                            HStack {
                                Text(g.name).font(theme.ui(12.5, .medium)).foregroundStyle(p.txt)
                                Spacer()
                                Text("\(g.percent)%").font(theme.num(12)).foregroundStyle(p.t2)
                            }
                            GeometryReader { geo in
                                Capsule().fill(p.surf3).overlay(alignment: .leading) {
                                    Capsule().fill(theme.accent).frame(width: shown ? geo.size.width * CGFloat(g.percent) / 100 : 0)
                                }
                            }.frame(height: 4)
                        }
                    }
                }
            } else {
                Shimmer(count: 3).padding(.top, 20)
            }
        }
        .task(id: range) { load() }
    }

    private func load() {
        shown = false
        s = try? Engine.core.stats(range: range, tzOffsetMin: Engine.tzMinutes)
        withAnimation(Motion.soft.delay(0.05)) { shown = true }
    }

    private func delta(_ s: StatsSummary) -> String {
        guard s.prevMinutes > 0 else { return "—" }
        let d = (Double(s.minutes) - Double(s.prevMinutes)) / Double(s.prevMinutes) * 100
        return String(format: "%@%.0f%% vs previous", d >= 0 ? "+" : "", d)
    }

    @ViewBuilder private func card<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 6) { c() }
            .padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(theme.p.surf))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.p.line2, lineWidth: 0.6))
    }

    private func rankRow(_ i: Int, _ n: NamedCount, circle: Bool) -> some View {
        let p = theme.p
        return HStack(spacing: 12) {
            Text("\(i + 1)").font(theme.num(12)).foregroundStyle(p.t3).frame(width: 18)
            ArtView(url: n.thumb, size: 38, radius: 6, circle: circle)
            Text(n.name).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1)
            Spacer()
            Text("\(n.minutes) min").font(theme.num(11.5)).foregroundStyle(p.t2)
        }.padding(.vertical, 5)
    }
}

/// Bars that grow in with a spring.
struct Bars: View {
    @EnvironmentObject var theme: Theme
    let values: [UInt32]
    let height: CGFloat
    let shown: Bool
    var body: some View {
        let mx = max(1, values.max() ?? 1)
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(Array(values.enumerated()), id: \.offset) { i, v in
                RoundedRectangle(cornerRadius: 3)
                    .fill(v == mx ? theme.accent : theme.p.surf4)
                    .frame(height: shown ? max(2, height * CGFloat(v) / CGFloat(mx)) : 2)
                    .animation(Motion.spring.delay(Double(i) * 0.012), value: shown)
            }
        }
        .frame(maxWidth: .infinity, alignment: .bottom)
    }
}

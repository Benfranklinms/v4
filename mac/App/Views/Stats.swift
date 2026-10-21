import AppKit
import UniformTypeIdentifiers
import SwiftUI

/// Stats page state (shared with the smoke test). Every core call runs off the main thread.
@MainActor
final class StatsModel: ObservableObject {
    static let shared = StatsModel()
    @AppStorage("statsRange") var range = "week" { didSet { objectWillChange.send(); Task { await load() } } }
    @Published var page: StatsPage?
    /// Bar under the pointer (hover scrubbing), -1 none.
    @Published var hover = -1
    @Published var ghost = false
    @Published var hour = -1
    @Published var hourDetail: HourDetail?
    @Published var day: DayPick?
    @Published var recapOpen = false
    @Published var hidden: Set<String> = []
    private var cache: [String: StatsPage] = [:]
    var loads = 0

    struct DayPick: Identifiable { let id = UUID(); let title: String; let from: Int64; let to: Int64 }

    func load() async {
        let r = range
        if let c = cache[r] { page = c }
        guard let p = await Engine.off({ try Engine.core.statsPage(range: r, tzOffsetMin: Engine.tzMinutes) }) else { return }
        cache[r] = p
        loads += 1
        if r == range { page = p }
    }
    func invalidate() { cache = [:]; Task { await load() } }

    func pickHour(_ h: Int) {
        hour = (h == hour) ? -1 : h
        hourDetail = nil
        guard hour >= 0 else { return }
        let r = range, hh = UInt32(hour)
        Task {
            let d = await Engine.off { try Engine.core.statsHour(range: r, hour: hh, tzOffsetMin: Engine.tzMinutes) }
            if self.hour == Int(hh) { withAnimation(Motion.spring) { self.hourDetail = d } }
        }
    }

    func setGoal(_ m: Int) {
        Engine.bg { try Engine.core.setStatsGoal(minutes: UInt32(m)) }
        Task { try? await Task.sleep(nanoseconds: 150_000_000); self.invalidate() }
    }
}

func hm(_ min: Int) -> String { min >= 60 ? "\(min / 60)h \(min % 60)m" : "\(min)m" }
func playsLabel(_ n: Int) -> String { n == 1 ? "1 play" : "\(n) plays" }
func hourName(_ h: Int) -> String { h == 0 ? "12 AM" : h < 12 ? "\(h) AM" : h == 12 ? "12 PM" : "\(h - 12) PM" }

/// Always-on listening stats (computed by the core from the local play log).
struct StatsView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @ObservedObject var m = StatsModel.shared
    private let ranges = [("week", "Week"), ("month", "Month"), ("year", "Year"), ("all", "All")]

    var body: some View {
        let p = theme.p
        Page(name: "stats") {
            HStack(alignment: .center) {
                Text("Stats").font(theme.ui(30, .bold)).foregroundStyle(p.txt)
                Spacer()
                RangeSwitch(ranges: ranges, selected: m.range) { r in withAnimation(Motion.spring) { m.range = r } }
                    .frame(width: 320)
                Button { Haptics.tap(); m.recapOpen = true } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "sparkles").font(.system(size: 12, weight: .semibold)).foregroundStyle(theme.accent)
                        Text("Recap").font(theme.ui(13, .semibold)).foregroundStyle(p.txt)
                    }.padding(.horizontal, 14).frame(height: 34).glassCapsule()
                }.buttonStyle(PressStyle()).padding(.leading, 10).accessibilityIdentifier("stats_recap")
            }
            if let s = m.page {
                content(s)
            } else {
                Shimmer(count: 3).padding(.top, 20)
            }
        }
        .task { await m.load() }
        .sheet(isPresented: $m.recapOpen) { RecapSheet(startRange: m.range == "month" ? "month" : "week").environmentObject(theme) }
        .sheet(item: $m.day) { d in DayPlaysSheet(pick: d).environmentObject(theme) }
    }

    @ViewBuilder private func content(_ s: StatsPage) -> some View {
        let p = theme.p
        let empty = s.historyDays == 0
        HStack(alignment: .top, spacing: 16) {
            HeroCard(s: s).frame(maxWidth: .infinity)
            ClockCard(s: s).frame(width: 330)
        }.padding(.top, 20)
        if empty {
            Text("Play something and your stats start here.").font(theme.ui(13, .medium)).foregroundStyle(p.t3).padding(.top, 18)
        } else {
            HStack(alignment: .top, spacing: 16) {
                if let a = s.topArtists.first {
                    ArtCard(art: a.thumb, chip: "Top artist", title: a.name, meta: "\(hm(Int(a.minutes))) · \(playsLabel(Int(a.plays)))",
                            open: { if !a.id.isEmpty { nav.push(.artist(a.id)) } },
                            play: { let r = s.range; Task { if let mix = await Engine.off({ try Engine.core.statsMix(range: r, artist: a.name, tzOffsetMin: Engine.tzMinutes) }), !mix.isEmpty { Player.shared.play(mix, index: 0, source: a.name) } } })
                }
                if let t = s.topTracks.first {
                    ArtCard(art: t.track.thumb, chip: "On repeat", title: t.track.title, meta: "\(playsLabel(Int(t.plays))) · \(t.minutes) min",
                            open: { Player.shared.play([t.track], index: 0, source: "On repeat") },
                            play: { Player.shared.play([t.track], index: 0, source: "On repeat") })
                }
                GoalCard(s: s).frame(maxWidth: .infinity)
            }.padding(.top, 16)

            HStack(alignment: .top, spacing: 28) {
                VStack(alignment: .leading, spacing: 0) { TopSongs(s: s) }.frame(maxWidth: .infinity, alignment: .topLeading)
                VStack(alignment: .leading, spacing: 0) {
                    TopArtists(s: s)
                    if !s.topAlbums.isEmpty { TopAlbums(s: s) }
                }.frame(maxWidth: .infinity, alignment: .topLeading)
            }
            HStack(alignment: .top, spacing: 28) {
                if s.newArtistsReady && !s.newArtists.isEmpty {
                    VStack(alignment: .leading, spacing: 0) { NewToYou(s: s) }.frame(maxWidth: .infinity, alignment: .topLeading)
                }
                if !s.skipped.isEmpty {
                    VStack(alignment: .leading, spacing: 0) { SkippedOften(s: s) }.frame(maxWidth: .infinity, alignment: .topLeading)
                }
            }
        }
    }
}

struct RangeSwitch: View {
    @EnvironmentObject var theme: Theme
    let ranges: [(String, String)]
    let selected: String
    let pick: (String) -> Void
    @Namespace private var ns
    var body: some View {
        let p = theme.p
        HStack(spacing: 0) {
            ForEach(ranges, id: \.0) { r in
                Button { Haptics.tap(); pick(r.0) } label: {
                    Text(r.1).font(theme.ui(12.5, .semibold)).foregroundStyle(selected == r.0 ? p.txt : p.t3)
                        .frame(maxWidth: .infinity).frame(height: 30)
                        .background {
                            if selected == r.0 {
                                Capsule().fill(p.txt.opacity(0.12)).overlay(Capsule().stroke(p.line3, lineWidth: 0.6)).matchedGeometryEffect(id: "pill", in: ns)
                            }
                        }
                        .contentShape(Capsule())
                }.buttonStyle(.plain).accessibilityIdentifier("range_\(r.0)")
            }
        }
        .padding(3).glassCapsule()
        .animation(Motion.bouncy, value: selected)
    }
}

/// Minutes that count up as h / m (JetBrains Mono).
struct BigTime: View, Animatable {
    @EnvironmentObject var theme: Theme
    var minutes: Double
    var animatableData: Double { get { minutes } set { minutes = newValue } }
    var body: some View {
        let p = theme.p
        let v = max(0, Int(minutes.rounded()))
        HStack(alignment: .lastTextBaseline, spacing: 3) {
            if v >= 60 {
                Text("\(v / 60)").font(theme.num(54, .light)).foregroundStyle(p.txt)
                Text("h").font(theme.ui(17, .medium)).foregroundStyle(p.t2).padding(.trailing, 10)
                Text(String(format: "%02d", v % 60)).font(theme.num(54, .light)).foregroundStyle(p.txt)
            } else {
                Text("\(v)").font(theme.num(54, .light)).foregroundStyle(p.txt)
            }
            Text("m").font(theme.ui(17, .medium)).foregroundStyle(p.t2)
        }.monospacedDigit()
    }
}

private struct HeroCard: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject var m = StatsModel.shared
    let s: StatsPage
    @State private var shown = 0.0

    var body: some View {
        let p = theme.p
        let bar = m.hover >= 0 && m.hover < s.bars.count ? s.bars[m.hover] : nil
        let hist = Int(s.historyDays)
        let sparse = hist >= 1 && hist <= 6
        let label = bar?.title ?? (hist == 0 ? "Nothing played yet" : sparse ? (hist == 1 ? "Your first day" : "Your first \(hist) days") : title(s.range))
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(theme.ui(13, .semibold)).foregroundStyle(p.t2).accessibilityIdentifier("stats_hero_label")
                    BigTime(minutes: bar.map { Double($0.minutes) } ?? shown)
                }
                Spacer()
                if bar == nil {
                    if sparse {
                        Text("First week").font(theme.ui(12, .semibold)).foregroundStyle(theme.accent).padding(.horizontal, 12).frame(height: 28).glassCapsule()
                    } else if s.hasPrev && s.prevMinutes > 0 {
                        let pct = Int(((Double(s.minutes) - Double(s.prevMinutes)) / Double(s.prevMinutes) * 100).rounded())
                        VStack(alignment: .trailing, spacing: 5) {
                            Button { withAnimation(Motion.spring) { m.ghost.toggle() } } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: pct >= 0 ? "arrow.up.right" : "arrow.down.right").font(.system(size: 11, weight: .bold))
                                    Text("\(pct >= 0 ? "+" : "")\(pct)%").font(theme.num(13, .medium))
                                }
                                .foregroundStyle(pct >= 0 ? theme.accent : p.t2)
                                .padding(.horizontal, 11).frame(height: 28).glassCapsule()
                                .overlay(Capsule().stroke(m.ghost ? p.t2 : .clear, lineWidth: 1))
                            }.buttonStyle(PressStyle()).help("Show last period's bars")
                            Text(prev(s.range)).font(theme.ui(11.5, .medium)).foregroundStyle(p.t3)
                        }
                    }
                }
            }
            let line = bar.map { $0.line.isEmpty ? "Nothing played" : $0.line } ?? s.moodLine
            if !line.isEmpty {
                HStack(spacing: 7) {
                    if bar == nil { Image(systemName: icon(s.mood)).font(.system(size: 13)).foregroundStyle(p.t2) }
                    Text(line).font(theme.ui(14, .medium)).foregroundStyle(p.t2).lineLimit(1)
                }
            }
            BarChart(s: s).frame(height: 170).padding(.top, 12)
            if sparse { Text("Fills in as you listen").font(theme.ui(12, .medium)).foregroundStyle(p.t3) }
        }
        .padding(20)
        .glassCard(24)
        .onAppear { withAnimation(Motion.reduce ? nil : .easeOut(duration: 0.75)) { shown = Double(s.minutes) } }
        .onChange(of: s.minutes) { _, n in withAnimation(Motion.reduce ? nil : .easeOut(duration: 0.75)) { shown = Double(n) } }
        .onChange(of: s.range) { _, _ in shown = 0; withAnimation(Motion.reduce ? nil : .easeOut(duration: 0.75)) { shown = Double(s.minutes) } }
    }

    private func title(_ r: String) -> String { ["week": "This week", "month": "Last 30 days", "year": "Last 12 months"][r] ?? "All time" }
    private func prev(_ r: String) -> String { ["week": "vs last week", "month": "vs 30 days before", "year": "vs the year before"][r] ?? "" }
    private func icon(_ mood: String) -> String { ["night": "moon.fill", "morning": "sunrise.fill", "afternoon": "sun.max.fill"][mood] ?? "clock" }
}

/// Bars with an average line; hover scrubs (the hero follows the pointer), click opens that day's plays.
private struct BarChart: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject var m = StatsModel.shared
    let s: StatsPage
    @State private var grow = Motion.reduce ? 1.0 : 0.0

    var body: some View {
        let p = theme.p
        let bars = s.bars
        let n = max(1, bars.count)
        let mx = max(1.0, Double(bars.map(\.minutes).max() ?? 1), m.ghost ? Double(s.prevBars.max() ?? 0) : 0, Double(s.avgMinutes))
        VStack(spacing: 8) {
            GeometryReader { geo in
                let w = geo.size.width, h = geo.size.height
                let slot = w / CGFloat(n)
                let bw = min(slot * 0.56, 34)
                ZStack(alignment: .topLeading) {
                    if s.avgMinutes > 0 {
                        let y = h - h * CGFloat(Double(s.avgMinutes) / mx)
                        Path { pp in pp.move(to: CGPoint(x: 0, y: y)); pp.addLine(to: CGPoint(x: w, y: y)) }
                            .stroke(p.t3.opacity(0.55), style: StrokeStyle(lineWidth: 1, dash: [6, 5]))
                        Text("avg \(hm(Int(s.avgMinutes)))").font(theme.num(11)).foregroundStyle(p.t3).offset(y: max(0, y - 18))
                    }
                    ForEach(Array(bars.enumerated()), id: \.offset) { i, b in
                        let cx = slot * CGFloat(i) + slot / 2
                        let v = Double(b.minutes)
                        let on = m.hover >= 0 ? i == m.hover : b.isCurrent
                        if m.ghost, i < s.prevBars.count, s.prevBars[i] > 0 {
                            let gh = max(6, h * CGFloat(Double(s.prevBars[i]) / mx))
                            RoundedRectangle(cornerRadius: bw / 2.6).stroke(p.t2.opacity(0.55), style: StrokeStyle(lineWidth: 1.2, dash: [4, 3]))
                                .frame(width: bw, height: gh).position(x: cx, y: h - gh / 2)
                        }
                        if v <= 0 {
                            Circle().fill(p.t4).frame(width: 5, height: 5).position(x: cx, y: h - 4)
                        } else {
                            let bh = max(6, h * CGFloat(v / mx) * grow)
                            RoundedRectangle(cornerRadius: bw / 2.6)
                                .fill(on ? theme.accent : Theme.mix(p.surf4, p.t3, 0.45))
                                .frame(width: bw, height: bh).position(x: cx, y: h - bh / 2)
                        }
                    }
                }
                .contentShape(Rectangle())
                .onContinuousHover { phase in
                    switch phase {
                    case .active(let pt):
                        let i = min(n - 1, max(0, Int(pt.x / slot)))
                        if i != m.hover { m.hover = i; if bars.indices.contains(i) { Haptics.tap() } }
                    case .ended: m.hover = -1
                    }
                }
                .onTapGesture { loc in
                    let i = min(n - 1, max(0, Int(loc.x / slot)))
                    guard bars.indices.contains(i), bars[i].minutes > 0 || bars[i].plays > 0 else { return }
                    m.day = StatsModel.DayPick(title: bars[i].title, from: bars[i].start, to: bars[i].end)
                }
            }
            HStack(spacing: 0) {
                let every = bars.count <= 12 ? 1 : bars.count <= 31 ? 5 : (bars.count + 5) / 6
                ForEach(Array(bars.enumerated()), id: \.offset) { i, b in
                    let on = m.hover >= 0 ? i == m.hover : b.isCurrent
                    Text((bars.count - 1 - i) % every == 0 || on ? b.label : " ")
                        .font(theme.num(11.5, on ? .medium : .regular)).foregroundStyle(on ? p.txt : p.t3)
                        .lineLimit(1).fixedSize().frame(maxWidth: .infinity)
                }
            }
        }
        .onAppear { withAnimation(Motion.reduce ? nil : .spring(response: 0.7, dampingFraction: 0.8)) { grow = 1 } }
        .onChange(of: s.range) { _, _ in
            guard !Motion.reduce else { return }
            grow = 0
            withAnimation(.spring(response: 0.7, dampingFraction: 0.8)) { grow = 1 }
        }
    }
}

private struct ClockCard: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject var m = StatsModel.shared
    let s: StatsPage

    var body: some View {
        let p = theme.p
        let peak = Int(s.peakHour)
        VStack(alignment: .leading, spacing: 4) {
            Text("Listening clock").font(theme.ui(13, .semibold)).foregroundStyle(p.t2)
            Text(m.hour >= 0 ? (m.hourDetail?.title ?? "Around \(hourName(m.hour))") : peak >= 0 ? "Mostly around \(hourName(peak))" : "Nothing yet")
                .font(theme.ui(18, .bold)).foregroundStyle(p.txt)
            if m.hour >= 0 { Text("\(m.hourDetail?.minutes ?? 0) min").font(theme.num(12)).foregroundStyle(p.t2) }
            Clock(clock: s.clock.map { Double($0) }, peak: peak, selected: m.hour, accent: theme.accent) { h in m.pickHour(h) }
                .frame(width: 210, height: 210).frame(maxWidth: .infinity).padding(.top, 8)
            if m.hour >= 0, let d = m.hourDetail {
                if d.tracks.isEmpty {
                    Text("Nothing played around \(hourName(m.hour)).").font(theme.ui(12.5, .medium)).foregroundStyle(p.t3)
                } else {
                    ForEach(Array(d.tracks.prefix(5).enumerated()), id: \.offset) { i, t in
                        Button { Player.shared.play(d.tracks.map(\.track), index: i, source: d.title) } label: {
                            HStack(spacing: 10) {
                                ArtView(url: t.track.thumb, size: 34, radius: 6)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(t.track.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                                    Text(t.track.artists).font(theme.ui(11.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                                }
                                Spacer()
                                Text("\(t.minutes) min").font(theme.num(11.5)).foregroundStyle(p.t3)
                            }.contentShape(Rectangle())
                        }.buttonStyle(PressStyle(scale: 0.98))
                    }
                    SecondaryButton(title: "Play these", icon: "play.fill") { Player.shared.play(d.tracks.map(\.track), index: 0, source: d.title) }.padding(.top, 6)
                }
            }
        }
        .padding(20).glassCard(24)
        .animation(Motion.spring, value: m.hour)
    }
}

/// 24 radial wedges; click or drag picks an hour (the centre picks the busiest one).
struct Clock: View {
    @EnvironmentObject var theme: Theme
    let clock: [Double]
    let peak: Int
    let selected: Int
    let accent: Color
    let pick: (Int) -> Void
    @State private var dragging = -1

    var body: some View {
        let p = theme.p
        GeometryReader { geo in
            let sz = min(geo.size.width, geo.size.height)
            let c = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
            let r0 = sz * 0.20, r1 = sz * 0.43
            let mx = max(1, clock.max() ?? 1)
            ZStack {
                Canvas { ctx, _ in
                    for h in 0..<24 {
                        let a = (Double(h) * 15 - 90 + 7.5) * .pi / 180
                        let act = min(1, max(0.04, (h < clock.count ? clock[h] : 0) / mx))
                        let rr = r0 + (r1 - r0) * act
                        var track = Path(); track.move(to: CGPoint(x: c.x + r0 * cos(a), y: c.y + r0 * sin(a))); track.addLine(to: CGPoint(x: c.x + r1 * cos(a), y: c.y + r1 * sin(a)))
                        ctx.stroke(track, with: .color(p.surf3), style: StrokeStyle(lineWidth: sz * 0.04, lineCap: .round))
                        var v = Path(); v.move(to: CGPoint(x: c.x + r0 * cos(a), y: c.y + r0 * sin(a))); v.addLine(to: CGPoint(x: c.x + rr * cos(a), y: c.y + rr * sin(a)))
                        let on = h == selected || (selected < 0 && h == peak)
                        ctx.stroke(v, with: .color(on ? accent : Theme.mix(p.surf4, p.t2, act)), style: StrokeStyle(lineWidth: sz * (h == selected ? 0.06 : 0.04), lineCap: .round))
                    }
                }
                ForEach([(0, "12A"), (6, "6A"), (12, "12P"), (18, "6P")], id: \.0) { h, l in
                    let a = (Double(h) * 15 - 90) * .pi / 180
                    let rr = r1 + sz * 0.06
                    Text(l).font(theme.num(10)).foregroundStyle(p.t3).position(x: c.x + rr * cos(a), y: c.y + rr * sin(a))
                }
                Text(selected >= 0 ? hourName(selected) : peak >= 0 ? hourName(peak) : "–").font(theme.num(13, .medium)).foregroundStyle(p.txt).position(c)
            }
            .contentShape(Circle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                let h = hourAt(g.location, c, sz)
                if g.translation == .zero { return }
                if h >= 0 && h != dragging { dragging = h; Haptics.tap(); if h != selected { pick(h) } }
            }.onEnded { g in
                if dragging < 0 { Haptics.tap(); pick(hourAt(g.location, c, sz)) }
                dragging = -1
            })
        }
    }

    private func hourAt(_ pt: CGPoint, _ c: CGPoint, _ sz: CGFloat) -> Int {
        if hypot(pt.x - c.x, pt.y - c.y) < sz * 0.18 { return peak }
        var deg = atan2(pt.y - c.y, pt.x - c.x) * 180 / .pi + 90
        if deg < 0 { deg += 360 }
        return Int(deg / 15) % 24
    }
}

private struct ArtCard: View {
    @EnvironmentObject var theme: Theme
    let art: String, chip: String, title: String, meta: String
    let open: () -> Void, play: () -> Void
    var body: some View {
        let p = theme.p
        Button(action: open) {
            VStack(alignment: .leading, spacing: 4) {
                ZStack(alignment: .topLeading) {
                    ArtView(url: art, size: 176, radius: 16)
                    Text(chip).font(theme.ui(11.5, .semibold)).foregroundStyle(p.txt).padding(.horizontal, 10).frame(height: 24).glassCapsule().padding(8)
                    Button(action: play) {
                        Image(systemName: "play.fill").font(.system(size: 14, weight: .bold)).foregroundStyle(p.txt).frame(width: 38, height: 38).glassSurface(Circle(), interactive: true)
                    }.buttonStyle(PressStyle()).frame(width: 176, height: 176, alignment: .bottomTrailing).offset(x: -8, y: -8)
                }
                Text(title).font(theme.ui(15, .bold)).foregroundStyle(p.txt).lineLimit(1).padding(.top, 8)
                Text(meta).font(theme.ui(12, .medium)).foregroundStyle(p.t2).lineLimit(1)
            }
            .frame(width: 176).padding(12).glassCard(24)
        }.buttonStyle(PressStyle(scale: 0.98))
    }
}

private struct GoalCard: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject var m = StatsModel.shared
    let s: StatsPage
    var body: some View {
        let p = theme.p
        let goal = max(1, Int(s.goalMinutes))
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Daily goal").font(theme.ui(13, .semibold)).foregroundStyle(p.t2)
                Spacer()
                Menu {
                    ForEach([15, 30, 60], id: \.self) { g in Button("\(g) min\(g == goal ? "  ✓" : "")") { m.setGoal(g) } }
                } label: {
                    HStack(spacing: 5) { Text("\(goal) min").font(theme.ui(12.5, .semibold)); Image(systemName: "pencil").font(.system(size: 11, weight: .semibold)) }
                        .foregroundStyle(p.txt)
                }.menuStyle(.borderlessButton).fixedSize().padding(.horizontal, 12).frame(height: 30).glassCapsule().accessibilityIdentifier("goal_edit")
            }
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                RollIn(target: Double(s.goalStreak), size: 40)
                Text("day streak").font(theme.ui(13, .semibold)).foregroundStyle(p.t3)
                Spacer()
                Text("best \(s.goalBest)").font(theme.num(11.5)).foregroundStyle(p.t3)
            }
            HStack {
                ForEach(Array(s.goalWeek.enumerated()), id: \.offset) { _, d in
                    let f = min(1, Double(d.minutes) / Double(goal))
                    VStack(spacing: 5) {
                        ZStack {
                            Circle().stroke(p.surf3, lineWidth: 3.6)
                            Circle().trim(from: 0, to: f).stroke(d.isToday ? theme.accent : p.t2, style: StrokeStyle(lineWidth: 3.6, lineCap: .round)).rotationEffect(.degrees(-90))
                        }.frame(width: 30, height: 30)
                        Text(d.label).font(theme.num(11, d.isToday ? .medium : .regular)).foregroundStyle(d.isToday ? p.txt : p.t3)
                    }.frame(maxWidth: .infinity)
                }
            }.padding(.top, 4)
            let left = max(0, goal - Int(s.todayMinutes))
            Text(left == 0 ? "Today's goal done" : "\(left) min to go today").font(theme.ui(12, .medium)).foregroundStyle(p.t3)
        }
        .padding(20).glassCard(24)
        .animation(Motion.spring, value: s.goalMinutes)
    }
}

private struct Head: View {
    @EnvironmentObject var theme: Theme
    let title: String
    var sub: String? = nil
    var action: (String, String, () -> Void)? = nil
    var body: some View {
        let p = theme.p
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(theme.ui(22, .bold)).foregroundStyle(p.txt)
                if let s = sub { Text(s).font(theme.ui(12.5, .medium)).foregroundStyle(p.t2) }
            }
            Spacer()
            if let a = action { SecondaryButton(title: a.0, icon: a.1.isEmpty ? nil : a.1, action: a.2) }
        }.padding(.top, 28).padding(.bottom, 10)
    }
}

private struct TopSongs: View {
    @EnvironmentObject var theme: Theme
    let s: StatsPage
    @State private var all = false
    var body: some View {
        let p = theme.p
        let list = s.topTracks
        let mx = max(1, Int(list.map(\.plays).max() ?? 1))
        Head(title: "Top songs", action: ("Play top \(min(25, list.count))", "play.fill", { Player.shared.play(list.map(\.track), index: 0, source: "Top songs") }))
        ForEach(Array(list.prefix(all ? 25 : 8).enumerated()), id: \.offset) { i, t in
            Button { Player.shared.play(list.map(\.track), index: i, source: "Top songs") } label: {
                HStack(spacing: 12) {
                    Text("\(i + 1)").font(theme.num(12)).foregroundStyle(p.t3).frame(width: 22)
                    ArtView(url: t.track.thumb, size: 42, radius: 7)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.track.title).font(theme.ui(14, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                        Text(t.track.artists).font(theme.ui(12, .medium)).foregroundStyle(p.t2).lineLimit(1)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 5) {
                        Text("\(t.plays)×").font(theme.num(12)).foregroundStyle(p.t2)
                        GeometryReader { g in
                            Capsule().fill(p.surf3).overlay(alignment: .leading) {
                                Capsule().fill(i == 0 ? theme.accent : p.t2).frame(width: g.size.width * CGFloat(t.plays) / CGFloat(mx))
                            }
                        }.frame(width: 56, height: 3)
                    }
                }.padding(.vertical, 5).contentShape(Rectangle())
            }.buttonStyle(PressStyle(scale: 0.99)).enter(i)
        }
        if list.count > 8 {
            Button(all ? "Show less" : "Show all \(list.count)") { withAnimation(Motion.spring) { all.toggle() } }
                .buttonStyle(.plain).font(theme.ui(12.5, .semibold)).foregroundStyle(p.t2).padding(.top, 6)
        }
    }
}

private struct TopArtists: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let s: StatsPage
    var body: some View {
        let p = theme.p
        Head(title: "Top artists", action: ("Play mix", "", {
            let r = s.range
            Task { if let mix = await Engine.off({ try Engine.core.statsMix(range: r, artist: nil, tzOffsetMin: Engine.tzMinutes) }), !mix.isEmpty { Player.shared.play(mix, index: 0, source: "Your top artists") } }
        }))
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 14)], alignment: .leading, spacing: 14) {
            ForEach(Array(s.topArtists.prefix(8).enumerated()), id: \.offset) { i, a in
                Button { if !a.id.isEmpty { nav.push(.artist(a.id)) } } label: {
                    VStack(spacing: 5) {
                        ArtView(url: a.thumb, size: 78, circle: true).padding(3)
                            .overlay(Circle().stroke(i == 0 ? theme.accent : .clear, lineWidth: 2))
                        Text(a.name).font(theme.ui(12.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                        Text(hm(Int(a.minutes))).font(theme.num(11)).foregroundStyle(p.t3)
                    }.frame(width: 96)
                }.buttonStyle(PressStyle()).enter(i)
            }
        }
    }
}

private struct TopAlbums: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let s: StatsPage
    var body: some View {
        let p = theme.p
        Head(title: "Top albums")
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(Array(s.topAlbums.enumerated()), id: \.offset) { i, a in
                    Button { open(a) } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            ArtView(url: a.thumb, size: 128, radius: 12)
                            Text(a.name).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                            Text("\(a.artist) · \(playsLabel(Int(a.plays)))").font(theme.ui(11.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                        }.frame(width: 128)
                    }.buttonStyle(PressStyle()).enter(i)
                }
            }
        }
    }
    private func open(_ a: AlbumStat) {
        if !a.albumId.isEmpty { nav.seeds[a.albumId] = RouteSeed(title: a.name, subtitle: a.artist, thumb: a.thumb); nav.push(.album(a.albumId)); return }
        nav.show("Opening \(a.name)")
        Task {
            let id = try? await Engine.core.statsAlbumId(name: a.name, artist: a.artist)
            if let id, !id.isEmpty {
                nav.seeds[id] = RouteSeed(title: a.name, subtitle: a.artist, thumb: a.thumb)
                nav.push(.album(id))
            } else { nav.show("Couldn't find \(a.name)") }
        }
    }
}

private struct NewToYou: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    let s: StatsPage
    var body: some View {
        let p = theme.p
        let n = s.newArtists.count
        Head(title: "New to you", sub: "\(n) \(n == 1 ? "artist" : "artists") you first played \(["week": "this week", "month": "this month", "year": "this year"][s.range] ?? "this month")")
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 80), spacing: 12)], alignment: .leading, spacing: 12) {
            ForEach(Array(s.newArtists.enumerated()), id: \.offset) { _, a in
                Button { if !a.id.isEmpty { nav.push(.artist(a.id)) } } label: {
                    VStack(spacing: 4) {
                        ArtView(url: a.thumb, size: 62, circle: true)
                        Text(a.name).font(theme.ui(12, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                    }.frame(width: 80)
                }.buttonStyle(PressStyle())
            }
        }
    }
}

private struct SkippedOften: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @ObservedObject var m = StatsModel.shared
    let s: StatsPage
    var body: some View {
        let p = theme.p
        let rows = s.skipped.filter { !m.hidden.contains($0.track.id) }
        let names = Dictionary(grouping: s.skipped.flatMap(\.playlists), by: \.title).max { $0.value.count < $1.value.count }?.key
        if !rows.isEmpty {
            Head(title: "Skipped often", sub: names.map { "You skip these in \($0)" } ?? "Songs you usually skip")
            ForEach(Array(rows.enumerated()), id: \.element.track.id) { i, k in
                HStack(spacing: 12) {
                    Text("\(i + 1)").font(theme.num(12)).foregroundStyle(p.t3).frame(width: 22)
                    ArtView(url: k.track.thumb, size: 40, radius: 7)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(k.track.title).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                        Text("skipped \(k.skips) of \(k.plays)").font(theme.num(11.5)).foregroundStyle(p.t3)
                    }
                    Spacer()
                    if !k.playlists.isEmpty {
                        SecondaryButton(title: "Remove") { remove(k) }
                    }
                }.padding(.vertical, 5)
            }
        }
    }
    private func remove(_ k: SkippedTrack) {
        let names = Set(k.playlists.map(\.title))
        let label = names.count == 1 ? names.first! : "\(names.count) playlists"
        let id = k.track.id
        nav.undoable("Removed from \(label)", apply: { withAnimation(Motion.spring) { _ = m.hidden.insert(id) } },
                     undo: { withAnimation(Motion.spring) { _ = m.hidden.remove(id) } },
                     commit: {
                         let slots = k.playlists
                         Engine.bg {
                             for (pid, ss) in Dictionary(grouping: slots, by: \.playlistId) {
                                 for sl in ss.sorted(by: { $0.position > $1.position }) { try Engine.core.removeFromPlaylist(playlistId: pid, position: sl.position) }
                             }
                         }
                         Task { @MainActor in try? await Task.sleep(nanoseconds: 300_000_000); m.invalidate() }
                     })
    }
}

private struct DayPlaysSheet: View {
    @EnvironmentObject var theme: Theme
    @Environment(\.dismiss) var dismiss
    let pick: StatsModel.DayPick
    @State private var plays: [PlayEntry]?
    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(pick.title).font(theme.ui(18, .bold)).foregroundStyle(p.txt)
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            if let ps = plays {
                let min = ps.reduce(0) { $0 + Int($1.listenedS) } / 60
                Text("\(playsLabel(ps.count)) · \(hm(min))").font(theme.num(12)).foregroundStyle(p.t2)
                let tracks = ps.reduce(into: [Track]()) { acc, e in if !acc.contains(where: { $0.id == e.track.id }) { acc.append(e.track) } }
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(ps.enumerated()), id: \.offset) { _, e in
                            Button {
                                Player.shared.play(tracks, index: tracks.firstIndex { $0.id == e.track.id } ?? 0, source: pick.title); dismiss()
                            } label: {
                                HStack(spacing: 12) {
                                    Text(e.time).font(theme.num(11.5)).foregroundStyle(p.t3).frame(width: 70, alignment: .leading)
                                    ArtView(url: e.track.thumb, size: 36, radius: 6)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(e.track.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                                        Text(e.track.artists).font(theme.ui(11.5, .medium)).foregroundStyle(p.t2).lineLimit(1)
                                    }
                                    Spacer()
                                    if e.skipped { Text("skipped").font(theme.ui(11, .medium)).foregroundStyle(p.t3) }
                                }.padding(.vertical, 5).contentShape(Rectangle())
                            }.buttonStyle(PressStyle(scale: 0.99))
                        }
                    }
                }
            } else { ProgressView().controlSize(.small) }
        }
        .padding(22).frame(width: 460, height: 520).background(p.bg)
        .task {
            let f = pick.from, t = pick.to
            plays = await Engine.off { try Engine.core.statsPlaysBetween(from: f, to: t) } ?? []
        }
    }
}

// MARK: Recap

/// Same title (trimmed, case-insensitive) by the same main artist = one row. The core already merges; this
/// keeps the card right for any list.
func recapMergeSameSongs(_ list: [TrackStat]) -> [TrackStat] {
    var order: [String] = []
    var m: [String: TrackStat] = [:]
    for t in list {
        let title = t.track.title.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").lowercased()
        let artist = (t.track.artists.split(whereSeparator: { $0 == "," || $0 == "&" }).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? "").lowercased()
        let k = title.isEmpty ? "id:" + t.track.id : title + "\u{1f}" + artist
        if var p = m[k] { p.plays += t.plays; p.minutes += t.minutes; m[k] = p } else { m[k] = t; order.append(k) }
    }
    return order.compactMap { m[$0] }.enumerated().sorted { a, b in
        if a.element.plays != b.element.plays { return a.element.plays > b.element.plays }
        if a.element.minutes != b.element.minutes { return a.element.minutes > b.element.minutes }
        return a.offset < b.offset
    }.map { $0.element }
}

/// The Pulse mark (app icon glyph): a disc with three rounded bars cut out. Fill with eoFill.
struct PulseMark: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24
        var p = Path()
        p.addEllipse(in: CGRect(x: rect.minX + 2 * s, y: rect.minY + 2 * s, width: 20 * s, height: 20 * s))
        for (x0, y0, y1) in [(7.4, 8.6, 15.4), (11.0, 6.4, 17.6), (14.6, 7.8, 16.2)] as [(CGFloat, CGFloat, CGFloat)] {
            p.addRoundedRect(in: CGRect(x: rect.minX + x0 * s, y: rect.minY + y0 * s, width: 2 * s, height: (y1 - y0) * s), cornerSize: CGSize(width: s, height: s))
        }
        return p
    }
}

private let recapBase = Color(.sRGB, red: 9 / 255, green: 9 / 255, blue: 10 / 255, opacity: 1)

/// Dark base, a soft low-saturation glow of the top artist's art, vignette and fine grain. No hard edges.
private struct RecapBackdrop: View {
    let art: NSImage?
    var body: some View {
        ZStack {
            recapBase
            if let a = art {
                Image(nsImage: a).resizable().aspectRatio(contentMode: .fill).frame(width: 560, height: 560).clipped()
                    .saturation(0.25).blur(radius: 60, opaque: false).opacity(0.7)
                    .mask(RadialGradient(stops: [.init(color: .black, location: 0), .init(color: .black.opacity(0.6), location: 0.38), .init(color: .black.opacity(0.15), location: 0.72), .init(color: .clear, location: 1)], center: .center, startRadius: 0, endRadius: 280))
                    .position(x: 252, y: 128)
            } else {
                RadialGradient(colors: [.white.opacity(0.12), .clear], center: UnitPoint(x: 0.7, y: 0.2), startRadius: 0, endRadius: 340)
            }
            LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: recapBase.opacity(0.6), location: 1)], startPoint: .top, endPoint: .bottom)
            RadialGradient(stops: [.init(color: .clear, location: 0), .init(color: .clear, location: 0.55), .init(color: .black.opacity(0.6), location: 1)], center: UnitPoint(x: 0.5, y: 0.42), startRadius: 0, endRadius: 500)
            Canvas { ctx, size in
                var seed: UInt64 = 29
                func next() -> Double { seed = seed &* 6364136223846793005 &+ 1442695040888963407; return Double(seed >> 33) / Double(1 << 31) }
                for _ in 0..<9000 {
                    let x = next() * size.width, y = next() * size.height, v = next()
                    ctx.fill(Path(CGRect(x: x, y: y, width: 0.34, height: 0.34)), with: .color(Color(white: v).opacity(0.10)))
                }
            }
        }
        .frame(width: RecapCard.W, height: RecapCard.H).clipped()
    }
}

/// The 9:16 story card (also rendered to PNG for Save / Copy). 360x640 pt, rendered @3x. 32 pt (= 96 px)
/// gutters; blocks stack from measured text; the footer sits on the bottom margin and the song list keeps
/// only the rows that fit above it.
struct RecapCard: View {
    let r: Recap
    let art: NSImage?
    let accent: Color
    static let W: CGFloat = 360, H: CGFloat = 640, G: CGFloat = 32

    private func ui(_ s: CGFloat, _ w: Font.Weight) -> Font { .custom("Manrope", size: s).weight(w) }
    private func num(_ s: CGFloat, _ w: Font.Weight = .regular) -> Font { .custom("JetBrains Mono", size: s).weight(w) }
    static func lineH(_ family: String, _ size: CGFloat, _ w: NSFont.Weight) -> CGFloat {
        let d = NSFontDescriptor(fontAttributes: [.family: family]).addingAttributes([.traits: [NSFontDescriptor.TraitKey.weight: w.rawValue]])
        let f = NSFont(descriptor: d, size: size) ?? NSFont.systemFont(ofSize: size, weight: w)
        return ceil(f.ascender - f.descender + f.leading)
    }

    /// "96 plays · +12% vs last week": numerals in JetBrains Mono, words in Manrope, one baseline.
    private func playsLine(white: Color, dim: Color, faint: Color) -> Text {
        var t = Text("\(r.plays)").font(num(13.3)).foregroundColor(accent)
        t = t + Text(r.plays == 1 ? " play" : " plays").font(ui(12.7, .medium)).foregroundColor(dim)
        if let c = r.changePercent {
            t = t + Text("   ·   ").font(ui(12.7, .medium)).foregroundColor(faint)
            t = t + Text("\(c >= 0 ? "+" : "−")\(abs(c))%").font(num(13.3)).foregroundColor(dim)
            t = t + Text(" vs last \(r.range == "month" ? "month" : "week")").font(ui(12.7, .medium)).foregroundColor(dim)
        }
        return t
    }

    var body: some View {
        let white = Color(white: 0.95), dim = Color.white.opacity(0.63), faint = Color.white.opacity(0.4)
        let songs = Array(recapMergeSameSongs(r.topTracks).prefix(5))
        let foot = [r.moodLine.isEmpty ? nil : r.moodLine, r.goalStreak > 0 ? "\(r.goalStreak)-day goal streak" : nil].compactMap { $0 }.joined(separator: "   ·   ")
        let m = Int(r.minutes)
        let parts: [(String, String)] = m >= 60 ? [("\(m / 60)", "h"), (String(format: "%02d", m % 60), "m")] : [("\(m)", "m")]
        // the big numerals shrink only when a long total would not fit the 296 pt between the gutters
        let digitCount = CGFloat(parts.reduce(0) { $0 + $1.0.count })
        let digitSize = min(83, (Self.W - 2 * Self.G - 64) / max(1, digitCount * 0.6))
        let labH = Self.lineH("Manrope", 10, .semibold) + 14
        let titleH = Self.lineH("Manrope", 16.7, .bold), artistH = Self.lineH("Manrope", 11, .medium)
        let rowH = titleH + 2 + artistH, rowGap: CGFloat = 13
        ZStack(alignment: .topLeading) {
            RecapBackdrop(art: art)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    PulseMark().fill(white, style: FillStyle(eoFill: true)).frame(width: 17, height: 17)
                    Text("iTune").font(ui(12.7, .bold)).foregroundStyle(white)
                    Spacer(minLength: 12)
                    Text(r.periodLabel).font(ui(10.7, .medium)).foregroundStyle(dim).lineLimit(1)
                }
                Text(r.title).font(ui(37, .bold)).tracking(-0.9).foregroundStyle(white).lineLimit(1).minimumScaleFactor(0.6).padding(.top, 30)
                HStack(alignment: .lastTextBaseline, spacing: 0) {
                    ForEach(Array(parts.enumerated()), id: \.offset) { i, pr in
                        Text(pr.0).font(num(digitSize, .light)).tracking(-3).foregroundStyle(white)
                        Text(pr.1).font(ui(digitSize * 0.3, .semibold)).foregroundStyle(dim).padding(.leading, 5).padding(.trailing, i < parts.count - 1 ? 15 : 0)
                    }
                }.lineLimit(1).minimumScaleFactor(0.5).padding(.top, 10)
                playsLine(white: white, dim: dim, faint: faint).lineLimit(1).padding(.top, 6)
                if let a = r.topArtist {
                    HStack(spacing: 17) {
                        ZStack {
                            if let i = art { Image(nsImage: i).resizable().aspectRatio(contentMode: .fill) }
                            else {
                                Color.white.opacity(0.085)
                                Text(String(a.name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased()).font(ui(28, .bold)).foregroundStyle(dim)
                            }
                        }
                        .frame(width: 69, height: 69).clipShape(Circle())
                        .overlay(Circle().stroke(accent.opacity(0.8), lineWidth: 1).padding(-3))
                        VStack(alignment: .leading, spacing: 5) {
                            Text("TOP ARTIST").font(ui(10, .semibold)).tracking(0.8).foregroundStyle(dim)
                            Text(a.name).font(ui(22, .bold)).tracking(-0.3).foregroundStyle(white).lineLimit(1).truncationMode(.tail)
                            Text(hm(Int(a.minutes))).font(num(11.3)).foregroundStyle(dim)
                        }
                    }.padding(.top, 38)
                }
                GeometryReader { g in
                    let n = songs.isEmpty ? 0 : max(0, min(songs.count, Int((g.size.height - labH + rowGap) / (rowH + rowGap))))
                    if n > 0 {
                        VStack(alignment: .leading, spacing: 0) {
                            Text("TOP SONGS").font(ui(10, .semibold)).tracking(0.8).foregroundStyle(dim).frame(height: labH - 14, alignment: .leading).padding(.bottom, 14)
                            VStack(alignment: .leading, spacing: rowGap) {
                                ForEach(Array(songs.prefix(n).enumerated()), id: \.offset) { i, t in
                                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                                        Text("\(i + 1)").font(num(12.7)).foregroundStyle(i == 0 ? accent : faint).frame(width: 25, alignment: .leading)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(t.track.title.trimmingCharacters(in: .whitespaces)).font(ui(16.7, .bold)).tracking(-0.15).foregroundStyle(white).lineLimit(1).truncationMode(.tail)
                                            Text(t.track.artists).font(ui(11, .medium)).foregroundStyle(dim).lineLimit(1).truncationMode(.tail)
                                        }
                                        Spacer(minLength: 12)
                                        Text("\(t.plays)×").font(num(12)).foregroundStyle(dim)
                                    }.frame(height: rowH, alignment: .top)
                                }
                            }
                        }
                    }
                }.padding(.top, 40)
                if !foot.isEmpty {
                    Text(foot).font(ui(10.7, .medium)).foregroundStyle(dim).lineLimit(1).truncationMode(.tail).padding(.top, 24)
                }
            }
            .padding(.horizontal, Self.G).padding(.top, 40).padding(.bottom, 40)
            .frame(width: Self.W, height: Self.H, alignment: .topLeading)
        }
        .frame(width: Self.W, height: Self.H)
    }
}

@MainActor
enum RecapRender {
    /// 1080x1920 PNG of the card.
    /// The card is always dark: the palette's own accent (Pure Mono / Adaptive: #E8E8E8), never the live album tint.
    static func cardAccent(_ theme: Theme) -> Color { theme.p.light ? Palette.hex("E8E8E8") : theme.p.acc }

    static func image(_ r: Recap, art: NSImage?, accent: Color) -> NSImage? {
        let rr = ImageRenderer(content: RecapCard(r: r, art: art, accent: accent))
        rr.scale = 3
        // a CGImage keeps the real pixel size (rr.nsImage reports points: 360x640)
        guard let cg = rr.cgImage else { return nil }
        let img = NSImage(size: NSSize(width: cg.width, height: cg.height))
        img.addRepresentation(NSBitmapImageRep(cgImage: cg))
        return img
    }
    static func png(_ img: NSImage) -> Data? {
        if let rep = img.representations.compactMap({ $0 as? NSBitmapImageRep }).first { return rep.representation(using: .png, properties: [:]) }
        guard let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }
}

private struct RecapSheet: View {
    @EnvironmentObject var theme: Theme
    @Environment(\.dismiss) var dismiss
    let startRange: String
    @State private var range = "week"
    @State private var recap: Recap?
    @State private var img: NSImage?

    var body: some View {
        let p = theme.p
        VStack(spacing: 14) {
            HStack {
                Text("Recap").font(theme.ui(18, .bold)).foregroundStyle(p.txt)
                Spacer()
                RangeSwitch(ranges: [("week", "Week"), ("month", "Month")], selected: range) { range = $0 }.frame(width: 180)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 18).fill(p.surf3)
                if let i = img { Image(nsImage: i).resizable().aspectRatio(contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 18)).transition(.opacity) }
                else { ProgressView().controlSize(.small) }
            }.frame(width: 288, height: 512)
            HStack(spacing: 10) {
                SecondaryButton(title: "Save…", icon: "square.and.arrow.down") { save() }.accessibilityIdentifier("recap_save")
                SecondaryButton(title: "Copy", icon: "doc.on.doc") { copy() }.accessibilityIdentifier("recap_copy")
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(22).frame(width: 420).background(p.bg)
        .onAppear { range = startRange }
        .task(id: range) { await render() }
    }

    private func render() async {
        let r = range
        guard let rc = await Engine.off({ try Engine.core.statsRecap(range: r, tzOffsetMin: Engine.tzMinutes) }) else { return }
        let url = rc.topArtist?.thumb.isEmpty == false ? rc.topArtist!.thumb : (rc.topTrack?.track.thumb ?? "")
        let art = url.isEmpty ? nil : await ArtCache.shared.image(url, points: 360)
        recap = rc
        withAnimation(.easeOut(duration: 0.2)) { img = RecapRender.image(rc, art: art, accent: RecapRender.cardAccent(theme)) }
    }

    private func save() {
        guard let i = img, let d = RecapRender.png(i) else { return }
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.png]
        panel.nameFieldStringValue = "Recap \(range == "month" ? "month" : "week").png"
        panel.begin { resp in
            if resp == .OK, let u = panel.url { try? d.write(to: u); Nav.shared.show("Recap saved") }
        }
    }

    private func copy() {
        guard let i = img else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.writeObjects([i])
        Nav.shared.show("Recap copied")
    }
}

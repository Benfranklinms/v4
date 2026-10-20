import SwiftUI

/// In-list search for playlist / album / Liked Songs / Downloads pages: case- and accent-insensitive match on
/// title, artist and album; every word of the query must appear. Runs off the main actor.
enum ListFilter {
    static func norm(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil).lowercased()
    }
    static func key(_ t: Track) -> String { norm(t.title) + "\u{1}" + norm(t.artists) + "\u{1}" + norm(t.album ?? "") }
    /// Indices matching `query`; nil when the query is blank (no filtering).
    static func match(_ keys: [String], _ query: String) -> [Int]? {
        let words = norm(query).split(whereSeparator: { $0.isWhitespace }).map(String.init)
        if words.isEmpty { return nil }
        return keys.indices.filter { i in words.allSatisfy { keys[i].contains($0) } }
    }
    static func isBlank(_ q: String) -> Bool { q.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    static func run(_ tracks: [Track], _ query: String) async -> [Int] {
        await Task.detached(priority: .userInitiated) { match(tracks.map(key), query) ?? [] }.value
    }
}

/// A filtered view of one track list: indices into the exact list that was searched (a tap plays that list).
struct ListHits { let list: [Track]; let idx: [Int] }

/// Small capsule field scoped to the page (same look as the main search pill). Esc clears, then unfocuses.
struct ListSearchField: View {
    @EnvironmentObject var theme: Theme
    @Binding var text: String
    @FocusState private var focused: Bool
    @State private var hover = false

    var body: some View {
        let p = theme.p
        let on = focused
        let fill: Color = p.light ? Color.black.opacity(on || hover ? 0.08 : 0.05) : Color.white.opacity(on || hover ? 0.11 : 0.075)
        let stroke: Color = on ? p.txt.opacity(0.92) : Color.white.opacity(hover ? 0.14 : 0.06)
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").font(.system(size: 12, weight: .medium)).foregroundStyle(on ? p.txt : p.t2)
            TextField("", text: $text, prompt: Text("Search").foregroundColor(p.t2))
                .textFieldStyle(.plain).font(theme.ui(13, .medium)).foregroundStyle(p.txt)
                .focused($focused).focusEffectDisabled()
                .onExitCommand { if text.isEmpty { focused = false } else { text = "" } }
                .accessibilityLabel("Search in list").accessibilityIdentifier("list_search")
            if !text.isEmpty {
                Button { Haptics.tap(); text = ""; focused = true } label: {
                    Image(systemName: "xmark").font(.system(size: 11, weight: .semibold)).foregroundStyle(p.t2)
                        .frame(width: 22, height: 22).contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(scale: 0.9)).help("Clear").accessibilityLabel("Clear search").accessibilityIdentifier("list_search_clear")
                .transition(.opacity.combined(with: .scale(scale: 0.6)))
            }
        }
        .padding(.leading, 12).padding(.trailing, 4)
        .frame(width: 220, height: 32)
        .background(Capsule().fill(fill))
        .overlay(Capsule().stroke(stroke, lineWidth: on ? 1.5 : 0.7))
        .contentShape(Capsule())
        .onTapGesture { focused = true }
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
        .animation(Motion.spring, value: text.isEmpty)
        .animation(Motion.spring, value: focused)
    }
}

/// "No matches" line under a filtered list.
struct NoMatches: View {
    @EnvironmentObject var theme: Theme
    var body: some View {
        Text("No matches").font(theme.ui(13.5, .semibold)).foregroundStyle(theme.p.t2)
            .frame(maxWidth: .infinity).padding(.vertical, 36)
            .accessibilityIdentifier("list_no_matches")
    }
}

/// Recomputes hits off the main actor while the user types (tiny debounce; older runs are cancelled).
struct ListFilterTask: ViewModifier {
    let tracks: [Track]?
    let query: String
    @Binding var hits: ListHits?
    func body(content: Content) -> some View {
        content.task(id: "\(query)\u{1}\(tracks?.count ?? -1)\u{1}\(tracks?.first?.id ?? "")\u{1}\(tracks?.last?.id ?? "")") {
            guard let ts = tracks, !ListFilter.isBlank(query) else { hits = nil; return }
            try? await Task.sleep(nanoseconds: 40_000_000)
            if Task.isCancelled { return }
            let idx = await ListFilter.run(ts, query)
            if Task.isCancelled { return }
            hits = ListHits(list: ts, idx: idx)
            Smoke.trace("list filter len=\(query.count) hits=\(idx.count) of=\(ts.count)")
        }
    }
}

extension View {
    func listFilter(_ tracks: [Track]?, _ query: String, _ hits: Binding<ListHits?>) -> some View { modifier(ListFilterTask(tracks: tracks, query: query, hits: hits)) }
}

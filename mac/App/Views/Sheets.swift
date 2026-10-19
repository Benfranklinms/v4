import AppKit
import SwiftUI
import WebKit

/// Name sheet for creating (or renaming) a playlist. Nothing is created on Cancel or with an empty name.
struct NamePlaylistSheet: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    let tracks: [Track]
    let edit: Playlist?
    @State private var name = ""
    @State private var desc = ""
    @State private var busy = false
    @FocusState private var focused: Bool

    var body: some View {
        let p = theme.p
        let ok = !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        VStack(alignment: .leading, spacing: 14) {
            Text(edit == nil ? "New playlist" : "Edit details").font(theme.ui(18, .bold)).foregroundStyle(p.txt)
            if !tracks.isEmpty && edit == nil {
                Text(tracks.count == 1 ? "With “\(tracks[0].title)”" : "With \(tracks.count) songs").font(theme.ui(12)).foregroundStyle(p.t2)
            }
            field("Name", text: $name).focused($focused).accessibilityIdentifier("playlist_name")
            field("Description (optional)", text: $desc)
            HStack {
                Spacer()
                SecondaryButton(title: "Cancel") { nav.sheet = nil }.keyboardShortcut(.cancelAction)
                PrimaryButton(title: edit == nil ? "Create" : "Save", busy: busy, busyTitle: "Creating…", enabled: ok) { submit() }
                    .keyboardShortcut(.defaultAction)
                    .accessibilityIdentifier("playlist_create")
            }.padding(.top, 4)
        }
        .padding(24).frame(width: 420)
        .background(p.surf)
        .onAppear {
            if let e = edit { name = e.title; desc = e.subtitle.hasPrefix("Imported") ? "" : e.subtitle }
            focused = true
        }
    }

    private func field(_ ph: String, text: Binding<String>) -> some View {
        TextField(ph, text: text).textFieldStyle(.plain).font(theme.ui(14)).foregroundStyle(theme.p.txt)
            .padding(.horizontal, 12).frame(height: 36)
            .background(RoundedRectangle(cornerRadius: 9).fill(theme.p.surf2))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(theme.p.line3, lineWidth: 0.6))
            .onSubmit { submit() }
    }

    private func submit() {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty, !busy else { return }
        if let e = edit {
            lib.rename(e, to: n, description: desc)
            nav.sheet = nil
            nav.show("Saved")
            return
        }
        busy = true
        Task {
            let pl = await lib.create(name: n, description: desc, tracks: tracks)
            busy = false
            nav.sheet = nil
            if let pl { nav.show("Created \(pl.title)"); nav.push(.playlist(pl.id)) } else { nav.show("Couldn't create the playlist") }
        }
    }
}

/// Playlist context menu (sidebar, Library tiles, playlist header).
struct PlaylistMenuItems: View {
    @EnvironmentObject var player: Player
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var lib: LibraryStore
    let p: Playlist

    var body: some View {
        let own = LibraryStore.isLocal(p.id)
        Button("Play") { withTracks { player.play($0, index: 0, source: p.title) } }
        Button("Shuffle") { withTracks { player.playShuffled($0, source: p.title) } }
        Button("Add to Queue") { withTracks { ts in ts.forEach { player.addToQueue($0) }; nav.show("Added to queue") } }
        Divider()
        if own {
            Button("Rename…") { nav.sheet = .rename(p) }
            Button("Edit Details…") { nav.sheet = .rename(p) }
        } else {
            Button(lib.isSaved(playlist: p.id) ? "Remove from Library" : "Save to Library") { lib.toggleSaved(playlist: p) }
        }
        Button(lib.pinned.contains(p.id) ? "Unpin" : "Pin") { lib.togglePin(p.id) }
        Divider()
        Button("Download") { withTracks { lib.download($0.filter { !$0.id.hasPrefix("local:") }, label: p.title) } }
        Button("Remove Download") { withTracks { ts in lib.removeDownloads(ts.map(\.id).filter { lib.downloaded.contains($0) }) } }
        Button("Share") {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(own ? p.title : "https://music.youtube.com/playlist?list=\(p.id.hasPrefix("VL") ? String(p.id.dropFirst(2)) : p.id)", forType: .string)
            nav.show(own ? "Name copied" : "Link copied")
        }
        if own {
            Divider()
            Button("Delete", role: .destructive) { lib.delete([p]) }
        }
    }

    private func withTracks(_ f: @escaping ([Track]) -> Void) {
        let id = p.id
        Task { if let pg = try? await Engine.core.playlist(id: id) { f(pg.tracks) } }
    }
}

/// Avatar → Account sheet: Google profile, a quick listening snapshot, Last.fm status, sign in / out.
struct AccountSheet: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @EnvironmentObject var account: AccountStore
    @State private var stats: StatsSummary?
    @State private var lastfm: LastfmState?
    @State private var showLogin = false

    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(p.surf3)
                    if let a = account.info?.avatar, !a.isEmpty { ArtView(url: a, size: 56, circle: true) }
                    else { Text(String((account.info?.name ?? "B").prefix(1)).uppercased()).font(theme.ui(22, .bold)).foregroundStyle(p.txt) }
                }.frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 3) {
                    Text(account.info?.name ?? (account.signedIn ? "Signed in" : "Not signed in")).font(theme.ui(17, .bold)).foregroundStyle(p.txt)
                    Text(account.info.map { $0.handle.isEmpty ? $0.email : $0.handle } ?? "Sign in with Google to sync likes and playlists")
                        .font(theme.ui(12)).foregroundStyle(p.t2).lineLimit(1)
                }
                Spacer()
                IconButton("xmark", size: 11) { nav.sheet = nil }.foregroundStyle(p.t2).accessibilityLabel("Close")
            }
            HStack(spacing: 10) {
                snap("\(stats?.minutes ?? 0)", "minutes this week")
                snap("\(stats?.plays ?? 0)", "plays")
                snap("\(stats?.streakDays ?? 0)", "day streak")
            }
            if let lf = lastfm, lf.available {
                HStack {
                    Image(systemName: "dot.radiowaves.left.and.right").foregroundStyle(p.t2)
                    Text(lf.username.isEmpty ? "Last.fm not connected" : "Scrobbling to Last.fm as \(lf.username)").font(theme.ui(12.5)).foregroundStyle(p.t2)
                    Spacer()
                }
            }
            // one row when it fits, two rows otherwise (labels never wrap)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { navButtons; Spacer(minLength: 12); accountButtons }
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 10) { navButtons }
                    HStack(spacing: 10) { Spacer(minLength: 0); accountButtons }
                }
            }
            .accessibilityIdentifier("account_buttons")
        }
        .padding(24).frame(width: 520)
        .background(p.surf)
        .task {
            stats = await Engine.off { try Engine.core.stats(range: "week", tzOffsetMin: Engine.tzMinutes) }
            lastfm = await Engine.off { Engine.core.lastfmState() }
            account.load(refresh: false)
        }
        .sheet(isPresented: $showLogin) {
            GoogleLoginView { ok in
                showLogin = false
                if ok { nav.show("Signed in") }
            }
            .environmentObject(theme).environmentObject(account)
        }
        .accessibilityIdentifier("account_sheet")
    }

    @ViewBuilder private var navButtons: some View {
        SecondaryButton(title: "Settings", icon: "gearshape") { nav.sheet = nil; nav.go(.settings) }
        SecondaryButton(title: "Stats", icon: "chart.bar") { nav.sheet = nil; nav.go(.stats) }
    }
    @ViewBuilder private var accountButtons: some View {
        if account.signedIn {
            SecondaryButton(title: "Switch account") { showLogin = true }
            PrimaryButton(title: "Sign out") { account.signOut(); nav.sheet = nil }
        } else {
            PrimaryButton(title: "Sign in with Google", icon: "person.crop.circle") { showLogin = true }
                .accessibilityIdentifier("google_sign_in")
        }
    }

    private func snap(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(v).font(theme.num(20, .medium)).foregroundStyle(theme.p.txt)
            Text(l).font(theme.ui(11)).foregroundStyle(theme.p.t2).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(theme.p.surf2))
    }
}

/// Google sign-in in a web view; once music.youtube.com has the session cookies they go to the core.
struct GoogleLoginView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var account: AccountStore
    let done: (Bool) -> Void
    @State private var status = "Sign in with your Google account"
    @State private var busy = false

    var body: some View {
        let p = theme.p
        VStack(spacing: 0) {
            HStack {
                Text(status).font(theme.ui(13, .semibold)).foregroundStyle(p.txt)
                if busy { ProgressView().controlSize(.small) }
                Spacer()
                SecondaryButton(title: "Cancel") { done(false) }
            }.padding(14)
            GoogleWebView { cookie in
                guard !busy else { return }
                busy = true
                status = "Signing in…"
                Task {
                    let ok = await account.signIn(cookie: cookie)
                    busy = false
                    if ok { done(true) } else { status = "Couldn't sign in. Try again." }
                }
            }
        }
        .frame(width: 520, height: 680)
        .background(p.surf)
    }
}

struct GoogleWebView: NSViewRepresentable {
    let onCookie: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCookie: onCookie) }

    func makeNSView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        let w = WKWebView(frame: .zero, configuration: cfg)
        // a desktop Safari user agent (embedded-browser agents get refused by Google sign-in)
        w.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
        w.navigationDelegate = context.coordinator
        if let u = URL(string: "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F") {
            w.load(URLRequest(url: u))
        }
        return w
    }
    func updateNSView(_ nsView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        let onCookie: (String) -> Void
        var sent = false
        init(onCookie: @escaping (String) -> Void) { self.onCookie = onCookie }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard !sent, webView.url?.host?.hasSuffix("music.youtube.com") == true else { return }
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                let yt = cookies.filter { $0.domain.hasSuffix("youtube.com") }
                guard yt.contains(where: { $0.name == "SAPISID" || $0.name == "__Secure-3PAPISID" }) else { return }
                let header = yt.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
                DispatchQueue.main.async {
                    guard !self.sent else { return }
                    self.sent = true
                    self.onCookie(header)
                }
            }
        }
    }
}

/// Crash reports and app details, with Copy.
struct DiagnosticsView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @State private var log = ""
    @State private var health: StreamingHealth?

    var body: some View {
        let p = theme.p
        Page {
            LargeTitle(text: "Diagnostics")
            Text("iTune \(CrashReporter.version) · core \(Engine.coreVersion)").font(theme.num(12)).foregroundStyle(p.t2).padding(.top, 4)
            if let h = health {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Stream client: \(h.workingClient.isEmpty ? "—" : h.workingClient) · failures \(h.failures)").font(theme.num(11.5)).foregroundStyle(p.t2)
                    if !h.lastError.isEmpty { Text(h.lastError).font(theme.num(11)).foregroundStyle(p.t3).lineLimit(3) }
                    Text("Clients: \(h.clients.joined(separator: ", "))").font(theme.num(11)).foregroundStyle(p.t3)
                }.padding(.top, 12)
            }
            Text("Crash reports").font(theme.ui(16, .bold)).foregroundStyle(p.txt).padding(.top, 22)
            ScrollView { Text(log.isEmpty ? "No crashes recorded." : log).font(.system(size: 10.5, design: .monospaced)).foregroundStyle(p.t2).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
                .frame(height: 320).padding(12).background(RoundedRectangle(cornerRadius: 12).fill(p.surf))
                .padding(.top, 8)
            HStack {
                SecondaryButton(title: "Clear") { CrashReporter.clearHistory(); log = "" }
                PrimaryButton(title: "Copy", icon: "doc.on.doc") {
                    NSPasteboard.general.clearContents(); NSPasteboard.general.setString(log, forType: .string); nav.show("Copied")
                }
            }.padding(.top, 12)
        }
        .task {
            log = await Engine.off { CrashReporter.history() } ?? ""
            health = await Engine.off { Engine.core.streamingHealth() }
        }
    }
}

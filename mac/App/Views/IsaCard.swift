import AppKit
import AVFoundation
import Speech
import SwiftUI

/// State behind the Isa Command card (shared so ⌘K, the mood tiles and the smoke test drive the same card).
@MainActor
final class IsaModel: ObservableObject {
    static let shared = IsaModel()
    enum Phase: Equatable { case idle, thinking, preview, error(String) }
    @Published var text = ""
    @Published var phase: Phase = .idle
    @Published var result: IsaResult?
    @Published var suggestions: [String] = []
    @Published var minutes: UInt32 = 0
    private var lastPrompt = ""
    private var suggestTask: Task<Void, Never>?

    static let lengths: [(String, UInt32)] = [("Any length", 0), ("30 min", 30), ("1 hour", 60), ("2 hours", 120)]
    var lengthLabel: String { IsaModel.lengths.first { $0.1 == minutes }?.0 ?? "Any length" }

    func typed(_ t: String) {
        suggestTask?.cancel()
        guard !t.trimmingCharacters(in: .whitespaces).isEmpty else { suggestions = []; return }
        suggestTask = Task {
            try? await Task.sleep(nanoseconds: 120_000_000)
            if Task.isCancelled { return }
            let s = await Engine.off { Engine.core.isaSuggestions(text: t) } ?? []
            if !Task.isCancelled { withAnimation(Motion.spring) { self.suggestions = Array(s.prefix(4)) } }
        }
    }

    func submit(_ prompt: String? = nil) async {
        let p = (prompt ?? text).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !p.isEmpty else { return }
        lastPrompt = p
        suggestions = []
        withAnimation(Motion.spring) { phase = .thinking }
        do {
            let r = try await Engine.core.isaQueue(prompt: p, maxMinutes: minutes)
            if r.tracks.isEmpty { withAnimation(Motion.spring) { phase = .error("Nothing matched that. Try other words.") }; return }
            Haptics.tap()
            withAnimation(Motion.spring) { result = r; phase = .preview }
        } catch {
            withAnimation(Motion.spring) { phase = .error(Engine.friendly(error, "Couldn't reach the music service.")) }
        }
    }
    func retry() async { await submit(lastPrompt) }
    func reset() { withAnimation(Motion.spring) { phase = .idle; result = nil; text = "" } }

    func play(shuffle: Bool) {
        guard let r = result else { return }
        if shuffle { Player.shared.playShuffled(r.tracks, source: "Isa · \(r.plan.title)") }
        else { Player.shared.setShuffle(false); Player.shared.play(r.tracks, index: 0, source: "Isa · \(r.plan.title)") }
    }
    func save() async -> Playlist? {
        guard let r = result else { return nil }
        let pl = await LibraryStore.shared.create(name: r.plan.title, description: "Made with Isa", tracks: r.tracks)
        if let pl { Nav.shared.show("Saved \(pl.title)") }
        return pl
    }
}

/// Isa design B "Command card": mono card, glowing orb + waveform, prompt with blinking cursor, length pill, mic, send;
/// mood tiles below. States: focus (spring expand), live suggestions, thinking (shimmer + orb pulse), queue preview, error.
struct IsaCommandCard: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var nav: Nav
    @ObservedObject var m = IsaModel.shared
    @StateObject private var speech = SpeechInput()
    @FocusState private var focused: Bool

    var body: some View {
        let p = theme.p
        let expanded = focused || m.phase != .idle
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    IsaOrb(active: m.phase == .thinking || speech.listening).frame(width: 26, height: 26)
                    Text("ISA").font(theme.num(11, .medium)).tracking(3).foregroundStyle(p.t2)
                    Spacer()
                    if m.phase != .idle {
                        IconButton("xmark", size: 10) { m.reset() }.foregroundStyle(p.t3).help("Clear")
                    }
                }
                ZStack(alignment: .leading) {
                    if m.text.isEmpty {
                        HStack(spacing: 1) {
                            Text("What should we play?").font(theme.ui(24, .medium)).foregroundStyle(p.t2)
                            BlinkingCursor(height: 26).opacity(focused ? 0 : 1)
                        }.allowsHitTesting(false)
                    }
                    TextField("", text: $m.text).textFieldStyle(.plain).font(theme.ui(24, .medium)).foregroundStyle(p.txt)
                        .focused($focused)
                        .onSubmit { Task { await m.submit() } }
                        .onChange(of: m.text) { _, t in m.typed(t) }
                        .accessibilityLabel("Ask Isa")
                        .accessibilityIdentifier("isa_field")
                }
                if !m.suggestions.isEmpty && m.phase == .idle {
                    HStack(spacing: 8) {
                        ForEach(m.suggestions, id: \.self) { s in
                            Chip(label: s, icon: "sparkles") { m.text = s; Task { await m.submit() } }
                        }
                    }.transition(.opacity.combined(with: .move(edge: .top)))
                }
                switch m.phase {
                case .thinking: ThinkingShimmer().frame(height: 54).transition(.opacity)
                case .preview: if let r = m.result { IsaPreview(r: r).transition(.opacity.combined(with: .scale(scale: 0.98))) }
                case .error(let e):
                    HStack {
                        Image(systemName: "exclamationmark.circle").foregroundStyle(p.t2)
                        Text(e).font(theme.ui(13)).foregroundStyle(p.t2)
                        Spacer()
                        SecondaryButton(title: "Retry", icon: "arrow.clockwise") { Task { await m.retry() } }
                    }.transition(.opacity)
                case .idle: EmptyView()
                }
                HStack(spacing: 10) {
                    Menu {
                        ForEach(IsaModel.lengths, id: \.1) { l in Button(l.0) { m.minutes = l.1 } }
                    } label: {
                        Label(m.lengthLabel, systemImage: "clock").font(theme.ui(12.5, .medium)).foregroundStyle(p.t2)
                    }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    .padding(.horizontal, 14).frame(height: 32)
                    .background(Capsule().fill(Color.white.opacity(0.07)))
                    .help("Length")
                    Spacer()
                    Button { speech.toggle { m.text = $0 } done: { Task { await m.submit() } } } label: {
                        Image(systemName: speech.listening ? "waveform" : "mic.fill").font(.system(size: 14, weight: .semibold)).foregroundStyle(p.txt)
                            .frame(width: 38, height: 38).background(Circle().fill(Color.white.opacity(speech.listening ? 0.2 : 0.08)))
                            .symbolEffect(.variableColor.iterative, isActive: speech.listening && !Motion.reduce)
                    }.buttonStyle(PressStyle()).help("Speak").accessibilityLabel("Speak")
                    Button { Task { await m.submit() } } label: {
                        Image(systemName: "arrow.up").font(.system(size: 15, weight: .bold)).foregroundStyle(Color(red: 0.035, green: 0.035, blue: 0.04))
                            .frame(width: 38, height: 38).background(Circle().fill(Color(red: 0.91, green: 0.91, blue: 0.91)))
                    }.buttonStyle(PressStyle()).help("Ask Isa").accessibilityLabel("Send").accessibilityIdentifier("isa_send")
                }
            }
            .padding(.horizontal, 22).padding(.vertical, expanded ? 20 : 18)
            .glassCard(26)
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(Color.white.opacity(focused ? 0.22 : 0.08), lineWidth: 0.8))
            .scaleEffect(expanded && !Motion.reduce ? 1.0 : 0.995)
            .animation(Motion.bouncy, value: expanded)
            .onTapGesture { focused = true }
            HStack(spacing: 14) {
                ForEach(MoodTile.Kind.allCases, id: \.self) { k in
                    MoodTile(kind: k) { m.text = k.prompt; Task { await m.submit() } }
                }
            }
        }
        .onChange(of: nav.focusIsa) { _, _ in focused = true }
        .accessibilityIdentifier("isa_card")
    }
}

struct IsaPreview: View {
    @EnvironmentObject var theme: Theme
    @ObservedObject var m = IsaModel.shared
    let r: IsaResult
    var body: some View {
        let p = theme.p
        let secs = r.tracks.reduce(0) { $0 + Int($1.durationS) }
        HStack(spacing: 14) {
            ZStack {
                ForEach(Array(r.tracks.prefix(3).enumerated().reversed()), id: \.offset) { i, t in
                    ArtView(url: t.thumb, size: 54, radius: 8).offset(x: CGFloat(i) * 16).shadow(color: .black.opacity(0.4), radius: 6)
                }
            }.frame(width: 90, alignment: .leading)
            VStack(alignment: .leading, spacing: 3) {
                Text(r.plan.title).font(theme.ui(15, .bold)).foregroundStyle(p.txt).lineLimit(1)
                Text("\(r.tracks.count) songs · \(secs / 3600 > 0 ? "\(secs / 3600) h " : "")\((secs % 3600) / 60) min").font(theme.num(11.5)).foregroundStyle(p.t2)
            }
            Spacer()
            PrimaryButton(title: "Play", icon: "play.fill") { m.play(shuffle: false) }.accessibilityIdentifier("isa_play")
            SecondaryButton(title: "Shuffle", icon: "shuffle") { m.play(shuffle: true) }
            SecondaryButton(title: "Save", icon: "plus") { Task { _ = await m.save() } }
        }
        .accessibilityIdentifier("isa_preview")
    }
}

/// Glowing mono orb with a live waveform; pulses while thinking / listening.
/// The timeline only runs while Isa is active (thinking / listening / focused) or hovered; idle it is a still frame.
struct IsaOrb: View {
    let active: Bool
    @State private var hover = false
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: Motion.reduce || !(active || hover))) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            let pulse = active ? 1 + 0.08 * sin(t * 6) : 1
            Canvas { g, size in
                let r = min(size.width, size.height) / 2
                let c = CGPoint(x: size.width / 2, y: size.height / 2)
                g.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)),
                       with: .radialGradient(Gradient(colors: [.white, Color(white: 0.55), Color(white: 0.12)]), center: CGPoint(x: c.x - r * 0.3, y: c.y - r * 0.35), startRadius: 0, endRadius: r * 1.2))
                var wave = Path()
                for i in 0...24 {
                    let x = c.x - r * 0.7 + CGFloat(i) / 24 * r * 1.4
                    let amp = (active ? 0.22 : 0.08) * r
                    let y = c.y + r * 0.45 + CGFloat(sin(Double(i) * 0.9 + t * (active ? 8 : 2))) * amp
                    if i == 0 { wave.move(to: CGPoint(x: x, y: y)) } else { wave.addLine(to: CGPoint(x: x, y: y)) }
                }
                g.stroke(wave, with: .color(.black.opacity(0.75)), lineWidth: 1.4)
            }
            .scaleEffect(pulse)
            .shadow(color: .white.opacity(active ? 0.45 : 0.18), radius: active ? 10 : 5)
        }
        .onHover { hover = $0 }
        .accessibilityHidden(true)
    }
}

struct BlinkingCursor: View {
    var height: CGFloat = 24
    @State private var on = true
    var body: some View {
        Rectangle().fill(Color.white.opacity(0.85)).frame(width: 2, height: height)
            .opacity(on ? 1 : 0)
            .onAppear { if !Motion.reduce { withAnimation(.easeInOut(duration: 0.55).repeatForever()) { on = false } } }
    }
}

struct ThinkingShimmer: View {
    @EnvironmentObject var theme: Theme
    @State private var x: CGFloat = -1
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach([0.9, 0.6], id: \.self) { w in
                GeometryReader { g in
                    RoundedRectangle(cornerRadius: 6).fill(theme.p.surf3)
                        .overlay(LinearGradient(colors: [.clear, .white.opacity(0.12), .clear], startPoint: .leading, endPoint: .trailing)
                            .frame(width: g.size.width * 0.4).offset(x: x * g.size.width))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .frame(width: g.size.width * w)
                }.frame(height: 18)
            }
        }
        .onAppear { if !Motion.reduce { withAnimation(.linear(duration: 1.2).repeatForever(autoreverses: false)) { x = 1.2 } } }
        .accessibilityLabel("Isa is thinking")
    }
}

/// Mood tiles with generated mono pattern art (rings, stripes, waves, dots).
struct MoodTile: View {
    enum Kind: CaseIterable { case focus, drive, rain, hype
        var title: String {
            switch self {
            case .focus: return "Focus"
            case .drive: return "Drive"
            case .rain: return "Rain"
            case .hype: return "Hype"
            }
        }
        var prompt: String {
            switch self {
            case .focus: return "focus, no vocals"
            case .drive: return "night drive"
            case .rain: return "rainy day, calm"
            case .hype: return "hype workout"
            }
        }
    }
    @EnvironmentObject var theme: Theme
    let kind: Kind
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Canvas { g, s in draw(&g, s) }
                .frame(width: 120, height: 120)
                .background(Color(white: 0.09))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.white.opacity(0.08), lineWidth: 0.6))
                .scaleEffect(hover && !Motion.reduce ? 1.03 : 1)
            Text(kind.title).font(theme.ui(14, .semibold)).foregroundStyle(theme.p.txt)
        }
        .contentShape(Rectangle())
        .onHover { h in withAnimation(Motion.spring) { hover = h } }
        .onTapGesture(perform: action)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(kind.title)
    }

    private func draw(_ g: inout GraphicsContext, _ s: CGSize) {
        let c = CGPoint(x: s.width / 2, y: s.height / 2)
        switch kind {
        case .focus:
            for i in 1...12 {
                let r = CGFloat(i) * 6
                g.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(.white.opacity(0.35)), lineWidth: 0.7)
            }
        case .drive:
            for i in -6...6 {
                var p = Path()
                let x = CGFloat(i) * 20
                p.move(to: CGPoint(x: x, y: s.height)); p.addLine(to: CGPoint(x: x + s.width, y: 0))
                g.stroke(p, with: .color(.white.opacity(i % 3 == 0 ? 0.55 : 0.18)), lineWidth: 9)
            }
        case .rain:
            g.fill(Path(CGRect(origin: .zero, size: CGSize(width: s.width, height: s.height * 0.35))), with: .linearGradient(Gradient(colors: [Color(white: 0.6), Color(white: 0.3)]), startPoint: .zero, endPoint: CGPoint(x: 0, y: s.height * 0.35)))
            for j in 0..<4 {
                var p = Path()
                let base = s.height * (0.3 + CGFloat(j) * 0.18)
                p.move(to: CGPoint(x: 0, y: s.height))
                for i in 0...20 {
                    let x = CGFloat(i) / 20 * s.width
                    p.addLine(to: CGPoint(x: x, y: base + 7 * sin(Double(i) * 0.7 + Double(j))))
                }
                p.addLine(to: CGPoint(x: s.width, y: s.height)); p.closeSubpath()
                g.fill(p, with: .color(Color(white: 0.18 + Double(j) * 0.05)))
            }
        case .hype:
            for i in 0..<7 {
                for j in 0..<7 {
                    let r: CGFloat = (i == 4 && j == 4) ? 6 : CGFloat(2 + (i * 7 + j) % 3)
                    let x = CGFloat(i) * 16 + 12, y = CGFloat(j) * 16 + 12
                    g.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(.white.opacity(i == 4 && j == 4 ? 0.95 : 0.4)))
                }
            }
        }
    }
}

/// Voice input for Isa (on-device speech recognition when available).
@MainActor
final class SpeechInput: ObservableObject {
    @Published var listening = false
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var finish: (() -> Void)?

    func toggle(onText: @escaping (String) -> Void, done: @escaping () -> Void) {
        if listening { stop(); return }
        SFSpeechRecognizer.requestAuthorization { status in
            Task { @MainActor in
                guard status == .authorized else { Nav.shared.show("Allow Speech Recognition in System Settings to talk to Isa"); return }
                self.start(onText: onText, done: done)
            }
        }
    }

    private func start(onText: @escaping (String) -> Void, done: @escaping () -> Void) {
        guard let rec = SFSpeechRecognizer(), rec.isAvailable else { Nav.shared.show("Speech isn't available right now"); return }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if rec.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        request = req
        finish = done
        let input = engine.inputNode
        let fmt = input.outputFormat(forBus: 0)
        guard fmt.sampleRate > 0 else { Nav.shared.show("No microphone found"); return }
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: fmt) { [weak req] buf, _ in req?.append(buf) }
        do { engine.prepare(); try engine.start() } catch { Nav.shared.show("Couldn't start the microphone"); return }
        listening = true
        task = rec.recognitionTask(with: req) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let r = result { onText(r.bestTranscription.formattedString) }
                if error != nil || result?.isFinal == true { self.stop() }
            }
        }
    }

    func stop() {
        guard listening else { return }
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        listening = false
        let f = finish; finish = nil
        f?()
    }
}

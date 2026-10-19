import AppKit
import SwiftUI

/// Central Liquid Glass styling. macOS 26: native `.glassEffect`; older macOS: `.ultraThinMaterial`;
/// Reduce Transparency: solid surfaces (#09090A family). Every floating container uses these modifiers.
enum GlassEnv {
    /// Test override (smoke): true = glass even when the system asks for Reduce Transparency, false = solid.
    nonisolated(unsafe) static var forceGlass: Bool?
    static var reduceTransparency: Bool { forceGlass.map { !$0 } ?? NSWorkspace.shared.accessibilityDisplayShouldReduceTransparency }
}

struct GlassSurface<S: Shape>: ViewModifier {
    @EnvironmentObject var theme: Theme
    let shape: S
    var interactive = false
    var tint: Color? = nil

    @ViewBuilder func body(content: Content) -> some View {
        let p = theme.p
        if GlassEnv.reduceTransparency {
            content
                .background(shape.fill(p.surf))
                .overlay(shape.stroke(p.line3, lineWidth: 0.6))
                .clipShape(shape)
        } else {
            glass(content, p)
        }
    }

    @ViewBuilder private func glass(_ content: Content, _ p: Palette) -> some View {
        #if compiler(>=6.2)
        if #available(macOS 26.0, *) {
            content
                .glassEffect(interactive ? Glass.regular.interactive() : Glass.regular, in: shape)
        } else {
            material(content, p)
        }
        #else
        material(content, p)
        #endif
    }

    private func material(_ content: Content, _ p: Palette) -> some View {
        content
            .background(shape.fill(.ultraThinMaterial))
            .background(shape.fill(p.bg.opacity(0.35)))
            .overlay(shape.stroke(LinearGradient(colors: [Color.white.opacity(0.18), Color.white.opacity(0.04)], startPoint: .top, endPoint: .bottom), lineWidth: 0.7))
            .clipShape(shape)
            .shadow(color: .black.opacity(0.35), radius: 18, y: 8)
    }
}

extension View {
    /// Floating glass container (sidebar, player bar, queue panel, cards).
    func glassSurface<S: Shape>(_ shape: S, interactive: Bool = false) -> some View {
        modifier(GlassSurface(shape: shape, interactive: interactive))
    }
    func glassCard(_ radius: CGFloat = 22) -> some View {
        glassSurface(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
    func glassCapsule() -> some View { glassSurface(Capsule(), interactive: true) }
}

/// Glass round button (transport, toolbar).
struct GlassIconButton: View {
    @EnvironmentObject var theme: Theme
    let name: String
    var size: CGFloat = 13
    var on = false
    var help = ""
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        let p = theme.p
        Button(action: { Haptics.tap(); hover = false; action() }) {
            Image(systemName: name)
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(on ? p.bg : (hover ? p.txt : p.t2))
                .frame(width: size + 18, height: size + 18)
                .background(Circle().fill(on ? p.txt : (hover ? Color.white.opacity(0.08) : .clear)))
                .contentShape(Circle())
        }
        .buttonStyle(PressStyle(scale: 0.9))
        .help(help)
        .accessibilityLabel(help.isEmpty ? name : help)
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
        .onDisappear { hover = false }
        .animation(Motion.spring, value: on)
    }
}

/// Trackpad haptics where the hardware supports them.
enum Haptics {
    static func tap() { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
    static func strong() { NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now) }
}

/// Primary (filled) button: dark #09090A text on #E8E8E8, visible spinner while busy. Never white-on-white.
struct PrimaryButton: View {
    @EnvironmentObject var theme: Theme
    let title: String
    var icon: String? = nil
    var busy = false
    var busyTitle: String? = nil
    var enabled = true
    let action: () -> Void

    var body: some View {
        let fg = Color(red: 0x09 / 255, green: 0x09 / 255, blue: 0x0A / 255)
        let bgc = theme.p.light ? Color(red: 0x11 / 255, green: 0x11 / 255, blue: 0x13 / 255) : Color(red: 0xE8 / 255, green: 0xE8 / 255, blue: 0xE8 / 255)
        let text = theme.p.light ? Color.white : fg
        Button(action: { if enabled && !busy { Haptics.tap(); action() } }) {
            HStack(spacing: 8) {
                if busy {
                    ProgressView().controlSize(.small).colorScheme(theme.p.light ? .dark : .light)
                } else if let i = icon {
                    Image(systemName: i).font(.system(size: 12, weight: .bold))
                }
                Text(busy ? (busyTitle ?? title) : title).font(theme.ui(13, .bold)).lineLimit(1).fixedSize()
            }
            .foregroundStyle(text)
            .padding(.horizontal, 18).frame(height: 36)
            .background(Capsule().fill(bgc.opacity(enabled ? 1 : 0.4)))
        }
        .buttonStyle(PressStyle(scale: 0.96))
        .disabled(!enabled || busy)
        .accessibilityLabel(busy ? (busyTitle ?? title) : title)
    }
}

/// Secondary (outlined) button.
struct SecondaryButton: View {
    @EnvironmentObject var theme: Theme
    let title: String
    var icon: String? = nil
    let action: () -> Void
    var body: some View {
        let p = theme.p
        Button(action: { Haptics.tap(); action() }) {
            HStack(spacing: 7) {
                if let i = icon { Image(systemName: i).font(.system(size: 12, weight: .semibold)) }
                Text(title).font(theme.ui(13, .semibold)).lineLimit(1).fixedSize()
            }
            .foregroundStyle(p.txt)
            .padding(.horizontal, 16).frame(height: 36)
            .background(Capsule().fill(p.surf3))
            .overlay(Capsule().stroke(p.line3, lineWidth: 0.6))
        }.buttonStyle(PressStyle(scale: 0.96))
    }
}

/// Backdrop: blurred current artwork under the whole window (colour only from art, content stays mono).
struct ArtBackdrop: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    var strength: Double = 1
    var body: some View {
        let p = theme.p
        ZStack {
            p.bg
            if !GlassEnv.reduceTransparency, let img = player.backdrop {
                // pre-blurred 96 px image stretched: no blur filter at render time. Drawn as an overlay of a
                // flexible colour so its aspect-fill size never sizes the layout (it used to push the window content).
                Color.clear
                    .overlay(Image(nsImage: img).resizable().interpolation(.high).aspectRatio(contentMode: .fill))
                    .clipped()
                    .opacity(0.55 * strength)
                    .transition(.opacity)
                p.bg.opacity(0.45)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .ignoresSafeArea()
        .animation(Motion.glow, value: player.current?.id)
    }
}

import AppKit
import SwiftUI

/// Menu-bar popover content.
struct MenuBarView: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player

    var body: some View {
        let p = theme.p
        VStack(alignment: .leading, spacing: 12) {
            if let t = player.current {
                HStack(spacing: 12) {
                    ArtView(url: t.thumb, image: player.artwork, size: 54, radius: 8)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(t.title).font(theme.ui(13.5, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                        Text(t.artists).font(theme.ui(11.5)).foregroundStyle(p.t2).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                Scrubber()
                HStack {
                    Transport(size: 15)
                    Spacer()
                    IconButton("pip") { MiniPlayerPanel.shared.toggle() }.foregroundStyle(p.t2)
                }
            } else {
                HStack(spacing: 10) {
                    PulseMark().frame(width: 18, height: 18).foregroundStyle(p.txt)
                    Text("Nothing playing").font(theme.ui(13, .medium)).foregroundStyle(p.t2)
                }
            }
            Divider().overlay(p.line2)
            HStack {
                Button("Open iTune") { MiniPlayerPanel.activateMain() }.buttonStyle(.plain).font(theme.ui(12, .semibold)).foregroundStyle(p.txt)
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }.buttonStyle(.plain).font(theme.ui(12)).foregroundStyle(p.t3)
            }
        }
        .padding(14)
        .frame(width: 300)
        .background(ZStack { p.surf; LinearGradient(colors: [theme.glow.opacity(0.18), .clear], startPoint: .top, endPoint: .center) })
    }
}

/// Floating mini player. The SwiftUI view is wrapped in a plain NSView container with autoresizing
/// (no Auto Layout between the window and NSHostingView, sizingOptions = []), which avoids the
/// macOS 27 "update constraints" loop crash seen when hosting views size their window.
@MainActor
final class MiniPlayerPanel {
    static let shared = MiniPlayerPanel()
    private var panel: NSPanel?
    private let size = NSSize(width: 360, height: 96)

    func toggle() {
        if let p = panel, p.isVisible { p.orderOut(nil); return }
        show()
    }

    func show() {
        if panel == nil {
            let p = NSPanel(contentRect: NSRect(origin: .zero, size: size),
                            styleMask: [.nonactivatingPanel, .fullSizeContentView, .borderless],
                            backing: .buffered, defer: false)
            p.isFloatingPanel = true
            p.level = .floating
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            p.isMovableByWindowBackground = true
            p.backgroundColor = .clear
            p.isOpaque = false
            p.hasShadow = true
            p.contentView = HostingSizing.container(
                MiniPlayerContent()
                    .environmentObject(Theme.shared)
                    .environmentObject(Player.shared),
                size: size)
            if let screen = NSScreen.main {
                let f = screen.visibleFrame
                p.setFrameOrigin(NSPoint(x: f.maxX - size.width - 24, y: f.minY + 24))
            }
            panel = p
        }
        panel?.alphaValue = 0
        panel?.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { c in
            c.duration = Motion.reduce ? 0 : 0.25
            panel?.animator().alphaValue = 1
        }
    }

    static func activateMain() {
        NSApp.activate(ignoringOtherApps: true)
        if let w = NSApp.windows.first(where: { $0.canBecomeMain && !($0 is NSPanel) }) {
            w.makeKeyAndOrderFront(nil)
        }
    }
}

enum HostingSizing {
    /// Plain NSView that owns an NSHostingView via frame + autoresizing mask only.
    @MainActor
    static func container<V: View>(_ view: V, size: NSSize) -> NSView {
        let box = NSView(frame: NSRect(origin: .zero, size: size))
        box.autoresizesSubviews = true
        let host = NSHostingView(rootView: view)
        if #available(macOS 13.0, *) { host.sizingOptions = [] }
        host.translatesAutoresizingMaskIntoConstraints = true
        host.frame = box.bounds
        host.autoresizingMask = [.width, .height]
        box.addSubview(host)
        return box
    }
}

struct MiniPlayerContent: View {
    @EnvironmentObject var theme: Theme
    @EnvironmentObject var player: Player
    @State private var hover = false

    var body: some View {
        let p = theme.p
        HStack(spacing: 12) {
            if let t = player.current {
                ArtView(url: t.thumb, image: player.artwork, size: 64, radius: 10)
                VStack(alignment: .leading, spacing: 6) {
                    Text(t.title).font(theme.ui(13, .semibold)).foregroundStyle(p.txt).lineLimit(1)
                    Text(t.artists).font(theme.ui(11)).foregroundStyle(p.t2).lineLimit(1)
                    GeometryReader { g in
                        Capsule().fill(p.surf4).overlay(alignment: .leading) {
                            Capsule().fill(p.txt).frame(width: g.size.width * CGFloat(player.duration > 0 ? min(1, player.position / player.duration) : 0))
                        }
                    }.frame(height: 3)
                }
                Spacer(minLength: 0)
                Transport(size: 13).opacity(hover ? 1 : 0.85)
            } else {
                PulseMark().frame(width: 22, height: 22).foregroundStyle(p.txt)
                Text("Nothing playing").font(theme.ui(12.5)).foregroundStyle(p.t2)
                Spacer()
            }
        }
        .padding(16)
        .frame(width: 360, height: 96)
        .background(
            ZStack {
                RoundedRectangle(cornerRadius: 18).fill(p.surf)
                RoundedRectangle(cornerRadius: 18).fill(LinearGradient(colors: [theme.glow.opacity(0.22), .clear], startPoint: .leading, endPoint: .trailing))
            }
        )
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(p.line3, lineWidth: 0.6))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .onHover { h in withAnimation(Motion.spring) { hover = h } }
        .contextMenu { Button("Close") { MiniPlayerPanel.shared.toggle() } }
    }
}

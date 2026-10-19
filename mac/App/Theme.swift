import AppKit
import CoreText
import SwiftUI

/// Pure Mono palette set (same ids/colours as Android).
struct Palette: Identifiable, Equatable {
    let id: String
    let name: String
    let light: Bool
    let bg, side, surf, surf2, surf3, surf4, line, line2, line3, txt, acc, t2, t3, t4: Color

    static func hex(_ h: String) -> Color {
        let v = UInt64(h, radix: 16) ?? 0
        return Color(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255, opacity: 1)
    }

    init(_ id: String, _ name: String, _ light: Bool, _ c: [String]) {
        self.id = id; self.name = name; self.light = light
        let x = c.map(Palette.hex)
        bg = x[0]; side = x[1]; surf = x[2]; surf2 = x[3]; surf3 = x[4]; surf4 = x[5]
        line = x[6]; line2 = x[7]; line3 = x[8]; txt = x[9]; acc = x[10]; t2 = x[11]; t3 = x[12]; t4 = x[13]
    }

    static let mono = Palette("mono", "Pure Mono", false, ["09090A", "0D0D0E", "111112", "171718", "1F1F21", "2A2A2C", "1A1A1C", "222224", "333335", "F2F2F2", "E8E8E8", "9A9A9C", "626264", "48484A"])
    static let all: [Palette] = [
        mono,
        Palette("oled", "OLED Black", false, ["000000", "000000", "0A0A0A", "101010", "181818", "262626", "141414", "1C1C1C", "2C2C2C", "F2F2F2", "E8E8E8", "9A9A9C", "626264", "48484A"]),
        Palette("adaptive", "Adaptive", false, ["09090A", "0D0D0E", "111112", "171718", "1F1F21", "2A2A2C", "1A1A1C", "222224", "333335", "F2F2F2", "E8E8E8", "9A9A9C", "626264", "48484A"]),
        Palette("nightgreen", "Night Green", false, ["121212", "0B0B0B", "181818", "1F1F1F", "282828", "333333", "1E1E1E", "282828", "3A3A3A", "FFFFFF", "1ED760", "B3B3B3", "727272", "535353"]),
        Palette("nightred", "Night Red", false, ["030303", "0F0F0F", "141414", "1C1C1C", "272727", "333333", "1A1A1A", "242424", "383838", "F1F1F1", "FF1F3D", "AAAAAA", "717171", "505050"]),
        Palette("daylight", "Daylight", true, ["FFFFFF", "F8F8FA", "F5F5F7", "F2F2F4", "E8E8EC", "DCDCE0", "ECECEF", "E3E3E7", "D2D2D7", "111113", "FA2D48", "6E6E73", "8E8E93", "B8B8BD"]),
        Palette("midnight", "Midnight", false, ["080A10", "0B0D14", "0F121A", "151925", "1D2230", "2A3042", "171B26", "1F2433", "2E3447", "EEF1F8", "A9BEFF", "949BB0", "5E6478", "454A5A"]),
        Palette("violet", "Violet", false, ["0B0A10", "0E0D14", "121119", "18171F", "211F2B", "2D2A3B", "1B1A23", "24222E", "35323F", "F1EFF8", "C7B8FF", "9C98AE", "645F75", "4A4658"]),
        Palette("ocean", "Ocean", false, ["060B0F", "081016", "0C141B", "111B24", "172431", "223344", "131D27", "1A2733", "283A4A", "EDF4F8", "8FD3FF", "8EA3B2", "587080", "3F5260"]),
        Palette("rose", "Rose", false, ["0F0A0C", "130D10", "161013", "1D1519", "281C22", "38272F", "1F161A", "291D23", "3B2A32", "F8EEF1", "F4B6C8", "B09AA2", "76626A", "54454B"]),
        Palette("sand", "Sand", false, ["0C0B09", "100F0C", "13120F", "1A1814", "23201B", "302C25", "1C1A16", "25221D", "36322B", "F3EEE6", "E8D9BE", "A39C90", "6A645B", "4C4740"]),
        Palette("mocha", "Mocha", false, ["0E0A08", "120D0A", "17110D", "1E1712", "291F18", "3A2C22", "1F1712", "2A1F18", "3D2E24", "F5EDE6", "D9B08C", "AD9A8A", "75655A", "554940"]),
        Palette("slate", "Slate", false, ["0B0D0F", "0E1013", "121519", "181C21", "20252C", "2C333C", "181B20", "20242A", "30363F", "EEF0F2", "C3CCD8", "98A0AA", "626A74", "474D55"]),
        Palette("forest", "Forest", false, ["08100B", "0B140E", "0E1912", "132119", "1A2C21", "26402F", "14211A", "1B2B21", "2A3F31", "ECF5EE", "7FD49A", "93A89A", "5E7365", "44564A"]),
        Palette("sunset", "Sunset", false, ["120A08", "170D0A", "1B110D", "231611", "2E1E17", "412A20", "21140F", "2C1B15", "402A21", "FBEFE9", "FF8A5B", "B59C90", "7A6359", "584640"]),
        Palette("snow", "Snow", true, ["FFFFFF", "FAFAFA", "F6F6F6", "F2F2F2", "EBEBEB", "D9D9D9", "EDEDED", "E6E6E6", "D6D6D6", "0A0A0A", "0A0A0A", "5A5A5A", "8C8C8C", "B4B4B4"]),
        Palette("ice", "Ice", true, ["EEF3F7", "E6EDF2", "FFFFFF", "F6F9FB", "E3EBF1", "CCD9E3", "E1E8EE", "D9E2E9", "C6D3DE", "0E1620", "2F6FA8", "52606E", "84919D", "AAB5BF"]),
        Palette("paper", "Paper", true, ["F7F5F0", "F1EEE8", "FFFEFB", "FBF9F5", "EDE9E1", "DCD6CB", "E9E5DD", "E2DDD4", "D2CBBF", "1B1915", "2B2823", "625C52", "938C80", "B9B2A6"]),
        Palette("linen", "Linen", true, ["F6F0E6", "F0E9DD", "FFFBF4", "FBF6ED", "EFE5D5", "E0D2BC", "EAE1D2", "E3D8C6", "D5C6AE", "231B12", "9A6B3F", "6B5D4C", "9A8B78", "BEB09C"]),
        Palette("lavender", "Lavender", true, ["F3F1F7", "ECE9F2", "FFFFFF", "F9F8FC", "E9E5F1", "D6D0E4", "E6E2EE", "DEDAE8", "CDC6DC", "16121F", "6B55B8", "5E5770", "8E879F", "B2ACC0"]),
    ]
    static func byId(_ id: String) -> Palette { all.first { $0.id == id } ?? mono }
}

/// App-wide look: palette, glow colour, fonts and text scale. Changes animate.
final class Theme: ObservableObject {
    static let shared = Theme()
    @AppStorage("theme") var themeId = "mono" { didSet { objectWillChange.send() } }
    @AppStorage("followSystem") var followSystem = false { didSet { objectWillChange.send() } }
    @AppStorage("pureBlack") var pureBlack = false { didSet { objectWillChange.send() } }
    @AppStorage("textScale") var textScale = 1.0 { didSet { objectWillChange.send() } }
    @AppStorage("lyricsScale") var lyricsScale = 1.0 { didSet { objectWillChange.send() } }
    @AppStorage("font") var fontId = "manrope" { didSet { objectWillChange.send() } }
    @AppStorage("customFontFamily") var customFamily = "" { didSet { objectWillChange.send() } }
    @AppStorage("customFontNumbers") var customNumbers = false { didSet { objectWillChange.send() } }
    @Published var glow: Color = Color(red: 0.36, green: 0.29, blue: 0.48)

    var p: Palette {
        var pal = Palette.byId(themeId)
        if followSystem {
            let dark = NSApp?.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            if dark && pal.light { pal = .mono }
            if !dark && !pal.light { pal = Palette.byId("snow") }
        }
        if pureBlack && !pal.light {
            pal = Palette(pal.id, pal.name, false, ["000000", "000000"] + [pal.surf, pal.surf2, pal.surf3, pal.surf4, pal.line, pal.line2, pal.line3, pal.txt, pal.acc, pal.t2, pal.t3, pal.t4].map(Theme.hexOf))
        }
        return pal
    }
    /// Accent for toggles / active items: album colour on Adaptive, the palette accent elsewhere.
    var accent: Color { themeId == "adaptive" ? glow : p.acc }

    static func hexOf(_ c: Color) -> String {
        let n = NSColor(c).usingColorSpace(.sRGB) ?? .black
        return String(format: "%02X%02X%02X", Int(n.redComponent * 255), Int(n.greenComponent * 255), Int(n.blueComponent * 255))
    }

    static let fonts: [(String, String)] = [("manrope", "Manrope"), ("inter", "Inter"), ("system", "System"), ("geist", "Geist"),
                                            ("spacegrotesk", "Space Grotesk"), ("dmsans", "DM Sans"), ("jakarta", "Plus Jakarta Sans")]

    func family(_ id: String? = nil) -> String? {
        switch id ?? fontId {
        case "inter": return "Inter"
        case "system": return nil
        case "geist": return "Geist"
        case "spacegrotesk": return "Space Grotesk"
        case "dmsans": return "DM Sans"
        case "jakarta": return "Plus Jakarta Sans"
        case "custom": return customFamily.isEmpty ? "Manrope" : customFamily
        default: return "Manrope"
        }
    }

    func ui(_ size: CGFloat, _ w: Font.Weight = .medium) -> Font {
        let s = size * textScale
        if let f = family() { return Font.custom(f, size: s).weight(w) }
        return .system(size: s, weight: w)
    }
    func num(_ size: CGFloat, _ w: Font.Weight = .regular) -> Font {
        let s = size * textScale
        if fontId == "custom" && customNumbers, let f = family() { return Font.custom(f, size: s).weight(w).monospacedDigit() }
        return Font.custom("JetBrains Mono", size: s).weight(w)
    }

    static func registerBundledFonts() {
        guard let dir = Bundle.main.resourceURL else { return }
        let urls = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        for u in urls where ["ttf", "otf"].contains(u.pathExtension.lowercased()) {
            CTFontManagerRegisterFontsForURL(u as CFURL, .process, nil)
        }
        if let custom = UserDefaults.standard.string(forKey: "customFontPath"), FileManager.default.fileExists(atPath: custom) {
            CTFontManagerRegisterFontsForURL(URL(fileURLWithPath: custom) as CFURL, .process, nil)
        }
    }

    /// Imports a .ttf/.otf into Application Support and returns its family name.
    func importFont(from url: URL) -> String? {
        let dir = Engine.supportDir.appendingPathComponent("fonts", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent("custom-\(Int(Date().timeIntervalSince1970)).\(url.pathExtension)")
        guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil,
              let descs = CTFontManagerCreateFontDescriptorsFromURL(dest as CFURL) as? [CTFontDescriptor],
              let first = descs.first,
              let fam = CTFontDescriptorCopyAttribute(first, kCTFontFamilyNameAttribute) as? String else { return nil }
        CTFontManagerRegisterFontsForURL(dest as CFURL, .process, nil)
        UserDefaults.standard.set(dest.path, forKey: "customFontPath")
        customFamily = fam
        fontId = "custom"
        return fam
    }
}

/// Springs used everywhere (respecting Reduce Motion).
enum Motion {
    static var reduce: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }
    static var spring: Animation { reduce ? .linear(duration: 0) : .spring(response: 0.42, dampingFraction: 0.82) }
    static var bouncy: Animation { reduce ? .linear(duration: 0) : .spring(response: 0.35, dampingFraction: 0.62) }
    static var soft: Animation { reduce ? .linear(duration: 0) : .spring(response: 0.6, dampingFraction: 0.9) }
    static var glow: Animation { reduce ? .linear(duration: 0) : .easeInOut(duration: 0.9) }
}

/// Press-scale button style.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.94
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !Motion.reduce ? scale : 1)
            .animation(Motion.bouncy, value: configuration.isPressed)
            .contentShape(Rectangle())
    }
}

/// Fade-and-rise entrance with a stagger index.
struct Enter: ViewModifier {
    let index: Int
    @State private var shown = Motion.reduce
    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 14)
            .onAppear {
                guard !shown else { return }
                withAnimation(Motion.spring.delay(Double(min(index, 14)) * 0.035)) { shown = true }
            }
    }
}

extension View {
    func enter(_ i: Int) -> some View { modifier(Enter(index: i)) }
}

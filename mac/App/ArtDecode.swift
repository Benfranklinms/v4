import AppKit
import CryptoKit
import CoreImage
import ImageIO
import SwiftUI

/// Off-main image decoding. Artwork is decoded with ImageIO thumbnails at the size it is shown
/// (× screen scale), already decompressed, so drawing never decodes full-resolution JPEGs on the main thread.
enum ArtDecode {
    struct Result: @unchecked Sendable {
        let image: NSImage
        let backdrop: NSImage?
        let glow: Color?
        let average: Color?
    }

    /// Downsampled, decoded CGImage (max side `maxPixels`).
    nonisolated static func thumbnail(_ data: Data, maxPixels: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return thumbnail(src, maxPixels: maxPixels)
    }
    nonisolated static func thumbnail(url: URL, maxPixels: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return thumbnail(src, maxPixels: maxPixels)
    }
    nonisolated private static func thumbnail(_ src: CGImageSource, maxPixels: Int) -> CGImage? {
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(16, maxPixels),
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }

    nonisolated static func nsImage(_ cg: CGImage, points: CGFloat? = nil) -> NSImage {
        let scale = max(1, CGFloat(cg.width) / (points ?? CGFloat(cg.width) / 2))
        return NSImage(cgImage: cg, size: NSSize(width: CGFloat(cg.width) / scale, height: CGFloat(cg.height) / scale))
    }

    /// Now-playing artwork: 1100 px max (fills the largest Now Playing art at 2×), a 96 px pre-blurred
    /// backdrop and the adaptive colours.
    nonisolated static func processed(_ data: Data) -> Result? {
        guard let cg = thumbnail(data, maxPixels: 1100) else { return nil }
        return processed(cg)
    }
    nonisolated static func processed(_ cg: CGImage) -> Result? {
        let small = scaled(cg, to: 16)
        return Result(image: nsImage(cg), backdrop: blurred(cg), glow: small.flatMap(Glow.vivid), average: small.flatMap(Glow.average))
    }

    nonisolated static func scaled(_ cg: CGImage, to side: Int) -> CGImage? {
        guard let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.interpolationQuality = .medium
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
        return ctx.makeImage()
    }

    private nonisolated(unsafe) static let ci = CIContext(options: [.useSoftwareRenderer: false, .cacheIntermediates: false])

    /// Small blurred + saturated copy rendered once; shown stretched (smooth because it's already blurred).
    nonisolated static func blurred(_ cg: CGImage) -> NSImage? {
        guard let small = scaled(cg, to: 96) else { return nil }
        let input = CIImage(cgImage: small)
        let out = input.clampedToExtent()
            .applyingGaussianBlur(sigma: 9)
            .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1.3])
            .cropped(to: input.extent)
        guard let r = ci.createCGImage(out, from: input.extent) else { return nil }
        return NSImage(cgImage: r, size: NSSize(width: 96, height: 96))
    }
}

/// Persistent artwork bytes on disk (Caches/iTune/art, keyed by URL hash): covers show instantly after a
/// relaunch. Trimmed to 96 MB, oldest first, at launch.
enum ArtDisk {
    nonisolated static let dir: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("iTune/art", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    nonisolated static func file(_ url: String) -> URL {
        let h = SHA256.hash(data: Data(url.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
        return dir.appendingPathComponent(h)
    }
    nonisolated static func read(_ url: String) -> Data? {
        let f = file(url)
        guard let d = try? Data(contentsOf: f) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: f.path)
        return d
    }
    nonisolated static func write(_ url: String, _ data: Data) { try? data.write(to: file(url), options: .atomic) }
    nonisolated static func trim(maxBytes: Int = 96 * 1024 * 1024) {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys) else { return }
        var items = files.compactMap { f -> (URL, Date, Int)? in
            guard let v = try? f.resourceValues(forKeys: Set(keys)) else { return nil }
            return (f, v.contentModificationDate ?? .distantPast, v.fileSize ?? 0)
        }
        var total = items.reduce(0) { $0 + $1.2 }
        guard total > maxBytes else { return }
        items.sort { $0.1 < $1.1 }
        for it in items where total > maxBytes { try? FileManager.default.removeItem(at: it.0); total -= it.2 }
    }
    nonisolated static func bytes() -> UInt64 {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(UInt64(0)) { $0 + UInt64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }
}

/// Image cache for artwork: one decode per (url, pixel size), in-flight requests shared.
@MainActor
final class ArtCache {
    static let shared = ArtCache()
    private let cache: NSCache<NSString, NSImage> = {
        let c = NSCache<NSString, NSImage>()
        c.totalCostLimit = 48 * 1024 * 1024  // decoded bytes
        return c
    }()
    private var inflight: [String: Task<NSImage?, Never>] = [:]

    /// Pixel bucket so nearby sizes share a decode.
    static func pixels(for points: CGFloat) -> Int {
        let px = Int(points * (NSScreen.main?.backingScaleFactor ?? 2))
        for b in [64, 96, 128, 192, 256, 384, 512, 768, 1100] where px <= b { return b }
        return 1100
    }
    private func key(_ url: String, _ px: Int) -> String { "\(px)|\(url)" }

    /// Warm the cache (first visible shelf, hovered tiles).
    func prefetch(_ urls: [String], points: CGFloat) {
        for u in urls where !u.isEmpty && cached(u, points: points) == nil { Task { _ = await image(u, points: points) } }
    }

    func cached(_ url: String, points: CGFloat) -> NSImage? {
        cache.object(forKey: key(url, ArtCache.pixels(for: points)) as NSString)
    }

    func image(_ url: String, points: CGFloat = 512) async -> NSImage? {
        let px = ArtCache.pixels(for: points)
        let k = key(url, px)
        if let i = cache.object(forKey: k as NSString) { return i }
        if let t = inflight[k] { return await t.value }
        guard let u = URL(string: url) else { return nil }
        let t = Task.detached(priority: .utility) { () -> NSImage? in
            let cg: CGImage?
            if u.isFileURL { cg = ArtDecode.thumbnail(url: u, maxPixels: px) }
            else if let d = ArtDisk.read(url) { cg = ArtDecode.thumbnail(d, maxPixels: px) }
            else {
                guard let (d, _) = try? await URLSession.shared.data(from: u), !d.isEmpty else { return nil }
                ArtDisk.write(url, d)
                cg = ArtDecode.thumbnail(d, maxPixels: px)
            }
            return cg.map { ArtDecode.nsImage($0) }
        }
        inflight[k] = t
        let img = await t.value
        inflight[k] = nil
        if let img, let rep = img.representations.first {
            cache.setObject(img, forKey: k as NSString, cost: rep.pixelsWide * rep.pixelsHigh * 4)
        }
        return img
    }
}

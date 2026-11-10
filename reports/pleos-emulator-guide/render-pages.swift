import AppKit
import PDFKit

guard CommandLine.arguments.count == 3 else {
    fatalError("Usage: swift render-pages.swift input.pdf output-directory")
}

let inputURL = URL(fileURLWithPath: CommandLine.arguments[1])
let outputURL = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
guard let document = PDFDocument(url: inputURL) else {
    fatalError("Cannot open PDF")
}
try FileManager.default.createDirectory(at: outputURL, withIntermediateDirectories: true)
print("PAGES \(document.pageCount)")

for index in 0..<document.pageCount {
    guard let page = document.page(at: index) else { fatalError("Missing page \(index)") }
    let bounds = page.bounds(for: .mediaBox)
    let scale = 1.6
    let width = Int(bounds.width * scale)
    let height = Int(bounds.height * scale)
    guard let context = CGContext(
        data: nil, width: width, height: height,
        bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { fatalError("Cannot create page context") }
    context.setFillColor(NSColor.white.cgColor)
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.scaleBy(x: scale, y: scale)
    page.draw(with: .mediaBox, to: context)
    guard let image = context.makeImage(),
          let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else {
        fatalError("Cannot render page \(index)")
    }
    let file = outputURL.appendingPathComponent(String(format: "page-%02d.png", index + 1))
    try png.write(to: file)
    print("PAGE \(index + 1) \(Int(bounds.width))x\(Int(bounds.height))pt TEXT_CHARS \((page.string ?? "").count)")
}

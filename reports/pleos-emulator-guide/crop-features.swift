import AppKit

let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let output = root.appendingPathComponent("assets/features", isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)

let crops: [(String, String, CGRect)] = [
    ("04-driving-starter.png", "driving-speed.png", CGRect(x: 0, y: 0, width: 850, height: 80)),
    ("04-driving-starter.png", "driving-block.png", CGRect(x: 1200, y: 740, width: 1000, height: 110)),
    ("10-hvac-24-degrees.png", "climate.png", CGRect(x: 0, y: 1295, width: 2560, height: 145)),
    ("12-energy-native.png", "battery.png", CGRect(x: 0, y: 0, width: 850, height: 80)),
    ("14-door-open-native.png", "door.png", CGRect(x: 0, y: 0, width: 850, height: 1110)),
    ("21-post-restore-check.png", "frunk-warning.png", CGRect(x: 1325, y: 510, width: 760, height: 365))
]

for (source, destination, rectangle) in crops {
    let data = try Data(contentsOf: root.appendingPathComponent("assets/\(source)"))
    guard let image = NSBitmapImageRep(data: data)?.cgImage,
          let cropped = image.cropping(to: rectangle),
          let png = NSBitmapImageRep(cgImage: cropped).representation(using: .png, properties: [:]) else {
        fatalError("Cannot crop \(source)")
    }
    try png.write(to: output.appendingPathComponent(destination))
    print("CROP \(destination)")
}

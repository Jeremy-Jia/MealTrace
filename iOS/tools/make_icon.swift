import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// Code-native vector artwork, rendered without a display's Retina scale.
let context = CGContext(data: nil, width: 1024, height: 1024, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
context.setFillColor(CGColor(red: 0.17, green: 0.36, blue: 0.29, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
context.setStrokeColor(CGColor(red: 0.95, green: 0.96, blue: 0.87, alpha: 1))
context.setLineWidth(26); context.strokeEllipse(in: CGRect(x: 180, y: 180, width: 664, height: 664))
context.setFillColor(CGColor(red: 0.72, green: 0.84, blue: 0.59, alpha: 1))
context.move(to: CGPoint(x: 360, y: 335))
context.addCurve(to: CGPoint(x: 685, y: 715), control1: CGPoint(x: 270, y: 650), control2: CGPoint(x: 535, y: 780))
context.addCurve(to: CGPoint(x: 360, y: 335), control1: CGPoint(x: 740, y: 435), control2: CGPoint(x: 575, y: 280))
context.fillPath()
context.setStrokeColor(CGColor(red: 0.17, green: 0.36, blue: 0.29, alpha: 1))
context.setLineWidth(23); context.setLineCap(.round)
context.move(to: CGPoint(x: 350, y: 318)); context.addLine(to: CGPoint(x: 590, y: 610)); context.strokePath()
let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: CommandLine.arguments[1]) as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(destination, context.makeImage()!, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Cannot write icon") }

import UIKit
import shared
import CoreGraphics

final class CGContextCanvasAdapter: NSObject, KLineCanvasAdapter {
    private let ctx: CGContext
    private let scale: Double

    init(context: CGContext, scale: Double) {
        self.ctx = context
        self.scale = scale > 0 ? scale : 1
        super.init()
        UIGraphicsPushContext(context)
        // 共享绘制内核输出物理像素坐标（与 Android Canvas 的像素坐标系一致），
        // 而 UIView.draw(_:) 传入的 CGContext 用户空间单位是 point，故按屏幕缩放系数换算。
        ctx.saveGState()
        ctx.scaleBy(x: CGFloat(1.0 / self.scale), y: CGFloat(1.0 / self.scale))
    }

    deinit {
        ctx.restoreGState()
        UIGraphicsPopContext()
    }

    private func applyDash(_ dash: [KotlinDouble], width: Double) {
        if dash.isEmpty {
            ctx.setLineDash(phase: 0, lengths: [])
            return
        }
        let lengths = dash.map { CGFloat(truncating: $0) }
        ctx.setLineDash(phase: 0, lengths: lengths)
    }

    private func color(from hex: String) -> UIColor {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        var rgb: UInt64 = 0
        Scanner(string: s).scanHexInt64(&rgb)
        switch s.count {
        case 6:
            let r = CGFloat((rgb & 0xFF0000) >> 16) / 255.0
            let g = CGFloat((rgb & 0x00FF00) >> 8) / 255.0
            let b = CGFloat(rgb & 0x0000FF) / 255.0
            return UIColor(red: r, green: g, blue: b, alpha: 1.0)
        case 8:
            let r = CGFloat((rgb & 0xFF000000) >> 24) / 255.0
            let g = CGFloat((rgb & 0x00FF0000) >> 16) / 255.0
            let b = CGFloat((rgb & 0x0000FF00) >> 8) / 255.0
            let a = CGFloat(rgb & 0x000000FF) / 255.0
            return UIColor(red: r, green: g, blue: b, alpha: a)
        default:
            return .gray
        }
    }

    func withSave(block: () -> Void) {
        ctx.saveGState()
        block()
        ctx.restoreGState()
    }

    func drawLine(startX: Double, startY: Double, endX: Double, endY: Double,
                  color: String, width: Double, dash: [KotlinDouble]) {
        ctx.setStrokeColor(self.color(from: color).cgColor)
        ctx.setLineWidth(max(CGFloat(width), 0.5))
        applyDash(dash, width: width)
        ctx.beginPath()
        ctx.move(to: CGPoint(x: CGFloat(startX), y: CGFloat(startY)))
        ctx.addLine(to: CGPoint(x: CGFloat(endX), y: CGFloat(endY)))
        ctx.strokePath()
    }

    func drawRect(left: Double, top: Double, right: Double, bottom: Double,
                  color: String, strokeColor: String?, strokeWidth: Double, cornerRadius: Double) {
        let rect = CGRect(x: CGFloat(left), y: CGFloat(top),
                          width: CGFloat(max(right - left, 0)), height: CGFloat(max(bottom - top, 0)))
        let path: CGPath
        if cornerRadius > 0 {
            path = CGPath(roundedRect: rect,
                          cornerWidth: CGFloat(cornerRadius),
                          cornerHeight: CGFloat(cornerRadius),
                          transform: nil)
        } else {
            path = CGPath(rect: rect, transform: nil)
        }
        ctx.addPath(path)
        ctx.setFillColor(self.color(from: color).cgColor)
        ctx.drawPath(using: .fill)
        if let strokeColor = strokeColor, strokeWidth > 0 {
            ctx.addPath(path)
            ctx.setStrokeColor(self.color(from: strokeColor).cgColor)
            ctx.setLineWidth(CGFloat(strokeWidth))
            ctx.drawPath(using: .stroke)
        }
    }

    func drawPolyline(
        points: [KotlinPair<KotlinDouble, KotlinDouble>],
        color: String,
        width: Double,
        dash: [KotlinDouble]
    ) {
        guard points.count >= 2 else { return }
        ctx.setStrokeColor(self.color(from: color).cgColor)
        ctx.setLineWidth(max(CGFloat(width), 0.5))
        applyDash(dash, width: width)
        ctx.beginPath()
        for (idx, pair) in points.enumerated() {
            guard let first = pair.first, let second = pair.second else { continue }
            let p = CGPoint(
                x: CGFloat(truncating: first),
                y: CGFloat(truncating: second)
            )
            if idx == 0 { ctx.move(to: p) } else { ctx.addLine(to: p) }
        }
        ctx.strokePath()
    }

    func drawCircle(cx: Double, cy: Double, radius: Double, color: String) {
        let rect = CGRect(x: CGFloat(cx - radius), y: CGFloat(cy - radius),
                          width: CGFloat(radius * 2), height: CGFloat(radius * 2))
        ctx.addEllipse(in: rect)
        ctx.setFillColor(self.color(from: color).cgColor)
        ctx.drawPath(using: .fill)
    }

    func drawCandle(x: Double, openY: Double, highY: Double, lowY: Double, closeY: Double,
                    bodyWidth: Double, color: String, wickWidth: Double) {
        let col = self.color(from: color).cgColor
        ctx.setStrokeColor(col)
        ctx.setFillColor(col)
        ctx.setLineWidth(max(CGFloat(wickWidth), 0.5))
        // wick
        ctx.beginPath()
        ctx.move(to: CGPoint(x: CGFloat(x), y: CGFloat(highY)))
        ctx.addLine(to: CGPoint(x: CGFloat(x), y: CGFloat(lowY)))
        ctx.strokePath()
        // body
        let half = CGFloat(bodyWidth / 2)
        let top = min(CGFloat(openY), CGFloat(closeY))
        var bottom = max(CGFloat(openY), CGFloat(closeY))
        if bottom - top < 1 { bottom = top + 1 }
        let body = CGRect(x: CGFloat(x) - half, y: top,
                          width: half * 2, height: bottom - top)
        ctx.fill(body)
    }

    func drawText(text: String, left: Double, top: Double, right: Double, bottom: Double,
                  color: String, textSize: Double) {
        let col = self.color(from: color)
        let font = UIFont.systemFont(ofSize: CGFloat(max(textSize, 8)))
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: col,
        ]
        let availW = max(CGFloat(right - left), 0)
        let availH = max(CGFloat(bottom - top), 0)
        let ns = text as NSString
        let tsize = ns.size(withAttributes: attributes)
        let drawRect = CGRect(
            x: CGFloat(left) + max(availW - tsize.width, 0),
            y: CGFloat(top) + (availH - tsize.height) / 2,
            width: min(tsize.width, availW),
            height: min(tsize.height, availH)
        )
        ns.draw(in: drawRect, withAttributes: attributes)
    }
}

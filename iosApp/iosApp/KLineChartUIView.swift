import SwiftUI
import UIKit
import shared

struct KLineChartViewRepresentable: UIViewRepresentable {
    func makeUIView(context: Context) -> KLineChartUIView {
        KLineChartUIView(frame: .zero)
    }

    func updateUIView(_ uiView: KLineChartUIView, context: Context) {}
}

class KLineChartUIView: UIView {
    private let handle = KLineDemoPageFactory.shared.createBasicPage()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        contentMode = .redraw
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit { handle.dispose() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = Int(bounds.width * contentScaleFactor)
        let h = Int(bounds.height * contentScaleFactor)
        handle.setSize(width: Int32(w), height: Int32(h))
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let adapter = CGContextCanvasAdapter(context: ctx, scale: Double(contentScaleFactor))
        handle.draw(adapter: adapter)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        handle.onDown(x: Double(p.x) * Double(contentScaleFactor),
                      y: Double(p.y) * Double(contentScaleFactor))
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        let scale = event?.allTouches?.count ?? 1 >= 2 ? 1.0 : 1.0
        handle.onMove(x: Double(p.x) * Double(contentScaleFactor),
                      y: Double(p.y) * Double(contentScaleFactor),
                      scaleFactor: scale,
                      pointerCount: Int32(event?.allTouches?.count ?? 1))
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        handle.onUp(x: Double(p.x) * Double(contentScaleFactor),
                    y: Double(p.y) * Double(contentScaleFactor))
        setNeedsDisplay()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        handle.onCancel(x: Double(p.x) * Double(contentScaleFactor),
                        y: Double(p.y) * Double(contentScaleFactor))
        setNeedsDisplay()
    }
}

import UIKit
import shared
import OpenKuiklyIOSRender

/**
 * Kuikly iOS 自定义原生视图：K线图。
 *
 * Kuikly iOS 通过 NSClassFromString(viewName) 按类名发现原生视图，
 * 故本类必须以 `@objc(KRKLineChart)` 暴露精确类名，
 * 与 Kotlin 侧 `KLineChartView.VIEW_NAME = "KRKLineChart"` 对齐
 * （对齐 Android `AndroidKLineChartView` 的行为）。
 *
 * 本类只负责：协议实现（属性/方法/复用）、CSS 通用属性、手势采集与绘制；
 * 全部业务逻辑在 shared 框架导出的 `KLCChartBridge`（Kotlin Mediator，
 * 复用 commonMain 完整 K线内核）中完成。
 */
@objc(KRKLineChart)
final class KRKLineChart: UIView, KuiklyRenderViewExportProtocol, UIGestureRecognizerDelegate {

    // MARK: - 状态

    private var bridge: KLCChartBridge?
    private var eventCallbacks: [String: KuiklyRenderCallback] = [:]

    /// 手势辅助
    private var touchDownPoint: CGPoint = .zero
    private var lastPinchScale: Double = 1.0
    private var isPinching = false
    private var pinchConsumedTouches = false
    private var longPressFired = false
    private var pressedPaneHeaderId: String?
    private var horizontalGesture = false
    private let gestureThreshold: CGFloat = 10

    // MARK: - 初始化

    override init(frame: CGRect) {
        super.init(frame: frame)
        commonInit()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        backgroundColor = .white
        contentMode = .redraw
        isMultipleTouchEnabled = true

        bridge = KLCChartBridge(
            onInvalidate: { [weak self] in
                self?.setNeedsDisplay()
            },
            onEvent: { [weak self] event, params in
                guard let self = self else { return }
                self.eventCallbacks[event]?(params)
            })

        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        pinch.cancelsTouchesInView = false
        pinch.delegate = self
        addGestureRecognizer(pinch)
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.delegate = self
        addGestureRecognizer(doubleTap)
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        singleTap.require(toFail: doubleTap)
        singleTap.delegate = self
        addGestureRecognizer(singleTap)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.4
        longPress.cancelsTouchesInView = false
        longPress.delegate = self
        addGestureRecognizer(longPress)
    }

    deinit {
        bridge?.viewDidDisappear()
    }

    // MARK: - 布局与绘制

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = Double(contentScaleFactor)
        bridge?.displayScale = scale
        let w = Int32(bounds.width * contentScaleFactor)
        let h = Int32(bounds.height * contentScaleFactor)
        bridge?.onSizeChanged(width: w, height: h)
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let adapter = CGContextCanvasAdapter(context: ctx, scale: Double(contentScaleFactor))
        bridge?.draw(canvas: adapter)
    }

    // MARK: - KuiklyRenderViewExportProtocol

    /// `KuiklyRenderCallback` 是 ObjC block（`void(^)(id)`）。Swift 的条件转换 `as?` 不支持把
    /// 未定型的 block 桥接成闭包类型，恒返回 nil，会导致所有事件静默绑不上，因此改为先按 NSBlock
    /// 类族判定，再以 `@convention(block)` 重解释指针。
    private static func asRenderCallback(_ value: Any) -> KuiklyRenderCallback? {
        var cls: AnyClass? = object_getClass(value as AnyObject)
        while let current = cls {
            if String(cString: class_getName(current)) == "NSBlock" {
                typealias CallbackBlock = @convention(block) (Any?) -> Void
                let block = unsafeBitCast(value as AnyObject, to: CallbackBlock.self)
                return { params in block(params) }
            }
            cls = class_getSuperclass(current)
        }
        return nil
    }

    func hrv_setProp(withKey propKey: String, propValue: Any) {
        // KUIKLY_SET_CSS_COMMON_PROP 宏的 Swift 等价实现：通用 CSS 属性由基类处理
        if css_setProp(withKey: propKey, value: propValue) {
            return
        }

        // 事件绑定：propValue 为 KuiklyRenderCallback block
        if let callback = Self.asRenderCallback(propValue) {
            eventCallbacks[propKey] = callback
            bridge?.onEventCallbackBound(event: propKey)
            return
        }

        // 普通属性：统一转字符串后交给 Mediator
        let value: String
        if let str = propValue as? String {
            value = str
        } else if let num = propValue as? NSNumber {
            value = num.stringValue
        } else {
            value = "\(propValue)"
        }
        _ = bridge?.setProp(propKey: propKey, propValue: value)
    }

    func hrv_call(withMethod method: String, params: String?, callback: KuiklyRenderCallback?) {
        guard let bridge = bridge else { return }
        let result = bridge.callMethod(method: method, params: params)
        if let result = result {
            callback?(result)
        }
    }

    func hrv_prepareForeReuse() {
        css_reset()
        eventCallbacks.removeAll()
        bridge?.viewDidDisappear()
    }

    // MARK: - 生命周期（Kuikly 视图容器切换）

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            bridge?.viewDidDisappear()
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            let w = Int32(bounds.width * contentScaleFactor)
            let h = Int32(bounds.height * contentScaleFactor)
            bridge?.viewWillAppear(width: w, height: h)
        }
    }

    // MARK: - 手势（对齐 Android：tap/pan/pinch/longPress/secondary/cancel）

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesBegan(touches, with: event)
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        let s = Double(contentScaleFactor)
        touchDownPoint = p
        longPressFired = false
        horizontalGesture = false
        pressedPaneHeaderId = bridge?.paneHeaderAt(x: Double(p.x) * s, y: Double(p.y) * s)
        if event?.allTouches?.count ?? 1 < 2 {
            pinchConsumedTouches = false
            bridge?.pointerDown(x: Double(p.x) * s, y: Double(p.y) * s)
        }
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesMoved(touches, with: event)
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        let s = Double(contentScaleFactor)
        let count = Int32(event?.allTouches?.count ?? 1)
        if count >= 2 || isPinching { return }
        let dx = abs(p.x - touchDownPoint.x)
        let dy = abs(p.y - touchDownPoint.y)
        if count == 1, max(dx, dy) > gestureThreshold {
            if dy > dx {
                pressedPaneHeaderId = nil
                bridge?.pointerCancel(x: Double(p.x) * s, y: Double(p.y) * s)
                return
            }
            horizontalGesture = true
            pressedPaneHeaderId = nil
        }
        bridge?.pointerMove(x: Double(p.x) * s, y: Double(p.y) * s,
                            scaleFactor: 1.0, pointerCount: count)
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesEnded(touches, with: event)
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        let s = Double(contentScaleFactor)
        if pinchConsumedTouches {
            if (event?.allTouches?.count ?? 0) <= touches.count {
                pinchConsumedTouches = false
            }
            return
        }
        bridge?.pointerUp(x: Double(p.x) * s, y: Double(p.y) * s)
        pressedPaneHeaderId = nil
        lastPinchScale = 1.0
        setNeedsDisplay()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesCancelled(touches, with: event)
        guard let touch = touches.first else { return }
        let p = touch.location(in: self)
        let s = Double(contentScaleFactor)
        bridge?.pointerCancel(x: Double(p.x) * s, y: Double(p.y) * s)
        lastPinchScale = 1.0
        setNeedsDisplay()
    }

    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        let p = gesture.location(in: self)
        let s = Double(contentScaleFactor)
        switch gesture.state {
        case .began:
            isPinching = true
            pinchConsumedTouches = true
            lastPinchScale = 1.0
            pressedPaneHeaderId = nil
            bridge?.pointerCancel(x: Double(p.x) * s, y: Double(p.y) * s)
            bridge?.pointerSecondaryDown(x: Double(p.x) * s, y: Double(p.y) * s)
        case .changed:
            lastPinchScale = Double(gesture.scale)
            bridge?.pointerMove(x: Double(p.x) * s, y: Double(p.y) * s,
                                scaleFactor: lastPinchScale, pointerCount: 2)
        case .ended:
            bridge?.pointerUp(x: Double(p.x) * s, y: Double(p.y) * s)
            isPinching = false
            lastPinchScale = 1.0
        case .cancelled, .failed:
            bridge?.pointerCancel(x: Double(p.x) * s, y: Double(p.y) * s)
            isPinching = false
            lastPinchScale = 1.0
        default:
            break
        }
        setNeedsDisplay()
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard !longPressFired, !isPinching else { return }
        let p = gesture.location(in: self)
        let s = Double(contentScaleFactor)
        if let paneId = bridge?.paneHeaderAt(x: Double(p.x) * s, y: Double(p.y) * s) {
            eventCallbacks["onPaneHeaderClick"]?(["paneId": paneId])
        } else {
            bridge?.pointerTap(x: Double(p.x) * s, y: Double(p.y) * s)
        }
        setNeedsDisplay()
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        bridge?.resetViewport()
        setNeedsDisplay()
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // Pinch/long-press belong to the chart. A surrounding vertical UIScrollView may
        // continue to recognize its pan until the chart establishes a horizontal drag.
        return otherGestureRecognizer.view is UIScrollView && !horizontalGesture
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        let p = gesture.location(in: self)
        let s = Double(contentScaleFactor)
        longPressFired = true
        bridge?.pointerLongPress(x: Double(p.x) * s, y: Double(p.y) * s)
        setNeedsDisplay()
    }
}

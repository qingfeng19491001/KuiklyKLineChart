# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Cross-platform host contract locked to `KLineChartView` prop/method/event/overlay-template constants, with Android-gold fixtures for density/pane layout, `KLinePointerEvent` gestures, load/realtime/crosshair/overlay/signal/FULL·COMPACT, and attach/detach restore. Public overlay names (`HORIZONTAL_LINE`) resolve to the engine registry.
- `KLineGestureArbitrator` lives in the library Host; Android / iOS / OHOS shells only collect raw touches and map CHART/PARENT onto platform intercept APIs.
- Map-style four artifacts (`KuiklyKLineChart` / Android / iOS / Ohos), unified `KLinePlatformHost`, non-SNAPSHOT coordinates, `publish-maven.sh`, and `MinimalLibrarySample`.
- Community Maven on GitHub Pages (`io.github.qingfeng19491001`) so other projects can depend without credentials, before official `com.tencent.kuiklybase` adoption.

### Fixed

- Long-press crosshair never activated on any host: `pointerDown` already started a `PANNING` session, and `showCrosshair` rejected non-IDLE/CROSSHAIR states. `KLineInteractionEngine` now cancels preemptable viewport sessions before taking over with CROSSHAIR; covered by `longPressPreemptsThePanThatTheSameTouchAlreadyStarted`.

### Removed

- Acceptance-only A/B scaffolding: `renderer`/`perfEnabled` props, `KLineABTest` page, Android `KLineRenderPerformanceTracker`, OHOS `KLinePerfTracker`, and host `batchedRenderEnabled` toggle (library still keeps `KLineCanvasRenderer.renderBatched` + unit tests).
- Dead iOS `KRKLineChartShadow` / `hrv_createShadow` experiment (shadow measure never participated in flex layout).

### Added

- Public Kuikly View bridges for pane state/order, indicator CRUD, overlay CRUD, history loading, and JSON state export/restore.
- Data-driven `KLineChart(dataSource, controller)` binding, full lifecycle events, and thread-safe one-shot binding registry.
- Publishable Android Native View and `registerKuiklyKLineChart()` registration helper inside the component AAR.
- Full chart controls, double-subchart layout, adaptive axes, current/high/low price annotations, compact and AI signal showcases.
- iOS and HarmonyOS Kuikly host project skeletons.
- FULL/COMPACT shared-engine presets, semantic AI signals, Kuikly JSON bars/signals props and signal-click event.
- Kuikly Router showcases for full charts, compact cards and signal-overlay interpretation.

- Core rendering engine: immutable `PersistentKLineBarList` with O(log N) prepend, append, replaceRange and timestamp-based binary search.
- State center: `KLineStore` with per-subsystem revision counters and observer hooks.
- Controller API: long-lived `KLineChartController` with command pipe (pan/zoom/switch-panes/add-indicator/add-overlay/export-state).
- Dynamic pane system: weighted layout, maximized/minimized per pane, separator-based resizing.
- Indicator extension: template+instance model with built-in MA, BOLL, EXPMA, BBI, ENE, VOL, AMOUNT, MACD, KDJ, RSI, WR, BBD.
- Overlay extension: template+instance model with magnet吸附, built-in horizontal/vertical line, segment, trend line, ray, price line, parallel lines, price channel, fibonacci retracement, text annotation, freehand.
- Interaction state machine with explicit priority: overlay control point > overlay figure > crosshair > pane separator > scale > pan > click.
- Rendering pipeline: `KLineRenderPlanner` produces immutable `KLineRenderPlan`; layered renderers for grid, candles, indicators, overlays, axis, crosshair, tooltip.
- `KLineChartEngine` public Facade: pointer dispatch, render plan flow, load before/after hooks.
- Shared demo module: `KLineChartView` DSL, `KLineCanvasAdapter` abstraction, RandomBarGenerator, BasicKLinePage.
- Android host shell: `MainActivity` with `AndroidKLineChartView` (GestureDetector + ScaleGestureDetector).
- iOS host shell: SwiftUI App, `KLineChartUIView` with ObjC-interop factory, `CGContextCanvasAdapter`.
- Gradle multi-module skeleton: `:KuiklyKLineChart` (KMP core), `:shared` (KMP demo layer), `:androidApp`, `:iosApp` hosts.

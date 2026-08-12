# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

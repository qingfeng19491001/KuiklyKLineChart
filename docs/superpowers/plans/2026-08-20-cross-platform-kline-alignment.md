# K 线图跨端对齐实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 iOS 和鸿蒙完整 K 线 demo 在视觉、API、事件和基础手势行为上对齐 Android 基准，并完成本机运行验收。

**Architecture:** 保持 commonMain K 线引擎为唯一业务实现，Android 宿主作为行为契约；iOS 和鸿蒙只在 Bridge、原生 View、Canvas primitive 绘制和手势采集层补齐差异。可移植的契约与手势判定下沉为可测试的 commonMain 逻辑，平台生命周期和安装启动通过集成构建与模拟器验证。

**Tech Stack:** Kotlin Multiplatform、Kuikly DSL、Kotlin/Native、Swift/UIKit/CoreGraphics、ArkTS/ArkUI Canvas、NAPI、Gradle、Xcode、hvigor/HDC。

---

### Task 1: 建立跨端 Bridge 契约测试

**Files:**
- Create: `shared/src/commonTest/kotlin/com/kuikly/kuiklyklinechart/shared/OhosKLineChartBridgeContractTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/kuikly/kuiklyklinechart/shared/OhosKLineChartBridge.kt`

- [ ] 写失败测试：逐项断言 Android 契约中的属性、方法和错误事件在鸿蒙 Bridge 可识别；断言含双 yAxes 的 config 能导出稳定 pane 状态。
- [ ] 运行 `./gradlew :shared:allTests --tests '*OhosKLineChartBridgeContractTest*'`，确认因 `bindingId` 被静默忽略或 yAxes 配置不完整而失败。
- [ ] 最小实现：补齐鸿蒙 Bridge 的属性分派、错误处理、yAxes 解析和状态事件，不改 commonMain 图表算法。
- [ ] 重跑定向测试和 `./gradlew :shared:allTests`，确认通过。

### Task 2: 对齐手势方向和多指状态机

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/kuiklyklinechart/shared/gesture/KLineGestureArbitrator.kt`
- Create: `shared/src/commonTest/kotlin/com/kuikly/kuiklyklinechart/shared/gesture/KLineGestureArbitratorTest.kt`
- Modify: `iosApp/iosApp/KRKLineChart.swift`
- Modify: `ohosApp/entry/src/main/ets/kuikly/components/KRKLineChart.ets`

- [ ] 写失败测试：覆盖阈值内保持 pending、横向占用、纵向交还父滚动、双指立即占用、long press 使用最新坐标和 cancel 后复位。
- [ ] 运行 `./gradlew :shared:allTests --tests '*KLineGestureArbitratorTest*'`，确认新行为尚不存在。
- [ ] 实现最小的纯逻辑仲裁器，并按同一状态转换修正 Swift/ArkTS 手势代码；iOS pinch 显式发送 secondaryDown/move/up，鸿蒙在第二指加入时重置 pinch 基准。
- [ ] 重跑手势测试，并分别编译 Swift 与 ArkTS 宿主。

### Task 3: 对齐 iOS Bridge 与绘制宿主

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/kuikly/kuiklyklinechart/shared/IOSKLineChartBridge.kt`
- Modify: `iosApp/iosApp/KRKLineChart.swift`
- Modify: `iosApp/iosApp/CGContextCanvasAdapter.swift`

- [ ] 用 Android `setKLineProp`、`call`、snapshot callback 清单逐项核对 iOS，实现缺失项并确保未知属性/方法交还 Kuikly 基类。
- [ ] 对照 Android Canvas adapter 校验 dash、text baseline、stroke/fill、candle 最小宽度、content scale 和 clipping。
- [ ] 运行 `./gradlew :shared:compileKotlinIosSimulatorArm64` 和 iOS simulator `xcodebuild`，修复所有编译问题。
- [ ] 启动 `FullChartDemo`，通过模拟器触摸注入和日志确认横拖、pinch、长按、pane 标题点击、十字线回调。

### Task 4: 对齐鸿蒙 Bridge、绘制和资源

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/kuikly/kuiklyklinechart/shared/OhosKLineChartBridge.kt`
- Modify: `ohosApp/entry/src/main/cpp/napi_init.cpp`
- Modify: `ohosApp/entry/src/main/cpp/types/libentry/index.d.ts`
- Modify: `ohosApp/entry/src/main/ets/kuikly/components/KRKLineChart.ets`
- Verify: `ohosApp/entry/src/main/resources/resfile/common/kuikly-logo.png`

- [ ] 对照 Android 属性/方法/事件清单补齐 NAPI 和 ArkTS 转发，并让未知方法返回明确错误事件。
- [ ] 校验 primitive 的层顺序、dash、stroke/fill、文字基线、candle 最小宽度、画布缩放和清屏行为。
- [ ] 确认 `shared/src/commonMain/assets/common/kuikly-logo.png` 与鸿蒙 `resfile/common` 内容一致。
- [ ] 运行 `./gradlew -c settings.ohos.gradle.kts :shared:linkDebugSharedOhosArm64` 和 hvigor HAP 构建。
- [ ] 安装启动完整 demo，通过触摸注入和日志确认横拖、pinch、长按、pane 标题点击、十字线回调。

### Task 5: 三端回归与证据归档

**Files:**
- Modify: `docs/handoff.md`
- Create: `docs/alignment-verification-2026-08-20.md`

- [ ] 运行 Android 基准测试与 debug 构建，确认本次跨端改动未破坏基准端。
- [ ] 重新执行 iOS 构建、安装、启动和截图，记录 simulator UDID、bundle、命令、日志和截图路径。
- [ ] 重新执行鸿蒙构建、安装、启动和截图，记录设备 ID、bundle、命令、日志和截图路径。
- [ ] 对照设计验收清单逐条标记“已验证/未验证/存在差异”，不得用构建成功代替运行验收。
- [ ] 运行 `git diff --check` 和所有相关测试，保存最终证据并更新交接说明。

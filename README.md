# KuiklyKLineChart

基于 Kuikly 跨端框架的专业 K 线（蜡烛图）组件，面向证券行情、加密货币行情等场景，使用统一渲染内核与平台 Canvas 适配。当前仓库提供 Android Kuikly DSL 页面和 Android/iOS Demo；HarmonyOS 与 Web 宿主仍在路线图中。

> 组件定位：只提供 K 线绘制和交互能力，**不绑定具体券商行情接口**。业务侧实现 `KLineDataSource` 即可接入。

## 1. Features

- 跨端一致性：KMP `commonMain` 编写核心引擎，已配置的 Android、iOS 和 JS 目标共用算法与状态机
- 高性能：不可变 AVL rope 数据结构，O(log N) 二分查找、追加、前插、区间替换
- 专业指标：内置 MA / BOLL / EXPMA / BBI / ENE / VOL / AMOUNT / MACD / KDJ / RSI / WR / BBD，可扩展自定义指标
- 画线系统：内置 11 种画线模板（水平/垂直/趋势线/射线/线段/平行线/价格通道/斐波那契/文本/手绘 等），支持磁铁吸附
- 手势交互：平移、双指缩放、长按十字光标、窗格拖拽、画线拖拽，优先级由状态机显式管理
- 主题与格式化：可变 DSL 配置，绘制前快照为不可变对象；自定义颜色、刻度文字、精度格式化
- 控制器 API：长期控制器代替一次性字段，可发送 pan/zoom、切换主图指标、增删副图、增删画线、导出状态

## 2. Project structure

```
KuiklyKLineChart/
├── KuiklyKLineChart/           # 核心模块（KMP），commonMain 写渲染内核
│   └── src/
│       ├── commonMain/         # 数据/状态/渲染/指标/画线/交互（纯算法）
│       └── commonTest/         # 核心逻辑单元测试
├── shared/                     # 跨端 Demo 层（KMP），平台 Canvas 适配器 + DSL 入口
│   └── src/
│       ├── commonMain/         # Kuikly @Page、Canvas adapter、平台无关 Demo scenes
│       ├── androidMain/        # AndroidKLineCanvasAdapter(android.graphics.Canvas)
│       └── iosMain/            # @ObjCName 导出工厂 + ObjC 互操作层
├── androidApp/                 # Android 宿主壳 Demo (Activity + View)
├── iosApp/                     # iOS 宿主壳 Demo (SwiftUI + UIView 桥接)
└── docs/
    ├── ROADMAP.md              # 里程碑 M1 ~ M4
    ├── staging/specs/          # 规格文档
    └── superpowers/plans/      # 执行计划
```

## 3. Quick start

构建环境：Android Studio Ladybug + Xcode 15+, Gradle 8.7, Kotlin 2.x.

### 3.1 Android

```shell
./gradlew :androidApp:assembleDebug
# adb install androidApp/build/outputs/apk/debug/*.apk
```

Android 首屏使用官方 Kuikly DSL 链路：`KuiklyBaseView` 承载 `@Page("KuiklyKLineDemo")`，页面通过 FlexBox、`Text`、`Button` 和 `KLineChart` 自定义 View 组成。宿主使用 `registerExternalRenderView` 将 `KRKLineChart` 注册到 `AndroidKLineChartView`。

### 3.2 iOS

iOS 示例目前使用 SwiftUI/UIView 宿主和 `KLineCanvasAdapter` 桥接公共引擎。先由实际 Xcode 工程集成 `shared` CocoaPods framework，再编译 `iosApp/iosApp` 下的 Swift 源码；仓库当前未提交 `.xcodeproj`。

> `shared` 和核心引擎使用 `kotlinx.coroutines`，适用于内置 KMP 模式，不支持 Kuikly 动态化模式。动态化接入应将异步数据能力封装到 Module，并在 Kuikly 线程更新 UI。

## 4. Core concepts

- [数据源协议](#41-数据源协议)
- [状态中心 KLineStore](#42-状态中心)
- [控制器 API](#43-控制器-api)
- [动态窗格](#44-动态窗格)
- [指标扩展](#45-指标扩展)
- [画线 Overlay](#46-画线-overlay)
- [交互状态机](#47-交互状态机)
- [渲染管线](#48-渲染管线)

### 4.1 数据源协议

实现 `KLineDataSource`：

```kotlin
interface KLineDataSource {
    val security: KLineMarket
    suspend fun initial(): Result<List<KLineBar>>
    suspend fun before(firstTimestamp: Long): Result<List<KLineBar>>
    suspend fun after(lastTimestamp: Long): Result<List<KLineBar>>
}
```

组件内部会自动做排序、时间戳去重与数值合法性校验。

### 4.2 状态中心

`KLineStore` 以快照方式暴露状态：行情 bars、视口、窗格、指标实例、画线实例、交互会话、光标、错误。每个子系统维护独立的 revision counter，便于渲染层只在必要时重算。

### 4.3 控制器 API

```kotlin
val controller = KLineChartController()
controller.send(KLineControllerCommand.Pan(deltaBars = 20))
controller.send(KLineControllerCommand.ZoomAt(factor = 1.2))
controller.send(KLineControllerCommand.ToggleIndicator("MA"))
val state: KLineChartState = controller.exportState()
```

### 4.4 动态窗格

不写死"主图 + 两个副图"。底层使用 `List<KLinePane>` 建模，支持权重分配、最小高度约束、`MAXIMIZED / MINIMIZED / NORMAL` 三态，以及分隔线拖拽缩放。

### 4.5 指标扩展

指标分为**模板**（算法）和**实例**（配置 + 缓存）。

```kotlin
object MyIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "MY"
    override fun parameters(): Map<String, Double> = mapOf("N" to 12.0)
    override fun lines(): List<KLineIndicatorLineSpec> = listOf(
        KLineIndicatorLineSpec("my", "#FF9800", KLineIndicatorFigureType.LINE),
    )
    // ... incremental calculation
}
KLineExtensionRegistry.default().register(MyIndicator)
```

### 4.6 画线 Overlay

画线同样是模板 + 实例。`KLineOverlayPoint` 用 (timestamp, value) 存储，不会因视口缩放、历史前插导致位置漂移。支持 3 种磁铁吸附模式：`NONE / TIMESTAMP / CLOSE`。

### 4.7 交互状态机

同一时刻处于 1 个会话中：IDLE / PANNING / SCALING / CROSSHAIR / DRAWING_OVERLAY / DRAGGING_OVERLAY / DRAGGING_OVERLAY_POINT / RESIZING_PANE。手势优先级由 `KLineInteractionPriorityResolver` 显式声明：

```
overlay control point > overlay figure > crosshair > pane separator > scale > pan > click
```

### 4.8 渲染管线

```
KLineStoreSnapshot
       ↓ (KLineRenderPlanner)
KLineRenderPlan (不可变：可见 bars、各 pane 布局、各指标系列、各 overlay 几何、axis ticks、crosshair、tooltip)
       ↓ (KLineRenderPipeline：按层产出 primitives)
List<KLineDrawingPrimitive> (Line/Rect/Circle/Candle/Polyline/Text)
       ↓ (平台 Canvas Adapter；仓库已实现 Android / iOS，Kuikly Canvas adapter 可供其他宿主接入)
像素绘制
```

业务侧可通过实现 `KLineCanvasAdapter` 复用同一渲染计划。平台适配器必须遵守统一的 `#RRGGBBAA` 颜色、圆角、文本基线和像素密度契约。

## 5. Tests

核心模块 commonTest 已覆盖：

| 子系统 | 测试文件 |
|---|---|
| 持久化 K 线列表 | `PersistentKLineBarListTest.kt` |
| Store | `KLineStoreTest.kt`, `KLineStoreBuiltInPerformanceTest.kt` |
| 视口交互 | `KLineViewportTest.kt`, `KLineViewportInteractionEngineTest.kt` |
| 交互引擎 | `KLineInteractionEngineTest.kt`, `KLineOverlayInteractionEngineTest.kt`, `KLineOverlayHitTesterTest.kt`, `KLinePaneResizeEngineTest.kt`, `KLineMagnetResolverTest.kt` |
| 指标引擎 | `KLineIndicatorEngineTest.kt`, `PersistentIndicatorListTest.kt`, `KLineBuiltInIncrementalAccessTest.kt` |
| Overlay | `KLineOverlayEngineTest.kt` |
| 格式化 | `KLineFormatterTest.kt` |
| 主题 | `KLineThemeTest.kt` |
| 渲染 | `KLineRenderTest.kt` |
| 数据会话 | `KLineDataSessionTest.kt` |
| 控制器 | `KLineChartControllerTest.kt` |

```shell
./gradlew :KuiklyKLineChart:allTests
```

## 6. Roadmap

详见 [docs/ROADMAP.md](docs/ROADMAP.md)。四个里程碑：

1. **M1 Core Readiness**：Kuikly DSL 主入口、Android 官方 Page 宿主、iOS Canvas Demo 源码、核心引擎编译通过
2. **M2 Cross-platform + Docs**：文档齐备、HarmonyOS 目标 + 宿主
3. **M3 Demo Completion**：Router 首页、独立功能页、Toolbar UI、跨平台视觉一致性校验
4. **M4 Performance + Stability**：大数量（10k/100k bars）基准测试、绘制缓存、增量重绘

## 7. License

MIT — 见 [LICENSE](LICENSE)；第三方依赖版权见 [NOTICE](NOTICE)。

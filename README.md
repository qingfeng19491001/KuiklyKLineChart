<!-- cspell:ignore Kuikly KuiklyKLineChart kuiklybase EXPMA MACD BRAR -->
# KuiklyKLineChart

面向 `Kuikly` 的跨端 K 线组件（扩展原生 View）。KMP 内核提供声明式 DSL 与 `KLineChartController`，Android / iOS / 鸿蒙各自提供名为 `KRKLineChart` 的原生扩展 View。

![KuiklyKLineChart preview](docs/preview.gif)

## 能力

- 开箱即用：`KLineChart(dataSource, controller)` 绑定数据源，`Kuikly` DSL 写属性与事件。
- 跨端同一套内核：Android AAR、iOS CocoaPods、鸿蒙路径依赖，三端只认 `KLineChartView` 的 prop / method / event。
- 主图样式：`candle` / `candle_hollow` / `candle_up_stroke` / `candle_down_stroke` / `ohlc` / `line` / `area`。
- 内置指标：`MA`、`SMA`、`EMA`、`EXPMA`、`BBI`、`ENE`、`BOLL`、`SAR`、`VOL`、`AMOUNT`、`OBV`、`MACD`、`KDJ`、`RSI`、`WR`、`BBD`、`CCI`、`DMI`、`BIAS`、`ROC`、`BRAR`、`CR`、`DMA`、`EMV`、`MTM`、`PSY`、`TRIX`、`VR`、`AO`、`PVT`、`AVP`。
- 画线：水平/垂直直线、射线、线段，趋势线、直线、射线、价格线、价格标签、平行线、价格通道、斐波那契回撤、文本、画笔；支持磁铁吸附、`groupId`、锁定与按组删除。
- 窗格：加权布局、最大化/最小化、分隔条拖拽；`full` / `compact` 两套预设。
- 交互：画线控制点 > 图形 > 十字光标 > 分隔条 > 缩放 > 平移 > 点击。
- 可扩展：`KLineExtensionRegistry` 注册自定义指标与画线模板；Store / Render / Interaction 内核类型不对外。

## 快速开始
先接一次组件：共享代码里加上 K 线依赖。Android 注册一次，iOS 装一个 pod，鸿蒙用 map.set 挂上同名原生 View。之后每个页面不用再注册。
页面中间放一个 KLineChart。mode("full") 是完整图，带坐标轴、副图和手势；compact 只留主图。priceStyle 只决定蜡烛、折线还是面积。均线、成交量、MACD 用 config 或 addIndicator 另挂。
K 线数据用 bars(json) 或 KLineDataSource 传进去。滑、捏、长按不用再写。周期按钮自己做，点了就换周期、画法和数据。要知道用户点了哪根、十字光标在哪，接 onBarClick 和 onCrosshairChange。


当前坐标在 GitHub Pages 公开 Maven。`com.tencent.kuiklybase` 是纳入官方仓之后的目标坐标，现网尚未发布。

| 层 | 当前可依赖 | 官方纳入后（尚未发布） |
| --- | --- | --- |
| KMP | `io.github.qingfeng19491001:kuiklyklinechart:0.1.0-2.1.21` | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.1.21` |
| KMP（鸿蒙工具链） | `./publish-maven.sh pages ohos-kmp` | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.0.21-KBA-010` |
| Android | `io.github.qingfeng19491001:kuiklyklinechartandroid:0.1.0-2.1.21` | `com.tencent.kuiklybase:KuiklyKLineChartAndroid:0.1.0-2.1.21` |
| iOS | CocoaPods `KuiklyKLineChartIOS`（git tag `0.1.0`） | 官方 pod 源 |
| OHOS | 本仓库 `KuiklyKLineChartOhos` 路径依赖 | ohpm `@kuiklybase/kuikly-kline-chart-ohos` |

### 1. 跨端侧

在业务 `commonMain` 增加依赖。鸿蒙工具链改用上表带 `KBA` 的坐标。

```kotlin
maven("https://qingfeng19491001.github.io/KuiklyKLineChart")
implementation("io.github.qingfeng19491001:kuiklyklinechart:0.1.0-2.1.21")
```

```kotlin
val controller = KLineChartController()
KLineChart(dataSource = stockDataSource, controller = controller) {
    attr {
        symbol("00700", "腾讯控股")
        period(1, "day") // minute、hour、day、week、month
        mode("full")
        theme("light")
        priceStyle("candle")
    }
    event {
        onVisibleRangeChange { start, end -> }
        onBarClick { timestamp, index -> }
        onCrosshairChange { timestamp, price -> }
        onError { code, message -> }
    }
}
```

没有 `KLineDataSource` 时，用 `bars(json)` 塞静态 K 线。`controller` 未挂上时命令会排队。

### 2. Android

```kotlin
implementation("io.github.qingfeng19491001:kuiklyklinechartandroid:0.1.0-2.1.21")

override fun registerExternalRenderView(export: IKuiklyRenderExport) {
    export.registerKuiklyKLineChart()
}
```

### 3. iOS

```ruby
pod "KuiklyKLineChartIOS", git: "https://github.com/qingfeng19491001/KuiklyKLineChart.git", tag: "0.1.0"
```

`KRKLineChart` 类名与 `viewName` 一致，运行时自动发现，无需手动注册。

### 4. 鸿蒙

```json5
"dependencies": {
  "@kuiklybase/kuikly-kline-chart-ohos": "file:../KuiklyKLineChartOhos"
}
```

```typescript
import { KRKLineChart } from '@kuiklybase/kuikly-kline-chart-ohos';

getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
  const map = new Map();
  map.set(KRKLineChart.VIEW_NAME, () => new KRKLineChart());
  return map;
}
```

Kotlin/Native 只导出业务 `shared` 里的公开类型。鸿蒙 NAPI 调用 `com.kuikly.kuiklyklinechart.shared.OhosKLineChartBridge`（对库内 `OhosKLineChartBridge` 的转发）。

三端图表背景透明，主题底色由内核画布绘制。页面退出时 Android `onDetachedFromWindow`、iOS `willMove(toWindow:)` / `deinit`、OHOS `aboutToDisappear` 会 `detach`/`dispose` Host。

## 使用

画法、指标、交互、画线都在上面的 `KLineChart` 上配，不用再引库或再注册。`mode("compact")` 只留主图（最多 60 根），关掉轴、副图、指标、画线和手势。

公开 API：`KLineChart`、`KLineChartController`、`KLineDataSource`、`KLineBar`、指标与 Overlay 配置、`KLineTheme`、`KLineChartMode`、`KLineSignal`。`density` 由原生壳注入一次。`KLineChartController` 与 `KLineChartView` 方法一一对应。

### 属性

| 属性 | 说明 |
| --- | --- |
| `symbol(ticker, name)` | 标的 |
| `period(value, unit)` | 周期 |
| `theme(name)` | `light` 或 `dark` |
| `mode(name)` | `full` 打开轴、副图、指标、画线、十字光标和手势；`compact` 仅主图 |
| `priceStyle(name)` | 主图画法，见下 |
| `config(json)` | 窗格与指标初始配置 |
| `bars(json)`、`signals(json)` | 静态 K 线快照、语义信号 |

### 画法

同一份开高低收，换 `priceStyle` 即可。

| 值 | 效果 |
| --- | --- |
| `candle` | 实心蜡烛（默认） |
| `candle_hollow` | 空心蜡烛 |
| `candle_up_stroke` | 上涨空心、下跌实心 |
| `candle_down_stroke` | 下跌空心、上涨实心 |
| `ohlc` | 美国线 |
| `line` | 收盘价折线 |
| `area` | 收盘价面积图 |

### 指标

先有窗格，再把指标挂上去。初始配置走 `config`：

```kotlin
attr {
    config("""
    {"panes":[
      {"id":"price","kind":"price","weight":3.0,"minHeight":120},
      {"id":"volume","kind":"indicator","weight":1.0,"minHeight":60}
    ],"indicators":[
      {"id":"ma","template":"MA","paneId":"price","params":[5,10,20,30]},
      {"id":"vol","template":"VOL","paneId":"volume","params":[]}
    ]}
    """.trimIndent())
}
```

运行中增删：

```kotlin
controller.addIndicator(
    KLineIndicatorInstance("macd", "MACD", "macdPane", listOf(12.0, 26.0, 9.0), precision = 2),
)
controller.removeIndicator("macd")
```

`View` 上对应 `addIndicator(json)`、`updateIndicator(json)`、`removeIndicator(id)`。主图指标的 `paneId` 用 `price`；量能和副图要单独的 `kind: indicator` 窗格。也可使用 `KLineBuiltInIndicators.MA` 等常量。自定义指标通过 `KLineExtensionRegistry.register`。

| 分类 | 模板名 |
| --- | --- |
| 主图 | `MA`、`SMA`、`EMA`、`EXPMA`、`BBI`、`ENE`、`BOLL`、`SAR` |
| 量能 | `VOL`、`AMOUNT`、`OBV`、`VR`、`PVT`、`AVP` |
| 副图 | `MACD`、`KDJ`、`RSI`、`WR`、`BBD`、`CCI`、`DMI`、`BIAS`、`ROC`、`BRAR`、`CR`、`DMA`、`EMV`、`MTM`、`PSY`、`TRIX`、`AO` |

### 交互

`mode("full")` 之后，横滑、双指缩放、长按十字光标、拖窗格分隔条由组件处理，不用再注册手势。嵌在可滚页面里时，横滑归图、竖滑归页面、双指归图。图内优先级：画线控制点、图形、十字光标、分隔条、缩放、平移、点击。

代码侧控制视口：

```kotlin
controller.scrollToLatest()
controller.scrollByBars(-20.0)
controller.zoom(1.2)
controller.resetViewport()
controller.clearCrosshair()
```

| 方法 | 作用 |
| --- | --- |
| `scrollToLatest`、`scrollToTimestamp`、`scrollByBars` | 滚动 |
| `zoom`、`zoomAtTimestamp`、`resetViewport` | 缩放与复位 |
| `setPane`、`removePane`、`movePane`、`setPaneState` | 窗格；state 为 `normal`、`minimized` 或 `maximized` |
| `loadBefore`、`loadAfter`、`retryInitialLoad` | 分页与重试 |
| `cancelInteraction`、`clearCrosshair` | 取消当前手势、清十字光标 |
| `exportState`、`restoreState` | 快照导入导出 |
| `setMarket`、`setTheme`、`setFormatters` | 标的、主题、格式化（Controller） |

| 事件 | 回调 |
| --- | --- |
| `onVisibleRangeChange` | 可见起止 index |
| `onBarClick` | timestamp、index |
| `onCrosshairChange` | timestamp、price |
| `onLoadStateChange` | initial、before、after |
| `onOverlayClick`、`onOverlayChange` | 选中 id、revision |
| `onPeriodChange`、`onIndicatorChange` | 周期、指标 id 列表 |
| `onPaneLayoutChange`、`onPaneHeaderClick` | 窗格布局、标题点击 |
| `onSignalClick` | 信号 id、title、summary |
| `onError` | code、message |

### 画线

画线不是默认手势。业务提供入口后调用 `beginOverlay`，用户再在图上点。模板名同时接受 DSL 常量（`HORIZONTAL_LINE`）与引擎名（`horizontal_line`）。

```kotlin
controller.beginOverlay(
    KLineChartView.OverlayTemplate.TREND_LINE,
    paneId = "price",
    magnetMode = KLineOverlayMagnetMode.STRONG,
    groupId = "draw-1",
    locked = false,
)
controller.deleteSelectedOverlay()
controller.removeOverlayGroup("draw-1")
controller.cancelInteraction()
```

| OverlayTemplate | 说明 |
| --- | --- |
| `HORIZONTAL_LINE`、`HORIZONTAL_RAY`、`HORIZONTAL_SEGMENT` | 水平直线、射线、线段 |
| `VERTICAL_LINE`、`VERTICAL_RAY`、`VERTICAL_SEGMENT` | 垂直直线、射线、线段 |
| `SEGMENT` | 线段 |
| `TREND_LINE`、`STRAIGHT_LINE`、`RAY` | 趋势线（无限）、直线、射线 |
| `PRICE_LINE`、`SIMPLE_TAG` | 价格线、价格标签 |
| `PARALLEL_LINES`、`PRICE_CHANNEL` | 平行线、价格通道 |
| `FIBONACCI_RETRACEMENT` | 斐波那契回撤 |
| `TEXT` | 文本标注 |
| `FREEHAND` | 画笔（别名 `brush`） |

磁铁：`none`、`weak`、`strong`。另有 `createOverlay`、`updateOverlay`、`removeOverlay`。

## Demo 与构建

本仓库 `shared` + `androidApp` / `iosApp` / `ohosApp` 仅作示例，接入方式与外部工程相同。

- `MinimalLibrarySample`：只使用 DSL `KLineChart`
- `FullChartDemo` / `CompactChartDemo` / `SignalOverlayDemo`
- `IndicatorSwitchPage` / `OverlayDrawPage`

```shell
./gradlew :KuiklyKLineChart:jsNodeTest :KuiklyKLineChartAndroid:assembleRelease :androidApp:assembleDebug
```

内核保留 `KLineCanvasRenderer.renderBatched`。经验基线（OHOS 热启动）：单帧 draw 约 0–3ms。验收以交互跟手、无持续掉帧为准。

## 发布

```shell
# Maven Local：KMP + Android AAR（默认 group 仍是 com.tencent.kuiklybase）
./publish-maven.sh local

# GitHub Pages 公开 Maven
./publish-maven.sh pages

# 鸿蒙工具链 KMP（settings.ohos.gradle.kts，版本带 KBA）
./publish-maven.sh pages ohos-kmp

# 其它远端（需 MAVEN_REPO_URL / MAVEN_USERNAME / MAVEN_PASSWORD）
./publish-maven.sh remote
```

iOS 走 CocoaPods git tag。鸿蒙在官方 ohpm 开通前使用仓库内 `KuiklyKLineChartOhos`。

## License

MIT，见 [LICENSE](LICENSE)。

# KuiklyKLineChart

面向 Kuikly 的跨端 K 线组件（扩展原生 View）。KMP 内核提供声明式 DSL 与 `KLineChartController`，Android / iOS / 鸿蒙各自提供名为 `KRKLineChart` 的原生扩展 View。

<img width="300" alt="KuiklyKLineChart preview" src="docs/preview.gif" />

## 能力

- 开箱即用：`KLineChart(dataSource, controller)` 绑定数据源，Kuikly DSL 写属性与事件。
- 跨端同一套内核：Android AAR、iOS CocoaPods、鸿蒙路径依赖，三端只认 `KLineChartView` 的 prop / method / event。
- 主图样式：`candle` / `candle_hollow` / `candle_up_stroke` / `candle_down_stroke` / `ohlc` / `line` / `area`。
- 内置指标：MA、SMA、EMA、EXPMA、BBI、ENE、BOLL、SAR、VOL、AMOUNT、OBV、MACD、KDJ、RSI、WR、BBD、CCI、DMI、BIAS、ROC、BRAR、CR、DMA、EMV、MTM、PSY、TRIX、VR、AO、PVT、AVP。
- 画线：水平/垂直直线、射线、线段，趋势线、直线、射线、价格线、价格标签、平行线、价格通道、斐波那契回撤、文本、画笔；支持磁铁吸附、`groupId`、锁定与按组删除。
- 窗格：加权布局、最大化/最小化、分隔条拖拽；`full` / `compact` 两套预设。
- 交互：画线控制点 > 图形 > 十字光标 > 分隔条 > 缩放 > 平移 > 点击。
- 可扩展：`KLineExtensionRegistry` 注册自定义指标与画线模板；Store / Render / Interaction 内核类型不对外。

## 使用

当前可解析坐标在 GitHub Pages 公开 Maven（无需账号密码）。`com.tencent.kuiklybase` 是纳入官方仓之后的目标坐标，现网尚未发布。

| 层 | 当前可依赖 | 官方纳入后（尚未发布） |
|----|------------|------------------------|
| KMP | `io.github.qingfeng19491001:kuiklyklinechart:0.1.0-2.1.21` | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.1.21` |
| KMP（鸿蒙工具链） | `./publish-maven.sh pages ohos-kmp` | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.0.21-KBA-010` |
| Android | `io.github.qingfeng19491001:kuiklyklinechartandroid:0.1.0-2.1.21` | `com.tencent.kuiklybase:KuiklyKLineChartAndroid:0.1.0-2.1.21` |
| iOS | CocoaPods `KuiklyKLineChartIOS`，`:tag => '0.1.0'` | 官方 pod 源 |
| OHOS | 本仓库 `KuiklyKLineChartOhos` 路径依赖 | ohpm `@kuiklybase/kuikly-kline-chart-ohos` |

```kotlin
maven("https://qingfeng19491001.github.io/KuiklyKLineChart")
implementation("io.github.qingfeng19491001:kuiklyklinechart:0.1.0-2.1.21")
implementation("io.github.qingfeng19491001:kuiklyklinechartandroid:0.1.0-2.1.21")
```

## 快速开始

```kotlin
val controller = KLineChartController()
KLineChart(dataSource = stockDataSource, controller = controller) {
    attr {
        symbol("00700", "腾讯控股")
        period(1, "day") // minute / hour / day / week / month
        mode("full")     // full | compact
        theme("light")   // light | dark
        priceStyle("candle")
    }
    event {
        onVisibleRangeChange { start, end -> }
        onBarClick { timestamp, index -> }
        onCrosshairChange { timestamp, price -> }
        onSignalClick { id, title, summary -> }
        onError { code, message -> }
    }
}
```

公开 API：`KLineChart` / `KLineChartController` / `KLineDataSource` 族 / `KLineBar` / 指标与 Overlay 配置 / `KLineTheme` / `KLineChartMode` / `KLineSignal` / `KLinePlatformHost`。加载重试通过 Host `call` 暴露，不直接暴露 Engine。

`density` 由原生壳注入一次；viewport / pane 在 `KLinePlatformHost` 按像素计算。手势统一为 `KLinePointerEvent`。

## 图表属性

| 属性 | 说明 |
|------|------|
| `symbol(ticker, name)` | 标的 |
| `period(value, unit)` | 周期 |
| `theme(name)` | `light` / `dark` |
| `mode(name)` | `full`：轴、副图、指标、画线、十字光标、tooltip、交互全开；`compact`：仅主图，最多 60 根 K 线 |
| `priceStyle(name)` | 见下表 |
| `config(json)` | 窗格 + 指标初始配置 |
| `bars(json)` / `signals(json)` | 静态 K 线快照 / 语义信号 |

**priceStyle**

| 值 | 效果 |
|----|------|
| `candle` | 实心蜡烛 |
| `candle_hollow` | 空心蜡烛 |
| `candle_up_stroke` | 上涨空心、下跌实心 |
| `candle_down_stroke` | 下跌空心、上涨实心 |
| `ohlc` | 美国线 |
| `line` | 收盘价折线 |
| `area` | 收盘价面积图 |

## 指标

通过 `controller.addIndicator` / `updateIndicator` / `removeIndicator`，或 `config` JSON：

```json
{
  "panes": [
    {"id":"price","kind":"price","weight":3.0,"minHeight":120},
    {"id":"volume","kind":"indicator","weight":1.0,"minHeight":60}
  ],
  "indicators": [
    {"id":"ma","template":"MA","paneId":"price","params":[5,10,20,30]},
    {"id":"vol","template":"VOL","paneId":"volume","params":[]}
  ]
}
```

| 分类 | 模板名 |
|------|--------|
| 主图 | `MA` `SMA` `EMA` `EXPMA` `BBI` `ENE` `BOLL` `SAR` |
| 量能 | `VOL` `AMOUNT` `OBV` `VR` `PVT` `AVP` |
| 副图 | `MACD` `KDJ` `RSI` `WR` `BBD` `CCI` `DMI` `BIAS` `ROC` `BRAR` `CR` `DMA` `EMV` `MTM` `PSY` `TRIX` `AO` |

也可使用 `KLineBuiltInIndicators.MA` 等常量。自定义指标通过 `KLineExtensionRegistry.register`。

## 画线

模板名同时接受 DSL 常量（`HORIZONTAL_LINE`）与引擎名（`horizontal_line`）。

```kotlin
controller.beginOverlay(
    KLineChartView.OverlayTemplate.TREND_LINE,
    paneId = "price",
    magnetMode = KLineOverlayMagnetMode.STRONG,
    groupId = "draw-1",
    locked = false,
)
controller.removeOverlayGroup("draw-1")
```

| `OverlayTemplate` | 说明 |
|-------------------|------|
| `HORIZONTAL_LINE` / `HORIZONTAL_RAY` / `HORIZONTAL_SEGMENT` | 水平直线 / 射线 / 线段 |
| `VERTICAL_LINE` / `VERTICAL_RAY` / `VERTICAL_SEGMENT` | 垂直直线 / 射线 / 线段 |
| `SEGMENT` | 线段 |
| `TREND_LINE` / `STRAIGHT_LINE` / `RAY` | 趋势线（无限）、直线、射线 |
| `PRICE_LINE` / `SIMPLE_TAG` | 价格线、价格标签 |
| `PARALLEL_LINES` / `PRICE_CHANNEL` | 平行线、价格通道 |
| `FIBONACCI_RETRACEMENT` | 斐波那契回撤 |
| `TEXT` | 文本标注 |
| `FREEHAND` | 画笔（别名 `brush`） |

磁铁：`none` / `weak` / `strong`。另有 `createOverlay` / `updateOverlay` / `removeOverlay` / `deleteSelectedOverlay`。

## 控制器与组件方法

`KLineChartController` 与 `KLineChartView` 方法一一对应，未 attach 时命令会排队。

| 方法 | 作用 |
|------|------|
| `scrollToLatest` / `scrollToTimestamp` / `scrollByBars` | 滚动 |
| `zoom` / `zoomAtTimestamp` / `resetViewport` | 缩放与复位 |
| `setPane` / `removePane` / `movePane` / `setPaneState` | 窗格；state 为 `normal` / `minimized` / `maximized` |
| `loadBefore` / `loadAfter` / `retryInitialLoad` | 分页与重试 |
| `cancelInteraction` / `clearCrosshair` | 取消交互、清十字光标 |
| `exportState` / `restoreState` | 快照导入导出 |
| `setMarket` / `setTheme` / `setFormatters` | 标的、主题、格式化（Controller） |

## 事件

| 事件 | 回调 |
|------|------|
| `onVisibleRangeChange` | 可见起止 index |
| `onBarClick` | timestamp、index |
| `onCrosshairChange` | timestamp、price |
| `onLoadStateChange` | initial / before / after |
| `onOverlayClick` / `onOverlayChange` | 选中 id、revision |
| `onPeriodChange` / `onIndicatorChange` | 周期、指标 id 列表 |
| `onPaneLayoutChange` / `onPaneHeaderClick` | 窗格布局、标题点击 |
| `onSignalClick` | 信号 id / title / summary |
| `onError` | code、message |

## 平台接入

### Android

```kotlin
implementation("io.github.qingfeng19491001:kuiklyklinechartandroid:0.1.0-2.1.21")

override fun registerExternalRenderView(export: IKuiklyRenderExport) {
    export.registerKuiklyKLineChart()
}
```

### iOS

```ruby
pod 'KuiklyKLineChartIOS', :git => 'https://github.com/qingfeng19491001/KuiklyKLineChart.git', :tag => '0.1.0'
```

`KRKLineChart` 类名与 `viewName` 一致，运行时自动发现，无需手动注册。

### 鸿蒙

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

# KuiklyKLineChart

面向 Kuikly 的跨端专业 K 线组件（扩展原生 View）。提供 KMP DSL/内核，以及 Android / iOS / 鸿蒙各自独立的原生扩展 View 产物。

当前对外可解析的是 **GitHub Packages 社区坐标**（`io.github.qingfeng19491001`）。`com.tencent.kuiklybase` / `@kuiklybase` 是纳入官方仓之后的目标坐标，现网还没有这些包。

## 产物

| 层 | 当前可依赖 | 官方纳入后（尚未发布） |
|----|------------|------------------------|
| KMP | `io.github.qingfeng19491001:kuiklyklinechart:0.1.0-2.1.21` | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.1.21` |
| KMP（鸿蒙工具链） | 同仓库 `./publish-maven.sh github ohos-kmp` | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.0.21-KBA-010` |
| Android | `io.github.qingfeng19491001:kuiklyklinechartandroid:0.1.0-2.1.21` | `com.tencent.kuiklybase:KuiklyKLineChartAndroid:0.1.0-2.1.21` |
| iOS | CocoaPods `KuiklyKLineChartIOS`，`:tag => '0.1.0'` | 官方 pod 源 |
| OHOS | 本仓库 `KuiklyKLineChartOhos` 路径依赖 | ohpm `@kuiklybase/kuikly-kline-chart-ohos` |

GitHub Packages 拉包需要 GitHub 账号（公开包也要 token）：

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/qingfeng19491001/KuiklyKLineChart")
    credentials {
        username = providers.gradleProperty("gpr.user").get()
        password = providers.gradleProperty("gpr.key").get()
    }
}
```

`gpr.user` 为 GitHub 用户名，`gpr.key` 为具有 `read:packages` 的 PAT。

## 1. KMP（DSL）

```kotlin
implementation("io.github.qingfeng19491001:kuiklyklinechart:0.1.0-2.1.21")
```

```kotlin
val controller = KLineChartController()
KLineChart(dataSource = stockDataSource, controller = controller) {
    attr {
        symbol("00700", "腾讯控股")
        period(1, "day")
        mode("full") // full | compact
        theme("light")
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

公开 API 白名单：`KLineChart` / `KLineChartController` / `KLineDataSource` 族 / `KLineBar` / 指标与 Overlay 配置 / `KLineTheme` / `KLineChartMode` / `KLineSignal` / `KLinePlatformHost`。Store / Render / Interaction 等内核类型已 `internal`；加载重试通过 Host/`call` 暴露，不直接暴露 Engine。

三端只认 `KLineChartView` 的 `PROP_*` / `METHOD_*`、`KLineChartEvent.EVENT_*` 与 `KLineChartView.OverlayTemplate`。`density` 由原生壳注入一次；viewport / pane 在 `KLinePlatformHost` 按像素计算（Android 金标）。手势统一为 `KLinePointerEvent`。画线模板同时接受 DSL 名（`HORIZONTAL_LINE`）与引擎名（`horizontal_line`）。

## 2. Android

```kotlin
implementation("io.github.qingfeng19491001:kuiklyklinechartandroid:0.1.0-2.1.21")
```

```kotlin
override fun registerExternalRenderView(export: IKuiklyRenderExport) {
    export.registerKuiklyKLineChart()
}
```

## 3. iOS

```ruby
pod 'KuiklyKLineChartIOS', :git => 'https://github.com/qingfeng19491001/KuiklyKLineChart.git', :tag => '0.1.0'
```

`KRKLineChart` 类名与 `viewName` 一致，运行时自动发现，无需手动注册。Kotlin 侧 `KLCChartBridge` 随业务 KMP `shared`（依赖本库）打进 framework。

## 4. 鸿蒙（OHOS）

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

## Demo

本仓库 `shared` + `androidApp` / `iosApp` / `ohosApp` 仅作示例，接入方式与外部工程相同。

- 路由页入口含 **库能力最小样例**（`MinimalLibrarySample`）：仅 DSL `KLineChart`，无 AI/行情业务壳。
- 完整业务演示：`FullChartDemo` / `CompactChartDemo` / `SignalOverlayDemo`。

```shell
./gradlew :KuiklyKLineChart:jsNodeTest :KuiklyKLineChartAndroid:assembleRelease :androidApp:assembleDebug
```

## 发布

```shell
# Maven Local：KMP + Android AAR（默认仍用官方目标 group com.tencent.kuiklybase）
./publish-maven.sh local

# GitHub Packages 社区坐标 io.github.qingfeng19491001（外人可依赖）
./publish-maven.sh github

# 鸿蒙工具链 KMP（settings.ohos.gradle.kts，版本带 KBA）
./publish-maven.sh github ohos-kmp

# 其它远端（需 MAVEN_REPO_URL / MAVEN_USERNAME / MAVEN_PASSWORD）
./publish-maven.sh remote
```

iOS 使用 CocoaPods `KuiklyKLineChartIOS`（本仓库 git tag）。鸿蒙在官方 ohpm 开通前使用仓库内 `KuiklyKLineChartOhos`。

## 性能

库内保留 `KLineCanvasRenderer.renderBatched`（同图元、少画笔切换）。不对外暴露 `perfEnabled` / renderer A/B 桩。经验基线（OHOS 热启动实测）：单帧 draw 约 0–3ms；掉帧率接近 0%。接入方以「交互跟手、无持续掉帧」为验收即可。

页面退出时 Android `onDetachedFromWindow`、iOS `willMove(toWindow:)` / `deinit`、OHOS `aboutToDisappear` 会 `detach`/`dispose` Host，避免 Bridge 泄漏。

## License

MIT，见 [LICENSE](LICENSE)。

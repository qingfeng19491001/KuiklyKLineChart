# KuiklyKLineChart

面向 Kuikly 的跨端专业 K 线组件（扩展原生 View）。提供 KMP DSL/内核，以及 Android / iOS / 鸿蒙各自独立的原生扩展 View 产物。

## 产物

| 层 | 坐标 / 引入方式 |
|----|----------------|
| KMP | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.1.21` |
| KMP（鸿蒙工具链） | `com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.0.21-KBA-010` |
| Android | `com.tencent.kuiklybase:KuiklyKLineChartAndroid:0.1.0-2.1.21` |
| iOS | CocoaPods `KuiklyKLineChartIOS` |
| OHOS | ohpm `@kuiklybase/kuikly-kline-chart-ohos` |

仓库：

```kotlin
maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
```

## 1. KMP（DSL）

```kotlin
implementation("com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.1.21")
// 鸿蒙 build.ohos.gradle.kts:
// implementation("com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.0.21-KBA-010")
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

## 2. Android

```kotlin
implementation("com.tencent.kuiklybase:KuiklyKLineChartAndroid:0.1.0-2.1.21")
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
  "@kuiklybase/kuikly-kline-chart-ohos": "0.1.0"
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
# Maven Local：KMP + Android AAR
./publish-maven.sh local

# 仅 KMP / 仅 Android
./publish-maven.sh local kmp
./publish-maven.sh local android

# 鸿蒙工具链 KMP（settings.ohos.gradle.kts，版本带 KBA）
./publish-maven.sh local ohos-kmp

# 远端（需 MAVEN_REPO_URL / MAVEN_USERNAME / MAVEN_PASSWORD）
./publish-maven.sh remote
```

iOS 使用 CocoaPods `KuiklyKLineChartIOS`；鸿蒙使用 ohpm `@kuiklybase/kuikly-kline-chart-ohos`。

## License

MIT，见 [LICENSE](LICENSE)。

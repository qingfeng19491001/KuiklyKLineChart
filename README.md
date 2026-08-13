# KuiklyKLineChart

面向 Kuikly 股票详情与 AI 行情场景的跨端专业 K 线组件，延续并产品化 [KuiklyChart PR #1](https://github.com/qingfeng19491001/KuiklyChart/pull/1) 已验证的交互与展示能力。

## 接入指南

普通 Kotlin/Android/iOS 坐标：

```kotlin
implementation("com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.1.21-SNAPSHOT")
```

HarmonyOS 使用 KBA 工具链对应坐标：

```kotlin
implementation("com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-2.0.21-KBA-010-SNAPSHOT")
```

Android 宿主注册：

```kotlin
override fun registerExternalRenderView(export: IKuiklyRenderExport) {
    export.registerKuiklyKLineChart()
}
```

iOS 由 Objective-C/Swift 类名 `KRKLineChart` 动态发现；HarmonyOS 在 `KuiklyViewDelegate.getCustomRenderViewCreatorRegisterMapV2()` 中用同名 `KRKLineChart` 注册。仓库已提供两端宿主工程骨架，本机为 Windows 且无对应运行环境，需在 macOS/HarmonyOS 开发机完成构建和真机验收。

## 核心 API

业务代码与组件处于同一 Kuikly 进程时，优先使用真实数据源入口：

```kotlin
val controller = KLineChartController()

KLineChart(dataSource = stockDataSource, controller = controller) {
    attr {
        symbol("00700", "腾讯控股")
        period(1, "day")
        mode("full")
    }
    event {
        onVisibleRangeChange { start, end -> }
        onBarClick { timestamp, index -> }
        onLoadStateChange { initial, before, after -> }
        onOverlayClick { id -> }
        onOverlayChange { revision -> }
        onSignalClick { id, title, summary -> }
    }
}
```

跨运行时或纯扩展 View 场景可通过 `bars(json)`、`signals(json)` 和 `config(json)` 推送序列化数据。Native View 默认不生成或写死股票数据。

`KLineChartController` 支持滚动到最新/时间戳、按 K 线数量平移、缩放、窗格和指标增删改、Overlay 增删改、状态导出恢复；View 侧还提供 `loadBefore()`、`loadAfter()` 与 `retryInitialLoad()`。

## 扩展能力

- `FULL`：多周期、主图与双副图、坐标、平移缩放、十字线、Tooltip、Overlay 和 AI 信号。
- `COMPACT`：复用同一内核，限制可见数量并关闭副图、坐标文字、Overlay 与手势，适合聊天回复卡片。
- 内置指标：MA、BOLL、EXPMA、BBI、ENE、VOL、AMOUNT、MACD、KDJ、RSI、WR、BBD。
- `KLineSignal`：BUY、SELL、RISK、INFO；组件负责绘制、命中和回调，AI 请求、真实性、解读卡与风险声明由业务页面负责。

## 示例

Demo 默认进入 `router`，提供三个可点页面：

- `FullChartDemo`：Task 1 与 Task 2 详情承接页使用的完整专业 K 线。
- `CompactChartDemo`：Task 2 聊天回复中的迷你行情卡片。
- `SignalOverlayDemo`：点击 AI 信号并联动业务解读卡。

Windows/Android 验证：

```shell
./gradlew :KuiklyKLineChart:jsNodeTest :shared:compileCommonMainKotlinMetadata :androidApp:assembleDebug
./gradlew :androidApp:installDebug
adb shell monkey -p com.kuikly.kuiklyklinechart -c android.intent.category.LAUNCHER 1
```

## License

MIT，见 [LICENSE](LICENSE)。

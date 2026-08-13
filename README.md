# KuiklyKLineChart

从 [KuiklyChart PR #1](https://github.com/qingfeng19491001/KuiklyChart/pull/1) 已验证 K 线能力独立迁移并持续产品化的 Kuikly 跨端 K 线组件。项目主要服务 Task 1 个股详情行情展示，并通过精简模式与 AI 信号 Overlay 支持 Task 2 的行情卡片和股票/指数详情承接页。

组件只负责行情数据、K 线/指标、视口交互、Overlay/Signal 与事件输出；不包含股票列表、AI 请求、聊天、Markdown、业务路由或完整详情页。

## 能力

- `FULL`：完整 pane、指标、坐标、Overlay、十字线、Tooltip 和视口交互。
- `COMPACT`：共用同一渲染内核的精简预设；仅保留价格 K 线和轴线，限制最后 60 条可见数据，隐藏坐标文字、副图、指标、Overlay、Tooltip 并禁用手势。
- `KLineSignal`：BUY / SELL / RISK / INFO 语义信号，复用现有 Overlay 渲染与命中测试。
- 静态/推送数据：Kuikly Attr 通过 `bars` JSON 替换当前快照；Native View 默认无内置假数据。
- 公共内核：KMP commonMain 包含数据、指标、pane、viewport、Overlay、交互与 RenderPlan/RenderPipeline。

## Maven

当前本地发布坐标：

```kotlin
implementation("com.tencent.kuiklybase:KuiklyKLineChart:0.1.0-SNAPSHOT")
```

仓库暂未声明远程 Maven 仓库或已发布版本；以上坐标用于本地 `publishToMavenLocal`/后续发布配置。

## Kuikly DSL

`bars` 和 `signals` 使用 JSON 字符串，因为 Kuikly 扩展 View 的跨端 `setProp` 只传基础类型：

```kotlin
KLineChart {
    attr {
        symbol("00700", "Tencent Holdings")
        period(1, "day")
        mode("full") // 或 compact
        bars(barsJson)
        signals(signalsJson)
    }
    event {
        onSignalClick { id, title, summary ->
            // 业务层展示 AI 解读卡
        }
        onCrosshairChange { timestamp, price -> }
        onError { code, message -> }
    }
}
```

`barsJson` 字段：`timestamp/open/high/low/close` 必填，`volume/turnover` 可选。`signalsJson` 字段：`id/timestamp/value/type/title/summary` 必填，`confidence` 可选且范围为 0..1。

## 三个 Showcase

Android 首屏 `KuiklyKLineDemo` 是 Kuikly Router：

- `FullChartDemo`：完整专业 K 线。
- `CompactChartDemo`：AI 回复卡片尺寸的精简 K 线。
- `SignalOverlayDemo`：信号点击联动业务层解读卡。

Demo 数据由这些 Kuikly Page 显式传入，不在 Native View 中生成。

## 平台真实状态

- Android：Kuikly `@Page` + 扩展 View + Native Canvas 已接通，可通过 `:androidApp:assembleDebug` 构建。
- iOS：仓库有 SwiftUI/UIView Canvas 示例源码和 KMP target，但当前不是与 Android 对等的 Kuikly 扩展 View；仓库也未提交完整 Xcode 工程，需在 macOS 集成验证。
- HarmonyOS：尚未实现 Native 扩展 View 或宿主工程。
- JS：核心/common 与 shared 的 Node 测试可运行；未提供浏览器宿主 UI。

因此当前不宣称 iOS/HarmonyOS 三端 Kuikly Demo 已完成。

## 构建与测试

```shell
./gradlew :KuiklyKLineChart:compileCommonMainKotlinMetadata
./gradlew :KuiklyKLineChart:jsNodeTest
./gradlew :shared:compileCommonMainKotlinMetadata
./gradlew :shared:jsNodeTest
./gradlew :androidApp:assembleDebug
```

## License

MIT，见 [LICENSE](LICENSE)；第三方说明见 [NOTICE](NOTICE)。

# KuiklyKLineChart 交付路线图

> 目标：把 §1-§19 方案中未完成的部分按里程碑落地，最终满足 §19 的全部验收标准。

- [ ] **M1：核心接入就绪（P0）** — 组件可用，Android/iOS 能跑通一张图
  - `KLineChart` Kuikly DSL 主入口 View（`attr`/`event` 两块全部契约）
  - `:shared` KMP Demo 模块 + 一个"基础 K 线"页面（静态数据渲染）
  - `:androidApp` Android 宿主壳工程
  - `:iosApp` iOS 宿主壳工程（XcodeGen 或 Gradle sync 可开）
  - 至少跑通一次 `StaticKLineDataSource` → Store → Engine → Canvas 的完整链路，眼睛能看到 K 线

- [ ] **M2：跨端三端 + 文档（P1）** — 鸿蒙接入 + 开源合规
  - KuiklyKLineChart 模块补 `ohos` target（若 Kuikly 2.15.0 提供）
  - `:ohosApp` 鸿蒙宿主壳工程
  - `README.md`（组件坐标、接入示例、API 速查）
  - `CHANGELOG.md`（0.1.0 初始版本条目）
  - `LICENSE`（Apache-2.0） + `NOTICE`（引用原 KuiklyChart 代码片段的合规声明）

- [ ] **M3：Demo 完整化 + 测试补全（P2）** — §17 Demo + §19 关键测试
  - Router 首页 + 9 个独立功能 Demo 页（基础/实时/分页/指标/窗格/画线/主题/状态恢复/大数据）
  - 指标菜单 + 参数设置 UI；画线工具栏；深浅色切换；状态导出/恢复按钮
  - commonTest 补充：Overlay 生命周期、坐标双向转换、前插视觉保持、迟到回调隔离、错误诊断事件

- [ ] **M4：性能 + Formatter 完善（P3）** — §14/§16 收尾
  - `DefaultFormatter` 补千位分隔、K/M/B 大数缩写、时区、中/英文案
  - 文本测量缓存、坐标刻度缓存
  - 1k/10k/100k 性能基准测试脚本与基线

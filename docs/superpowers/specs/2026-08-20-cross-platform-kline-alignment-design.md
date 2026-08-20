# K 线图 iOS / 鸿蒙对齐 Android 设计

## 目标

以 Android `AndroidKLineChartView` 为行为基准，使 iOS `KRKLineChart` 和鸿蒙 `KRKLineChart` 在完整 K 线 demo 中达到一致的视觉、属性、方法、事件和基础手势行为。

## 范围

- 共用 commonMain 图表内核和 demo 数据，不新增平台分叉业务逻辑。
- 对齐属性：symbol、period、bars、theme、mode、priceStyle、signals、config、bindingId、renderer、perfEnabled。
- 对齐方法：viewport 操作、pane/indicator 管理、overlay 生命周期、状态导入导出、加载控制和交互取消。
- 对齐事件：可见范围、bar 点击、加载状态、指标/overlay/周期变化、十字线、信号点击、pane 布局和 pane 标题点击。
- 对齐手势：tap、横向拖动、纵向方向仲裁、pinch 缩放、长按十字线、cancel 和双击复位（平台手势 API 允许时）。
- 完成 iOS 和鸿蒙本机模拟器/设备上的构建、安装、启动、截图和交互日志验收。

## 实现方案

以 Android 宿主实现为契约，保持 commonMain 内核不变：

1. iOS Bridge 补齐 Android 已有的绑定注册、配置解析和事件转发语义；Swift 宿主只负责坐标缩放、手势状态机和 CGContext 绘制。
2. 鸿蒙 Bridge 补齐 `bindingId`、完整 pane/yAxes 配置、overlay/pane 回调和状态恢复；ArkTS 宿主负责 Canvas primitive 绘制、触摸状态机、多指缩放和长按位置更新。
3. 对三端默认页面尺寸、主题、pane 和 indicator 初始状态做一致性检查，避免平台默认值造成视觉偏差。
4. 所有平台差异限制在宿主/Bridge 层；commonMain 只接受必要的通用修复。

## 验收

- Android 基准回归：现有单元测试和 debug 构建通过。
- iOS：`xcodebuild` 构建成功，模拟器安装/启动成功，完整页面截图包含行情头部、周期栏、MACD、蜡烛、均线和解读卡片；日志证明拖动、pinch、长按和回调路径执行。
- 鸿蒙：`linkDebugSharedOhosArm64` 与 HAP 构建成功，模拟器/设备安装启动成功，完整页面截图和日志证明同样的绘制与手势路径；资源从 `commonMain/assets` 正确打包。
- 对任何未能在本机验证的能力明确记录为未验收，不宣称三端完全一致。

## 风险与边界

- iOS 没有现成的系统化性能 AB 采集，本次只验证功能和渲染结果，不扩展性能基准。
- 鸿蒙 Bridge 的 `bindingId` 若受 NAPI 生命周期限制，必须报告实际限制，不能静默忽略。
- 平台坐标系和 content scale 需在入口统一换算，避免手势命中区域与绘制偏移。

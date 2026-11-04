## 0.1.1

- Android: 合并通话通知状态，并优化通知栏接听后的前后台跳转。
- Flutter/Web: 补齐前台来电横幅与页面运行时的浏览器来电通知。
- Call state: 修复 Android、iOS、Web 的终态去重及重复来电处理，避免已结束呼叫被重复消息恢复。

## 0.1.0

- Android: 优化通知栏接听交互，前台就地静默接听，后台直达主页避免中转闪烁。
- Android: 升级静音通知渠道至 v2，消除重叠双重铃声，并适配系统铃声音量与扬声器流类型。
- Android: 支持原生推送免引擎入口 `UnifiedCall.showIncomingCall` 与 `cancelCall`，基于 `AtomicFile` 保证落盘安全。
- Android: 支持 Self-managed Telecom、全屏来电界面与通话前台保活服务。
- iOS: 基于 `UserNotifications`（免 CallKit/PushKit 限制）实现原生来电展示与 APNs 恢复机制。
- iOS: 支持原生静态代理入口 `FlutterUnifiedCallPlugin.handleNotificationAction`，支持冷启动事件原子暂存。
- Web: 基于 IndexedDB、BroadcastChannel 与 Service Worker 实现多标签页呼叫同步与恢复。
- Flutter: 提供 `FlutterUnifiedCall` 核心 API 与通用前台横幅组件 `CallBannerHost`。
- Initial release.

## 0.3.0 - 2026-11-06

- Android: 前台服务成功显示通知后才移除重复通知；服务启动、提升失败或销毁时恢复仍在进行的通话通知。
- Flutter/Android/iOS/Web: 新增 `OutgoingCallConfig`、`CallDirection`、`CallKind` 和独立 `startOutgoingCall`，串联持久化、事件回放及 setup 超时；支持可选 `setCallConnecting`。
- Android: 点对点呼出使用 Telecom outgoing Connection，提供静默呼出通知和取消/挂断；创建/加入会议及会中通知默认仅打开会议页，不再提供挂断或注册电话式连接。
- Call state: 呼出超时记录 `ended/unanswered`，加入会议超时记录 `ended/failed`，不再混入未接来电；冷启动清理无法恢复的本地媒体状态。
- iOS/Web: 本地呼出与会议使用静默状态记录，不弹来电界面或播放来电铃声；补齐 `updateCall` 存储链路。
- Example/docs: 增加呼出和会议加入模拟、场景操作说明及跨端回归覆盖。

## 0.2.0 - 2026-11-06

- Flutter/Android/iOS/Web: 统一 `CallTexts` 配置，为通知、Android 锁屏页和 banner 提供中英文默认文案及自定义覆盖，兼容原有按钮文本参数。
- Flutter: banner 继承同一 controller 的初始化文案，支持配置更新及错误、重试文案本地化；初始化失败时保留原配置。
- Android: 原生免引擎入口支持中英文资源回退；自定义按钮文案或显式语言使用普通通知 action，避免系统 CallStyle 覆盖配置。
- Android: 会议通知按接通中/会议中状态显示，支持调用方传入会议名称和字符串会议号，并保留号码前导零；不再沿用邀请副标题。
- Android: 新增可本地化、自定义的 `meetingNamePrefix`，默认格式为“会议名称：名称” / “会议中 · 会议号”。

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

import Flutter
import UIKit
import UserNotifications

public class FlutterUnifiedCallPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var sink: FlutterEventSink?
    private let listenerId = UUID()
    private var runtime: CallRuntime { get throws { try CallRuntime.shared.get() } }
    /// Call on the main queue from the host push SDK's background callback.
    public static func showIncomingCall(_ config: [String: Any], completion: @escaping (Error?) -> Void) {
        DispatchQueue.main.async {
            do { try CallRuntime.shared.get().incoming(config, completion: completion) }
            catch { completion(error) }
        }
    }
    /// Returns false for notifications not owned by this plugin. Safe before Flutter registration.
    @discardableResult
    public static func handleNotificationAction(identifier: String, userInfo: [AnyHashable: Any],
                                                completion: @escaping (Error?) -> Void) -> Bool {
        guard userInfo[CallRuntime.payloadKey] != nil else { return false }
        DispatchQueue.main.async {
            do {
                guard try CallRuntime.shared.get().handleAction(identifier, userInfo: userInfo) else {
                    throw CallStoreError.invalidPayload
                }
                completion(nil)
            } catch { completion(error) }
        }
        return true
    }
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FlutterUnifiedCallPlugin()
        registrar.addMethodCallDelegate(instance, channel: FlutterMethodChannel(name: "flutter_unified_call", binaryMessenger: registrar.messenger()))
        FlutterEventChannel(name: "flutter_unified_call/events", binaryMessenger: registrar.messenger()).setStreamHandler(instance)
        registrar.addApplicationDelegate(instance)
    }
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        do {
            let runtime = try self.runtime
            sink = events
            runtime.listeners[listenerId] = { [weak self] event in self?.sink?(event) }
            return nil
        } catch { return failure(error) }
    }
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        sink = nil
        do { try runtime.listeners.removeValue(forKey: listenerId); return nil }
        catch { return failure(error) }
    }
    deinit { if case .success(let runtime) = CallRuntime.shared { runtime.listeners.removeValue(forKey: listenerId) } }
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        do {
            let runtime = try self.runtime
            let args = call.arguments as? [String: Any] ?? [:]
            switch call.method {
            case "initialize": runtime.initialize(args) { error in result(error.map(self.failure)) }
            case "showIncomingCall": runtime.incoming(args) { error in result(error.map(self.failure)) }
            case "getPendingEvents": try runtime.reconcile(); result(runtime.store.events)
            case "getActiveCalls":
                try runtime.reconcile()
                result(runtime.store.allCalls.filter { $0["state"] as? String != "ended" })
            case "acknowledgeEvent":
                guard let id = args["eventId"] as? String else { throw CallStoreError.invalidPayload }
                try runtime.store.acknowledge(id); result(nil)
            case "acceptCall", "declineCall", "setCallConnected", "endCall":
                guard let id = args["callId"] as? String, !id.isEmpty else { throw CallStoreError.invalidPayload }
                let reason = args["reason"] as? String ?? "localEnded"
                let action = call.method == "acceptCall" ? "accepted" : call.method == "declineCall" ? "declined" :
                    call.method == "setCallConnected" ? "connected" : reason == "missed" ? "missed" : "ended"
                try runtime.act(id, action: action, reason: action == "declined" ? "declined" : ["ended", "missed"].contains(action) ? reason : nil)
                result(nil)
            case "endAllCalls":
                for call in runtime.store.allCalls {
                    if let config = call["config"] as? [String: Any], let id = config["callId"] as? String { try runtime.act(id, action: "ended", reason: "localEnded") }
                }
                result(nil)
            case "getAudioEndpoints", "requestAudioEndpoint":
                result(FlutterError(code: "unsupported", message: "Audio endpoints require Android Telecom", details: nil))
            case "checkPermissions": permissions(result)
            case "requestPermissions":
                UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, error in
                    DispatchQueue.main.async {
                        if let error = error { result(self.failure(error)) } else { self.permissions(result) }
                    }
                }
            case "openFullScreenIntentSettings": result(FlutterError(code: "unsupported", message: "iOS has no full-screen call intent", details: nil))
            default: result(FlutterMethodNotImplemented)
            }
        } catch { result(failure(error)) }
    }
    private func permissions(_ result: @escaping FlutterResult) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let status: String
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral: status = "granted"
            case .denied: status = "denied"
            case .notDetermined: status = "notDetermined"
            @unknown default: status = "unsupported"
            }
            DispatchQueue.main.async { result(["notifications": status, "fullScreenIntent": "unsupported", "telecom": "unsupported"]) }
        }
    }
    private func failure(_ error: Error) -> FlutterError { FlutterError(code: "call_error", message: error.localizedDescription, details: nil) }
    public func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                       withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        guard notification.request.content.userInfo[CallRuntime.payloadKey] != nil else { return }
        DispatchQueue.main.async {
            do {
                if let config = try CallRuntime.payload(notification.request.content.userInfo) { try self.runtime.ingest(config) }
            } catch { NSLog("flutter_unified_call: foreground payload failed: %@", String(describing: error)) }
            completionHandler([]) // Flutter Banner + foreground player own presentation.
        }
    }
    public func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                       withCompletionHandler completionHandler: @escaping () -> Void) {
        Self.handleNotificationAction(identifier: response.actionIdentifier,
            userInfo: response.notification.request.content.userInfo) { error in
            if let error = error { NSLog("flutter_unified_call: notification action failed: %@", String(describing: error)) }
            completionHandler()
        }
    }
    public func applicationDidBecomeActive(_ application: UIApplication) {
        do { try runtime.reconcile() }
        catch { NSLog("flutter_unified_call: reconciliation failed: %@", String(describing: error)) }
    }
}

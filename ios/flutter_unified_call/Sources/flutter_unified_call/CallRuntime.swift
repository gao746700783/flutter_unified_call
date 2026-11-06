import Foundation
import UIKit
import UserNotifications
import AVFoundation

final class CallRuntime {
    static let category = "flutter_unified_call.incoming"
    static let payloadKey = "flutter_unified_call"
    private static var cached: CallRuntime?
    static var shared: Result<CallRuntime, Error> { Result {
        if let cached = cached { return cached }
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                   appropriateFor: nil, create: true)
        let runtime = try CallRuntime(store: CallStore(url: directory.appendingPathComponent("flutter_unified_call/state.json")))
        cached = runtime
        return runtime
    } }
    let store: CallStore
    var listeners: [UUID: ([String: Any]) -> Void] = [:]
    private var timers: [String: Timer] = [:]
    private let ringer: ForegroundRinger
    private var observers: [NSObjectProtocol] = []
    private var foreground = UIApplication.shared.applicationState == .active
    private let center = UNUserNotificationCenter.current()
    init(store: CallStore, ringer: ForegroundRinger = ForegroundRinger()) throws {
        self.store = store
        self.ringer = ringer
        let center = NotificationCenter.default
        for name in [UIApplication.didBecomeActiveNotification, UIScene.didActivateNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.foreground = true
                do { try self?.reconcile() } catch { NSLog("flutter_unified_call: foreground recovery failed: %@", String(describing: error)) }
            })
        }
        for name in [UIApplication.willResignActiveNotification, UIScene.willDeactivateNotification, UIApplication.didEnterBackgroundNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.foreground = false; self?.ringer.stop()
                if name == UIApplication.didEnterBackgroundNotification { self?.showBackgroundCalls() }
            })
        }
        store.onEvent = { [weak self] event in
            guard let self = self else { return }
            for listener in Array(self.listeners.values) { listener(event) }
        }
    }
    func initialize(_ options: [String: Any], completion: @escaping (Error?) -> Void) {
        do {
            if let name = options["iosRingtoneFileName"] as? String {
                guard !name.contains("/"), !name.contains(".."),
                      ["caf", "wav", "aiff"].contains((name as NSString).pathExtension.lowercased()),
                      let url = Bundle.main.url(forResource: name, withExtension: nil) else {
                    throw NSError(domain: "flutter_unified_call", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "Ringtone must be a bundled caf, wav or aiff file"])
                }
                let audio = try AVAudioPlayer(contentsOf: url)
                guard audio.duration > 0 && audio.duration < 30 else {
                    throw NSError(domain: "flutter_unified_call", code: 2,
                                  userInfo: [NSLocalizedDescriptionKey: "Notification sound must be less than 30 seconds"])
                }
            }
            try store.configure(options)
            ringer.reset()
            let accept = UNNotificationAction(identifier: "accepted", title: text("acceptText"), options: [.foreground])
            let decline = UNNotificationAction(identifier: "declined", title: text("declineText"), options: [])
            let category = UNNotificationCategory(identifier: Self.category, actions: [accept, decline], intentIdentifiers: [], options: [])
            center.getNotificationCategories { [weak self] existing in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    var categories = existing.filter { $0.identifier != Self.category }
                    categories.insert(category)
                    self.center.setNotificationCategories(categories)
                    do { try self.reconcile(); completion(nil) } catch { completion(error) }
                }
            }
        } catch { completion(error) }
    }
    func reconcile() throws {
        for call in store.allCalls {
            guard call["state"] as? String == "ringing", let config = call["config"] as? [String: Any],
                  let id = config["callId"] as? String, let expiry = config["expiresAt"] as? NSNumber else { continue }
            if expiry.doubleValue <= Date().timeIntervalSince1970 * 1000 { try act(id, action: "missed", reason: "missed") }
            else { schedule(id, expiry: expiry.doubleValue) }
        }
        try syncRinger()
    }
    private func syncRinger() throws {
        let now = Date().timeIntervalSince1970 * 1000
        let ringing = store.allCalls.compactMap { call -> [String: Any]? in
            guard call["state"] as? String == "ringing", let config = call["config"] as? [String: Any],
                  let expiry = config["expiresAt"] as? NSNumber, expiry.doubleValue > now else { return nil }
            return config
        }.sorted { (($0["expiresAt"] as? NSNumber)?.doubleValue ?? 0) < (($1["expiresAt"] as? NSNumber)?.doubleValue ?? 0) }
        let id = ringing.first?["callId"] as? String
        do {
            try ringer.update(callId: id, resource: store.options["iosRingtoneFileName"] as? String,
                              foreground: foreground, enabled: store.options["iosEnableRingtone"] as? Bool ?? true)
        } catch {
            if let id = id { try store.platformError(id: id, error: error) }
        }
    }
    deinit { observers.forEach(NotificationCenter.default.removeObserver) }
    func incoming(_ config: [String: Any], completion: @escaping (Error?) -> Void) {
        do {
            guard try ingest(config) else { completion(nil); return }
            try postNotification(config, completion: completion)
        } catch { completion(error) }
    }
    private func text(_ key: String) -> String {
        if let value = store.options[key] as? String { return value }
        let chinese = Locale.preferredLanguages.first?.hasPrefix("zh") == true
        switch key {
        case "acceptText": return chinese ? "接听" : "Accept"
        case "declineText": return chinese ? "拒绝" : "Decline"
        case "audioSubtitle": return chinese ? "语音来电" : "Incoming audio call"
        case "videoSubtitle": return chinese ? "视频来电" : "Incoming video call"
        case "incomingTitle": return chinese ? "来电" : "Incoming call"
        default: preconditionFailure("Unknown call text: \(key)")
        }
    }

    private func showBackgroundCalls() {
        let now = Date().timeIntervalSince1970 * 1000
        for call in store.allCalls {
            guard call["state"] as? String == "ringing", let config = call["config"] as? [String: Any],
                  let expiry = config["expiresAt"] as? NSNumber, expiry.doubleValue > now else { continue }
            do {
                try postNotification(config) { error in
                    if let error = error { NSLog("flutter_unified_call: background notification failed: %@", String(describing: error)) }
                }
            } catch { NSLog("flutter_unified_call: background notification failed: %@", String(describing: error)) }
        }
    }
    private func postNotification(_ config: [String: Any], completion: @escaping (Error?) -> Void) throws {
        guard let id = config["callId"] as? String else { throw CallStoreError.invalidPayload }
        let content = try notificationContent(config)
        center.add(UNNotificationRequest(identifier: "unified-call-\(id)", content: content, trigger: nil)) { error in
            DispatchQueue.main.async {
                do {
                    if error != nil { try self.act(id, action: "ended", reason: "failed") }
                    if self.store.get(id)?["state"] as? String != "ringing" {
                        self.center.removePendingNotificationRequests(withIdentifiers: ["unified-call-\(id)"])
                        self.center.removeDeliveredNotifications(withIdentifiers: ["unified-call-\(id)"])
                    }
                    completion(error)
                } catch { completion(error) }
            }
        }
    }
    func notificationContent(_ config: [String: Any]) throws -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = config["callerName"] as? String ?? text("incomingTitle")
        let handle = config["callerHandle"] as? String ?? ""
        content.body = handle.isEmpty ? text(config["mediaType"] as? String == "video" ? "videoSubtitle" : "audioSubtitle") : handle
        content.categoryIdentifier = Self.category
        let payload = try JSONSerialization.data(withJSONObject: config)
        guard let payloadString = String(data: payload, encoding: .utf8) else { throw CallStoreError.invalidPayload }
        content.userInfo = [Self.payloadKey: payloadString]
        if store.options["iosEnableRingtone"] as? Bool == false { content.sound = nil }
        else if let name = store.options["iosRingtoneFileName"] as? String {
            content.sound = UNNotificationSound(named: UNNotificationSoundName(rawValue: name))
        } else { content.sound = .default }
        if #available(iOS 15.0, *), store.options["iosTimeSensitive"] as? Bool == true { content.interruptionLevel = .timeSensitive }
        return content
    }
    func act(_ id: String, action: String, reason: String? = nil) throws {
        guard try store.transition(id, action: action, reason: reason) else { return }
        timers.removeValue(forKey: id)?.invalidate()
        center.removePendingNotificationRequests(withIdentifiers: ["unified-call-\(id)"])
        center.removeDeliveredNotifications(withIdentifiers: ["unified-call-\(id)"])
        try syncRinger()
    }
    static func payload(_ userInfo: [AnyHashable: Any]) throws -> [String: Any]? {
        if let value = userInfo[payloadKey] as? [String: Any] { return value }
        if let value = userInfo[payloadKey] as? String, let data = value.data(using: .utf8) {
            guard let config = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw CallStoreError.invalidPayload }
            return config
        }
        return nil
    }
    @discardableResult
    func ingest(_ config: [String: Any]) throws -> Bool {
        guard try store.incoming(config), let id = config["callId"] as? String,
              let expiry = config["expiresAt"] as? NSNumber else { return false }
        schedule(id, expiry: expiry.doubleValue)
        try syncRinger()
        return true
    }
    func handleAction(_ identifier: String, userInfo: [AnyHashable: Any]) throws -> Bool {
        guard let config = try Self.payload(userInfo),
              let id = config["callId"] as? String else { return false }
        if store.get(id) == nil { try store.incoming(config) }
        switch identifier {
        case "accepted": try act(id, action: "accepted")
        case "declined": try act(id, action: "declined", reason: "declined")
        default: try reconcile() // A body tap opens the app, never auto-accepts.
        }
        return true
    }
    private func schedule(_ id: String, expiry: Double) {
        timers.removeValue(forKey: id)?.invalidate()
        let delay = max(0, expiry / 1000 - Date().timeIntervalSince1970)
        timers[id] = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            do { try self?.act(id, action: "missed", reason: "missed") }
            catch { NSLog("flutter_unified_call: timeout persistence failed: %@", String(describing: error)) }
        }
    }
}

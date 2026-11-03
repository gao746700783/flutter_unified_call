import Foundation

enum CallStoreError: Error, LocalizedError {
    case invalidPayload, invalidState
    var errorDescription: String? {
        switch self {
        case .invalidPayload: return "Invalid call payload"
        case .invalidState: return "Accept the call before connecting"
        }
    }
}

/// Access only from the main queue. Commits the entire state atomically before dispatch.
final class CallStore {
    private let url: URL
    private var calls: [String: [String: Any]] = [:]
    private(set) var events: [[String: Any]] = []
    private(set) var options: [String: Any] = [:]
    var onEvent: (([String: Any]) -> Void)?
    var allCalls: [[String: Any]] { Array(calls.values) }
    init(url: URL) throws {
        self.url = url
        if FileManager.default.fileExists(atPath: url.path) {
            let data = try Data(contentsOf: url)
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let calls = root["calls"] as? [String: [String: Any]],
                  let events = root["events"] as? [[String: Any]] else { throw CallStoreError.invalidPayload }
            self.calls = calls; self.events = events; self.options = root["options"] as? [String: Any] ?? [:]
        }
    }
    func get(_ id: String) -> [String: Any]? { calls[id] }
    func configure(_ options: [String: Any]) throws {
        try commit(calls: calls, events: events, options: options)
    }
    func acknowledge(_ id: String) throws {
        try commit(calls: calls, events: events.filter { $0["eventId"] as? String != id }, options: options)
    }
    @discardableResult
    func incoming(_ config: [String: Any], now: Double = Date().timeIntervalSince1970 * 1000) throws -> Bool {
        guard let id = config["callId"] as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let name = config["callerName"] as? String, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let expiry = config["expiresAt"] as? NSNumber,
              let media = config["mediaType"] as? String, ["audio", "video"].contains(media),
              config["extra"] is [String: Any], JSONSerialization.isValidJSONObject(config) else { throw CallStoreError.invalidPayload }
        if expiry.doubleValue <= now { return false }
        if var existing = calls[id] {
            guard let state = existing["state"] as? String, state != "ended",
                  var original = existing["config"] as? [String: Any],
                  let originalExpiry = original["expiresAt"] as? NSNumber,
                  var extra = original["extra"] as? [String: Any],
                  let updates = config["extra"] as? [String: Any],
                  state != "ringing" || originalExpiry.doubleValue > now else { return false }
            extra.merge(updates) { _, latest in latest }
            original["extra"] = extra; existing["config"] = original
            var updated = calls; updated[id] = existing
            try commit(calls: updated, events: events, options: options)
            return false
        }
        let call: [String: Any] = ["config": config, "state": "ringing"]
        try emit(id: id, call: call, type: "incoming", now: now)
        return true
    }
    @discardableResult
    func transition(_ id: String, action: String, reason: String? = nil,
                    now: Double = Date().timeIntervalSince1970 * 1000) throws -> Bool {
        guard var call = calls[id], let state = call["state"] as? String, state != "ended" else { return false }
        if ["accepted", "declined", "missed"].contains(action) && state != "ringing" { return false }
        if action == "connected" {
            if state == "connected" { return false }
            guard state == "accepted" else { throw CallStoreError.invalidState }
        }
        if action == "accepted", let config = call["config"] as? [String: Any],
           let expiry = config["expiresAt"] as? NSNumber, expiry.doubleValue <= now {
            return try transition(id, action: "missed", reason: "missed", now: now)
        }
        call["state"] = ["accepted", "connected"].contains(action) ? action : "ended"
        try emit(id: id, call: call, type: action, reason: reason, now: now)
        return true
    }
    func platformError(id: String, error: Error) throws {
        guard let call = calls[id], call["state"] as? String != "ended" else { return }
        try emit(id: id, call: call, type: "platformError", now: Date().timeIntervalSince1970 * 1000,
                 metadata: ["operation": "ringtone", "error": error.localizedDescription])
    }
    private func emit(id: String, call: [String: Any], type: String, reason: String? = nil, now: Double, metadata: [String: Any] = [:]) throws {
        guard let config = call["config"] as? [String: Any] else { throw CallStoreError.invalidPayload }
        let event: [String: Any] = ["eventId": UUID().uuidString, "type": type, "call": config,
                                  "occurredAt": Int64(now), "reason": reason as Any? ?? NSNull(), "metadata": metadata]
        var updated = calls; updated[id] = call
        try commit(calls: updated, events: events + [event], options: options)
        onEvent?(event)
    }
    private func commit(calls: [String: [String: Any]], events: [[String: Any]], options: [String: Any]) throws {
        var prunedCalls = calls
        let endedKeys = prunedCalls.filter { $0.value["state"] as? String == "ended" }.map { $0.key }
        if endedKeys.count > 50 {
            for key in endedKeys.prefix(endedKeys.count - 50) {
                prunedCalls.removeValue(forKey: key)
            }
        }
        let root: [String: Any] = ["calls": prunedCalls, "events": events, "options": options]
        let data = try JSONSerialization.data(withJSONObject: root)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        self.calls = prunedCalls; self.events = events; self.options = options
    }
}

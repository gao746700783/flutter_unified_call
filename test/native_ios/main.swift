import Foundation

func check(_ condition: Bool, _ message: String) {
    if !condition { fatalError(message) }
}
let directory = FileManager.default.temporaryDirectory.appendingPathComponent("unified-call-tests-\(UUID().uuidString)")
defer { do { try FileManager.default.removeItem(at: directory) } catch { print("Cleanup: \(error)") } }
let url = directory.appendingPathComponent("state.json")
let now = Date().timeIntervalSince1970 * 1000
let config: [String: Any] = ["callId": "test", "callerName": "Alice", "mediaType": "audio", "expiresAt": now + 30000, "extra": ["room": "one"]]
let store = try CallStore(url: url)
check(try store.incoming(config), "Incoming failed")
check(!(try store.incoming(config)), "Duplicate emitted")
do {
    try store.transition("test", action: "connected")
    fatalError("Connected without acceptance")
} catch CallStoreError.invalidState { }
check(try store.transition("test", action: "accepted"), "Acceptance failed")
check(!(try store.transition("test", action: "missed", reason: "missed")), "Accepted became missed")
check(try store.transition("test", action: "connected"), "Connection failed")
check(try store.transition("test", action: "ended", reason: "remoteEnded"), "End failed")
check(!(try store.transition("test", action: "accepted")), "Ended revived")
let recovered = try CallStore(url: url)
check(recovered.events.count == 4, "Events not persisted")
let eventId = recovered.events[1]["eventId"] as! String
try recovered.acknowledge(eventId)
check(try CallStore(url: url).events.count == 3, "Acknowledgment not persisted")
var late = config; late["callId"] = "late"; late["expiresAt"] = now - 1
check(!(try recovered.incoming(late)), "Expired incoming accepted")
var delayed = config; delayed["callId"] = "delayed"
check(try recovered.incoming(delayed, now: now), "Delayed setup failed")
try recovered.transition("delayed", action: "accepted", now: now + 60000)
check(recovered.events.last?["type"] as? String == "missed", "Late accept should become missed")
// An unsuccessful atomic write must leave in-memory state unchanged.
let blocking = directory.appendingPathComponent("not-a-directory")
try Data().write(to: blocking)
let unwritable = try CallStore(url: blocking.appendingPathComponent("state.json"))
do {
    try unwritable.incoming(config)
    fatalError("Persistence failure swallowed")
} catch { check(unwritable.events.isEmpty, "Failed write mutated memory") }
print("PASS: dedupe, lifecycle, expiry, durable replay/ack and persistence rollback")

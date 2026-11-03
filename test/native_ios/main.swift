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
let mergeURL = directory.appendingPathComponent("merge.json")
let merging = try CallStore(url: mergeURL)
check(try merging.incoming(config, now: now), "Merge setup failed")
var duplicate = config
// Only extra may change; identity and timeout stay with the original delivery.
duplicate["callerName"] = "Changed"; duplicate["expiresAt"] = now + 60000
duplicate["extra"] = ["room": "updated", "nullable": NSNull()]
check(!(try merging.incoming(duplicate, now: now)), "Duplicate re-rang")
let mergedConfig = merging.get("test")!["config"] as! [String: Any]
check(mergedConfig["callerName"] as? String == "Alice", "Duplicate replaced caller")
check((mergedConfig["expiresAt"] as! NSNumber).doubleValue == now + 30000, "Duplicate extended expiry")
check((mergedConfig["extra"] as! [String: Any])["nullable"] is NSNull, "Null was lost")
check(merging.events.count == 1, "Duplicate emitted an event")
check((merging.events[0]["call"] as! [String: Any])["extra"] as! [String: String] == ["room": "one"], "Historical event mutated")
let diskMerged = try CallStore(url: mergeURL).get("test")!["config"] as! [String: Any]
check((diskMerged["extra"] as! [String: Any])["room"] as? String == "updated", "Merge not durable")
try merging.transition("test", action: "accepted", now: now)
let acceptedConfig = merging.events.last!["call"] as! [String: Any]
check((acceptedConfig["extra"] as! [String: Any])["room"] as? String == "updated", "Acceptance lost merged extra")
// A directory at the target path forces an atomic-write failure.
try FileManager.default.removeItem(at: mergeURL)
try FileManager.default.createDirectory(at: mergeURL, withIntermediateDirectories: false)
duplicate["extra"] = ["room": "failed"]
do {
    try merging.incoming(duplicate, now: now)
    fatalError("Merge failure swallowed")
} catch {
    let current = merging.get("test")!["config"] as! [String: Any]
    check((current["extra"] as! [String: Any])["room"] as? String == "updated", "Failed merge mutated memory")
}
try FileManager.default.removeItem(at: mergeURL)
try merging.transition("test", action: "ended", now: now)
check(!(try merging.incoming(duplicate, now: now)), "Terminal duplicate revived")
let retainedURL = directory.appendingPathComponent("retained.json")
let retained = try CallStore(url: retainedURL)
for index in 0...50 {
    var item = config
    item["callId"] = "terminal-\(index)"
    item["expiresAt"] = now + 60000
    check(try retained.incoming(item, now: now), "Retention setup failed")
    check(try retained.transition("terminal-\(index)", action: "declined", reason: "declined", now: now),
          "Retention transition failed")
}
var replay = config
replay["callId"] = "terminal-0"
replay["expiresAt"] = now + 60000
check(!(try CallStore(url: retainedURL).incoming(replay, now: now)), "Terminal call revived after 51 endings")
print("PASS: extra merge, historical snapshots, lifecycle, expiry, durable replay/ack and persistence rollback")

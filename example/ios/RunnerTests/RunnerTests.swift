import AVFoundation
import XCTest
@testable import flutter_unified_call

private final class Playback: RingtonePlayback {
  var numberOfLoops = 0
  var starts = 0
  var stops = 0
  var succeeds = true
  func play() -> Bool { starts += 1; return succeeds }
  func stop() { stops += 1 }
}

class RunnerTests: XCTestCase {
  func testRingOncePerCallAndStopForAcceptanceOrBackground() throws {
    var players: [Playback] = []
    let ringer = ForegroundRinger { _ in let player = Playback(); players.append(player); return player }
    try ringer.update(callId: "one", resource: nil, foreground: true, enabled: true)
    try ringer.update(callId: "one", resource: nil, foreground: true, enabled: true)
    XCTAssertEqual(players.count, 1)
    XCTAssertEqual(players[0].numberOfLoops, -1)
    XCTAssertEqual(players[0].starts, 1)
    try ringer.update(callId: nil, resource: nil, foreground: true, enabled: true)
    XCTAssertEqual(players[0].stops, 1)
    try ringer.update(callId: "two", resource: nil, foreground: true, enabled: true)
    try ringer.update(callId: "two", resource: nil, foreground: false, enabled: true)
    XCTAssertEqual(players[1].stops, 1)
  }
  func testFailedPlaybackReportsOnceAndResetAllowsRetry() throws {
    let player = Playback(); player.succeeds = false
    var builds = 0
    let ringer = ForegroundRinger { _ in builds += 1; return player }
    XCTAssertThrowsError(try ringer.update(callId: "one", resource: nil, foreground: true, enabled: true))
    try ringer.update(callId: "one", resource: nil, foreground: true, enabled: true)
    XCTAssertEqual(builds, 1)
    ringer.reset(); player.succeeds = true
    try ringer.update(callId: "one", resource: nil, foreground: true, enabled: true)
    XCTAssertEqual(builds, 2)
    ringer.stop()
  }
  func testDisabledRingingDoesNotBuildPlayer() throws {
    let ringer = ForegroundRinger { _ in XCTFail("Disabled ringer built a player"); return Playback() }
    try ringer.update(callId: "one", resource: nil, foreground: true, enabled: false)
  }
  func testRuntimeAcceptanceAndLifecycleNotificationsControlRinger() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { do { try FileManager.default.removeItem(at: directory) } catch { XCTFail("Cleanup failed: \(error)") } }
    let store = try CallStore(url: directory.appendingPathComponent("state.json"))
    var players: [Playback] = []
    let runtime = try CallRuntime(store: store, ringer: ForegroundRinger { _ in
      let player = Playback(); players.append(player); return player
    })
    NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    let config: [String: Any] = ["callId": "runtime", "callerName": "Alice", "mediaType": "audio",
      "expiresAt": Date().timeIntervalSince1970 * 1000 + 30000, "extra": [String: Any]()]
    try runtime.ingest(config)
    XCTAssertEqual(players.count, 1)
    NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
    XCTAssertEqual(players[0].stops, 1)
    NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertEqual(players.count, 2)
    try runtime.act("runtime", action: "accepted")
    XCTAssertEqual(players[1].stops, 1)
    XCTAssertEqual(store.get("runtime")?["state"] as? String, "accepted")
    try runtime.act("runtime", action: "ended", reason: "localEnded")
  }
  func testLocalNotificationSoundHonorsDisableAndPreservesPayload() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { do { try FileManager.default.removeItem(at: directory) } catch { XCTFail("Cleanup failed: \(error)") } }
    let store = try CallStore(url: directory.appendingPathComponent("state.json"))
    let runtime = try CallRuntime(store: store)
    try store.configure(["iosEnableRingtone": false])
    let config: [String: Any] = ["callId": "notification", "callerName": "Alice", "mediaType": "audio",
      "expiresAt": Date().timeIntervalSince1970 * 1000 + 30000, "extra": ["room": "one"]]
    let silent = try runtime.notificationContent(config)
    XCTAssertNil(silent.sound)
    XCTAssertEqual(silent.categoryIdentifier, CallRuntime.category)
    XCTAssertEqual(try CallRuntime.payload(silent.userInfo)?["callId"] as? String, "notification")
    try store.configure(["iosEnableRingtone": true])
    XCTAssertNotNil(try runtime.notificationContent(config).sound)
  }
  func testColdActionPersistsWithoutDartAndRejectsExpiredAcceptance() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { do { try FileManager.default.removeItem(at: directory) } catch { XCTFail("Cleanup failed: \(error)") } }
    let url = directory.appendingPathComponent("state.json")
    let store = try CallStore(url: url)
    let runtime = try CallRuntime(store: store, ringer: ForegroundRinger { _ in Playback() })
    let now = Date().timeIntervalSince1970 * 1000
    let config: [String: Any] = ["callId": "cold", "callerName": "Alice", "mediaType": "audio",
      "expiresAt": now + 30000, "extra": ["room": "retained"]]
    XCTAssertTrue(try runtime.handleAction("accepted", userInfo: [CallRuntime.payloadKey: config]))
    XCTAssertTrue(try runtime.handleAction("accepted", userInfo: [CallRuntime.payloadKey: config]))
    let recovered = try CallStore(url: url)
    XCTAssertEqual(recovered.events.map { $0["type"] as? String }, ["incoming", "accepted"])
    XCTAssertEqual(recovered.get("cold")?["state"] as? String, "accepted")
    XCTAssertEqual((recovered.events.last?["call"] as? [String: Any])?["extra"] as? [String: String], ["room": "retained"])
    XCTAssertFalse(try runtime.handleAction("accepted", userInfo: ["other": "payload"]))
    var late = config; late["callId"] = "late"; late["expiresAt"] = now - 1
    XCTAssertTrue(try runtime.handleAction("accepted", userInfo: [CallRuntime.payloadKey: late]))
    XCTAssertNil(store.get("late"))
    try runtime.act("cold", action: "ended", reason: "localEnded")
  }
  func testUnownedStaticActionIsNotConsumed() {
    let owned = FlutterUnifiedCallPlugin.handleNotificationAction(identifier: "accepted", userInfo: ["other": true]) { _ in
      XCTFail("Foreign notification callback should not be consumed")
    }
    XCTAssertFalse(owned)
  }
  func testDefaultPCMIsReadableByActualAudioPlayer() throws {
    let player = try AVAudioPlayer(data: ForegroundRinger.defaultTone())
    XCTAssertEqual(player.duration, 1, accuracy: 0.01)
    XCTAssertEqual(player.numberOfChannels, 1)
  }
}

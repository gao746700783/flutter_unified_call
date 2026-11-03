import Foundation
import AVFoundation
import AudioToolbox

protocol RingtonePlayback: AnyObject {
    var numberOfLoops: Int { get set }
    func play() -> Bool
    func stop()
}
extension AVAudioPlayer: RingtonePlayback {}

/// Main-queue owner of foreground ringing only. Never changes AVAudioSession.
final class ForegroundRinger {
    private let makePlayer: (String?) throws -> RingtonePlayback
    private var player: RingtonePlayback?
    private var vibrationTimer: Timer?
    private struct Request: Equatable { let callId: String; let resource: String? }
    private var key: Request?
    private var failedKey: Request?
    init(makePlayer: @escaping (String?) throws -> RingtonePlayback = ForegroundRinger.makePlayer) {
        self.makePlayer = makePlayer
    }
    func update(callId: String?, resource: String?, foreground: Bool, enabled: Bool) throws {
        guard foreground, enabled, let id = callId else { stop(); return }
        let next = Request(callId: id, resource: resource)
        guard key != next, failedKey != next else { return }
        stop()
        do {
            let audio = try makePlayer(resource)
            audio.numberOfLoops = -1
            guard audio.play() else {
                audio.stop()
                throw NSError(domain: "flutter_unified_call", code: 3,
                              userInfo: [NSLocalizedDescriptionKey: "Foreground ringtone playback failed"])
            }
            player = audio; key = next
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            vibrationTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { _ in
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            }
        } catch { failedKey = next; throw error }
    }
    func reset() { stop(); failedKey = nil }
    func stop() {
        player?.stop(); player = nil; key = nil
        vibrationTimer?.invalidate(); vibrationTimer = nil
    }
    deinit { player?.stop() }
    private static func makePlayer(_ resource: String?) throws -> RingtonePlayback {
        if let resource = resource {
            guard let url = Bundle.main.url(forResource: resource, withExtension: nil) else {
                throw NSError(domain: "flutter_unified_call", code: 4,
                              userInfo: [NSLocalizedDescriptionKey: "Foreground ringtone resource unavailable"])
            }
            return try AVAudioPlayer(contentsOf: url)
        }
        return try AVAudioPlayer(data: defaultTone())
    }
    /// Original one-second PCM tone: 600ms sound followed by 400ms silence.
    static func defaultTone() -> Data {
        let count = 16000
        var data = Data()
        func ascii(_ text: String) { data.append(contentsOf: text.utf8) }
        func u16(_ value: UInt16) { data.append(UInt8(value & 255)); data.append(UInt8(value >> 8)) }
        func u32(_ value: UInt32) { u16(UInt16(value & 65535)); u16(UInt16(value >> 16)) }
        ascii("RIFF"); u32(UInt32(36 + count * 2)); ascii("WAVEfmt "); u32(16)
        u16(1); u16(1); u32(16000); u32(32000); u16(2); u16(16); ascii("data"); u32(UInt32(count * 2))
        for sample in 0..<count {
            let phase = Double(sample) / 16000
            let envelope = sample < 9600 ? min(1, min(Double(sample) / 160, Double(9600 - sample) / 160)) : 0
            let value = Int16(5000 * envelope * sin(2 * Double.pi * 440 * phase))
            u16(UInt16(bitPattern: value))
        }
        return data
    }
}

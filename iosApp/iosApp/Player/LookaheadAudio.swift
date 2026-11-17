import Foundation
import Libmpv

/// Free look-ahead audio for subtitle sync.
///
/// mpv already downloads and demuxes the film ahead of playback. `dump-cache` writes what it holds to a
/// file, and keeps appending what it reads from then on. A second, headless mpv then decodes that file
/// to 16 kHz mono float PCM far faster than real time. No second connection and no audio tap are needed.
///
/// A recording's own clock starts at 0. Its place on the film's timeline is the start of the buffered
/// range when recording began; measured against the truth on a real stream (tools/mpv-lookahead-probe)
/// that is within 20 ms, including after a resume, where the player seeks back to a keyframe and the
/// resume position alone would be up to a second out.
///
/// `dump-cache` is marked experimental by mpv, so every failure here ends in a `false` or a NaN for the
/// caller to handle, never a crash.
final class LookaheadAudio {
    private let player: () -> OpaquePointer?
    private let lock = NSLock()
    private var anchor = Double.nan
    private var isRecording = false

    /// [player] returns the playing mpv handle, which this class borrows and never destroys.
    init(player: @escaping () -> OpaquePointer?) {
        self.player = player
    }

    /// Where the current recording's first packet sits on the media clock, in seconds; NaN if none.
    var anchorSeconds: Double {
        lock.lock()
        defer { lock.unlock() }
        return anchor
    }

    /// How far mpv has buffered, in seconds on the media clock; NaN while the buffer is empty.
    func bufferEndSeconds() -> Double {
        guard let mpv = player() else { return .nan }
        var value = Double()
        return mpv_get_property(mpv, "demuxer-cache-time", MPV_FORMAT_DOUBLE, &value) >= 0 ? value : .nan
    }

    /// Starts recording the buffered range that holds the playhead, from its start, and keeps appending.
    /// False when the buffer is still empty or mpv refuses; the caller can try again shortly.
    func start(path: String) -> Bool {
        guard let mpv = player() else { return false }
        stop()
        guard let rangeStart = bufferedRangeStart(mpv) else { return false }
        try? FileManager.default.removeItem(atPath: path)
        let status = commandAsync(mpv, ["dump-cache", String(rangeStart), "no", path])
        guard status >= 0 else { return false }
        lock.lock()
        anchor = rangeStart
        isRecording = true
        lock.unlock()
        return true
    }

    /// Ends the recording, if one is running. The file is complete once the call returns.
    func stop() {
        lock.lock()
        let wasRecording = isRecording
        isRecording = false
        lock.unlock()
        guard wasRecording, let mpv = player() else { return }
        _ = withArguments(["dump-cache", "0", "0", ""]) { mpv_command(mpv, $0) }
    }

    /// Decodes the audio of [recordingPath] to 16 kHz mono float PCM at [pcmPath]. Blocks until done, so
    /// call it off the main thread. [endSeconds] stops short of the newest data, on the recording's own
    /// clock, so mpv does not wait at the end of a file that is still growing; 0 or less decodes it all.
    func decode(recordingPath: String, pcmPath: String, endSeconds: Double) -> Bool {
        try? FileManager.default.removeItem(atPath: pcmPath)
        guard let decoder = mpv_create() else { return false }
        defer { mpv_terminate_destroy(decoder) }
        var options: [(String, String)] = [
            ("vo", "null"), ("vid", "no"), ("sid", "no"),
            ("ao", "pcm"), ("ao-pcm-file", pcmPath), ("ao-pcm-waveheader", "no"),
            ("audio-samplerate", "16000"), ("audio-channels", "mono"), ("audio-format", "float"),
            ("terminal", "no"), ("msg-level", "all=error"),
        ]
        if endSeconds > 0 { options.append(("end", String(endSeconds))) }
        for (name, value) in options where mpv_set_option_string(decoder, name, value) < 0 { return false }
        guard mpv_initialize(decoder) >= 0 else { return false }
        guard commandAsync(decoder, ["loadfile", recordingPath]) >= 0 else { return false }

        let deadline = Date().addingTimeInterval(Self.decodeTimeout)
        var finished = false
        while Date() < deadline {
            guard let event = mpv_wait_event(decoder, 0.05) else { continue }
            let id = event.pointee.event_id
            if id == MPV_EVENT_END_FILE || id == MPV_EVENT_SHUTDOWN { finished = true; break }
        }
        guard finished else { return false }
        let size = (try? FileManager.default.attributesOfItem(atPath: pcmPath)[.size] as? NSNumber)?.intValue ?? 0
        return size > 0
    }

    // MARK: - mpv

    /// The start of the buffered range that contains the playhead, or the first range if none does.
    private func bufferedRangeStart(_ mpv: OpaquePointer) -> Double? {
        guard let state = node(mpv, "demuxer-cache-state") as? [String: Any],
              let ranges = state["seekable-ranges"] as? [Any] else { return nil }
        let maps = ranges.compactMap { $0 as? [String: Any] }
        var position = Double()
        let hasPosition = mpv_get_property(mpv, "time-pos", MPV_FORMAT_DOUBLE, &position) >= 0
        let containing = maps.first { range in
            guard hasPosition, let start = range["start"] as? Double, let end = range["end"] as? Double else { return false }
            return start <= position && position <= end
        }
        return (containing ?? maps.first)?["start"] as? Double
    }

    private func commandAsync(_ mpv: OpaquePointer, _ arguments: [String]) -> Int32 {
        withArguments(arguments) { mpv_command_async(mpv, 0, $0) }
    }

    /// Builds the NULL-terminated C argument array an mpv command takes, and frees it afterwards.
    private func withArguments(_ arguments: [String], _ body: (UnsafeMutablePointer<UnsafePointer<CChar>?>) -> Int32) -> Int32 {
        var pointers: [UnsafeMutablePointer<CChar>?] = arguments.map { strdup($0) }
        pointers.append(nil)
        defer { pointers.forEach { free($0) } }
        return pointers.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count, body)
        }
    }

    /// A structured property as nested dictionaries, arrays and numbers.
    private func node(_ mpv: OpaquePointer, _ name: String) -> Any? {
        var value = mpv_node()
        guard mpv_get_property(mpv, name, MPV_FORMAT_NODE, &value) >= 0 else { return nil }
        defer { mpv_free_node_contents(&value) }
        return Self.convert(value)
    }

    private static func convert(_ node: mpv_node) -> Any? {
        switch node.format {
        case MPV_FORMAT_STRING: return node.u.string.map { String(cString: $0) }
        case MPV_FORMAT_FLAG: return node.u.flag != 0
        case MPV_FORMAT_INT64: return Double(node.u.int64)
        case MPV_FORMAT_DOUBLE: return node.u.double_
        case MPV_FORMAT_NODE_ARRAY:
            guard let list = node.u.list?.pointee else { return nil }
            return (0..<Int(list.num)).map { convert(list.values[$0]) as Any }
        case MPV_FORMAT_NODE_MAP:
            guard let list = node.u.list?.pointee else { return nil }
            var result: [String: Any] = [:]
            for index in 0..<Int(list.num) {
                if let key = list.keys[index], let value = convert(list.values[index]) { result[String(cString: key)] = value }
            }
            return result
        default: return nil
        }
    }

    /// Decoding runs about 800x real time, so this is generous.
    private static let decodeTimeout: TimeInterval = 60
}

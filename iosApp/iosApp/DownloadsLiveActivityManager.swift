import Foundation
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
import ActivityKit
#endif

private let downloadsLiveStatusUpdatedNotification = Notification.Name("NuvioDownloadsLiveStatusUpdated")
private let downloadsLiveStatusPayloadKey = "nuvio.downloads.live_status.payload"

final class DownloadsLiveActivityManager {
    static let shared = DownloadsLiveActivityManager()

    private var observer: NSObjectProtocol?

    private init() {}

    func start() {
        guard observer == nil else { return }

        observer = NotificationCenter.default.addObserver(
            forName: downloadsLiveStatusUpdatedNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncFromPayloadStore()
        }

        syncFromPayloadStore()
    }

    private func syncFromPayloadStore() {
#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
        guard #available(iOS 16.1, *) else { return }

        let payload = loadPayload()
        Task {
            await apply(payload)
        }
#endif
    }

    private func loadPayload() -> DownloadsLiveStatusPayload? {
        guard let encoded = UserDefaults.standard.string(forKey: downloadsLiveStatusPayloadKey) else {
            return nil
        }
        let data = Data(encoded.utf8)
        return try? JSONDecoder().decode(DownloadsLiveStatusPayload.self, from: data)
    }

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
    @available(iOS 16.1, *)
    private func apply(_ payload: DownloadsLiveStatusPayload?) async {
        let existing = Activity<DownloadsLiveActivityAttributes>.activities.first

        guard let payload else {
            if let existing {
                await existing.end(dismissalPolicy: .immediate)
            }
            return
        }

        let state = DownloadsLiveActivityAttributes.ContentState(
            status: payload.status,
            sizeText: sizeText(payload),
            title: payload.title,
            subtitle: payload.subtitle,
            moreCount: payload.moreCount ?? 0
        )

        // One activity for the whole queue: it is updated in place, which works with the app in the
        // background, while starting a new one does not.
        if let existing {
            await existing.update(using: state)
            return
        }

        let attributes = DownloadsLiveActivityAttributes(
            downloadId: payload.id,
            title: payload.title,
            subtitle: payload.subtitle
        )

        _ = try? Activity<DownloadsLiveActivityAttributes>.request(
            attributes: attributes,
            contentState: state,
            pushType: nil
        )
    }
#endif

    /// The file size, when it is known.
    private func sizeText(_ payload: DownloadsLiveStatusPayload) -> String {
        guard let total = payload.totalBytes, total > 0 else { return "" }
        return formatBytes(total)
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

#if canImport(ActivityKit) && os(iOS) && !targetEnvironment(macCatalyst)
@available(iOS 16.1, *)
struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let status: String
        let sizeText: String
        let title: String
        let subtitle: String
        /// The other downloads waiting, shown as "2 more".
        let moreCount: Int

        init(status: String, sizeText: String, title: String, subtitle: String, moreCount: Int) {
            self.status = status
            self.sizeText = sizeText
            self.title = title
            self.subtitle = subtitle
            self.moreCount = moreCount
        }

        // An activity started by an earlier version has no count.
        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            status = try values.decode(String.self, forKey: .status)
            sizeText = try values.decode(String.self, forKey: .sizeText)
            title = try values.decode(String.self, forKey: .title)
            subtitle = try values.decode(String.self, forKey: .subtitle)
            moreCount = try values.decodeIfPresent(Int.self, forKey: .moreCount) ?? 0
        }
    }

    let downloadId: String
    let title: String
    let subtitle: String
}
#endif

private struct DownloadsLiveStatusPayload: Decodable {
    let id: String
    let title: String
    let subtitle: String
    let status: String
    let totalBytes: Int64?
    let moreCount: Int?
}

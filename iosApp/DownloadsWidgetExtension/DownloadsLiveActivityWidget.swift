import ActivityKit
import SwiftUI
import WidgetKit

struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let status: String
        /// The file size, or empty when it is not known.
        let sizeText: String
    }

    let downloadId: String
    let title: String
    let subtitle: String
}

/// Shows the download that is running: its show and episode, that it is downloading, and its size.
/// There is no progress figure on purpose: iOS only lets the app change what this shows while the
/// app runs, so a progress figure would freeze when the app is left. It changes when the next
/// download in the queue starts.
@available(iOSApplicationExtension 16.1, *)
struct DownloadsLiveActivityWidget: Widget {
    private let downloadsUrl = URL(string: "nuvio://downloads")
    private let appBlue = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadsLiveActivityAttributes.self) { context in
            DownloadActivityLockScreenView(context: context)
                .widgetURL(downloadsUrl)
        } dynamicIsland: { context in
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(statusLabel(context.state.status), systemImage: statusIcon(context.state.status))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.88))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if !context.state.sizeText.isEmpty {
                        Text(context.state.sizeText)
                            .font(.caption.monospacedDigit().weight(.semibold))
                            .foregroundStyle(.white.opacity(0.88))
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(context.attributes.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.86)
                            .truncationMode(.tail)
                        Text(context.attributes.subtitle)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(1)
                            .minimumScaleFactor(0.9)
                            .truncationMode(.tail)
                    }
                    .padding(.top, 4)
                    .padding(.horizontal, 10)
                }
            } compactLeading: {
                AccentGlyphView()
            } compactTrailing: {
                Image(systemName: statusIcon(context.state.status))
                    .font(.caption2.bold())
                    .foregroundStyle(appBlue)
            } minimal: {
                AccentGlyphView()
            }
            .widgetURL(downloadsUrl)
            .keylineTint(appBlue)
        }
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "downloading": return "Downloading"
        case "paused": return "Paused"
        case "failed": return "Failed"
        default: return "Active"
        }
    }

    private func statusIcon(_ status: String) -> String {
        switch status.lowercased() {
        case "paused": return "pause.fill"
        case "failed": return "exclamationmark.triangle.fill"
        default: return "arrow.down"
        }
    }
}

@available(iOSApplicationExtension 16.1, *)
private struct DownloadActivityLockScreenView: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>

    var body: some View {
        ZStack {
            LinearGradient(
                colors: backgroundGradientColors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing,
            )

            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(context.attributes.title)
                        .font(.headline.weight(.semibold))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(context.attributes.subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.82))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }

                HStack {
                    Label(statusLabel(context.state.status), systemImage: statusIcon(context.state.status))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.9))
                    Spacer()
                    if !context.state.sizeText.isEmpty {
                        Text(context.state.sizeText)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.white.opacity(0.86))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, minHeight: 96, maxHeight: .infinity, alignment: .top)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.white.opacity(0.10), lineWidth: 1),
        )
        .activityBackgroundTint(.clear)
        .activitySystemActionForegroundColor(.white)
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "downloading": return "Downloading"
        case "paused": return "Paused"
        case "failed": return "Failed"
        default: return "Active"
        }
    }

    private func statusIcon(_ status: String) -> String {
        switch status.lowercased() {
        case "paused": return "pause.fill"
        case "failed": return "exclamationmark.triangle.fill"
        default: return "arrow.down"
        }
    }

    private var backgroundGradientColors: [Color] {
        let accent = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)
        return [
            accent.opacity(0.92),
            accent.opacity(0.42),
            Color.black.opacity(0.97),
        ]
    }
}

private struct AccentGlyphView: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0).opacity(0.88))
            Image(systemName: "arrow.down")
                .font(.caption2.bold())
                .foregroundStyle(.white)
        }
        .frame(width: 22, height: 22)
    }
}

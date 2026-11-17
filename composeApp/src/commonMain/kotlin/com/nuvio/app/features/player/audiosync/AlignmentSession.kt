package com.nuvio.app.features.player.audiosync

import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * The audio sync of one subtitle: its dialogue track and the [AudioSyncTracker] that decides when
 * the audio proves a mapping. Turns each tracker outcome into the state and status the rest of the
 * app cares about. [update] runs on the alignment thread; the state is readable from any thread.
 */
internal class AlignmentSession(
    val key: String,
    val track: SubtitleSpeechTrack,
) {
    private val tracker = AudioSyncTracker(track)

    /** The mapping in effect: confirmed, or an early estimate while [estimated]. */
    @Volatile
    var model: SubtitleSyncModel? = null
        private set

    /** The mapping is an early estimate that may still be withdrawn. */
    @Volatile
    var estimated: Boolean = false
        private set

    /** Nothing more to find: a confirmed mapping is in effect. */
    val isConfirmed: Boolean get() = model != null && !estimated

    /** What one [update] changed. */
    class Update(
        /** Worth telling the viewer about, or null. */
        val status: AudioSyncStatus?,
        /** A mapping to remember for next time, or null. */
        val remember: SubtitleSyncModel?,
        /** The mapping was just confirmed, so analysis can ease off later. */
        val confirmed: Boolean,
    )

    /** Takes a mapping found earlier (a remembered one) as confirmed; the tracker only refines it. */
    fun adopt(restored: SubtitleSyncModel, positionMs: Long): AudioSyncStatus.Synced {
        tracker.adopt(restored)
        model = restored
        estimated = false
        AudioSyncLog.i("restored remembered sync $restored")
        return synced(restored, positionMs, rateCorrected = restored.segments.first().scale != 1.0)
    }

    fun update(timeline: SpeechTimeline, positionMs: Long): Update {
        val outcome = tracker.update(timeline, positionMs)
        return when (outcome) {
            is AudioSyncTracker.Outcome.Provisional -> {
                val first = model == null
                model = outcome.model
                estimated = true
                AudioSyncLog.i("provisional ${outcome.model} ${outcome.estimate.describe()}")
                Update(
                    status = if (first) AudioSyncStatus.Estimated(offsetAt(outcome.model, positionMs)) else null,
                    remember = null,
                    confirmed = false,
                )
            }
            is AudioSyncTracker.Outcome.Retracted -> {
                model = null
                estimated = false
                AudioSyncLog.i("early estimate withdrawn ${outcome.estimate.describe()}")
                Update(AudioSyncStatus.Withdrawn, remember = null, confirmed = false)
            }
            is AudioSyncTracker.Outcome.Locked -> {
                model = outcome.model
                estimated = false
                AudioSyncLog.i("LOCKED ${outcome.model} ${outcome.estimate.describe()}")
                Update(
                    status = synced(outcome.model, positionMs, rateCorrected = outcome.estimate.scale != 1.0),
                    remember = outcome.model,
                    confirmed = true,
                )
            }
            is AudioSyncTracker.Outcome.Refined -> {
                model = outcome.model
                AudioSyncLog.d("refined ${outcome.model} ${outcome.estimate.describe()}")
                NO_CHANGE
            }
            is AudioSyncTracker.Outcome.Jumped -> {
                model = outcome.model
                AudioSyncLog.i("jump detected ${outcome.model} ${outcome.estimate.describe()}")
                Update(
                    status = AudioSyncStatus.Adjusted(offsetAt(outcome.model, positionMs)),
                    remember = outcome.model,
                    confirmed = false,
                )
            }
            else -> NO_CHANGE
        }
    }

    private fun synced(model: SubtitleSyncModel, positionMs: Long, rateCorrected: Boolean) =
        AudioSyncStatus.Synced(offsetAt(model, positionMs), rateCorrected)

    private fun offsetAt(model: SubtitleSyncModel, positionMs: Long): Long = model.delayUsAt(positionMs * 1_000L) / 1_000L

    private fun SubtitleAudioAligner.Estimate.describe(): String =
        "scale=${scale.toFixed(5)} shift=${shiftMs.roundToInt()}ms peak=${peak.toFixed(3)} " +
            "prominence=${prominence.toFixed(3)} cues=$cueCount speech=${speechSeconds.roundToInt()}s"

    private companion object {
        val NO_CHANGE = Update(status = null, remember = null, confirmed = false)
    }
}

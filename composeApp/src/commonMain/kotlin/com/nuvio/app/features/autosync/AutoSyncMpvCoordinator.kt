package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.audiosync.AudioModelTimeMapper
import com.nuvio.app.features.player.audiosync.AudioSyncStatus
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_no_fit
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** File name prefix of the retimed subtitle copies that mpv players load. */
internal const val LIBMPV_AUTOSYNC_FILE_PREFIX = "autosync_"

/** What an mpv player needs from its platform to apply AutoSync results. */
internal interface AutoSyncMpvPlatform {
    /**
     * Writes [content] to the subtitle cache as [fileName], deletes the other files whose names
     * start with [stalePrefix], and returns the written file's path, or null when it cannot be written.
     */
    suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String?

    /** Shows a short message over the player. */
    fun showMessage(text: String)
}

/**
 * AutoSync for one mpv source (Android libmpv, iOS MPVKit). mpv exposes no embedded subtitle
 * timing while it plays, so only the container's own subtitle index serves as the reference.
 * The original subtitle shows right away; a confident result replaces it with a copy whose
 * timestamps are rewritten.
 */
internal class AutoSyncMpvCoordinator(
    private val scope: CoroutineScope,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getPreferredLanguage: () -> String?,
    private val platform: AutoSyncMpvPlatform,
    /** Syncs a subtitle to the audio when AutoSync keeps its original timing; null where the platform cannot. */
    private val audioFallback: AutoSyncAudioFallback? = null,
) {
    private var job: Job? = null
    private var audioApplyJob: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null

    /**
     * [replaceExternalSubtitle] loads a local file in place of the external track that was loaded
     * from a URL, keeping that track's label.
     */
    fun wrap(
        controller: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ): PlayerEngineController =
        AutoSyncMpvEngineController(controller, this, replaceExternalSubtitle)

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun cancel() {
        operationToken++
        job?.cancel()
        job = null
        audioApplyJob?.cancel()
        audioApplyJob = null
        audioFallback?.stop()
        AutoSyncSyncedSubtitle.clearSyncing()
    }

    fun dispose() {
        cancel()
        appliedListener = null
    }

    private fun showFailure(token: Long, resource: StringResource) {
        scope.launch {
            val text = getString(resource)
            if (operationToken == token) platform.showMessage(text)
        }
    }

    fun start(
        url: String,
        candidateScope: AutoSyncCandidateScope,
        base: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ) {
        cancel()
        val token = operationToken
        base.setSubtitleUri(url)
        AutoSyncPreferencesRepository.ensureLoaded()
        if (decideAutoSyncStart(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) ==
            AutoSyncStartAction.ATTACH_ORIGINAL ||
            !isSyncableSource(sourceUrl)) {
            return
        }

        val subtitleHeaders = getSubtitleHeaders(url)
        val subtitleLanguage = candidates.firstOrNull { it.url == url }?.language
            ?.takeIf { it.isNotBlank() } ?: getPreferredLanguage()
        // A download has no stream to read subtitle timing from: only its audio can say.
        val isDownload = isLocalFileUrl(sourceUrl)
        if (!isDownload) EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)

        job = scope.launch {
            try {
                var outcome: AutoSyncAnalysisOutcome? = null
                suspend fun search(searchScope: AutoSyncCandidateScope) =
                    AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = sourceUrl,
                        sourceHeaders = sourceHeaders,
                        selectedSubtitleUrl = url,
                        selectedSubtitleHeaders = subtitleHeaders,
                        preferredLanguage = subtitleLanguage,
                        alternativeSubtitles = searchScope.alternativeCandidates(candidates),
                        alternativeSubtitlesProvider = if (searchScope.usesAlternativeProvider) {
                            { candidates }
                        } else null,
                        onAnalysisOutcome = { outcome = it },
                        allowLiveReference = false,
                    )
                var resolved = if (isDownload) null else search(candidateScope)
                // The restored subtitle does not fit: look for another one that does.
                if (
                    !isDownload && resolved == null && token == operationToken &&
                    candidateScope.searchAfterSelectedFailed(outcome)
                ) {
                    resolved = search(AutoSyncCandidateScope.STARTUP_SEARCH)
                }
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (resolved == null) {
                    // The original timing is kept and nothing says it is right: try the audio.
                    if (startAudioFallback(url, subtitleLanguage, outcome, token, base, replaceExternalSubtitle)) {
                        return@launch
                    }
                    // Only a confirmed no-fit is worth telling; anything else stays silent.
                    if (outcome == AutoSyncAnalysisOutcome.NO_FIT) {
                        showFailure(token, Res.string.autosync_toast_no_fit)
                    }
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                val withinTolerance = tolerance > 0 && chosenUrl == url &&
                    resolved.timeline.maxAlignmentShiftMs() <= tolerance
                if (!withinTolerance) {
                    val path = writeRetimedSubtitle(chosenUrl, resolved, subtitleLanguage)
                    currentCoroutineContext().ensureActive()
                    if (token != operationToken) return@launch
                    if (path == null) return@launch
                    replaceExternalSubtitle(url, path)
                }
                base.setSubtitleDelayMs(0)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Technical failures stay silent: the original subtitle keeps its timing.
            }
        }
    }

    /**
     * Hands [url] to the audio fallback, which finds a mapping from the speech and reports each one. The
     * subtitle card shows "Syncing to audio" meanwhile. False when the audio fallback did not start.
     */
    private suspend fun startAudioFallback(
        url: String,
        languageHint: String?,
        outcome: AutoSyncAnalysisOutcome?,
        token: Long,
        base: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ): Boolean {
        val fallback = audioFallback ?: return false
        if (!shouldSyncToAudio(outcome, sidecarStillShowsSubtitle = true)) return false
        val body = try {
            AutomaticSubtitleSync.downloadSubtitleBody(url, getSubtitleHeaders(url), languageHint)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return false
        }
        currentCoroutineContext().ensureActive()
        if (token != operationToken) return false
        val dialogue = AutoSyncSubtitleCueParser.parse(body, url).map { Triple(it.startTimeMs, it.endTimeMs, it.text) }

        // Mappings arrive as the audio is heard; only the latest is worth writing.
        val mappings = Channel<SubtitleSyncModel>(Channel.CONFLATED)
        AutoSyncSyncedSubtitle.markSyncing(url)
        val started = fallback.takeOver(
            subtitleKey = url,
            dialogue = dialogue,
            onModel = { mappings.trySend(it) },
            onStatus = { status -> if (token == operationToken) showAudioSyncStatus(url, status) },
        )
        if (!started) {
            AutoSyncSyncedSubtitle.clearSyncing()
            return false
        }
        audioApplyJob = scope.launch {
            for (model in mappings) {
                try {
                    applyAudioMapping(url, body, model, base, replaceExternalSubtitle)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Technical failures stay silent: the subtitle keeps the timing it has.
                }
            }
        }
        return true
    }

    /** Writes the subtitle with [model]'s timing and swaps it in for the one loaded from [url]. */
    private suspend fun applyAudioMapping(
        url: String,
        body: String,
        model: SubtitleSyncModel,
        base: PlayerEngineController,
        replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
    ) {
        val retimed = withContext(autoSyncCpuDispatcher) {
            AutoSyncSubtitleTextRetimer.retime(body, AudioModelTimeMapper(model))
        } ?: return
        val path = writeSubtitleCopy(url, retimed) ?: return
        replaceExternalSubtitle(url, path)
        base.setSubtitleDelayMs(0)
        appliedListener?.invoke(url, 0)
    }

    /** What the card says: still lining up with the audio, or done. */
    private fun showAudioSyncStatus(url: String, status: AudioSyncStatus) {
        when (status) {
            is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted -> AutoSyncSyncedSubtitle.mark(url)
            else -> AutoSyncSyncedSubtitle.markSyncing(url)
        }
    }

    /**
     * Writes the subtitle at [subtitleUrl] with the AutoSync timeline applied, and returns its path,
     * or null when its format cannot be retimed. The name follows the content, so mpv's `cached`
     * sub-add reuses the track when the same result is applied again.
     */
    private suspend fun writeRetimedSubtitle(
        subtitleUrl: String,
        resolved: AutoSyncResolvedTimeline,
        languageHint: String?,
    ): String? {
        val body = resolved.subtitleBody ?: AutomaticSubtitleSync.downloadSubtitleBody(
            url = subtitleUrl,
            headers = resolved.subtitleHeaders.ifEmpty { getSubtitleHeaders(subtitleUrl) },
            languageHint = languageHint,
        )
        val retimed = withContext(autoSyncCpuDispatcher) {
            AutoSyncSubtitleTextRetimer.retime(body, resolved.timeline)
        } ?: return null
        return writeSubtitleCopy(subtitleUrl, retimed)
    }

    /** Writes [retimed] to the subtitle cache. The name follows the content, so mpv reuses the track for the same result. */
    private suspend fun writeSubtitleCopy(subtitleUrl: String, retimed: String): String? {
        val prefix = "$LIBMPV_AUTOSYNC_FILE_PREFIX${subtitleUrl.hashCode().toHexString()}_"
        val extension = when {
            retimed.trimStart().startsWith("WEBVTT") -> "vtt"
            retimed.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        return platform.writeSubtitleFile(
            fileName = "$prefix${retimed.hashCode().toHexString()}.$extension",
            stalePrefix = prefix,
            content = retimed,
        )
    }

    /** Same digits as java.lang.Integer.toHexString. */
    private fun Int.toHexString(): String = toUInt().toString(16)
}

/**
 * A player's mpv controller with AutoSync layered on top. Every call is forwarded to [base]
 * unchanged; subtitle changes first stop any AutoSync run that still targets the old subtitle.
 * Open so a platform can keep extra interfaces of its controller (iOS system controls).
 */
internal open class AutoSyncMpvEngineController(
    private val base: PlayerEngineController,
    private val coordinator: AutoSyncMpvCoordinator,
    private val replaceExternalSubtitle: (replacedUrl: String, path: String) -> Unit,
) : PlayerEngineController by base, AutoSyncPlayerController {

    override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) =
        coordinator.setCandidates(candidates)

    override fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    ) = coordinator.setAppliedListener(listener)

    override fun setSubtitleUri(url: String) {
        coordinator.cancel()
        base.setSubtitleUri(url)
    }

    override fun setSubtitleUriWithAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.STARTUP_SEARCH, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithSelectedAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_ONLY, base, replaceExternalSubtitle)

    override fun setSubtitleUriWithRestoredAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_THEN_SEARCH, base, replaceExternalSubtitle)

    override fun selectSubtitleTrack(index: Int) {
        coordinator.cancel()
        base.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        coordinator.cancel()
        base.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        coordinator.cancel()
        base.clearExternalSubtitleAndSelect(trackIndex)
    }

    override fun cancelForManualSubtitleDelay() {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
    }
}

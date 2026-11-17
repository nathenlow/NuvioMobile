# Audio subtitle sync: port plan

Branch: `feat/audio-subtitle-sync` (from `cmp-rewrite`). Status: plan only, no code written.

Source: [NuvioMobile-Reshaped](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped), branch
`subtitle-autosync`, package `features/player/audiosync` (release `1.1.0-beta.1-autosync.2`).
Both projects are GPL-3.0.

## 1. Goal

Today AutoSync aligns an add-on subtitle to the video's **embedded subtitle timing**. When the video
has no usable embedded subtitles, or the match is weak, the original timing is kept and the subtitle
can stay out of sync.

This work adds the fork's **audio fallback**: when AutoSync keeps the original timing, align the
subtitle to the speech in the audio instead.

The motivating case is an English subtitle over Korean or Japanese audio (K-drama, anime). The
alignment must not depend on the audio's language.

### In scope
- Speech detection (Silero VAD) on the playing stream's audio, aligned to the subtitle's cue pattern.
- Constant offset, frame-rate stretch, and mid-film jumps (the fork's tracker already models these).
- Look-ahead capture from the audio ExoPlayer demuxes, plus sampling short dialogue-dense spots
  ahead of the playhead, so a result can arrive before the viewer notices.
- One setting to turn the fallback on or off, profile-scoped like the other AutoSync settings.
- Android internal ExoPlayer with the sidecar subtitle renderer (same envelope as today's AutoSync).

### Out of scope (deliberately)
- Speech recognition: the sherpa-onnx AAR, the 74 MB English model, the model download and offer
  dialog, `AsrSyncEngine`, `WordAnchorMatcher`, `SubtitleBridge`, `SpeechSegmenter`.
- `SubtitleCandidatePool` (tests other same-language subtitles against the audio). It is built on the
  word matcher. Your existing embedded-timing search already tries other candidates.
- The glass bubble UI, the on-screen statistics overlay, the shareable sync log.
- libmpv engine, ASS/SSA drawn by libass, iOS. See "Known limits".

## 2. How it works (VAD-only path)

```
ExoPlayer demux ──► AudioSyncExtractorsFactory  (copies compressed samples as they pass)
                          │
 spot sampler ────────────┤   (range-requests short dialogue-dense stretches ahead of playhead)
                          ▼
                    AudioSyncDecoder ──► MediaCodec, else FFmpeg shim, else playback PCM tap
                          ▼
                    SpeechAnalyzer + SileroVad ──► SpeechTimeline (speech probability per frame)
                          ▼
 subtitle cues ──► SubtitleSpeechTrack ──► SubtitleAudioAligner (cross-correlation over shift + scale)
                          ▼
                    AudioSyncTracker (Searching → Provisional → Locked → Refined / Jumped / Retracted)
                          ▼
                    SubtitleSyncModel (piecewise shift + scale)  ──► retime sidecar cues in place
```

Nothing in this chain looks at words or language. The fork's `audioIsForeign()` check only exists to
switch the English recogniser off, so it disappears with the recogniser.

## 3. Findings that shape the plan

Verified by reading the fork and this repo:

| Finding | Consequence |
|---|---|
| This repo's AutoSync already routes every sidecar result through `SidecarSubtitleController.commitPreparedSidecarSubtitle(expectedCurrentUrl, newUrl, cues, expectedGeneration)`; `currentGenerationFor`, `sidecarTimedCues`, `activeSidecarSubtitleKey` and `AutoSyncSyncedSubtitle` all exist. | The fallback's apply path ports unchanged. No new player surface is needed. |
| `composeApp/libs/lib-decoder-ffmpeg-release.aar` is already vendored and contains `FfmpegAudioDecoder` and `FfmpegLibrary`. | The fork's `AudioSyncFfmpegDecoder` shim (placed in package `androidx.media3.decoder.ffmpeg` to reach package-private classes) can be reused. No new Gradle dependency. |
| `composeApp/src/androidMain/res/raw` exists. | The 487 KB `silero_vad_v5_16k.bin` weights file drops in. |
| `PlayerEngine.android.kt` already builds `extractorsFactory` (~line 317, wrapped by `AutoSyncExtractorsFactory`) and overrides `buildAudioSink` in `SubtitleOffsetRenderersFactory` (~line 2916). | Both taps have an obvious hook. |
| `AutoSyncPlayerCoordinator.start()` ends in `resolved == null → restoreOriginalIfRelevant() + showNoFit()`, and success ends in `AutoSyncSyncedSubtitle.mark()`. | These are the `takeOver` and `disarm` points. |
| `AutoSyncPreferencesRepository` is profile-scoped, with persistence injected per platform. | The new setting goes there, not in a second settings object. |
| This repo has no bubble UI and no debug-log setting. Feedback is the "Auto synced" chip plus failure toasts. | Fork's `showAutoSyncMessage` and `SyncLog` are replaced, not ported. |
| In the fork, `AudioSubtitleSyncController` is ~1,300 lines. About 90 lines mention ASR/recognition and it also owns capture, spot sampling, alignment scheduling, persistence, status mapping and reference fetching. | Copying it and deleting lines would leave a god object. It is split (section 4). |

Not verified, and called out as risks in section 8: whether the vendored FFmpeg build decodes the
audio codecs you care about, and whether the two extractor wrappers chain cleanly.

## 4. Target structure

Package `com.nuvio.app.features.player.audiosync` in `androidMain` (all of it needs Android
`MediaCodec`/Media3). Shared settings and status types stay in `commonMain`.

Principle: each unit has one job and a small interface, and the pure parts have no Android imports so
they run as host tests.

### Pure signal core (no Android types; host-testable)
| File | Source | Notes |
|---|---|---|
| `Fft.kt`, `SpeechTimeline.kt`, `SubtitleSpeechTrack.kt`, `SubtitleAudioAligner.kt`, `AudioSyncTracker.kt` | copy | `AudioSyncTracker.kt` has one ASR mention to check. `SubtitleSyncModel` and `SubtitleSyncSegment` live here. |
| `SileroVad.kt` + `res/raw/silero_vad_v5_16k.bin` | copy | Pure-Kotlin port of Silero v5, MIT. Needs a license notice. |
| `SpeechAnalyzer.kt` | copy, edit | Remove the `ChunkListener` seam that only the ASR segmenter used, if nothing else does. |

### Audio capture
| File | Source | Notes |
|---|---|---|
| `AudioSyncExtractors.kt` | copy | Extractors wrapper that copies samples. |
| `PlaybackAudioTap.kt` | copy | `AudioSink` wrapper for the playback-PCM path. |
| `AudioSyncDecoder.kt` | copy | MediaCodec first, FFmpeg shim second. |
| `androidx/media3/decoder/ffmpeg/AudioSyncFfmpegDecoder.kt` | copy | |
| `DialogueSpotPlanner.kt`, `AudioSpotSampler.kt` | copy | Picks and fetches dialogue spots. |

### Orchestration (rewritten from the 1,300-line controller; done in phase 3)
| Unit | Responsibility |
|---|---|
| `AudioSyncEngine` | Per stream: owns the alignment thread, the active session, scheduling and the listener for status. Added during phase 3; it is what is left of the controller once the units below are taken out. |
| `AudioSyncCapture` | Decides which audio is worth analysing, owns the decoder, and implements `AudioSampleSink` and `PlaybackPcmListener`. |
| `SpotSampling` (+ `SpotSourcePolicy`) | Samples dialogue spots across the film, once per stream, on unmetered networks. |
| `AlignmentSession` | One subtitle's track and tracker; turns each outcome into the model, status, and "remember this" decision. |
| `SyncMappingStore` (+ `SyncMappingCodec`, `MappingStorage`) | Remembers and restores a mapping per (stream, subtitle). Hashed keys, versioned, storage behind an interface. |
| `SubtitleRetimer` | `retime(cues, model)` and `dialogueOf(cues)`, both pure. |
| `AnalysisDuty` | Eases analysis off once a mapping has been confirmed for a while. |
| `AudioSyncFallback` | Thin glue for one stream: `arm`, `disarm`, `takeOver`, `stop`, `release`; applies each new model to the sidecar through `commitPreparedSidecarSubtitle`; keeps the "Auto synced" chip in step. Built with `create(...)`. |
| `AudioSyncTaps` | Registry the player's extractor and audio-sink wrappers look up. |
| `AudioSyncStatus`, `AudioTrackMatching`, `NetworkMetering`, `SileroVadResources`, `AudioSyncLog` | Small helpers. |

Dropped from the controller with no replacement: the fetch pool, reference parsing, candidate and
`switchedAway` tracking, `Handover`, recogniser loading and `onAsrLock`, `bridgeReferences`,
`englishCandidates`, `audioIsForeign`, `setReferenceSubtitles`, `setContent`, the diagnostics panel,
and `autoDelayMs` (the fallback retimes cues, so no extra delay is needed).

Measured size: the fork's controller (62 KB) and fallback (18 KB) became 13 files totalling 48 KB, the
largest 9 KB. Each unit is covered by host tests.

Behaviour decisions made while splitting:
- **A confirmed lock is remembered.** In the fork, only the speech-recognition and subtitle-pool
  paths saved a mapping, so a plain VAD lock was never saved and the store would have been dead code
  here. The port saves on a confirmed lock and on a detected jump. Note that stream URLs from
  debrid services often carry per-session tokens, so a replay of the "same" stream may not hit it.
- **A remembered mapping also eases analysis off** after ten minutes, like a fresh lock. The fork
  left such a session on full analysis for as long as it played.
- **Metered networks are re-checked** (at most every 5 s) instead of declining sampling for the whole
  stream, so a viewer who moves onto Wi-Fi mid-film gets it then. There is no mobile-data setting.
- **Spots are planned from the chosen subtitle only.** The fork also used English reference subtitles
  to place them; those came from the recognition path.
- **Stored mappings are never pruned.** Entries are tiny, but they accumulate per stream and
  subtitle. Worth a size cap later.

### Shared (commonMain)
- `AutoSyncPreferencesRepository`: add `audioSyncFallbackEnabled` (default on, profile-scoped), with
  persistence in `AutoSyncPreferencesAndroid` and a no-op in `AutoSyncPreferencesIos`.
- Do **not** bring over `AudioSyncSettings` or `SubtitleSyncStatus`. Their only remaining jobs
  (model state, switch requests, diagnostics overlay) belong to dropped features.

## 5. Integration points in this repo

1. **`PlayerEngine.android.kt` ~317** (`extractorsFactory`): when the fallback is enabled, wrap with
   `AudioSyncTaps.wrapExtractors(factory, sourceUrl)`. Needs a check that it chains with
   `AutoSyncExtractorsFactory` and the libass wrapping (see risks).
2. **`PlayerEngine.android.kt` ~2916** (`buildAudioSink`): wrap the sink with
   `AudioSyncTaps.wrapAudioSink`.
3. **`rememberAutoSyncCoordinator` (~line 660)**: pass the source audio URL (if separate) and the
   `dataSourceFactory` already built at ~328, so spot sampling uses the same headers and proxy.
4. **`AutoSyncPlayerCoordinator`**:
   - construct `AudioSyncFallback`; call `release()` from `dispose()` and `stop()` from `cancel()`;
   - `arm()` once the sidecar subtitle is attached and analysis starts;
   - on `resolved == null`, and only when the outcome is not `SUBTITLE_UNAVAILABLE` and
     `sidecar.activeSidecarSubtitleKey == url`: call `takeOver(url)`; otherwise `disarm()`;
   - `disarm()` on any applied result and on the within-tolerance keep-original path.
5. **Settings UI**: one row in `AutoSyncPlaybackSettingsRows`, shown only while AutoSync is on.
6. **Strings**: English in `autosync_strings.xml`. Other locales fall back to English, as with new
   strings elsewhere; translations are a separate task.
7. **Docs**: update `docs/subtitle-autosync.md`, which currently says it does not analyze audio.

## 6. Behaviour decisions (defaults proposed)

| Decision | Proposal | Why |
|---|---|---|
| Feedback on success | "Auto synced" chip only | Matches the current "only failures show toasts" rule. |
| Feedback while working | A "Syncing to audio…" chip with a spinner on the selected subtitle card, replaced by "Auto synced" when confirmed. No toast. | Analysis can take a while, and silence looks broken. A toast was tried first and dropped. The chip is only visible while the subtitle panel is open. |
| Spot sampling on mobile data | Off; waits for an unmetered network. No setting. | Avoids a surprise download. Look-ahead from already-demuxed audio costs no extra data. |
| Setting default | On, but only reached when AutoSync is on | The fallback only runs after AutoSync keeps the original timing. |
| Remembered mappings | Keep (per stream and subtitle) | Replays instantly on resume or the next session. |

## 7. Phases (each a self-contained commit)

1. **Signal core + weights + tests.** Done (`147f2321`).
2. **Capture.** Done (`0513daeb`).
3. **Orchestration.** Done (`6527e5a5`).
4. **Wire-in.** Done. Preference and settings row (hidden where the platform has no audio fallback),
   strings, the two taps in `PlayerEngine.android.kt` (only while AutoSync and the fallback are both
   on), and the coordinator's `arm` / `takeOver` / `disarm` / `stop` / `freeze` calls. The takeover
   decision is a pure function, `shouldSyncToAudio`, with tests.
5. **Device verification** (checklist in `docs/subtitle-autosync.md`). Not done: nothing has run on a
   device yet.

Wire-in decisions:
- **Setting the delay by hand keeps the synced timing** (`freeze`). Every other cancel puts the
  original timing back (`stop`), so a viewer who nudges the delay after a sync does not lose it.
- **The taps are chosen when the player is created.** Turning the fallback off mid-playback restores
  the original timing at once; turning it on needs the next playback for the look-ahead tap.
- **The fallback object is created for every ExoPlayer**, even with AutoSync off. It is cheap: no
  thread starts and no audio is touched until a subtitle is armed.

## 8. Risks and open questions

- **FFmpeg coverage (checked in phase 2).** The vendored build, on arm64-v8a and armeabi-v7a,
  contains decoders for AAC, AC-3, E-AC-3, DTS, TrueHD/MLP, MP3, Opus, Vorbis, FLAC and ALAC, and
  `FfmpegAudioDecoder`'s constructor matches the fork's shim (read from the AAR with `javap`; the shim
  compiles). Not yet confirmed: that the library loads and decodes on a real device. x86 builds were
  not part of the decoder list I read. Formats with neither a usable MediaCodec nor FFmpeg still
  degrade to the playback-PCM tap, which only hears audio as it plays.
- **Extractor wrapper chaining (partly settled in phase 2).** The tap implements the repo's
  `RewrappableExtractor`, the same contract `AutoSyncExtractorsFactory` uses, so the libass swap can
  still reach the innermost extractor. The real wrapping order and behaviour still need a device
  check with AutoSync on and off.
- **Fork tests (resolved in phases 1 and 2).** `AudioSubtitleSyncTest` and `SpotSamplingTest` had no
  ASR references and are ported, split by unit. `AsrMatchingTest` and `SubtitleCandidatePoolTest`
  are dropped.
- **Spot sampling reads through the player's data source factory.** That is the one the player
  builds, which may include the VOD cache, so sampled stretches could be written to it. Not checked.
- **Accuracy on real content is unproven.** VAD matches speech rhythm, so loud music, songs and heavy
  sound effects can hurt it, and translated subtitles merge, split or drop lines. Judge it on actual
  K-drama and anime episodes before calling it done.
- **Fork code was read selectively.** Every file in phases 1 and 2 was read in full before it was
  copied. Phase 3 rewrites the controller, so it starts with a full read of the controller and the
  fallback.
- **Silero licence.** MIT, Silero Team. Add the notice next to the existing entries in
  `androidMain/assets/licenses` (present in the fork; confirm the folder exists here).

## 9. Known limits

- **ASS/SSA through libass is not covered.** Anime add-on subtitles are often ASS. In this repo those
  go through `startLibass` and `AutoSyncLibassSubtitles`, not the sidecar, so the audio fallback does
  not reach them in this version. The model it produces could be applied to the libass copy using the
  existing libass retiming path; treat that as a follow-up, and a high-value one for anime.
- **libmpv and iOS** are unchanged.
- **Subtitles with very few dialogue lines** are skipped (the fork's `MIN_TRACK_CUES`).

## 10. Verification checklist (device)

1. Fallback off: behaviour identical to today, no extra network or CPU.
2. Embedded match succeeds: the audio path disarms and does no work.
3. Video without embedded subtitles, English subtitle with a constant offset, non-English audio:
   syncs, chip appears, remembered on replay.
4. Same, with a frame-rate-stretched subtitle: scale correction applied.
5. Seek far ahead and back: mapping holds or re-locks; no flicker of old timing.
6. Switch subtitle, disable subtitles, change audio track, leave player: old results never apply.
7. Dolby/DTS audio: either look-ahead works or it degrades to live-only without crashing.
8. Mobile data: no spot-sampling download.
9. Battery and CPU during a full episode with the fallback active.

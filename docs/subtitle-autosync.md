# Subtitle AutoSync

Subtitle AutoSync compares an add-on subtitle with subtitle timing embedded in the
playing video. Confident results can correct a constant delay, frame-rate drift,
or the timing of individual cue groups. When that finds nothing to compare with or
no confident match, Android's audio fallback can line the subtitle up with the speech
in the audio instead (see below). It does not use speech models or recognize words.

Enable **Auto Sync Subtitle** in **Settings → Playback → Subtitle / Audio** after
choosing a preferred subtitle language. The switch is off by default. Its tolerance
and Thorough/Quick search controls appear only while the switch is on. Tolerance
keeps the original timing when the whole-film correction is within the chosen
100–500 ms limit; Off applies all confident corrections.

This port supports Android's internal ExoPlayer with HTTP(S) streams and subtitles
that its sidecar renderer supports. ASS/SSA selected with libass, libmpv, and external
players continue using their existing subtitle behavior; the audio fallback also runs on
iOS (see below).

## Audio fallback

When AutoSync keeps a subtitle's original timing (the video has no embedded subtitles,
none usable, or no confident match), the subtitle is aligned to the speech in the
audio. Speech is detected with Silero VAD, which does not depend on the language
spoken, so an English subtitle can be aligned to Korean or Japanese audio. It
corrects a constant delay, a frame-rate stretch, and later jumps in the timing.

- **Setting:** **Auto Sync: Match the Audio**, shown under the other AutoSync rows
  on Android and iOS and on by default. Turning it off mid-playback puts the subtitle back on
  its original timing.
- **Where the audio comes from:** the audio ExoPlayer is already demuxing (no extra
  download), plus the playing audio where no second decoder can run. On unmetered
  connections it also reads four short stretches of the stream ahead of playback so a
  result can arrive earlier. It waits for an unmetered connection and never samples on
  mobile data.
- **Feedback:** no toast. While the audio is working, the selected subtitle's card in the
  subtitle panel shows a **Syncing to audio…** chip with a small spinner; once a mapping is
  confirmed it becomes the **Auto synced** chip. If an early estimate is withdrawn, the card goes
  back to **Syncing to audio…**. Failures to sync stay silent. The chip is only visible while
  the subtitle panel is open.
- **Setting the delay by hand** keeps the timing applied so far and stops adjusting.
- **Remembered:** a confirmed mapping is saved for that stream and subtitle. Streams
  whose URL changes each session will not find it again.
- **Limits:** it works from speech rhythm, so loud music or songs can hurt accuracy, and
  translated subtitles that merge or drop lines are matched less exactly. On Android,
  ASS/SSA drawn by libass and the libmpv engine are not covered. A subtitle with fewer
  than 20 dialogue lines is skipped.

### On iOS

iOS plays through mpv, which has no audio tap, so the audio comes from mpv's own read-ahead
instead (`LookaheadAudio.swift`, `LookaheadAudioSync.kt`):

- mpv's `dump-cache` writes the buffered range that holds the playhead to a file in Caches, from
  the start of the range, and keeps appending what mpv reads. A second, headless mpv decodes that
  file to 16 kHz mono PCM about 800 times faster than real time. There is no second connection.
- A recording's own clock starts at 0, so each recording is placed on the film's timeline by the
  start of the buffered range when it began. Measured on a real stream, with video, resumed
  mid-film, that is within 20 ms (`tools/mpv-lookahead-probe` on the `proto/ios-mpv-lookahead`
  branch). The resume position alone would be up to a second out.
- **It writes the video too**, since it records what mpv reads. Recording stops as soon as a
  mapping is confirmed, after 6 minutes of recorded media, or after 10 minutes of trying, and
  the files are deleted. The folder is emptied each time a player starts.
- A seek out of the recorded range, or a recording that stops growing while mpv keeps
  buffering, starts a new recording with a new anchor. What was heard before stays on the timeline.
- The result is applied by rewriting the subtitle file's timestamps and swapping it in for the
  add-on subtitle, so **ASS/SSA keeps its styling** through libass. A confirmed mapping is
  remembered for the stream.
- Limits: `dump-cache` is marked experimental by mpv; only MKV, MP4, MOV, MPEG-TS and WebM
  streams can be recorded; a stream with a separate audio URL is not covered (mpv ignores external
  files here); there is no early look-ahead before a subtitle is picked, and no sampling from
  across the film, so it needs a little more playback than Android to lock; and once it locks
  it stops, so a later change of cut is not followed.

### Downloaded episodes

A download plays from a file, and its add-on subtitles are saved beside it, so there is no stream to read
subtitle timing from. AutoSync skips the embedded-subtitle search for these and goes straight to the audio
fallback, reading the saved subtitle file directly. On Android the audio is tapped as it plays (no sampling
ahead); on iOS the headless mpv decodes the first six minutes of the downloaded file, with nothing to record.
The mapping is remembered against the file. ASS/SSA drawn by libass is not covered, as for streams.

At startup, AutoSync can try other add-on subtitles in the same language. When the
user selects a subtitle manually, it checks only that subtitle. An automatic
replacement updates the subtitle selection but does not persist an automatic pick
as a new manual preference for later episodes.

Success is indicated by **Auto synced** on the selected subtitle card. Only failures
show toasts. Changing subtitles, selecting an embedded track, disabling subtitles,
manually adjusting delay, or leaving playback cancels pending analysis. Index
requests start only after an add-on subtitle is selected for AutoSync. Applying a
result swaps sidecar cues without reloading the media source or discarding its buffer.

AutoSync has no debug logging setting and does not emit diagnostic logs.

## Sources

- Mobile integration and retimer:
  [NuvioMobile-Reshaped](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped/tree/5332f76a).
- Audio fallback: the speech detection, alignment and tracking of NuvioMobile-Reshaped's
  audio sync, without its speech recognition. Silero VAD is MIT-licensed
  (`androidMain/assets/licenses/silero-vad.txt`).
- Reviewed matcher, parser, sampled-reference handling, and UX behavior:
  [NuvioTV PR #3703](https://github.com/NuvioMedia/NuvioTV/pull/3703), head `613dbd2d`.
- The extractor rewrapping preserves upstream libass handling underneath AutoSync's
  observation wrapper. Existing parsers, general player preferences, dependencies,
  manifests, branding, and release workflows are kept intact.

## Verification

Local validation covered offsets, drift, grouping, confidence rejection, tolerance,
dense dialogue, reference consistency, no-fit tracking, and subtitle formats. Local
network tests covered subtitle HTTP cancellation, shared index requests, and retrying
an index after its owning request is cancelled. The test files are kept outside
the PR at the author’s request.

Physical-device checks still required:

1. With AutoSync off, verify ordinary subtitle selection and manual delay on Android
   and iOS; verify libmpv and external-player behavior.
2. Enable AutoSync and select an offset or drifting subtitle against an MKV or MP4
   with embedded subtitles. Verify the badge appears without a success toast, and
   playback continues without rebuffering.
3. Check an already-aligned subtitle and a correction inside the selected tolerance.
4. Start analysis, then switch to an embedded subtitle, disable subtitles, choose
   another add-on subtitle, adjust delay, or leave the player. The old result must
   not return or overwrite the new choice.
5. Test a video without embedded subtitles and unsupported ASS/libass rendering.
   With **Match the Audio** off, verify a simple failure toast and the original timing.
6. Audio fallback, with **Match the Audio** on:
   - Pick an offset add-on subtitle for a video without embedded subtitles, with
     non-English audio. Open the subtitle panel and verify the card shows "Syncing to audio…",
     then "Auto synced", and that the dialogue lines up.
   - Repeat with a frame-rate-stretched subtitle, and seek far ahead and back.
   - Verify a dolby or DTS track either syncs or degrades without a crash.
   - On mobile data, verify no sampling download happens.
   - Adjust the delay by hand after a sync: the synced timing stays.
   - Turn the setting off mid-playback: the subtitle returns to its original timing.
   - Verify that, with AutoSync or the setting off, no audio is analysed at all.
   - On iOS, repeat the first three on an MKV and an MP4 stream, resuming mid-film as well as from the
     start. Open the subtitle panel to watch the card, and check that the `lookahead` folder in the app's
     Caches is empty once the sync has locked, and after leaving the player.
   - On iOS, seek well outside the buffered range while it is still syncing; it should start a new
     recording and still lock.
7. Capture the settings rows and selected subtitle badge for PR review.

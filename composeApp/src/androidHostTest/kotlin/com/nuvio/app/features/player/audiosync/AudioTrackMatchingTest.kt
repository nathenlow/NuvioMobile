@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.app.Application
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Robolectric: Format.Builder.setLanguage uses android.text.TextUtils.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioTrackMatchingTest {
    private fun audio(
        id: String? = null,
        mime: String = MimeTypes.AUDIO_AC3,
        language: String? = null,
        channels: Int = 6,
        rate: Int = 48_000,
    ): Format = Format.Builder()
        .setId(id)
        .setSampleMimeType(mime)
        .setLanguage(language)
        .setChannelCount(channels)
        .setSampleRate(rate)
        .build()

    @Test
    fun theMergingSourcePrefixIsIgnoredWhenComparingIds() {
        assertTrue(audio(id = "0:2").isSameAudioTrack(audio(id = "2")))
        assertTrue(audio(id = "1:0:2").isSameAudioTrack(audio(id = "2")))
    }

    @Test
    fun differentIdsAreDifferentTracks() {
        assertFalse(audio(id = "1").isSameAudioTrack(audio(id = "2")))
    }

    @Test
    fun differentCodecsAreDifferentTracks() {
        assertFalse(audio(id = "1").isSameAudioTrack(audio(id = "1", mime = MimeTypes.AUDIO_AAC)))
    }

    @Test
    fun withoutIdsLanguageChannelsAndRateDecide() {
        assertTrue(audio(language = "ko").isSameAudioTrack(audio(language = "ko")))
        assertFalse(audio(language = "ko").isSameAudioTrack(audio(language = "ja")))
        assertFalse(audio(language = "ko").isSameAudioTrack(audio(language = "ko", channels = 2)))
        assertFalse(audio(language = "ko").isSameAudioTrack(audio(language = "ko", rate = 44_100)))
    }
}

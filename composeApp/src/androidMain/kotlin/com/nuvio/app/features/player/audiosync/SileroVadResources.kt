package com.nuvio.app.features.player.audiosync

import android.content.Context
import com.nuvio.app.R

/** The bundled Silero VAD weights, read once and shared by every player. */
internal object SileroVadResources {
    @Volatile
    private var cached: SileroVadWeights? = null

    /** Null (and logged) when the weights cannot be read. */
    fun weights(context: Context): SileroVadWeights? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                context.applicationContext.resources.openRawResource(R.raw.silero_vad_v5_16k).use(SileroVadWeights::read)
            }.onFailure {
                AudioSyncLog.w("could not load VAD weights: ${it.message}")
            }.getOrNull()?.also { cached = it }
        }
    }
}

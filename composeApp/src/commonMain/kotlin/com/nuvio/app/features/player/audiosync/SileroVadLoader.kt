package com.nuvio.app.features.player.audiosync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nuvio.composeapp.generated.resources.Res

/** The bundled Silero VAD weights, read once and shared by every player on both platforms. */
internal object SileroVadLoader {
    private val mutex = Mutex()
    private var cached: SileroVadWeights? = null

    /** Null (and logged) when the weights cannot be read. */
    suspend fun weights(): SileroVadWeights? = mutex.withLock {
        cached ?: try {
            SileroVadWeights.read(Res.readBytes("files/silero_vad_v5_16k.bin")).also { cached = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AudioSyncLog.w("could not load VAD weights: ${error.message}")
            null
        }
    }
}

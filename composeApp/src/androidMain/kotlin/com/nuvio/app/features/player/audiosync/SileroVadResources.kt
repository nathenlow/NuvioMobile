package com.nuvio.app.features.player.audiosync

import kotlinx.coroutines.runBlocking

/**
 * The shared VAD weights for the Android audio sync, which asks for them from its own threads (never the
 * main thread), so waiting for the read is fine.
 */
internal object SileroVadResources {
    fun weightsBlocking(): SileroVadWeights? = runBlocking { SileroVadLoader.weights() }
}

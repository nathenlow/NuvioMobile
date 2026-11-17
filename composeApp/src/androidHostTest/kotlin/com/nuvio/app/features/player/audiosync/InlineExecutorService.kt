package com.nuvio.app.features.player.audiosync

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Runs work on the calling thread, so tests of threaded code stay deterministic. */
internal class InlineExecutorService : AbstractExecutorService() {
    private var shutDown = false

    override fun execute(command: Runnable) {
        if (shutDown) throw RejectedExecutionException()
        command.run()
    }

    override fun shutdown() {
        shutDown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutDown = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutDown

    override fun isTerminated(): Boolean = shutDown

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

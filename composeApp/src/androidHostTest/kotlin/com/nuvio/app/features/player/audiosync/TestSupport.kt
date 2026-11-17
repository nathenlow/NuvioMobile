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

/** A clock tests move by hand. */
internal class FakeClock(var nowMs: Long = 1_000L) {
    fun now(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}

internal class MemoryMappingStorage : MappingStorage {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }
}

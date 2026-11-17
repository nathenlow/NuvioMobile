package com.nuvio.app.features.player.audiosync

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

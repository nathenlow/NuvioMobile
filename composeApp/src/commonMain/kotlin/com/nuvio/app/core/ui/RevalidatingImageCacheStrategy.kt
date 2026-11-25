package com.nuvio.app.core.ui

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.CacheStrategy.ReadResult
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import com.nuvio.app.features.streams.epochMs

/**
 * The server's cache headers decide how long a saved image is trusted, and some hosts allow a year.
 * A poster or cover image replaced behind the same link then keeps showing the old picture. This
 * keeps those headers, but a saved image older than [maxTrustedAgeMs] is checked with the server
 * again (a conditional request: a "not modified" reply costs almost nothing, a new picture replaces
 * the old one).
 */
@OptIn(ExperimentalCoilApi::class)
internal class RevalidatingImageCacheStrategy(
    private val delegate: CacheStrategy = CacheControlCacheStrategy(),
    private val maxTrustedAgeMs: Long = DEFAULT_MAX_TRUSTED_AGE_MS,
    private val nowMs: () -> Long = ::epochMs,
) : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): ReadResult {
        val result = delegate.read(cacheResponse, networkRequest, options)
        val usesSavedImage = result.response != null
        if (!usesSavedImage || nowMs() - cacheResponse.responseMillis <= maxTrustedAgeMs) return result

        val builder = networkRequest.headers.newBuilder()
        val etag = cacheResponse.headers["ETag"]
        val lastModified = cacheResponse.headers["Last-Modified"]
        when {
            etag != null -> builder["If-None-Match"] = etag
            lastModified != null -> builder["If-Modified-Since"] = lastModified
            // Nothing to compare with: fetch it again.
            else -> return ReadResult(networkRequest)
        }
        return ReadResult(networkRequest.copy(headers = builder.build()))
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult = delegate.write(cacheResponse, networkRequest, networkResponse, options)

    companion object {
        /** A saved image is trusted without asking for this long at most (one week). */
        const val DEFAULT_MAX_TRUSTED_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}

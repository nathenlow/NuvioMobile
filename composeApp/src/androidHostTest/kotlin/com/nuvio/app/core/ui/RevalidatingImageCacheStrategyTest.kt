package com.nuvio.app.core.ui

import android.app.Application
import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.CacheStrategy.ReadResult
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RevalidatingImageCacheStrategyTest {
    private val now = 10L * 60L * 60L * 1000L
    private val hour = 60L * 60L * 1000L
    private val request = NetworkRequest(url = "https://example.com/cover.png")
    private val options get() = Options(RuntimeEnvironment.getApplication())

    /** A delegate that trusts every saved image, as a server allowing a year of caching does. */
    private val trustsSaved = object : CacheStrategy {
        override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options) =
            ReadResult(cacheResponse)

        override suspend fun write(
            cacheResponse: NetworkResponse?,
            networkRequest: NetworkRequest,
            networkResponse: NetworkResponse,
            options: Options,
        ) = CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
    }

    private fun strategy(delegate: CacheStrategy = trustsSaved) =
        RevalidatingImageCacheStrategy(delegate = delegate, maxTrustedAgeMs = hour, nowMs = { now })

    private fun saved(ageMs: Long, vararg headers: Pair<String, String>) = NetworkResponse(
        responseMillis = now - ageMs,
        headers = NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> this[k] = v } }.build(),
    )

    private fun read(response: NetworkResponse, delegate: CacheStrategy = trustsSaved) =
        runBlocking { strategy(delegate).read(response, request, options) }

    @Test
    fun aSavedImageIsTrustedForAWeekByDefault() {
        assertEquals(7L * 24L * 60L * 60L * 1000L, RevalidatingImageCacheStrategy.DEFAULT_MAX_TRUSTED_AGE_MS)
    }

    @Test
    fun aRecentSavedImageIsUsedWithoutAsking() {
        val response = saved(ageMs = 10L * 60L * 1000L, "ETag" to "\"v1\"")

        val result = read(response)

        assertEquals(response, result.response)
        assertNull(result.request)
    }

    @Test
    fun anOldSavedImageIsCheckedWithItsEtag() {
        val result = read(saved(ageMs = 2 * hour, "ETag" to "\"v1\""))

        assertNull(result.response)
        assertEquals("\"v1\"", result.request?.headers?.get("If-None-Match"))
    }

    @Test
    fun anOldSavedImageWithOnlyADateIsCheckedWithIt() {
        val result = read(saved(ageMs = 2 * hour, "Last-Modified" to "Wed, 21 Oct 2026 07:28:00 GMT"))

        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", result.request?.headers?.get("If-Modified-Since"))
    }

    @Test
    fun anOldSavedImageWithNothingToCompareIsFetchedAgain() {
        val result = read(saved(ageMs = 2 * hour))

        assertNull(result.response)
        assertNull(result.request?.headers?.get("If-None-Match"))
        assertEquals(request.url, result.request?.url)
    }

    @Test
    fun aRequestTheServerHeadersAlreadyCallForIsLeftAlone() {
        val network = object : CacheStrategy by trustsSaved {
            override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options) =
                ReadResult(networkRequest)
        }

        val result = read(saved(ageMs = 5 * hour, "ETag" to "\"v1\""), network)

        assertEquals(request, result.request)
    }
}

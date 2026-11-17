package com.nuvio.app.features.autosync

/**
 * Where the playing media comes from. Downloaded episodes play from a file on the device, and their
 * subtitles are saved beside them as files too, so AutoSync meets `file:` addresses (Android writes `file:/path`, iOS `file:///path`) as well as
 * `http(s)://` ones.
 */
internal fun isLocalFileUrl(url: String): Boolean = url.startsWith("file:", ignoreCase = true)

internal fun isRemoteUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/** True for a source AutoSync can work with: a stream, or a download on the device. */
internal fun isSyncableSource(url: String): Boolean = isRemoteUrl(url) || isLocalFileUrl(url)

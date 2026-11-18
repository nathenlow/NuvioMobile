package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The download profiles and which one bulk download uses. The built-in ones live in code, so they
 * improve with the app; only the editable ones and the choice are stored, device-wide.
 */
object DownloadProfilesRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Serializable
    private data class Stored(
        val activeId: String = PROFILE_ID_CACHED,
        val profiles: List<DownloadProfile> = emptyList(),
        val format: StreamFormatSetup = StreamFormatSetup(),
    )

    // Names are set by the screens, which can read the string resources.
    private var cachedName = "Cached debrid"
    private var httpName = "HTTP links"
    private var diyName = "My profile"

    private val _profiles = MutableStateFlow<List<DownloadProfile>>(emptyList())
    val profiles: StateFlow<List<DownloadProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(PROFILE_ID_CACHED)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _format = MutableStateFlow(StreamFormatSetup())
    /** The addon's name and description templates and the markers read from them. */
    val format: StateFlow<StreamFormatSetup> = _format.asStateFlow()

    private var loaded = false

    fun setBuiltInNames(cached: String, http: String, diy: String) {
        cachedName = cached
        httpName = http
        diyName = diy
        if (loaded) {
            _profiles.value = _profiles.value.map { profile ->
                when (profile.id) {
                    PROFILE_ID_CACHED -> profile.copy(name = cached)
                    PROFILE_ID_HTTP -> profile.copy(name = http)
                    else -> profile
                }
            }
        }
    }

    fun ensureLoaded() {
        if (loaded) return
        val stored = DownloadsStorage.loadProfilesPayload()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<Stored>(it) }.getOrNull() }
            ?: Stored()
        val custom = stored.profiles.filter { !it.builtIn }.let { list ->
            if (list.any { it.id == PROFILE_ID_DIY }) list else listOf(diyProfile(diyName)) + list
        }
        val all = listOf(cachedDebridProfile(cachedName), httpProfile(httpName)) + custom
        _profiles.value = all
        _activeId.value = stored.activeId.takeIf { id -> all.any { it.id == id } } ?: PROFILE_ID_CACHED
        _format.value = stored.format
        loaded = true
    }

    /** The markers that tell cached, source and the like from a stream's text. */
    fun markers(): List<StreamMarker> {
        ensureLoaded()
        return _format.value.markers
    }

    fun saveFormat(setup: StreamFormatSetup) {
        ensureLoaded()
        _format.value = setup
        persist()
    }

    /** The profile bulk download uses now. */
    fun active(): DownloadProfile {
        ensureLoaded()
        return _profiles.value.firstOrNull { it.id == _activeId.value } ?: cachedDebridProfile(cachedName)
    }

    fun setActive(id: String) {
        ensureLoaded()
        if (_profiles.value.none { it.id == id }) return
        _activeId.value = id
        persist()
    }

    /** Saves an edited custom profile. Built-in profiles are never changed. */
    fun save(profile: DownloadProfile) {
        ensureLoaded()
        if (profile.builtIn) return
        val current = _profiles.value
        _profiles.value = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        persist()
    }

    /** A new editable copy of [source]. Returns it. */
    fun duplicate(source: DownloadProfile, name: String): DownloadProfile {
        ensureLoaded()
        var n = 1
        while (_profiles.value.any { it.id == "custom-$n" }) n++
        val copy = source.copy(id = "custom-$n", name = name, builtIn = false)
        save(copy)
        return copy
    }

    /** Removes a custom profile. The DIY profile is reset to its starting rules instead. */
    fun delete(id: String) {
        ensureLoaded()
        val target = _profiles.value.firstOrNull { it.id == id } ?: return
        if (target.builtIn) return
        _profiles.value = if (id == PROFILE_ID_DIY) {
            _profiles.value.map { if (it.id == id) diyProfile(it.name) else it }
        } else {
            _profiles.value.filterNot { it.id == id }
        }
        if (_activeId.value == id && id != PROFILE_ID_DIY) _activeId.value = PROFILE_ID_CACHED
        persist()
    }

    /** The profile as JSON, to share it. */
    fun export(profile: DownloadProfile): String =
        Json { prettyPrint = true; encodeDefaults = false }.encodeToString(profile)

    /** A profile from JSON, saved as a new editable one. Null when the text is not a profile. */
    fun import(text: String): DownloadProfile? {
        val parsed = runCatching { json.decodeFromString<DownloadProfile>(text.trim()) }.getOrNull() ?: return null
        ensureLoaded()
        return duplicate(parsed, parsed.name.ifBlank { diyName })
    }

    fun clearLocalState() {
        loaded = false
    }

    private fun persist() {
        DownloadsStorage.saveProfilesPayload(
            json.encodeToString(
                Stored(
                    activeId = _activeId.value,
                    profiles = _profiles.value.filter { !it.builtIn },
                    format = _format.value,
                ),
            ),
        )
    }
}

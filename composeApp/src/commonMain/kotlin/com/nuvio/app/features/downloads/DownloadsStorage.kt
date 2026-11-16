package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun loadMaxConcurrentDownloads(): Int?
    fun saveMaxConcurrentDownloads(value: Int)
    fun loadSaveFullEpisodeList(): Boolean?
    fun saveSaveFullEpisodeList(value: Boolean)
    fun loadPreferredResolution(): Int?
    fun savePreferredResolution(value: Int)
    fun loadProfilesPayload(): String?
    fun saveProfilesPayload(payload: String)
    fun loadStorageVolumeId(): String?
    fun saveStorageVolumeId(value: String)
}

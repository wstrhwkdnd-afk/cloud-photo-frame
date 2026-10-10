package com.cloudframe.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The list of photos in the chosen Degoo folder, cached on the TV so the
 * screensaver starts instantly and works when Degoo is slow or offline.
 */
class PhotoLibrary(context: Context, private val api: DegooApi, private val prefs: Prefs) {

    data class Photo(val id: String, val name: String, val url: String, val created: String)

    private val file = File(context.filesDir, "photos.json")
    private val mutex = Mutex()
    private var memory: List<Photo>? = null
    private var memoryFolder = ""

    suspend fun photos(forceRefresh: Boolean = false, onProgress: (Int) -> Unit = {}): List<Photo> = mutex.withLock {
        val folder = prefs.folderId
        if (folder.isEmpty()) return emptyList()
        if (!forceRefresh && memoryFolder == folder) memory?.let { return it }

        val cached = withContext(Dispatchers.IO) { readCache(folder) }
        val stale = cached == null || System.currentTimeMillis() - cached.first > MAX_AGE_MS
        if (cached != null && !forceRefresh && !stale) return remember(folder, cached.second)

        return try {
            val fresh = api.allImages(folder, onProgress = onProgress)
                .map { Photo(it.id, it.name, it.url, it.created) }
            withContext(Dispatchers.IO) { writeCache(folder, fresh) }
            remember(folder, fresh)
        } catch (e: Exception) {
            // Offline or Degoo hiccup: fall back to the last known list
            if (cached != null) remember(folder, cached.second) else throw e
        }
    }

    /** Signed download links expire, so ask Degoo for a fresh one. */
    suspend fun freshUrl(photo: Photo): String = api.item(photo.id).url

    fun clear() {
        memory = null
        file.delete()
    }

    private fun remember(folder: String, list: List<Photo>): List<Photo> {
        memory = list; memoryFolder = folder
        return list
    }

    private fun readCache(folder: String): Pair<Long, List<Photo>>? = runCatching {
        val o = JSONObject(file.readText())
        if (o.getString("folder") != folder) return null
        val arr = o.getJSONArray("photos")
        o.getLong("scannedAt") to (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            Photo(p.getString("id"), p.optString("name"), p.optString("url"), p.optString("created"))
        }
    }.getOrNull()

    private fun writeCache(folder: String, list: List<Photo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("created", it.created))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("folder", folder).put("scannedAt", System.currentTimeMillis())
            .put("photos", arr).toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val MAX_AGE_MS = 12L * 60 * 60 * 1000   // re-scan Degoo twice a day
    }
}

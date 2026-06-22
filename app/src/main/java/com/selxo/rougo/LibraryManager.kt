package com.selxo.rougo

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.lazy.items
import androidx.core.content.edit
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class LibraryManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("rougo_library", Context.MODE_PRIVATE)

    fun getItems(): List<LibraryItem> {
        val jsonString = prefs.getString("items", "[]") ?: "[]"
        val jsonArray = try {
            JSONArray(jsonString)
        } catch (e: Exception) {
            CrashReporter.recordHandled(appContext, "LibraryManager.getItems root JSON", e)
            JSONArray()
        }
        val list = mutableListOf<LibraryItem>()
        for (i in 0 until jsonArray.length()) {
            try {
                val obj = jsonArray.getJSONObject(i)
                val itemKind = runCatching {
                    LibraryItemKind.valueOf(obj.optString("itemKind", LibraryItemKind.Media.name))
                }.getOrDefault(LibraryItemKind.Media)
                val mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() }
                    ?: if (itemKind == LibraryItemKind.Playlist) "" else continue

                val recordingsList = mutableListOf<ShadowRecording>()
                if (obj.has("recordings")) {
                    val recArray = obj.optJSONArray("recordings") ?: JSONArray()
                    for (j in 0 until recArray.length()) {
                        try {
                            val recObj = recArray.getJSONObject(j)
                            val filePath = recObj.optString("filePath").takeIf { it.isNotBlank() } ?: continue
                            recordingsList.add(
                                ShadowRecording(
                                    id = recObj.optString("id", UUID.randomUUID().toString()),
                                    filePath = filePath,
                                    startTime = recObj.optLong("startTime", 0L),
                                    endTime = recObj.optLong("endTime", 0L),
                                    timestamp = recObj.optLong("timestamp", System.currentTimeMillis())
                                )
                            )
                        } catch (e: Exception) {
                            CrashReporter.recordHandled(appContext, "LibraryManager.getItems recording $i/$j", e)
                        }
                    }
                }

                list.add(
                    LibraryItem(
                        id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        title = obj.optCleanString("title") ?: "Unknown Media",
                        mediaUri = mediaUri,
                        subtitleUri = obj.optString("subtitleUri").takeIf { it.isNotBlank() },
                        progress = obj.optLong("progress", 0L),
                        duration = obj.optLong("duration", 0L),
                        isVideo = obj.optBoolean("isVideo", false),
                        recordings = recordingsList,
                        sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() },
                        formatId = obj.optString("formatId").takeIf { it.isNotBlank() },
                        artist = obj.optCleanString("artist"),
                        album = obj.optCleanString("album"),
                        albumArtist = obj.optCleanString("albumArtist"),
                        genre = obj.optCleanString("genre"),
                        year = obj.optCleanString("year"),
                        coverArtPath = obj.optCleanString("coverArtPath"),
                        httpUserAgent = obj.optCleanString("httpUserAgent"),
                        httpReferer = obj.optCleanString("httpReferer"),
                        itemKind = itemKind,
                        parentId = obj.optString("parentId").takeIf { it.isNotBlank() },
                        playlistSourceUrl = obj.optString("playlistSourceUrl").takeIf { it.isNotBlank() },
                        playlistItemIndex = obj.optInt("playlistItemIndex", 0)
                    )
                )
            } catch (e: Exception) {
                CrashReporter.recordHandled(appContext, "LibraryManager.getItems item $i", e)
            }
        }
        return list
    }

    fun saveItem(item: LibraryItem) {
        val current = getItems().toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) current[index] = item else current.add(0, item)
        prefs.edit { putString("items", itemsToJson(current).toString()) }
    }

    fun deleteItem(id: String) {
        val items = getItems()
        items.filter { it.id == id || it.parentId == id }.forEach { item ->
            deleteLibraryItemAssociatedFiles(appContext, item)
        }
        prefs.edit { putString("items", itemsToJson(items.filter { it.id != id && it.parentId != id }).toString()) }
    }

    private fun itemsToJson(items: List<LibraryItem>): JSONArray {
        val array = JSONArray()
        items.forEach { array.put(itemToJson(it)) }
        return array
    }

    private fun itemToJson(item: LibraryItem): JSONObject {
        val obj = JSONObject()
        obj.put("id", item.id); obj.put("title", item.title); obj.put("mediaUri", item.mediaUri)
        obj.put("subtitleUri", item.subtitleUri ?: ""); obj.put("progress", item.progress)
        obj.put("duration", item.duration); obj.put("isVideo", item.isVideo)
        obj.put("sourceUrl", item.sourceUrl ?: ""); obj.put("formatId", item.formatId ?: "")
        obj.put("artist", item.artist ?: ""); obj.put("album", item.album ?: "")
        obj.put("albumArtist", item.albumArtist ?: ""); obj.put("genre", item.genre ?: "")
        obj.put("year", item.year ?: ""); obj.put("coverArtPath", item.coverArtPath ?: "")
        obj.put("httpUserAgent", item.httpUserAgent ?: ""); obj.put("httpReferer", item.httpReferer ?: "")
        obj.put("itemKind", item.itemKind.name); obj.put("parentId", item.parentId ?: "")
        obj.put("playlistSourceUrl", item.playlistSourceUrl ?: "")
        obj.put("playlistItemIndex", item.playlistItemIndex)

        val recArray = JSONArray()
        item.recordings.forEach { rec ->
            val recObj = JSONObject()
            recObj.put("id", rec.id); recObj.put("filePath", rec.filePath)
            recObj.put("startTime", rec.startTime); recObj.put("endTime", rec.endTime)
            recObj.put("timestamp", rec.timestamp)
            recArray.put(recObj)
        }
        obj.put("recordings", recArray)
        return obj
    }
}

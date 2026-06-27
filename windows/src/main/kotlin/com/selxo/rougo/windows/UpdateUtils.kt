package com.selxo.rougo.windows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.awt.Desktop
import java.net.URI
import java.time.Instant

data class UpdateInfo(
    val tagName: String,
    val downloadUrl: String,
    val body: String,
    val publishedAtMillis: Long
)

suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val api = URI("https://api.github.com/repos/1Selxo/rougo/releases").toURL()
        val text = api.openConnection().apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Rougo-Windows")
        }.getInputStream().bufferedReader().use { it.readText() }
        val releases = JSONArray(text)
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.optJSONArray("assets") ?: JSONArray()
            var downloadUrl = release.optString("html_url")
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                if (name.endsWith(".msi", ignoreCase = true) || name.endsWith(".exe", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url", downloadUrl)
                    break
                }
            }
            return@withContext UpdateInfo(
                tagName = release.optString("tag_name"),
                downloadUrl = downloadUrl,
                body = release.optString("body"),
                publishedAtMillis = parseGithubTimestamp(release.optString("published_at"))
            )
        }
        null
    }.getOrElse {
        CrashReporter.recordHandled("checkForUpdates", it)
        null
    }
}

fun openUpdateDownload(info: UpdateInfo) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(info.downloadUrl))
        }
    }.onFailure {
        CrashReporter.recordHandled("openUpdateDownload", it)
    }
}

fun isNewerVersion(remoteTag: String, currentVersion: String?): Boolean {
    val remote = versionParts(remoteTag)
    val current = versionParts(currentVersion ?: return true)
    val max = maxOf(remote.size, current.size)
    for (i in 0 until max) {
        val r = remote.getOrElse(i) { 0 }
        val c = current.getOrElse(i) { 0 }
        if (r != c) return r > c
    }
    return false
}

fun isUpdateAvailable(info: UpdateInfo, currentVersion: String?): Boolean =
    isNewerVersion(info.tagName, currentVersion)

private fun versionParts(value: String): List<Int> =
    Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()

private fun parseGithubTimestamp(value: String): Long =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

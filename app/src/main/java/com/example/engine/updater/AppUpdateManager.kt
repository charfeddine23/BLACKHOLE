package com.example.engine.updater

import com.example.BuildConfig
import com.example.engine.extractor.VideoStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GitHubReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long,
) {
    val preferredLaunchUrl: String
        get() = apkDownloadUrl?.takeIf { it.isNotBlank() } ?: releasePageUrl
}

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpToDate(val currentVersion: String) : UpdateCheckState()
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

class AppUpdateManager(
    private val client: OkHttpClient = VideoStreamExtractor.defaultOkHttpClient(),
) {

    suspend fun checkLatestRelease(
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): UpdateCheckState = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Blackhole-Pulse-Android/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext UpdateCheckState.UpToDate(normalizeVersion(currentVersion))
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckState.Error(
                        "Update server returned HTTP ${response.code}",
                    )
                }

                val rawJson = response.body?.string().orEmpty()
                if (rawJson.isBlank()) {
                    return@withContext UpdateCheckState.Error("Empty response from GitHub Releases")
                }

                val json = JSONObject(rawJson)
                if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                    return@withContext UpdateCheckState.UpToDate(normalizeVersion(currentVersion))
                }

                val tagName = json.optString("tag_name").trim()
                val remoteVersion = normalizeVersion(tagName)
                val releaseTitle = json.optString("name").trim().ifBlank { tagName }
                val releaseNotes = json.optString("body").trim()
                    .ifBlank { "New release available from charfeddine23/BLACKHOLE." }
                val htmlUrl = json.optString("html_url").trim()
                    .ifBlank { REPO_RELEASES_FALLBACK_URL }

                var apkUrl: String? = null
                var apkName: String? = null
                var apkSize = -1L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkName = name
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            apkSize = asset.optLong("size", -1L)
                            break
                        }
                    }
                }

                if (remoteVersion.isNotBlank() && isNewerVersion(remoteVersion, currentVersion)) {
                    UpdateCheckState.UpdateAvailable(
                        GitHubReleaseInfo(
                            versionName = remoteVersion,
                            tagName = tagName.ifBlank { "v$remoteVersion" },
                            releaseTitle = releaseTitle,
                            releaseNotes = releaseNotes,
                            releasePageUrl = htmlUrl,
                            apkDownloadUrl = apkUrl,
                            apkFileName = apkName,
                            apkSizeBytes = apkSize,
                        ),
                    )
                } else {
                    UpdateCheckState.UpToDate(normalizeVersion(currentVersion))
                }
            }
        } catch (e: Exception) {
            UpdateCheckState.Error(e.message ?: "Unable to reach GitHub Releases")
        }
    }

    internal fun normalizeVersion(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .trim()
    }

    internal fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = normalizeVersion(remote)
            .split(".")
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val currentParts = normalizeVersion(current)
            .split(".")
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    companion object {
        const val REPO_URL = "https://github.com/charfeddine23/BLACKHOLE"
        const val REPO_RELEASES_FALLBACK_URL = "https://github.com/charfeddine23/BLACKHOLE/releases/latest"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/charfeddine23/BLACKHOLE/releases/latest"
    }
}

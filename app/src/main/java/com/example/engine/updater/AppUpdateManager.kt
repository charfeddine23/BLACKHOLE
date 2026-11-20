package com.example.engine.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import com.example.BuildConfig
import com.example.engine.extractor.VideoStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

data class GitHubReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long,
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpToDate(val currentVersion: String) : UpdateCheckState()
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckState()
    data class Downloading(val release: GitHubReleaseInfo, val progress: Float) : UpdateCheckState()
    data class ReadyToInstall(val release: GitHubReleaseInfo, val apkFile: File) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

sealed class InAppInstallOutcome {
    data object SessionCommitted : InAppInstallOutcome()
    data object NeedsInstallPermission : InAppInstallOutcome()
    data class Failed(val message: String) : InAppInstallOutcome()
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

    suspend fun downloadApkInApp(
        context: Context,
        release: GitHubReleaseInfo,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val downloadUrl = release.apkDownloadUrl?.takeIf { it.isNotBlank() } ?: return@withContext null

        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        val safeName = (release.apkFileName ?: "Blackhole.${release.tagName}.apk")
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val targetFile = File(updatesDir, safeName)

        // Reuse valid complete file if already cached for this exact release
        if (targetFile.exists() && targetFile.length() > 100_000L &&
            (release.apkSizeBytes <= 0L || targetFile.length() == release.apkSizeBytes)
        ) {
            onProgress(1f)
            return@withContext targetFile
        }

        // Clean out older partial or stale APK files
        updatesDir.listFiles()?.forEach { file ->
            if (file.name != safeName) file.delete()
        }

        val partFile = File(updatesDir, "$safeName.part")
        if (partFile.exists()) partFile.delete()

        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Blackhole-Pulse-Android/${BuildConfig.VERSION_NAME}")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val totalBytes = body.contentLength().takeIf { it > 0L }
                    ?: release.apkSizeBytes.takeIf { it > 0L }
                    ?: -1L

                body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = 0L
                        var lastEmitMs = 0L

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmitMs >= 60L) {
                                val fraction = if (totalBytes > 0L) {
                                    (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0.02f, 0.99f)
                                } else {
                                    // Smooth progressive arc if Content-Length header is absent
                                    (1f - 1f / (1f + downloaded / (8f * 1024f * 1024f))).coerceIn(0.05f, 0.95f)
                                }
                                onProgress(fraction)
                                lastEmitMs = now
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (partFile.exists() && partFile.length() > 100_000L) {
                if (targetFile.exists()) targetFile.delete()
                partFile.renameTo(targetFile)
                onProgress(1f)
                targetFile
            } else {
                partFile.delete()
                null
            }
        }.getOrElse {
            partFile.delete()
            null
        }
    }

    suspend fun installApkInApp(
        context: Context,
        apkFile: File,
    ): InAppInstallOutcome = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return@withContext InAppInstallOutcome.Failed("Update package file not found.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return@withContext InAppInstallOutcome.NeedsInstallPermission
        }

        var session: PackageInstaller.Session? = null
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            session.openWrite("blackhole_update.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(out)
                }
                session.fsync(out)
            }

            val receiverIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                receiverIntent,
                flags,
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            InAppInstallOutcome.SessionCommitted
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            InAppInstallOutcome.Failed(e.message ?: "Unable to commit package update.")
        }
    }

    fun cleanupInstalledUpdates(context: Context) {
        val updatesDir = File(context.filesDir, "updates")
        if (!updatesDir.exists()) return
        val currentVer = normalizeVersion(BuildConfig.VERSION_NAME)
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") || file.name.contains(currentVer)) {
                file.delete()
            }
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
        const val REPO_RELEASES_FALLBACK_URL = "https://github.com/charfeddine23/BLACKHOLE/releases/latest"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/charfeddine23/BLACKHOLE/releases/latest"
    }
}

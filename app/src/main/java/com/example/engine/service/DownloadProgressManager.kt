package com.example.engine.service

import com.example.data.local.DownloadedVideoEntity
import com.example.engine.model.DownloadStatus
import com.example.engine.model.DownloadTaskState
import com.example.engine.model.PlatformBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single reactive StateFlow source of truth for active WorkManager downloads
 * and completed/failed download cards in the Blackhole UI.
 */
object DownloadProgressManager {

    private val _tasks = MutableStateFlow<List<DownloadTaskState>>(emptyList())
    val tasks: StateFlow<List<DownloadTaskState>> = _tasks.asStateFlow()

    fun upsertTask(task: DownloadTaskState) {
        _tasks.update { current ->
            val index = current.indexOfFirst { it.taskId == task.taskId }
            if (index >= 0) {
                current.toMutableList().apply { set(index, task) }
            } else {
                listOf(task) + current
            }
        }
    }

    fun updateTask(taskId: String, transform: (DownloadTaskState) -> DownloadTaskState) {
        _tasks.update { current ->
            val index = current.indexOfFirst { it.taskId == taskId }
            if (index >= 0) {
                current.toMutableList().apply {
                    set(index, transform(get(index)))
                }
            } else {
                current
            }
        }
    }

    fun removeTask(taskId: String) {
        _tasks.update { current -> current.filterNot { it.taskId == taskId } }
    }

    fun removeBySavedVideoId(videoId: Long) {
        _tasks.update { current -> current.filterNot { it.savedVideoId == videoId } }
    }

    fun syncPersistedCompletedVideos(persisted: List<DownloadedVideoEntity>) {
        _tasks.update { current ->
            val activeOrRecent = current.toMutableList()
            val trackedSavedIds = activeOrRecent.mapNotNull { it.savedVideoId }.toHashSet()
            val trackedUris = activeOrRecent.mapNotNull { it.mediaStoreUri }.toHashSet()

            persisted.forEach { entity ->
                if (!trackedSavedIds.contains(entity.id) && !trackedUris.contains(entity.contentUri)) {
                    activeOrRecent.add(
                        DownloadTaskState(
                            taskId = "saved_${entity.id}",
                            sourceUrl = entity.sourceUrl,
                            title = entity.title,
                            thumbnailUrl = entity.thumbnailPath,
                            platform = PlatformBadge.entries.firstOrNull {
                                it.label.equals(entity.platform, ignoreCase = true)
                            } ?: PlatformBadge.fromUrl(entity.sourceUrl),
                            qualityLabel = entity.qualityLabel,
                            videoUrl = entity.streamUrl,
                            mimeType = entity.mimeType,
                            status = DownloadStatus.Completed,
                            progress = 1f,
                            downloadedBytes = entity.fileSizeBytes,
                            totalBytes = entity.fileSizeBytes,
                            mediaStoreUri = entity.contentUri,
                            localFilePath = entity.filePath,
                            localThumbnailPath = entity.thumbnailPath,
                            savedVideoId = entity.id,
                            createdAt = entity.createdAt,
                        ),
                    )
                }
            }
            activeOrRecent.sortedWith(
                compareByDescending<DownloadTaskState> { it.isActive }
                    .thenByDescending { it.createdAt },
            )
        }
    }
}

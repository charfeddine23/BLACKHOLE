package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.DownloadedVideoDao
import com.example.data.local.DownloadedVideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class VideoVaultRepository(
    private val context: Context,
    private val videoDao: DownloadedVideoDao,
) {
    val allVideos: Flow<List<DownloadedVideoEntity>> = videoDao.observeAllVideos()

    suspend fun insertVideo(video: DownloadedVideoEntity): Long {
        return videoDao.insertVideo(video)
    }

    suspend fun getVideoById(id: Long): DownloadedVideoEntity? {
        return videoDao.getVideoById(id)
    }

    suspend fun deleteVideo(video: DownloadedVideoEntity) {
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(video.contentUri)
                if (uri.scheme == "content") {
                    context.contentResolver.delete(uri, null, null)
                }
            }
            if (video.filePath.isNotBlank()) {
                runCatching { File(video.filePath).delete() }
            }
            if (!video.thumbnailPath.isNullOrBlank()) {
                runCatching { File(video.thumbnailPath).delete() }
            }
            videoDao.deleteVideoById(video.id)
        }
    }
}

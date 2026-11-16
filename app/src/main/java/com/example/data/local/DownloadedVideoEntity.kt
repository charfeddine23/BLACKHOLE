package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_videos")
data class DownloadedVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val sourceUrl: String,
    val streamUrl: String,
    val platform: String,
    val qualityLabel: String,
    val resolution: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val contentUri: String,
    val filePath: String,
    val thumbnailPath: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

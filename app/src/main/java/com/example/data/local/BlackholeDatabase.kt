package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadedVideoEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BlackholeDatabase : RoomDatabase() {
    abstract fun downloadedVideoDao(): DownloadedVideoDao

    companion object {
        @Volatile
        private var INSTANCE: BlackholeDatabase? = null

        fun getInstance(context: Context): BlackholeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlackholeDatabase::class.java,
                    "blackhole_vault.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}

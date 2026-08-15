package com.elladecide.lola.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Product::class, SyncMeta::class],
    version = 1,
    exportSchema = false,
)
abstract class LolaDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        fun build(context: Context): LolaDatabase =
            Room.databaseBuilder(context, LolaDatabase::class.java, "lola.db")
                .build()
    }
}

package com.elladecide.lola.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Product::class, SyncMeta::class],
    version = 2,
    exportSchema = false,
)
abstract class LolaDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        /** v1 → v2: campos de verificación de integridad en sync_meta. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_meta ADD COLUMN row_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_meta ADD COLUMN last_verified_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): LolaDatabase =
            Room.databaseBuilder(context, LolaDatabase::class.java, "lola.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

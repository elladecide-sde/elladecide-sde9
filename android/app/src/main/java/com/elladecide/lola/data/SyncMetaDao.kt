package com.elladecide.lola.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncMetaDao {

    @Query("SELECT * FROM sync_meta WHERE `key` = 'catalog'")
    suspend fun get(): SyncMeta?

    @Upsert
    suspend fun upsert(meta: SyncMeta)
}

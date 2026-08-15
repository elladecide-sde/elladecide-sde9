package com.elladecide.lola.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Estado de sincronización local (fila singleton).
 * Guarda el { revision, hash } que devolvió el último sync exitoso, para
 * compararlo contra /api/v1/version y saber si hay que re-sincronizar.
 */
@Entity(tableName = "sync_meta")
data class SyncMeta(
    @PrimaryKey val key: String = "catalog",
    val revision: Int = 0,
    val hash: String = "",
)

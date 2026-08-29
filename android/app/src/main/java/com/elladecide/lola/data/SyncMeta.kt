package com.elladecide.lola.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Estado de sincronización local (fila singleton).
 *
 * [revision] y [hash] son los que devolvió el último sync exitoso, para
 * compararlos contra /api/v1/version y saber si hay que re-sincronizar.
 *
 * [count] y [lastVerifiedAt] sirven para detectar CORRUPCIÓN local:
 *  - [count] = filas de products tras el último sync. Si en una apertura el
 *    conteo real difiere, la tabla se tocó → resync forzado (chequeo barato).
 *  - [lastVerifiedAt] = epoch ms de la última vez que se recomputó el hash local
 *    ([CatalogHash]) y coincidió con [hash] (chequeo completo, throttled).
 */
@Entity(tableName = "sync_meta")
data class SyncMeta(
    @PrimaryKey val key: String = "catalog",
    val revision: Int = 0,
    val hash: String = "",
    @ColumnInfo(name = "row_count") val count: Int = 0,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long = 0L,
)

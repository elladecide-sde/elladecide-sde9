package com.elladecide.lola.data

/**
 * Decisiones PURAS del sync, testables sin red ni base de datos.
 * [SyncRepository] las compone.
 */
object SyncPolicy {

    /** Intervalo mínimo entre verificaciones completas de integridad local. */
    const val VERIFY_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /**
     * Chequeo barato por conteo (cada apertura): si products tiene un número de
     * filas distinto al que dejó el último sync exitoso, la tabla se tocó.
     * Solo tiene sentido cuando ya hubo al menos un sync ([SyncMeta.count] > 0);
     * antes de eso el conteo local no es referencia.
     */
    fun countCorrupted(meta: SyncMeta, localCount: Int): Boolean =
        meta.count > 0 && localCount != meta.count

    /**
     * ¿Toca la verificación completa (recomputar [CatalogHash] sobre products)?
     * Una vez por proceso y, como mínimo, cada [VERIFY_INTERVAL_MS]. Solo tiene
     * sentido si ya hubo al menos un sync ([SyncMeta.hash] no vacío): sin eso no
     * hay hash de referencia contra el que comparar.
     */
    fun needsFullVerify(meta: SyncMeta, verifiedThisProcess: Boolean, now: Long): Boolean =
        meta.hash.isNotEmpty() &&
            !verifiedThisProcess &&
            (meta.lastVerifiedAt == 0L || now - meta.lastVerifiedAt >= VERIFY_INTERVAL_MS)

    /** ¿El server tiene un catálogo distinto al local? */
    fun needsServerSync(meta: SyncMeta, serverHash: String): Boolean =
        meta.hash != serverHash
}
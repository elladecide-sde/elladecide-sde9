package com.elladecide.lola.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente de sync contra el backend Cloudflare (ver backend/src/index.ts).
 *
 * Flujo offline-first por HASH, con detección de corrupción local:
 *   1. Chequeo BARATO (cada apertura): el COUNT de products debe coincidir con
 *      el que dejó el último sync ([SyncMeta.count]); si no → resync forzado.
 *   2. Verificación COMPLETA por hash, THROTTLED (una vez por proceso y como
 *      mínimo cada 24h): se recomputa [CatalogHash] sobre products y se compara
 *      con [SyncMeta.hash]; si no coincide → resync forzado.
 *   3. GET /version → { revision, hash }
 *   4. si el hash coincide con lo local → al día, 0 requests más
 *   5. si no → GET /sync → catálogo COMPLETO → reemplazo atómico de la tabla local
 *
 * Todo corre en Dispatchers.IO: el main thread del IME nunca se bloquea.
 *
 * `baseUrl` y `secret` se configuran desde la pantalla de ajustes
 * (ConfigActivity). Por defecto, en el emulador 10.0.2.2 apunta al localhost
 * de la máquina. Si `secret` está seteado, se manda como `Authorization:
 * Bearer <secret>` (para backend que proteja el sync).
 *
 * Errores: [SyncNetworkException] = backend inalcanzable/offline (esperable,
 * no es un bug); [SyncProtocolException] = el backend respondió mal (HTTP no
 * 2xx o JSON inválido) → indica un bug de contrato, NO debe silenciarse.
 */
class SyncRepository(
    private val productDao: ProductDao,
    private val metaDao: SyncMetaDao,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val secret: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Resultado(
        val sincronizado: Boolean,
        val productos: Int,
        val motivo: String? = null,
    )

    suspend fun sync(): Resultado = withContext(Dispatchers.IO) {
        val meta = metaDao.get() ?: SyncMeta()
        val localCount = productDao.count()
        val ahora = now()

        // 1) Chequeo barato por conteo (cada apertura).
        if (SyncPolicy.countCorrupted(meta, localCount)) {
            return@withContext fullSync().copy(
                motivo = "catálogo local inconsistente (count $localCount ≠ ${meta.count})",
            )
        }

        // 2) Verificación completa por hash, throttled (una vez por proceso + 24h).
        if (SyncPolicy.needsFullVerify(meta, verifiedThisProcess.get(), ahora)) {
            verifiedThisProcess.set(true)
            val localHash = CatalogHash.compute(productDao.getAll())
            if (localHash != meta.hash) {
                return@withContext fullSync().copy(motivo = "catálogo local corrupto (hash no coincide)")
            }
            metaDao.upsert(meta.copy(lastVerifiedAt = ahora))
        }

        // 3) Flujo normal por hash del server.
        val version = getJson("/api/v1/version")
        if (!SyncPolicy.needsServerSync(meta, version.getString("hash"))) {
            return@withContext Resultado(sincronizado = false, productos = 0, motivo = null)
        }
        fullSync()
    }

    /** Baja el catálogo completo y lo reemplaza atómicamente (Room @Transaction). */
    private suspend fun fullSync(): Resultado {
        val full = getJson("/api/v1/sync")
        val productos = parseProducts(full.getJSONArray("products"))
        val ahora = now()
        productDao.replaceAll(productos)
        metaDao.upsert(
            SyncMeta(
                revision = full.getInt("revision"),
                hash = full.getString("hash"),
                count = productos.size,
                lastVerifiedAt = ahora,
            ),
        )
        // Tras un sync, la integridad local está garantizada (datos == server).
        verifiedThisProcess.set(true)
        return Resultado(sincronizado = true, productos = productos.size, motivo = null)
    }

    private fun getJson(path: String): JSONObject {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            if (!secret.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $secret")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw SyncProtocolException("HTTP $code en $path")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw SyncProtocolException("JSON inválido en $path: ${e.message}")
            }
        } catch (e: IOException) {
            // red/offline: esperable, no es un bug del contrato
            throw SyncNetworkException(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseProducts(arr: JSONArray): List<Product> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val preciosArr = o.getJSONArray("precios")
            Product.fromPrecios(
                codigoUnico = o.getString("codigo_unico"),
                nombre = o.getString("nombre"),
                precios = (0 until preciosArr.length()).map { preciosArr.getInt(it) },
            )
        }

    companion object {
        // Emulador Android → localhost de la máquina host. Se puede cambiar en
        // ConfigActivity (pantalla de ajustes del teclado).
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8787"

        /** Verificación completa ya hecha en este proceso (evita repetirla en cada apertura). */
        private val verifiedThisProcess = AtomicBoolean(false)
    }
}

/** Backend inalcanzable (offline, DNS, timeout, conexión rechazada). */
class SyncNetworkException(cause: Throwable) : Exception(cause)

/** El backend respondió, pero mal (HTTP no 2xx o cuerpo inválido). */
class SyncProtocolException(message: String) : Exception(message)

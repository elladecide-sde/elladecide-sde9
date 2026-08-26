package com.elladecide.lola.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente de sync contra el backend Cloudflare (ver backend/src/index.ts).
 *
 * Flujo offline-first:
 *   1. GET /version → { revision, hash }
 *   2. si coincide con lo local → al día, 0 requests más
 *   3. si no → GET /sync?last_revision=N → aplicar deltas (upserts + borrados)
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
) {
    data class Resultado(val sincronizado: Boolean, val productos: Int, val borrados: Int)

    suspend fun sync(): Resultado = withContext(Dispatchers.IO) {
        val version = getJson("/api/v1/version")
        val serverRevision = version.getInt("revision")
        val serverHash = version.getString("hash")
        val local = metaDao.get() ?: SyncMeta()

        if (local.revision == serverRevision && local.hash == serverHash) {
            return@withContext Resultado(false, 0, 0) // al día
        }

        val delta = getJson("/api/v1/sync?last_revision=${local.revision}")
        val productos = parseProducts(delta.getJSONArray("products"))
        val borrados = (0 until delta.getJSONArray("deleted").length())
            .map { delta.getJSONArray("deleted").getString(it) }

        if (productos.isNotEmpty()) productDao.upsertAll(productos)
        if (borrados.isNotEmpty()) productDao.deleteAll(borrados)
        metaDao.upsert(
            SyncMeta(
                revision = delta.getInt("revision"),
                hash = delta.getString("hash"),
            ),
        )

        Resultado(true, productos.size, borrados.size)
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
    }
}

/** Backend inalcanzable (offline, DNS, timeout, conexión rechazada). */
class SyncNetworkException(cause: Throwable) : Exception(cause)

/** El backend respondió, pero mal (HTTP no 2xx o cuerpo inválido). */
class SyncProtocolException(message: String) : Exception(message)

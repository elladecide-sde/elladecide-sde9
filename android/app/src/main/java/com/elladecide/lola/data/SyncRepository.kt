package com.elladecide.lola.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 * BASE_URL: en el emulador, 10.0.2.2 apunta al localhost de la máquina.
 * En un dispositivo real o producción, poner la URL del Worker desplegado.
 * TODO: mover BASE_URL a una pantalla de configuración (settings activity).
 */
class SyncRepository(
    private val productDao: ProductDao,
    private val metaDao: SyncMetaDao,
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
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
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
        // Emulador Android → localhost de la máquina host.
        // Producción: reemplazar por la URL real del Worker.
        const val BASE_URL = "http://10.0.2.2:8787"
    }
}

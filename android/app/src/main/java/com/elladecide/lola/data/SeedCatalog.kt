package com.elladecide.lola.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Seed inicial desde assets/seed_catalog.json.
 * Solo se usa la PRIMERA vez (cuando la base está vacía) para que el teclado
 * funcione offline desde el minuto cero, sin backend. Después el sync contra
 * D1 (SyncRepository) es la fuente de verdad.
 */
object SeedCatalog {

    suspend fun sembrar(context: Context, db: LolaDatabase) {
        val productos = withContext(Dispatchers.IO) {
            parse(leer(context))
        }
        if (productos.isNotEmpty()) db.productDao().upsertAll(productos)
    }

    private fun leer(context: Context): String =
        context.assets.open("seed_catalog.json").bufferedReader().use { it.readText() }

    private fun parse(json: String): List<Product> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val preciosArr = o.getJSONArray("precios")
            Product.fromPrecios(
                codigoUnico = o.getString("codigo_unico"),
                nombre = o.getString("nombre"),
                precios = (0 until preciosArr.length()).map { preciosArr.getInt(it) },
            )
        }
    }
}

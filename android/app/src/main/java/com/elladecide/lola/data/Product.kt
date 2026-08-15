package com.elladecide.lola.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray

/**
 * Producto del catálogo.
 *
 * `codigoUnico` es la identidad estable (generada como abreviación del nombre +
 * código de artículo/modelo — ver backend/schema.sql). Por ahora, en el seed de
 * ejemplo, usa el contador del catalog.js como placeholder hasta que exista el
 * script de import que lo genere de verdad.
 *
 * `precios` se guarda como JSON [L1, L2, L3, L4] para mantener el contrato 1:1
 * con el backend (que también lo serializa como JSON).
 */
@Entity(tableName = "products")
data class Product(
    @PrimaryKey val codigoUnico: String,
    val nombre: String,
    val precios: String,
    val updatedAtRevision: Int = 0,
) {
    fun preciosList(): List<Int> {
        val arr = JSONArray(precios)
        return (0 until arr.length()).map { arr.getInt(it) }
    }

    companion object {
        fun fromPrecios(codigoUnico: String, nombre: String, precios: List<Int>, revision: Int = 0): Product =
            Product(codigoUnico, nombre, JSONArray(precios).toString(), revision)
    }
}

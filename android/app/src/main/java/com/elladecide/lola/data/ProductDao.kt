package com.elladecide.lola.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface ProductDao {

    @Query("SELECT * FROM products")
    suspend fun getAll(): List<Product>

    @Query("DELETE FROM products")
    suspend fun clearAll()

    /** Reemplazo atómico del catálogo local (sync completo). */
    @Transaction
    suspend fun replaceAll(products: List<Product>) {
        clearAll()
        upsertAll(products)
    }

    @Upsert
    suspend fun upsertAll(products: List<Product>)

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int
}
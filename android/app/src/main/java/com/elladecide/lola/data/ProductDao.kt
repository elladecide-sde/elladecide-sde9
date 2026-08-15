package com.elladecide.lola.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProductDao {

    @Query("SELECT * FROM products")
    suspend fun getAll(): List<Product>

    @Upsert
    suspend fun upsertAll(products: List<Product>)

    @Query("DELETE FROM products WHERE codigoUnico IN (:codigos)")
    suspend fun deleteAll(codigos: List<String>)

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int
}

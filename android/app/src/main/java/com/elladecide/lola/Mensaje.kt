package com.elladecide.lola

import java.text.NumberFormat
import java.util.Locale

/** Formato de moneda y nombres de lista (compartido entre texto y PNG). */
object Mensaje {
    val LISTA_NAMES = listOf("Lista 1", "Lista 2", "Lista 3", "Lista 4")

    /** Nombre de lista en mayúsculas, como la extensión (listNameMsg). */
    fun listaMsg(i: Int): String = LISTA_NAMES[i].uppercase()

    private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale("es", "AR"))

    fun fmt(n: Int): String = "$" + nf.format(n) // $20.434 (es-AR)
}

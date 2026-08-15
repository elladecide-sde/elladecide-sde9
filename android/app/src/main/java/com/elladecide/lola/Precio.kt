package com.elladecide.lola

import com.elladecide.lola.data.Product
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Un ítem del pedido.
 * `listaIndex`: -1 = "todas las listas" (se listan las 4), 0..3 = lista elegida.
 */
data class CartItem(
    val product: Product,
    val listaIndex: Int,
    val qty: Int = 1,
)

/**
 * Motor de precios — port del buildLineParts/buildMessage/getUnitPrices de la
 * extensión (content.js).
 *
 * Reglas (idénticas a la extensión):
 *  - Oferta (nombre termina en "." o en una keyword como "liq"/"oferta"):
 *    NO lleva descuento, pero SÍ recargo tarjeta (RECARGO).
 *  - Producto normal: sin recargo tarjeta; descuento sobre el efectivo.
 *  - Redondeo POR UNIDAD antes de multiplicar por cantidad (para que el total
 *    cierre exacto en pesos).
 *
 * TODO (futuro): descuento MANUAL por ítem (hoy es global), concepto N/U,
 * valorModo LISTA/DESCUENTO y lectura LC/LL como switches configurables.
 */
object Precio {

    const val RECARGO = 15
    const val FOOTER_TEXT =
        "Los precios de lista expresados son a modo de referencia. El valor final puede variar y se confirmará al momento de facturar."
    val DESCUENTOS = listOf(0, 10, 15, 20)
    private val OFERTA_KEYWORDS = listOf("oferta", "liq")

    fun esOferta(nombre: String): Boolean {
        val n = nombre.trim()
        if (n.endsWith(".")) return true
        val low = n.lowercase()
        return OFERTA_KEYWORDS.any { low.endsWith(it) }
    }

    /** Nombre corto (lectura LC): tokens hasta el primero que contiene un número. */
    fun shortName(nombre: String): String {
        val tokens = nombre.split(Regex("\\s+"))
        val idx = tokens.indexOfFirst { Regex("\\d").containsMatchIn(it) }
        if (idx == -1) return nombre
        return tokens.subList(0, idx + 1).joinToString(" ")
    }

    // Por defecto lectura LC (short), igual que la extensión.
    // TODO: toggle LL (nombre completo) como switch configurable.
    fun displayName(product: Product): String = shortName(product.nombre)

    /** Precio por unidad: (tarjeta, efectivo). */
    fun unitPrices(item: CartItem, listaIndex: Int, descuento: Int): Pair<Int, Int> {
        val base = item.product.preciosList()[listaIndex]
        if (esOferta(item.product.nombre)) {
            val tarjeta = (base * (1 + RECARGO / 100.0)).roundToInt()
            return tarjeta to base
        }
        val efectivo = when {
            descuento > 0 -> (base * (1 - descuento / 100.0)).roundToInt()
            descuento < 0 -> (base * (1 + abs(descuento) / 100.0)).roundToInt()
            else -> base
        }
        return base to efectivo
    }

    /** Texto explicativo del descuento/recargo para una línea. */
    fun resto(item: CartItem, listaIndex: Int, descuento: Int): String {
        val (_, efectivo) = unitPrices(item, listaIndex, descuento)
        return if (esOferta(item.product.nombre)) {
            "Sin Desc. De OFERTA"
        } else {
            val monto = Mensaje.fmt(efectivo * item.qty)
            when {
                descuento > 0 -> "Con Efec/Trans Desc.-$descuento% $monto"
                descuento < 0 -> "Con Recargo +${abs(descuento)}% $monto"
                else -> "Sin descuento $monto"
            }
        }
    }

    fun buildLinea(item: CartItem, listaIndex: Int, descuento: Int): String {
        val base = item.product.preciosList()[listaIndex]
        return "▸ *${displayName(item.product)} || ${Mensaje.listaMsg(listaIndex)}  >>  " +
            "${Mensaje.fmt(base)}*  ${resto(item, listaIndex, descuento)}"
    }

    /** Totales (tarjeta, efectivo) solo para ítems con lista elegida. */
    fun totales(cart: List<CartItem>, descuento: Int): Pair<Int, Int> {
        var tarjeta = 0
        var efectivo = 0
        cart.filter { it.listaIndex != -1 }.forEach { item ->
            val (t, e) = unitPrices(item, item.listaIndex, descuento)
            tarjeta += t * item.qty
            efectivo += e * item.qty
        }
        return tarjeta to efectivo
    }

    /** Mensaje completo del pedido — port de buildMessage(). */
    fun buildMensaje(cart: List<CartItem>, descuento: Int): String {
        if (cart.isEmpty()) return ""
        val SEP = "_".repeat(39)
        val conLista = cart.filter { it.listaIndex != -1 }
        val todas = cart.filter { it.listaIndex == -1 }

        val todasBlocks = todas.map { item ->
            val lines = item.product.preciosList().indices
                .joinToString("\n") { i -> buildLinea(item, i, descuento) }
            SEP + "\n" + lines
        }

        if (conLista.isEmpty()) {
            return todasBlocks.joinToString("\n") + "\n" + SEP + "\n\n_${FOOTER_TEXT}_"
        }

        val (totalTarjeta, totalEfectivo) = totales(cart, descuento)
        val lines = conLista.map { buildLinea(it, it.listaIndex, descuento) }

        val sb = StringBuilder()
        lines.forEach { sb.append(SEP).append('\n').append(it).append('\n') }
        sb.append(SEP).append('\n')
        sb.append("*>>  TOTAL  << | ${Mensaje.fmt(totalTarjeta)} P. LISTA |*\n")
        sb.append("*>>  TOTAL  << | ${Mensaje.fmt(totalEfectivo)} EFEC/TRAN*\n")
        sb.append(SEP)
        sb.append("\n\n_${FOOTER_TEXT}_")
        if (todasBlocks.isNotEmpty()) {
            sb.append("\n\n").append(todasBlocks.joinToString("\n")).append('\n').append(SEP)
        }
        return sb.toString()
    }
}

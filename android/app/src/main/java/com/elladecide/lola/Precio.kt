package com.elladecide.lola

import com.elladecide.lola.data.Product
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lectura del nombre del producto: corta (LC) o completa (LL). */
enum class Lectura { LC, LL }

/** Concepto de la línea: normal (N, sin cantidad) o unidad (U, con ×cantidad). */
enum class Concepto { N, U }

/** Valor principal de la línea: precio de lista o precio con descuento aplicado. */
enum class ValorModo { LISTA, DESCUENTO }

/** Descuento del pedido: global (todos) o manual (por ítem). */
enum class DescuentoModo { TODOS, MANUAL }

/**
 * Switches de formato del mensaje (port de LOLA v3.2, `buildLineParts`):
 * lectura LC/LL, concepto N/U, texto de descuento ON/OFF y valor LISTA/DESCUENTO.
 */
data class PedidoConfig(
    val lectura: Lectura = Lectura.LC,
    val concepto: Concepto = Concepto.N,
    val textoDesc: Boolean = true,
    val valorModo: ValorModo = ValorModo.LISTA,
)

/**
 * Un ítem del pedido.
 * `listaIndex`: -1 = "todas las listas" (se listan las 4), 0..3 = lista elegida.
 * `descuento`: override individual (modo MANUAL); null = usa el descuento global.
 */
data class CartItem(
    val product: Product,
    val listaIndex: Int,
    val qty: Int = 1,
    val descuento: Int? = null,
)

/**
 * Motor de precios — port del buildLineParts/buildMessage/getUnitPrices de la
 * extensión (content.js v3.2).
 *
 * Reglas (idénticas a la extensión):
 *  - Oferta (nombre termina en "." o en una keyword como "liq"/"oferta"):
 *    NO lleva descuento, pero SÍ recargo tarjeta (RECARGO).
 *  - Producto normal: sin recargo tarjeta; descuento sobre el efectivo.
 *  - Redondeo POR UNIDAD antes de multiplicar por cantidad (para que el total
 *    cierre exacto en pesos).
 *  - El descuento efectivo es por ítem en modo MANUAL (item.descuento) o el
 *    global en modo TODOS (descuentoGlobal).
 */
object Precio {

    const val RECARGO = 15
    const val FOOTER_TEXT =
        "Los precios de lista expresados son a modo de referencia. El valor final puede variar y se confirmará al momento de facturar."
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

    /** Nombre según la lectura configurada: LC (corta) o LL (completa). */
    fun displayName(product: Product, config: PedidoConfig): String =
        if (config.lectura == Lectura.LC) shortName(product.nombre) else product.nombre

    /** Descuento efectivo del ítem: su override manual o el global (modo TODOS). */
    fun descuentoEfectivo(item: CartItem, descuentoGlobal: Int): Int =
        item.descuento ?: descuentoGlobal

    /** Precio por unidad: (tarjeta, efectivo). */
    fun unitPrices(item: CartItem, listaIndex: Int, descuentoGlobal: Int): Pair<Int, Int> {
        val base = item.product.preciosList()[listaIndex]
        if (esOferta(item.product.nombre)) {
            val tarjeta = (base * (1 + RECARGO / 100.0)).roundToInt()
            return tarjeta to base
        }
        val d = descuentoEfectivo(item, descuentoGlobal)
        val efectivo = when {
            d > 0 -> (base * (1 - d / 100.0)).roundToInt()
            d < 0 -> (base * (1 + abs(d) / 100.0)).roundToInt()
            else -> base
        }
        return base to efectivo
    }

    /** Valor principal de la línea (port de buildLineParts.valorPrincipal). */
    fun valorPrincipal(item: CartItem, listaIndex: Int, descuentoGlobal: Int, config: PedidoConfig): Int {
        val base = item.product.preciosList()[listaIndex]
        if (esOferta(item.product.nombre)) return base
        if (config.valorModo == ValorModo.DESCUENTO) return unitPrices(item, listaIndex, descuentoGlobal).second
        return base
    }

    /** Texto explicativo del descuento/recargo para una línea (resto). */
    fun resto(item: CartItem, listaIndex: Int, descuentoGlobal: Int, config: PedidoConfig): String {
        val (_, efectivo) = unitPrices(item, listaIndex, descuentoGlobal)
        val qtyPrefix = if (config.concepto == Concepto.U) "x ${item.qty} (unidad) " else ""
        return if (esOferta(item.product.nombre)) {
            "${qtyPrefix}Sin Desc. De OFERTA"
        } else {
            when {
                config.valorModo == ValorModo.DESCUENTO -> "${qtyPrefix}Con Descuentos aplicados."
                !config.textoDesc -> qtyPrefix.trim()
                config.concepto == Concepto.U ->
                    "${qtyPrefix}con Descuento ${Mensaje.fmt(efectivo * item.qty)}"
                else -> {
                    val d = descuentoEfectivo(item, descuentoGlobal)
                    val monto = Mensaje.fmt(efectivo * item.qty)
                    when {
                        d > 0 -> "Con Efec/Trans Desc.-$d% $monto"
                        d < 0 -> "Con Recargo +${abs(d)}% $monto"
                        else -> "Sin descuento $monto"
                    }
                }
            }
        }
    }

    fun buildLinea(item: CartItem, listaIndex: Int, descuentoGlobal: Int, config: PedidoConfig): String {
        val valor = Mensaje.fmt(valorPrincipal(item, listaIndex, descuentoGlobal, config))
        val resto = resto(item, listaIndex, descuentoGlobal, config)
        return "▸ *${displayName(item.product, config)} || ${Mensaje.listaMsg(listaIndex)}  >>  $valor*" +
            (if (resto.isEmpty()) "" else "  $resto")
    }

    /**
     * Piezas limpias de una línea (sin markdown) para el modo PNG y para el
     * mensaje de texto. Port de `buildLineParts` de la extensión v3.2.
     */
    data class LineaParts(
        val line: String,
        val tarjeta: Int,
        val efectivo: Int,
        val qty: Int,
        val name: String,
        val lista: String,
        val valor: String,
        val resto: String,
    )

    fun buildLineParts(item: CartItem, listaIndex: Int, descuentoGlobal: Int, config: PedidoConfig): LineaParts {
        val (tarjeta, efectivo) = unitPrices(item, listaIndex, descuentoGlobal)
        return LineaParts(
            line = buildLinea(item, listaIndex, descuentoGlobal, config),
            tarjeta = tarjeta,
            efectivo = efectivo,
            qty = item.qty,
            name = displayName(item.product, config),
            lista = Mensaje.listaMsg(listaIndex),
            valor = Mensaje.fmt(valorPrincipal(item, listaIndex, descuentoGlobal, config)),
            resto = resto(item, listaIndex, descuentoGlobal, config),
        )
    }

    /** Totales (tarjeta, efectivo) solo para ítems con lista elegida. */
    fun totales(cart: List<CartItem>, descuentoGlobal: Int): Pair<Int, Int> {
        var tarjeta = 0
        var efectivo = 0
        cart.filter { it.listaIndex != -1 }.forEach { item ->
            val (t, e) = unitPrices(item, item.listaIndex, descuentoGlobal)
            tarjeta += t * item.qty
            efectivo += e * item.qty
        }
        return tarjeta to efectivo
    }

    /** Mensaje completo del pedido — port de buildMessage() (v3.2). */
    fun buildMensaje(cart: List<CartItem>, descuentoGlobal: Int, config: PedidoConfig): String {
        if (cart.isEmpty()) return ""
        val SEP = "_".repeat(39)
        val conLista = cart.filter { it.listaIndex != -1 }
        val todas = cart.filter { it.listaIndex == -1 }

        val todasBlocks = todas.map { item ->
            val lines = item.product.preciosList().indices
                .joinToString("\n") { i -> buildLineParts(item, i, descuentoGlobal, config).line }
            SEP + "\n" + lines
        }

        if (conLista.isEmpty()) {
            return todasBlocks.joinToString("\n") + "\n" + SEP + "\n\n_${FOOTER_TEXT}_"
        }

        val (totalTarjeta, totalEfectivo) = totales(cart, descuentoGlobal)
        val lines = conLista.map { buildLineParts(it, it.listaIndex, descuentoGlobal, config).line }

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
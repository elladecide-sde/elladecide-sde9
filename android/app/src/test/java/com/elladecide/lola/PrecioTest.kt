package com.elladecide.lola

import com.elladecide.lola.data.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port del `buildLineParts`/`buildMessage` de la extensión LOLA v3.2 con todos
 * los switches: lectura LC/LL, concepto N/U, texto de descuento, valor
 * LISTA/DESCUENTO, descuento global vs manual por ítem, cantidad y oferta.
 */
class PrecioTest {

    private fun producto(nombre: String, precios: List<Int> = listOf(1000, 2000, 3000, 4000)): Product =
        Product.fromPrecios(nombre, nombre, precios)

    private val normal = producto("ZAPATO 42 NEGRO")
    private val oferta = producto("CAMPERA LIQ.")

    private fun item(
        product: Product,
        listaIndex: Int = 0,
        qty: Int = 1,
        descuento: Int? = null,
    ) = CartItem(product, listaIndex, qty, descuento)

    // ── Lectura LC/LL ──────────────────────────────────────────────────────

    @Test
    fun `shortName corta en el token con numero`() {
        assertEquals("ZAPATO 42", Precio.shortName("ZAPATO 42 NEGRO"))
        assertEquals("ZAPATO 42", Precio.shortName("ZAPATO 42 NEGRO."))
    }

    @Test
    fun `displayName respeta lectura LC y LL`() {
        assertEquals("ZAPATO 42", Precio.displayName(normal, PedidoConfig(lectura = Lectura.LC)))
        assertEquals("ZAPATO 42 NEGRO", Precio.displayName(normal, PedidoConfig(lectura = Lectura.LL)))
    }

    // ── unitPrices: global vs oferta ───────────────────────────────────────

    @Test
    fun `unitPrices aplica descuento global al efectivo`() {
        val (tarjeta, efectivo) = Precio.unitPrices(item(normal), 0, descuentoGlobal = 10)
        assertEquals(1000, tarjeta)
        assertEquals(900, efectivo) // 1000 - 10%
    }

    @Test
    fun `unitPrices en oferta aplica recargo tarjeta sin descuento`() {
        val (tarjeta, efectivo) = Precio.unitPrices(item(oferta), 0, descuentoGlobal = 10)
        assertEquals(1150, tarjeta) // recargo 15%
        assertEquals(1000, efectivo)
    }

    @Test
    fun `unitPrices soporta recargo negativo global`() {
        val (_, efectivo) = Precio.unitPrices(item(normal), 0, descuentoGlobal = -15)
        assertEquals(1150, efectivo) // +15%
    }

    // ── Descuento manual por ítem ──────────────────────────────────────────

    @Test
    fun `descuento manual del item tiene prioridad sobre el global`() {
        val (_, efectivo) = Precio.unitPrices(item(normal, descuento = 20), 0, descuentoGlobal = 10)
        assertEquals(800, efectivo) // 20% por ítem, no 10%
    }

    @Test
    fun `item sin descuento usa el global`() {
        val (_, efectivo) = Precio.unitPrices(item(normal, descuento = null), 0, descuentoGlobal = 10)
        assertEquals(900, efectivo)
    }

    @Test
    fun `descuentoEfectivo resuelve null al global`() {
        assertEquals(10, Precio.descuentoEfectivo(item(normal), 10))
        assertEquals(20, Precio.descuentoEfectivo(item(normal, descuento = 20), 10))
    }

    // ── buildLinea: switches combinados ────────────────────────────────────

    @Test
    fun `linea por defecto muestra lista y frase de descuento`() {
        val linea = Precio.buildLinea(item(normal), 0, descuentoGlobal = 15, PedidoConfig())
        assertEquals("▸ *ZAPATO 42 || LISTA 1  >>  $1.000*  Con Efec/Trans Desc.-15% $850", linea)
    }

    @Test
    fun `linea sin descuento dice Sin descuento`() {
        val linea = Precio.buildLinea(item(normal), 0, descuentoGlobal = 0, PedidoConfig())
        assertEquals("▸ *ZAPATO 42 || LISTA 1  >>  $1.000*  Sin descuento $1.000", linea)
    }

    @Test
    fun `concepto U agrega cantidad y muestra monto por total`() {
        val config = PedidoConfig(concepto = Concepto.U)
        val linea = Precio.buildLinea(item(normal, qty = 2), 0, descuentoGlobal = 15, config)
        assertEquals(
            "▸ *ZAPATO 42 || LISTA 1  >>  $1.000*  x 2 (unidad) con Descuento $1.700",
            linea,
        )
    }

    @Test
    fun `valor DESCUENTO muestra el efectivo como valor principal`() {
        val config = PedidoConfig(valorModo = ValorModo.DESCUENTO)
        val linea = Precio.buildLinea(item(normal), 0, descuentoGlobal = 15, config)
        assertEquals("▸ *ZAPATO 42 || LISTA 1  >>  $850*  Con Descuentos aplicados.", linea)
    }

    @Test
    fun `textoDesc OFF omite la frase de descuento`() {
        val config = PedidoConfig(textoDesc = false)
        val linea = Precio.buildLinea(item(normal), 0, descuentoGlobal = 15, config)
        assertEquals("▸ *ZAPATO 42 || LISTA 1  >>  $1.000*", linea)
    }

    @Test
    fun `oferta mantiene valor de lista y avisa sin descuento`() {
        val linea = Precio.buildLinea(item(oferta), 0, descuentoGlobal = 15, PedidoConfig())
        assertEquals("▸ *CAMPERA LIQ. || LISTA 1  >>  $1.000*  Sin Desc. De OFERTA", linea)
    }

    @Test
    fun `lectura LL usa el nombre completo`() {
        val config = PedidoConfig(lectura = Lectura.LL)
        val linea = Precio.buildLinea(item(normal), 0, descuentoGlobal = 0, config)
        assertTrue(linea.startsWith("▸ *ZAPATO 42 NEGRO || LISTA 1  >>  $1.000*"))
    }

    // ── totales ────────────────────────────────────────────────────────────

    @Test
    fun `totales suman efectivo y tarjeta con cantidad`() {
        val cart = listOf(
            item(normal, listaIndex = 0, qty = 3), // 850 * 3 = 2550 efectivo, 1000*3 tarjeta
            item(oferta, listaIndex = 1, qty = 1), // 2000 efectivo, 2300 tarjeta
        )
        val (tarjeta, efectivo) = Precio.totales(cart, descuentoGlobal = 15)
        assertEquals(3000 + 2300, tarjeta)
        assertEquals(2550 + 2000, efectivo)
    }

    @Test
    fun `totales ignoran items con todas las listas`() {
        val cart = listOf(item(normal, listaIndex = -1))
        val (tarjeta, efectivo) = Precio.totales(cart, descuentoGlobal = 15)
        assertEquals(0, tarjeta)
        assertEquals(0, efectivo)
    }

    // ── buildMensaje ───────────────────────────────────────────────────────

    @Test
    fun `buildMensaje arma separadores y totales`() {
        val cart = listOf(item(normal, listaIndex = 0), item(oferta, listaIndex = 1))
        val msg = Precio.buildMensaje(cart, descuentoGlobal = 15, PedidoConfig())
        val SEP = "_".repeat(39)
        assertTrue(msg.startsWith("$SEP\n"))
        assertTrue(msg.contains("*>>  TOTAL  << | $3.300 P. LISTA |*"))
        assertTrue(msg.contains("*>>  TOTAL  << | $2.850 EFEC/TRAN*"))
        assertTrue(msg.contains(Precio.FOOTER_TEXT))
    }

    @Test
    fun `buildMensaje con todas las listas lista cada precio`() {
        val cart = listOf(item(normal, listaIndex = -1))
        val msg = Precio.buildMensaje(cart, descuentoGlobal = 15, PedidoConfig())
        assertTrue(msg.contains("LISTA 1"))
        assertTrue(msg.contains("LISTA 4"))
        assertFalse(msg.contains("TOTAL"))
    }
}
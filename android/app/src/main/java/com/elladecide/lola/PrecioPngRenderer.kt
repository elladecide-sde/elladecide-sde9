package com.elladecide.lola

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

/**
 * Renderizador del mensaje "PRECIOS" en modo PNG — port del generarPrecioPNG()
 * de la extensión (variante "lista": filas + caja de TOTAL).
 *
 * Paleta idéntica: fondo #1f2c34, precios verde #00d4a8, total #25d366 sobre
 * fondo #0d2318, watermark "ELLA DECIDE" al 16% de opacidad.
 */
object PrecioPngRenderer {

    private val BG = Color.parseColor("#1f2c34")
    private val LINE = Color.parseColor("#2a3942")
    private val NAME = Color.parseColor("#e9edef")
    private val MUTED = Color.parseColor("#8696a0")
    private val VALOR = Color.parseColor("#00d4a8")
    private val TOTAL = Color.parseColor("#25d366")
    private val TOTAL_BG = Color.parseColor("#0d2318")

    private const val W = 780
    private const val PAD = 36f
    private const val INNER = W - 2 * PAD

    private const val TOP_PAD = 24f
    private const val NAME_LINE_H = 24f
    private const val SUB_LINE_H = 20f
    private const val ROW_GAP = 8f
    private const val TOTAL_BOX_H = 88f
    private const val GAP = 12f
    private const val SEP_GAP = 22f
    private const val FOOTER_LINE_H = 18f
    private const val BOTTOM_PAD = 40f

    private fun paint(size: Float, color: Int, style: Int = Typeface.NORMAL): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (w in text.split(Regex("\\s+"))) {
            val cand = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(cand) > maxWidth && cur.isNotEmpty()) {
                out.add(cur)
                cur = w
            } else {
                cur = cand
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var s = text
        while (s.length > 1 && p.measureText(s + "…") > maxWidth) s = s.dropLast(1)
        return s + "…"
    }

    private class Fila(
        val nameLines: List<String>,
        val valor: String,
        val sub: String,
        val altura: Float,
    )

    /** Render del pedido completo (filas por lista + caja de TOTAL). */
    fun renderPedido(cart: List<CartItem>, descuento: Int): Bitmap {
        val namePaint = paint(19f, NAME, Typeface.BOLD)
        val valorPaint = paint(20f, VALOR, Typeface.BOLD)
        val subPaint = paint(13f, MUTED)
        val footerPaint = paint(13f, MUTED, Typeface.ITALIC)

        // 1) Preparar filas.
        val filas = mutableListOf<Fila>()
        for (item in cart) {
            val indices =
                if (item.listaIndex == -1) item.product.preciosList().indices.toList()
                else listOf(item.listaIndex)
            for (i in indices) {
                val valor = Mensaje.fmt(item.product.preciosList()[i])
                val vw = valorPaint.measureText(valor)
                val nameLines = wrap(Precio.displayName(item.product), namePaint, INNER - vw - 20f)
                val sub = ellipsize(
                    "${Mensaje.listaMsg(i)} · ${Precio.resto(item, i, descuento)}",
                    subPaint,
                    INNER,
                )
                filas.add(Fila(nameLines, valor, sub, nameLines.size * NAME_LINE_H + SUB_LINE_H + ROW_GAP))
            }
        }

        val (totalTarjeta, totalEfectivo) = Precio.totales(cart, descuento)
        val tieneTotal = cart.any { it.listaIndex != -1 }
        val footerLines = wrap(Precio.FOOTER_TEXT, footerPaint, INNER)

        // 2) Altura.
        var h = TOP_PAD + filas.sumOf { it.altura.toDouble() }.toFloat() + GAP
        if (tieneTotal) h += TOTAL_BOX_H + GAP
        h += SEP_GAP + footerLines.size * FOOTER_LINE_H + BOTTOM_PAD
        val H = h.toInt().coerceAtLeast(160)

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(BG)
        val linePaint = Paint().apply { color = LINE; strokeWidth = 1f }

        var top = TOP_PAD

        // 3) Filas.
        for (f in filas) {
            canvas.drawText(
                f.valor,
                W - PAD - valorPaint.measureText(f.valor),
                top - valorPaint.fontMetrics.ascent,
                valorPaint,
            )
            var y = top
            for (ln in f.nameLines) {
                canvas.drawText(ln, PAD, y - namePaint.fontMetrics.ascent, namePaint)
                y += NAME_LINE_H
            }
            canvas.drawText(f.sub, PAD, y - subPaint.fontMetrics.ascent, subPaint)
            top += f.altura
        }
        top += GAP

        // 4) Caja de TOTAL (port del bloque 'total' de la extensión).
        if (tieneTotal) {
            val boxTop = top
            val totalPaint = paint(26f, TOTAL, Typeface.BOLD)
            val lblPaint = paint(14f, MUTED, Typeface.BOLD)
            val basePaint = paint(17f, MUTED)
            val ahorroPaint = paint(14f, MUTED)

            canvas.drawRoundRect(
                PAD, boxTop, W - PAD, boxTop + TOTAL_BOX_H, 10f, 10f,
                Paint().apply { color = TOTAL_BG },
            )

            val lbl1 = "PRECIO DE LISTA"
            val lbl2 = "EFECTIVO/TRANSFERENCIA"
            val val1 = Mensaje.fmt(totalTarjeta)
            val val2 = Mensaje.fmt(totalEfectivo)
            val ahorro = (totalTarjeta - totalEfectivo).let { if (it > 0) "· ahorrás " + Mensaje.fmt(it) else "" }

            val maxLblW = max(lblPaint.measureText(lbl1), lblPaint.measureText(lbl2))
            val row1W = maxLblW + 14f + basePaint.measureText(val1)
            val row2W = maxLblW + 14f + totalPaint.measureText(val2) +
                (if (ahorro.isNotEmpty()) 14f + ahorroPaint.measureText(ahorro) else 0f)
            val startX = PAD + (INNER - max(row1W, row2W)) / 2f

            var ry = boxTop + 24f
            canvas.drawText(lbl1, startX + (maxLblW - lblPaint.measureText(lbl1)), ry - lblPaint.fontMetrics.ascent, lblPaint)
            canvas.drawText(val1, startX + maxLblW + 14f, ry - basePaint.fontMetrics.ascent, basePaint)
            ry += 34f
            canvas.drawText(lbl2, startX + (maxLblW - lblPaint.measureText(lbl2)), ry - lblPaint.fontMetrics.ascent, lblPaint)
            val val2x = startX + maxLblW + 14f
            canvas.drawText(val2, val2x, ry - totalPaint.fontMetrics.ascent, totalPaint)
            if (ahorro.isNotEmpty()) {
                canvas.drawText(ahorro, val2x + totalPaint.measureText(val2) + 14f, ry - ahorroPaint.fontMetrics.ascent, ahorroPaint)
            }

            top += TOTAL_BOX_H + GAP
        }

        // 5) Separador + footer.
        canvas.drawLine(PAD, top, W - PAD, top, linePaint)
        top += SEP_GAP
        for (ln in footerLines) {
            canvas.drawText(ln, PAD, top - footerPaint.fontMetrics.ascent, footerPaint)
            top += FOOTER_LINE_H
        }

        // 6) Watermark discreto.
        val marca = "ELLA DECIDE"
        val wm = paint(16f, MUTED, Typeface.BOLD).apply { alpha = (255 * 0.16f).toInt() }
        canvas.drawText(marca, W - PAD - wm.measureText(marca), H - 26f, wm)

        return bmp
    }
}

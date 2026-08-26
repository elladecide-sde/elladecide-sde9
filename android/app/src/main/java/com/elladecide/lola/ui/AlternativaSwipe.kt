package com.elladecide.lola.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.elladecide.lola.keyboard.KeyboardMetrics
import com.elladecide.lola.keyboard.PopupController
import com.elladecide.lola.keyboard.PopupGeometry
import com.elladecide.lola.keyboard.PopupGesture
import com.elladecide.lola.keyboard.PopupInfo
import com.elladecide.lola.keyboard.PopupPositioner

/**
 * Bucle de selección por swipe del popup de long-press (spec F3 §7). Compartido
 * entre las teclas y las celdas del panel de emojis para no duplicar la lógica
 * del gesto: abre el popup posicionado sobre la tecla/celda, sigue el dedo,
 * resalta la alternativa y al levantar el dedo devuelve la seleccionada.
 *
 * Es una extensión de [AwaitPointerEventScope] (el scope restringido que
 * provee [androidx.compose.ui.input.pointer.awaitEachGesture]), igual que
 * `awaitFirstDown`, para poder invocar `awaitPointerEvent()`.
 *
 * - [PopupSwipeOutcome.Cancelado]: Back/gesto cancelado → el llamador no hace
 *   nada (evita commits fantasma, spec §8).
 * - [PopupSwipeOutcome.SinSeleccion]: el dedo se levantó sin elegir → el
 *   llamador decide (p. ej. tratar como tap del carácter base).
 * - [PopupSwipeOutcome.Seleccionada]: alternativa elegida → commitear.
 */
internal sealed class PopupSwipeOutcome {
    data object Cancelado : PopupSwipeOutcome()
    data object SinSeleccion : PopupSwipeOutcome()
    data class Seleccionada(val valor: String) : PopupSwipeOutcome()
}

internal suspend fun AwaitPointerEventScope.seleccionarAlternativa(
    down: PointerInputChange,
    posPx: Offset,
    sizePx: IntSize,
    viewportW: Float,
    viewportH: Float,
    alternativas: List<String>,
    popupCtl: PopupController,
    metrics: KeyboardMetrics,
    density: Density,
): PopupSwipeOutcome {
    val cellPx = with(density) { metrics.popupCellDp.dp.toPx() }
    val altoPx = with(density) { metrics.popupHeightDp.dp.toPx() }
    val popupW = cellPx * alternativas.size
    val (popupLeft, popupTop) = PopupPositioner.position(
        keyLeft = posPx.x,
        keyTop = posPx.y,
        keyWidth = sizePx.width.toFloat(),
        keyHeight = sizePx.height.toFloat(),
        popupWidth = popupW,
        popupHeight = altoPx,
        viewportWidth = viewportW,
        viewportHeight = viewportH,
    )
    val gesture = PopupGesture().also { it.start() }
    popupCtl.mostrar(
        PopupInfo(
            alternativas,
            popupLeft.toInt(),
            popupTop.toInt(),
            cellPx.toInt(),
            altoPx.toInt(),
        ),
    )
    val tokenInicio = popupCtl.cancelToken
    var cancelado = false
    while (true) {
        val e = awaitPointerEvent()
        val change = e.changes.firstOrNull { it.id == down.id } ?: break
        // Back físico (spec §8B): el token cambia ANTES del up, para no
        // commitear una alternativa fantasma al soltar el dedo.
        if (popupCtl.cancelToken != tokenInicio) { cancelado = true; break }
        if (change.changedToUp()) break
        if (!change.pressed) { cancelado = true; break }
        val dedoX = posPx.x + change.position.x
        val idx = PopupGeometry.cellIndexAt(
            dedoX, popupLeft, popupW, cellPx, alternativas.size,
        )
        gesture.moveTo(idx)
        popupCtl.seleccionar(gesture.selected)
    }
    if (cancelado) {
        gesture.cancel()
        popupCtl.cerrar()
        return PopupSwipeOutcome.Cancelado
    }
    val sel = gesture.commit()
    popupCtl.cerrar()
    return if (sel >= 0) PopupSwipeOutcome.Seleccionada(alternativas[sel])
    else PopupSwipeOutcome.SinSeleccion
}
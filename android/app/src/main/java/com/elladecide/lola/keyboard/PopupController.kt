package com.elladecide.lola.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Estado visible del popup de long-press. La geometría llega YA calculada en px
 * por [PopupPositioner] (mismo sistema de coordenadas que la tecla: el root).
 */
data class PopupInfo(
    val alternativas: List<String>,
    val leftPx: Int,
    val topPx: Int,
    val cellPx: Int,
    val heightPx: Int,
    val seleccion: Int = -1,
)

/**
 * Estado observable del popup de long-press. Vive en el controller (nivel IME)
 * para que el servicio pueda cerrarlo con el botón ATRÁS físico/software (spec
 * §8B) sin acoplarse a Compose: `cancelarExterno()` limpia el estado e
 * incrementa el token de cancelación, que el bucle del long-press lee para no
 * hacer commits fantasma cuando el dedo se suelta después del Back.
 */
class PopupController {
    var info by mutableStateOf<PopupInfo?>(null)
        private set

    var cancelToken by mutableStateOf(0)
        private set

    val visible: Boolean get() = info != null

    /** Abre el popup con la geometría calculada. */
    fun mostrar(info: PopupInfo) {
        this.info = info
    }

    /** Resalta la alternativa bajo el dedo (gesto de deslizamiento, §7). */
    fun seleccionar(seleccion: Int) {
        info = info?.copy(seleccion = seleccion)
    }

    /** Cierra tras commitear o descartar (gesto normal). */
    fun cerrar() {
        info = null
    }

    /**
     * Cierra sin commitear (Back o gesto externo). Devuelve true si había un
     * popup abierto (el llamador consume el evento). El token evita que el
     * gesto en curso, al soltar el dedo, commit a una alternativa fantasma.
     */
    fun cancelarExterno(): Boolean {
        if (info != null) {
            info = null
            cancelToken++
            return true
        }
        return false
    }
}

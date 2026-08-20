package com.elladecide.lola

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Contenedor raíz del input view del IME.
 *
 * Compose resuelve el recomposer desde el ROOT de la ventana del teclado
 * (WindowRecomposer.android.kt: `contentChild`) y busca un LifecycleOwner SUBIENDO
 * por la jerarquía desde ahí. Como el `ComposeView` hijo es un descendiente, setear los
 * owners solo sobre él no alcanza: la búsqueda sube, no baja, y Compose tira
 * "ViewTreeLifecycleOwner not found".
 *
 * Este contenedor se adjunta ANTES que sus hijos (el framework hace
 * dispatchAttachedToWindow padre-primero), así que acá propagamos los owners al root de
 * la ventana a tiempo, antes de que el ComposeView hijo resuelva el recomposer.
 */
class ImeRootView(
    context: Context,
    private val ime: LolaImeService,
    private var alturaPx: Int,
) : FrameLayout(context) {

    /**
     * Redimensiona el contenedor sin recrear la jerarquía de views.
     *
     * `requestLayout()` propaga el re-measure por la cadena de parents: el
     * `mInputFrame` (WRAP_CONTENT) vuelve a medir este contenedor, que reporta
     * la nueva [alturaPx] vía [onMeasure], y la ventana del IME se ajusta. Así
     * el `ComposeView` (y su composición) se conservan intactos al expandir o
     * contraer el teclado, a diferencia de `setInputView()` que los destruía.
     */
    fun setAlturaPx(px: Int) {
        if (px != alturaPx) {
            alturaPx = px
            requestLayout()
        }
    }

    /**
     * Fija la altura del contenedor en [alturaPx].
     *
     * `InputMethodService` agrega el input view al `mInputFrame` con
     * `WRAP_CONTENT`, descartando el `layoutParams.height` que le asignamos en
     * `buildInputView()`. Con wrap-content, el `ComposeView` hijo (MATCH_PARENT)
     * se mide con la altura máxima disponible y el `fillMaxHeight()` del `Column`
     * pinta todo el viewport. Forzando la altura acá, el fondo queda acotado al
     * tamaño real del teclado y la ventana del IME se dimensiona en consecuencia.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSpec = View.MeasureSpec.makeMeasureSpec(alturaPx, View.MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, heightSpec)
        setMeasuredDimension(measuredWidth, alturaPx)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView?.let { root ->
            if (root.findViewTreeLifecycleOwner() == null) {
                root.setViewTreeLifecycleOwner(ime)
            }
            if (root.findViewTreeSavedStateRegistryOwner() == null) {
                root.setViewTreeSavedStateRegistryOwner(ime)
            }
        }
    }
}
package com.elladecide.lola

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencias compartidas del teclado LOLA (config + estado persistido).
 * Fuente única de claves: ConfigActivity, KeyboardController y LolaImeService
 * no duplican strings de prefs.
 */
object LolaPrefs {

    const val NAME = "lola"

    // Estado persistido.
    const val KEY_EMOJI_RECENTS = "emoji_recents"
    const val RECENTS_DELIMITER = ","
    const val KEY_FOLDER_URI = "folder_uri"
    const val KEY_SUGGESTIONS_ENABLED = "suggestions_enabled"

    // Config del pedido (formato del mensaje, port de LOLA v3.2).
    const val KEY_LECTURA = "pedido_lectura"
    const val KEY_CONCEPTO = "pedido_concepto"
    const val KEY_TEXTO_DESC = "pedido_texto_desc"
    const val KEY_VALOR_MODO = "pedido_valor_modo"
    const val KEY_DESCUENTO_MODO = "pedido_descuento_modo"
    const val KEY_LISTA_DEFAULT = "pedido_lista_default"
    const val KEY_MODO_RESULTADO = "pedido_modo_resultado"

    // Config de red (ConfigActivity).
    const val KEY_BASE_URL = "base_url"
    const val KEY_SECRET = "secret"
    const val KEY_IMAGENES_ENABLED = "imagenes_enabled"

    // Mini portapapeles (local, opt-in).
    const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
    const val KEY_CLIPBOARD_ITEMS = "clipboard_items"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
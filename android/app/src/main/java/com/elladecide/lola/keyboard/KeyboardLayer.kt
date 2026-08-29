package com.elladecide.lola.keyboard

/**
 * Capa visible del teclado base.
 *
 * [CURSOR] es el teclado de navegación dedicado (cursor izq./der., volver,
 * deshacer), accesible desde la cinta con su acrónimo; no es un modo de teclado
 * como [LOLA]. [CLIPBOARD] muestra el mini portapapeles (panel, no teclas).
 */
enum class KeyboardLayer { LETTERS, SYMBOLS, EMOJI, CLIPBOARD, CURSOR }

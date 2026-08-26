package com.elladecide.lola.emoji

/**
 * Emoji del catálogo curado: glifo + nombre legible (para accesibilidad y
 * búsqueda). `skinTone` marca si el emoji acepta modificadores de tono de piel
 * (manos y gestos tipo Gboard). Puro, sin android.*.
 */
data class Emoji(val glyph: String, val name: String, val skinTone: Boolean = false)

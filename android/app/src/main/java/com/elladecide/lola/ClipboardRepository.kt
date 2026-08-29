package com.elladecide.lola

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Mini portapapeles (estilo Gboard): historial LOCAL de lo que se copió.
 *
 * El núcleo ([add], [remove], [purge]) es puro y testeable; la persistencia es
 * SharedPreferences con JSON (org.json). Está activo por defecto y NUNCA se
 * sincroniza: es data sensible (contraseñas, tarjetas), por eso el toggle en
 * Ajustes.
 *
 * Reglas: últimos [MAX_ITEMS], TTL [TTL_MS] desde el copy, sin duplicados
 * (la copia más reciente gana).
 */
object ClipboardRepository {

    const val MAX_ITEMS = 20
    const val TTL_MS = 24 * 60 * 60 * 1000L

    data class ClipItem(val text: String, val ts: Long)

    // ── Núcleo puro (testeable en JVM) ─────────────────────────────────────

    /** Agrega el texto copiado (más reciente primero), purgando lo vencido. */
    fun add(items: List<ClipItem>, text: String, now: Long): List<ClipItem> {
        if (text.isBlank()) return items
        return (listOf(ClipItem(text, now)) + purge(items, now))
            .distinctBy { it.text }
            .take(MAX_ITEMS)
    }

    fun remove(items: List<ClipItem>, text: String): List<ClipItem> =
        items.filterNot { it.text == text }

    /** Descarta lo copiado hace más de [TTL_MS]. */
    fun purge(items: List<ClipItem>, now: Long): List<ClipItem> =
        items.filter { now - it.ts < TTL_MS }

    // ── Persistencia (SharedPreferences + JSON) ────────────────────────────

    fun load(prefs: SharedPreferences): List<ClipItem> {
        val raw = prefs.getString(LolaPrefs.KEY_CLIPBOARD_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ClipItem(o.getString("t"), o.getLong("ts"))
            }
            purge(items, System.currentTimeMillis()).distinctBy { it.text }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun save(prefs: SharedPreferences, items: List<ClipItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.text).put("ts", it.ts)) }
        prefs.edit().putString(LolaPrefs.KEY_CLIPBOARD_ITEMS, arr.toString()).apply()
    }
}
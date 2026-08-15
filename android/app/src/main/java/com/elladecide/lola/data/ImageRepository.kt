package com.elladecide.lola.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.Normalizer

/** Una imagen indexada de la carpeta del vendedor. */
data class ImageEntry(
    val name: String,
    val uri: Uri,
    val score: Int = 0,
)

/**
 * Acceso a la carpeta de imágenes — port de imagenes.js de la extensión.
 *
 * - `scan`: escaneo recursivo de la carpeta elegida (SAF / DocumentFile).
 * - `buscar`: matcheo producto→imagen por CONVENCIÓN DE NOMBRE:
 *     1) estricto: fábrica (primer token del archivo) + modelo (números 2+ dígitos).
 *     2) fallback: primeras palabras (fábrica + descripción) en orden, sin "liq".
 */
object ImageRepository {

    private val IMG_RE = Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)

    fun isImage(name: String): Boolean = IMG_RE.matches(name)

    fun scan(context: Context, rootUri: Uri): List<ImageEntry> {
        val tree = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val out = mutableListOf<ImageEntry>()
        scanDir(tree, out, "")
        return out
    }

    private fun scanDir(dir: DocumentFile, out: MutableList<ImageEntry>, prefix: String) {
        for (f in dir.listFiles()) {
            when {
                f.isDirectory -> scanDir(f, out, prefix + (f.name ?: "") + "/")
                isImage(f.name ?: "") -> out.add(ImageEntry(prefix + (f.name ?: ""), f.uri))
            }
        }
    }

    /** Último segmento del path, sin extensión, en minúsculas. */
    private fun normalizar(name: String): String =
        name.substringAfterLast('/').replace(Regex("\\.[a-z0-9]+$", RegexOption.IGNORE_CASE), "").lowercase()

    // Divide en tokens: separa letras/números, quita tildes y puntos.
    // "BAKHOU2005" → ["bakhou","2005"]; "B.S_..." → ["bs", ...].
    private fun tokenizarNombre(s: String): List<String> {
        val limpio = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(".", "")
        return Regex("\\d+|[a-z]+").findAll(limpio).map { it.value }.toList()
    }

    fun buscar(product: Product, indice: List<ImageEntry>): List<ImageEntry> {
        val nombreTokens = tokenizarNombre(product.nombre)
        val modelos = Regex("\\d{2,}").findAll(product.nombre.lowercase())
            .map { it.value }.distinct().toList()
        val out = mutableListOf<ImageEntry>()

        // 1) Estricto: fábrica + modelo.
        if (modelos.isNotEmpty()) {
            for (e in indice) {
                val nTokens = tokenizarNombre(normalizar(e.name))
                if (nTokens.isEmpty()) continue
                val marca = nTokens[0]
                if (!modelos.any { it in nTokens }) continue
                if (marca !in nombreTokens) continue
                out.add(e.copy(score = 4))
            }
        }

        // 2) Fallback: primeras palabras en orden (sin "liq").
        if (out.isEmpty()) {
            val head = nombreTokens.filter { it != "liq" }.take(3)
            if (head.size >= 2) {
                for (e in indice) {
                    val nTokens = tokenizarNombre(normalizar(e.name))
                    val nHead = nTokens.take(head.size)
                    if (nHead.size == head.size && head.withIndex().all { (i, t) -> t == nHead[i] }) {
                        out.add(e.copy(score = 3))
                    }
                }
            }
        }

        return out.sortedByDescending { it.score }
    }

    /** Miniatura con submuestreo (para la lista de resultados). */
    fun decodeThumbnail(context: Context, entry: ImageEntry, maxSize: Int = 128): Bitmap? {
        return try {
            context.contentResolver.openInputStream(entry.uri)?.use { input ->
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
                    sample *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) {
            null
        }
    }
}

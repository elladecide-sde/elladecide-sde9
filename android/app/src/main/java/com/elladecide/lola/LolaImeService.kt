package com.elladecide.lola

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.elladecide.lola.data.LolaDatabase
import com.elladecide.lola.ui.KeyboardScreen
import java.io.File

/**
 * LOLA IME — teclado personalizado (InputMethodService).
 *
 * - UI en Compose (ComposeView dentro del input view).
 * - Texto vía commitText(); PNG e imágenes vía commitContent() (como Gboard).
 * - Sin AccessibilityServices: el usuario SIEMPRE aprieta "Enviar" a mano.
 */
class LolaImeService : InputMethodService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var database: LolaDatabase
    private lateinit var controller: KeyboardController

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        database = LolaDatabase.build(this)
        controller = KeyboardController(lifecycleScope, ::commitText, ::commitImage, ::commitImageFile)
        controller.cargar(database, applicationContext)
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        controller.rescanear(this)
        return ComposeView(this).apply {
            val h = (300 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            setViewTreeLifecycleOwner(this@LolaImeService)
            setContent { KeyboardScreen(controller) }
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val variation = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val esPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return !esPassword
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    /** Entrega un Bitmap (PNG de precios) vía commitContent(). */
    private fun commitImage(bitmap: Bitmap) {
        val ic = currentInputConnection ?: return
        val uri = saveToCache(bitmap, Bitmap.CompressFormat.PNG, "png") ?: run {
            controller.estado = "No se pudo generar la imagen"
            return
        }
        commitContentUri(ic, uri, "image/png", "LOLA precio")
    }

    /** Entrega una imagen de la carpeta del vendedor vía commitContent(). */
    private fun commitImageFile(sourceUri: Uri) {
        val ic = currentInputConnection ?: return
        val mime = contentResolver.getType(sourceUri) ?: "image/jpeg"
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val out = copyToCache(sourceUri, ext) ?: run {
            controller.estado = "No se pudo leer la imagen"
            return
        }
        commitContentUri(ic, out, mime, "LOLA imagen")
    }

    private fun commitContentUri(ic: InputConnection, uri: Uri, mime: String, label: String) {
        val desc = ClipDescription(label, arrayOf(mime))
        val info = InputContentInfo(uri, desc, null)
        val ok = try {
            ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            val clip = ClipData.newUri(contentResolver, label, uri)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            controller.estado = "Imagen copiada — pegala con mantener apretado → Pegar"
        }
    }

    private fun saveToCache(bitmap: Bitmap, format: Bitmap.CompressFormat, ext: String): Uri? {
        return try {
            val dir = File(cacheDir, "precios").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}.$ext")
            file.outputStream().use { out -> bitmap.compress(format, 100, out) }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    private fun copyToCache(source: Uri, ext: String): Uri? {
        return try {
            val dir = File(cacheDir, "precios").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }
}

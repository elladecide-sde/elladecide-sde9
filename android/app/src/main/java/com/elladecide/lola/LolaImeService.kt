package com.elladecide.lola

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.elladecide.lola.data.LolaDatabase
import com.elladecide.lola.editor.AndroidEditorConnection
import com.elladecide.lola.editor.EditorInfoClassifier
import com.elladecide.lola.editor.EditorSession
import com.elladecide.lola.language.PlaceholderWords
import com.elladecide.lola.language.PrefixSuggestionProvider
import com.elladecide.lola.ui.KeyboardScreen
import java.io.File

/**
 * LOLA IME — teclado personalizado (InputMethodService).
 *
 * - UI en Compose (ComposeView dentro del input view).
 * - Texto vía commitText(); PNG e imágenes vía commitContent() (como Gboard).
 * - Sin AccessibilityServices: el usuario SIEMPRE aprieta "Enviar" a mano.
 */
class LolaImeService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var database: LolaDatabase
    private lateinit var controller: KeyboardController
    private lateinit var rootView: ImeRootView
    private val session = EditorSession()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        database = LolaDatabase.build(this)
        controller = KeyboardController(
            lifecycleScope,
            onCommitText = { session.commitText(it) },
            onCommitImage = ::commitImage,
            onCommitImageFile = ::commitImageFile,
            onDelete = { session.deleteBackwards() },
            onEnter = { session.sendEnter() },
            onMoveCursor = { session.moveCursor(it) },
            onCambiarAltura = { redimensionar() },
            onTypeText = { session.typeText(it) },
            onReplaceComposing = { session.replaceComposingText(it) },
            onUndo = { session.undo() },
            onCurrentWord = { session.currentWord() },
            suggestionProvider = PrefixSuggestionProvider(PlaceholderWords.LIST),
        )
        controller.suggestionsEnabled = getSharedPreferences("lola", Context.MODE_PRIVATE)
            .getBoolean("suggestions_enabled", true)
        controller.cargar(database, applicationContext)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        session.onStartInput(
            inputType = attribute?.inputType ?: 0,
            imeOptions = attribute?.imeOptions ?: 0,
            provider = { AndroidEditorConnection(currentInputConnection) },
        )
        controller.profile = session.profile
    }

    override fun onFinishInput() {
        super.onFinishInput()
        session.onFinishInput()
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        controller.rescanear(this)
        return buildInputView()
    }

    private fun buildInputView(): View {
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LolaImeService)
            setViewTreeSavedStateRegistryOwner(this@LolaImeService)
            setContent { KeyboardScreen(controller) }
        }
        rootView = ImeRootView(this, this, alturaPx(controller.expandida)).apply {
            addView(
                composeView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return rootView
    }

    /**
     * Redimensiona la ventana del IME al expandir/contraer el teclado, sin
     * recrear el `ComposeView`. Antes se llamaba a `setInputView(buildInputView())`,
     * que destruía la composición y podía perder foco/estado visual.
     */
    private fun redimensionar() {
        if (::rootView.isInitialized) {
            rootView.setAlturaPx(alturaPx(controller.expandida))
        }
    }

    /** Altura de la ventana del IME en px según el modo (contraído/expandido). */
    private fun alturaPx(expandida: Boolean): Int {
        val alturaDp = if (expandida) 440 else 250
        return (alturaDp * resources.displayMetrics.density).toInt()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        val info = currentInputEditorInfo ?: return true
        return !EditorInfoClassifier.classify(info.inputType, info.imeOptions).isPassword
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
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

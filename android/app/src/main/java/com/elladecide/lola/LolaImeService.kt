package com.elladecide.lola

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.KeyEvent
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
import com.elladecide.lola.language.NeuralSuggestionProvider
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
    private lateinit var neuralSuggestionProvider: NeuralSuggestionProvider
    private lateinit var rootView: ImeRootView
    private val session = EditorSession()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        database = LolaDatabase.build(this)
        neuralSuggestionProvider = NeuralSuggestionProvider(applicationContext)
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
            onContextBefore = { session.contextBeforeCurrentWord() },
            onDeleteWord = { session.deleteWord() },
            onSyncEditor = { session.syncEditor() },
            suggestionProvider = neuralSuggestionProvider,
        )
        controller.suggestionsEnabled = LolaPrefs.get(this)
            .getBoolean(LolaPrefs.KEY_SUGGESTIONS_ENABLED, true)
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

    /**
     * El editor reporta la selección real (fuente de verdad del cursor, spec
     * §17): se reenvía a la sesión para que el composing y la navegación se
     * resincronicen. Sin esto, el cursor interno diverge del real (bugs de
     * "insertar en el medio" y "cursor que vuelve atrás").
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        session.onSelectionChanged(newSelStart, newSelEnd)
    }

    /**
     * El Back físico/software (spec F3 §8B): el IME recibe el KEYCODE_BACK en
     * [onKeyDown] ANTES que la Activity. Si hay un popup de long-press abierto,
     * lo cierra SIN commitear y consume el evento (el teclado queda visible).
     * Si no hay popup, delega en el comportamiento por defecto (ocultar el IME).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (controller.popupController.cancelarExterno()) return true
        }
        return super.onKeyDown(keyCode, event)
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
        rootView = ImeRootView(this, this, alturaPx(controller)).apply {
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
            rootView.setAlturaPx(alturaPx(controller))
        }
    }

    /** Altura de la ventana del IME en px según el modo (CHAT/LOLA/tamaño). */
    private fun alturaPx(controller: KeyboardController): Int {
        val dp = controller.alturaDp()
        // EXPANDED_FILL_DP: pedir todo el alto de la pantalla; el ImeRootView
        // lo clampa al área realmente disponible (sin porcentajes fijos).
        if (dp < 0) return resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        // Fracción del alto de pantalla para que el teclado crezca en pantallas
        // grandes (tablets) en vez de quedar clavado en un dp fijo. El PISO es
        // el dp de cada modo (pantallas chicas/landscape no bajan de ahí); el
        // ImeRootView clampa al área real, así nunca desborda.
        val fraccion = if (controller.enModoLola) {
            KeyboardController.LOLA_SCREEN_FRACTION
        } else {
            KeyboardController.CHAT_SCREEN_FRACTION
        }
        val escalado = (screenH * fraccion).toInt()
        val piso = (dp * density).toInt()
        return maxOf(escalado, piso)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // La decisión de privacidad reemplaza deliberadamente la política base:
    // un campo password nunca debe mostrar este IME aunque el sistema diga sí.
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown(): Boolean {
        val info = currentInputEditorInfo ?: return true
        return !EditorInfoClassifier.classify(info.inputType, info.imeOptions).isPassword
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        controller.cerrarPortapapeles()
        if (::neuralSuggestionProvider.isInitialized) neuralSuggestionProvider.close()
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
        // Negociar MIME: si el editor no declara qué acepta, asumimos que acepta
        // el nuestro (la mayoría no expone contentMimeTypes). Si lo declara y no
        // nos incluye, no intentamos commitContent (evita el fallback tonto).
        val supported = currentInputEditorInfo?.contentMimeTypes
        if (supported != null && supported.none { it.equals(mime, ignoreCase = true) }) {
            clipboardFallback(uri, label)
            return
        }
        val desc = ClipDescription(label, arrayOf(mime))
        val info = InputContentInfo(uri, desc, null)
        val ok = try {
            ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            clipboardFallback(uri, label)
        }
    }

    /** Fallback explícito: copia al portapapeles y avisa al usuario. */
    private fun clipboardFallback(uri: Uri, label: String) {
        val clip = ClipData.newUri(contentResolver, label, uri)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        controller.estado = "Imagen copiada — pegala con mantener apretado → Pegar"
    }

    private fun saveToCache(bitmap: Bitmap, format: Bitmap.CompressFormat, ext: String): Uri? {
        return try {
            val dir = File(cacheDir, "precios").apply { mkdirs() }
            pruneCache(dir)
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
            pruneCache(dir)
            val file = File(dir, "img_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Limpia la carpeta de cache de imágenes generadas: borra los archivos con
     * más de 24h (el contenido ya fue committeado o copiado al portapapeles, así
     * que no se vuelven a necesitar). Evita que el cache crezca sin límite.
     */
    private fun pruneCache(dir: File) {
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private companion object {
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}

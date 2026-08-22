package com.elladecide.lola

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elladecide.lola.data.ImageEntry
import com.elladecide.lola.data.ImageRepository
import com.elladecide.lola.data.LolaDatabase
import com.elladecide.lola.data.Product
import com.elladecide.lola.data.SeedCatalog
import com.elladecide.lola.data.SyncRepository
import com.elladecide.lola.editor.EditorProfile
import com.elladecide.lola.emoji.EmojiEngine
import com.elladecide.lola.keyboard.Key
import com.elladecide.lola.keyboard.KeyEffect
import com.elladecide.lola.keyboard.KeyEngine
import com.elladecide.lola.keyboard.KeyType
import com.elladecide.lola.keyboard.KeyboardLayer
import com.elladecide.lola.language.SuggestionProvider
import com.elladecide.lola.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModoResultado { TEXTO, PNG }

/**
 * Estado del teclado (Compose state holder) + orquestación de catálogo/sync,
 * del pedido (carrito) y de la carpeta de imágenes.
 *
 * El estado y la semántica del teclado base (capa, shift, layouts, teclas)
 * viven en [keyEngine], que es puro y testeable en la JVM.
 *
 * [Stable]: todas las propiedades públicas son `val` o snapshot-state
 * observables (`mutableStateOf`), así Compose puede saltear la recomposición
 * de los hijos cuyo estado no cambió. Sin esta anotación el compilador marca
 * al controller como "unstable" y fuerza recomponer todos los hijos en cada
 * recomposición del padre (ver reporte `-PcomposeCompilerMetrics`).
 */
@Stable
class KeyboardController(
    private val scope: CoroutineScope,
    private val onCommitText: (String) -> Unit,
    private val onCommitImage: (Bitmap) -> Unit,
    private val onCommitImageFile: (Uri) -> Unit,
    private val onDelete: () -> Unit,
    private val onEnter: () -> Unit,
    private val onMoveCursor: (Int) -> Unit,
    private val onCambiarAltura: (() -> Unit)? = null,
    private val onTypeText: (String) -> Unit,
    private val onReplaceComposing: (String) -> Unit,
    private val onUndo: () -> Unit,
    private val onCurrentWord: () -> String,
    private val onDeleteWord: () -> Unit,
    private val suggestionProvider: SuggestionProvider,
) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Product>>(emptyList())
    var modo by mutableStateOf(ModoResultado.TEXTO)
    var estado by mutableStateOf("Cargando catálogo…")
    var cart by mutableStateOf<List<CartItem>>(emptyList())
    var descuento by mutableStateOf(15)
    var expandedCodigo by mutableStateOf<String?>(null)
    var imagenes by mutableStateOf<List<ImageEntry>>(emptyList())
    var expandida by mutableStateOf(false)

    /** Motor del teclado base (capa + shift + resolución de teclas). */
    val keyEngine = KeyEngine()

    /** Motor del panel de emojis (categorías + recientes). */
    val emojiEngine = EmojiEngine()

    /** Perfil del editor activo (para la etiqueta/acción del Enter). */
    var profile by mutableStateOf(EditorProfile.EMPTY)

    /** Candidatos actuales del motor de sugerencias (modo teclado normal). */
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set

    /** Privacidad: opt-out del usuario para desactivar las sugerencias. */
    var suggestionsEnabled by mutableStateOf(true)

    /** True cuando la barra de sugerencias debe mostrarse. */
    val sugerenciasVisibles: Boolean
        get() = !expandida && capa != KeyboardLayer.EMOJI &&
            suggestionsEnabled && profile.suggestionsAllowed && suggestions.isNotEmpty()

    /** Capa visible, delegada al motor. */
    val capa: KeyboardLayer get() = keyEngine.layer

    private var productos: List<Product> = emptyList()
    private var lastScannedUri: String? = null
    private var appContext: Context? = null

    val totalEfectivo: Int get() = Precio.totales(cart, descuento).second

    /** Carga inicial: seed offline si está vacío + intento de sync best-effort. */
    fun cargar(db: LolaDatabase, appContext: Context) {
        this.appContext = appContext
        val recents = appContext.getSharedPreferences("lola", Context.MODE_PRIVATE)
            .getString(EMOJI_RECENTS_KEY, null)
            ?.split(RECENTS_DELIMITER)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        emojiEngine.setRecents(recents)
        scope.launch {
            val count = withContext(Dispatchers.IO) { db.productDao().count() }
            if (count == 0) {
                SeedCatalog.sembrar(appContext, db)
            }
            try {
                SyncRepository(db.productDao(), db.syncMetaDao()).sync()
            } catch (e: Exception) {
                // sin backend alcanzable → catálogo local (offline-first)
            }
            productos = withContext(Dispatchers.IO) { db.productDao().getAll() }
            estado = "${productos.size} productos listos"
        }
    }

    fun onQueryChanged(q: String) {
        query = q
        results = if (q.isBlank()) emptyList() else SearchEngine.search(productos, q)
    }

    fun toggleExpanded(codigo: String) {
        expandedCodigo = if (expandedCodigo == codigo) null else codigo
    }

    fun addToCart(product: Product, listaIndex: Int) {
        cart = cart + CartItem(product, listaIndex)
        expandedCodigo = null
        estado = "${cart.size} ítem(s) en el pedido"
    }

    fun removeFromCart(index: Int) {
        cart = cart.filterIndexed { i, _ -> i != index }
    }

    fun limpiarCart() {
        cart = emptyList()
        estado = "Pedido vacío"
    }

    fun toggleModo() {
        modo = if (modo == ModoResultado.TEXTO) ModoResultado.PNG else ModoResultado.TEXTO
    }

    fun enviar() {
        if (cart.isEmpty()) return
        when (modo) {
            ModoResultado.TEXTO -> onCommitText(Precio.buildMensaje(cart, descuento))
            ModoResultado.PNG -> onCommitImage(PrecioPngRenderer.renderPedido(cart, descuento))
        }
        cart = emptyList()
        estado = "Pedido enviado — apretá Enviar en la app"
    }

    /**
     * Escribe texto ya resuelto por el motor. En modo LOLA (expandida) alimenta
     * la búsqueda; en modo teclado normal (contraída) tipea en la app anfitriona
     * con composición de palabra (para poder sugerir y reemplazar).
     */
    fun tecla(texto: String) {
        if (expandida) {
            onQueryChanged(query + texto)
        } else {
            onTypeText(texto)
            refreshSuggestions()
        }
    }

    /**
     * Inserta una alternativa de long-press (ya viene capitalizada por el
     * motor) y consume el shift temporal, como haría una letra normal.
     */
    fun comprometerAlternativa(texto: String) {
        keyEngine.consumeTemporaryShift()
        tecla(texto)
    }

    /** Borra un carácter: de la búsqueda si estamos en modo LOLA, o de la app. */
    fun borrar() {
        if (expandida) {
            onQueryChanged(query.dropLast(1))
        } else {
            onDelete()
            refreshSuggestions()
        }
    }

    /** Selecciona una sugerencia: reemplaza la palabra en composición. */
    fun sugerir(palabra: String) {
        onReplaceComposing(palabra)
        refreshSuggestions()
    }

    /** Deshace la última palabra committeada por el tipeo. */
    fun deshacer() {
        onUndo()
        refreshSuggestions()
    }

    /** Recalcula candidatos a partir de la palabra en composición actual. */
    private fun refreshSuggestions() {
        suggestions = if (!expandida && suggestionsEnabled && profile.suggestionsAllowed) {
            suggestionProvider.suggest(onCurrentWord(), "", 3)
        } else {
            emptyList()
        }
    }

    /** Tecla Enter: se envía a la app anfitriona (acción IME o nueva línea). */
    fun enter() {
        onEnter()
    }

    /** Fija el bloqueo de mayúsculas (long-press o doble tap en Shift). */
    fun fijarShift() {
        keyEngine.lockShift()
    }

    /** Inserta un emoji siempre en la app anfitriona (no alimenta la búsqueda). */
    fun emoji(e: String) {
        onCommitText(e)
        refreshSuggestions()
        emojiEngine.recordRecent(e)
        persistirRecents()
    }

    /** Borra la palabra completa a la izquierda del cursor (swipe-delete). */
    fun borrarPalabra() {
        onDeleteWord()
        refreshSuggestions()
    }

    /** Mueve el cursor (cursor glide en la barra espaciadora). */
    fun moverCursor(delta: Int) {
        onMoveCursor(delta)
        refreshSuggestions()
    }

    /** Persiste los emojis recientes en SharedPreferences. */
    private fun persistirRecents() {
        appContext?.getSharedPreferences("lola", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(EMOJI_RECENTS_KEY, emojiEngine.recentsList().joinToString(RECENTS_DELIMITER))
            ?.apply()
    }

    // ── Teclado base (delegado al motor) ──

    /** Layout de la capa actual, ya resuelto por el motor. */
    fun teclasActuales(): List<List<Key>> = keyEngine.layout()

    /** Fila inferior compartida (layer/emoji/cursor/espacio/lola/enter). */
    fun filaInferior(): List<Key> = keyEngine.bottomRow()

    fun etiqueta(k: Key): String = keyEngine.label(k, profile)

    fun peso(k: Key): Float = keyEngine.weight(k)

    /** Resalta la tecla (shift activo, capa emoji o modo LOLA expandido). */
    fun esActiva(k: Key): Boolean = keyEngine.isActive(k) || (k.type == KeyType.LOLA && expandida)

    fun alternativas(k: Key): List<String> = keyEngine.alternatives(k)

    /** Descripción legible de la tecla para accesibilidad (TalkBack). */
    fun descripcion(k: Key): String = keyEngine.contentDescription(k, profile)

    /** Estado a anunciar, incluyendo el modo LOLA expandido (que no conoce el motor). */
    fun descripcionEstado(k: Key): String? = keyEngine.stateDescription(k)
        ?: if (k.type == KeyType.LOLA && expandida) "Modo LOLA activado" else null

    /** Procesa una tecla vía el motor y enruta el efecto resultante. */
    fun press(k: Key) {
        when (val e = keyEngine.press(k)) {
            is KeyEffect.Commit -> tecla(e.text)
            KeyEffect.Delete -> borrar()
            KeyEffect.Enter -> enter()
            KeyEffect.MoveLeft -> {
                onMoveCursor(-1)
                refreshSuggestions()
            }
            KeyEffect.MoveRight -> {
                onMoveCursor(1)
                refreshSuggestions()
            }
            KeyEffect.Undo -> deshacer()
            KeyEffect.ToggleExpand -> toggleExpandir()
            null -> Unit
        }
    }

    /** Alterna entre teclado expandido (modo LOLA) y contraído (teclado normal). */
    fun toggleExpandir() {
        expandida = !expandida
        onCambiarAltura?.invoke()
    }

    // ── Imágenes (carpeta local, convención fábrica + modelo) ──

    fun elegirCarpeta(context: Context) {
        val intent = Intent(context, FolderPickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            estado = "No se pudo abrir el selector de carpeta"
        }
    }

    /** Relee la carpeta persistida y escanea (idempotente por URI). */
    fun rescanear(context: Context) {
        val uriStr = context.getSharedPreferences("lola", Context.MODE_PRIVATE)
            .getString("folder_uri", null) ?: return
        if (uriStr == lastScannedUri) return
        lastScannedUri = uriStr
        scope.launch {
            val uri = Uri.parse(uriStr)
            imagenes = withContext(Dispatchers.IO) { ImageRepository.scan(context, uri) }
            estado = "${imagenes.size} imágenes listas"
        }
    }

    fun imagenDe(product: Product): ImageEntry? =
        if (imagenes.isEmpty()) null else ImageRepository.buscar(product, imagenes).firstOrNull()

    fun insertarImagen(product: Product) {
        val entry = imagenDe(product) ?: run {
            estado = "Sin imagen para este producto"
            return
        }
        onCommitImageFile(entry.uri)
    }

    companion object {
        private const val EMOJI_RECENTS_KEY = "emoji_recents"
        private const val RECENTS_DELIMITER = ","
    }
}

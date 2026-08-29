package com.elladecide.lola

import android.content.ClipboardManager
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
import com.elladecide.lola.data.SyncNetworkException
import com.elladecide.lola.data.SyncProtocolException
import com.elladecide.lola.data.SyncRepository
import com.elladecide.lola.editor.EditorProfile
import com.elladecide.lola.emoji.EmojiEngine
import com.elladecide.lola.keyboard.Key
import com.elladecide.lola.keyboard.KeyEffect
import com.elladecide.lola.keyboard.KeyEngine
import com.elladecide.lola.keyboard.KeyType
import com.elladecide.lola.keyboard.KeyboardLayer
import com.elladecide.lola.keyboard.KeyboardMode
import com.elladecide.lola.keyboard.KeyboardSize
import com.elladecide.lola.keyboard.PopupController
import com.elladecide.lola.language.SuggestionProvider
import com.elladecide.lola.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val onSyncEditor: () -> Unit,
    private val suggestionProvider: SuggestionProvider,
) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Product>>(emptyList())
    var modo by mutableStateOf(ModoResultado.PNG)
    var estado by mutableStateOf("Cargando catálogo…")
    var cart by mutableStateOf<List<CartItem>>(emptyList())
    var descuento by mutableStateOf(15)
    var expandedCodigo by mutableStateOf<String?>(null)
    var imagenes by mutableStateOf<List<ImageEntry>>(emptyList())

    /** UX: mostrar e insertar fotos de productos (configurable en Ajustes). */
    var imagenesEnabled by mutableStateOf(true)

    /** Switches de formato del mensaje (lectura LC/LL, concepto N/U, etc.). */
    var pedidoConfig by mutableStateOf(PedidoConfig())

    /** Descuento global (TODOS) o por ítem (MANUAL). */
    var descuentoModo by mutableStateOf(DescuentoModo.TODOS)

    /**
     * Lista fijada para TODO el pedido (modo de lista global): -1 = "Todas"
     * (se elige lista al expandir cada producto); 0..3 = Lista 1..4. Fijarla
     * migra el pedido entero a esa lista (cada ítem se reasigna, ver
     * [fijarLista]).
     */
    var defaultListaIndex by mutableStateOf(-1)
        private set

    /** Ítem en edición de descuento (modo MANUAL) + descuento pendiente. */
    var editingCartIndex by mutableStateOf<Int?>(null)
    var pendingDescuento by mutableStateOf(0)

    /** Modo de funcionamiento (CHAT = teclado normal, LOLA = catálogo). */
    var mode by mutableStateOf(KeyboardMode.CHAT)
        private set

    /** Tamaño del área (solo aplica en modo LOLA). */
    var size by mutableStateOf(KeyboardSize.COMPACT)
        private set

    /** Conveniencia para la UI: el catálogo LOLA ocupa el área expandida. */
    val enModoLola: Boolean get() = mode == KeyboardMode.LOLA
    val expandida: Boolean get() = enModoLola && size == KeyboardSize.EXPANDED

    /** Altura de la ventana del IME en dp según modo/tamaño (adaptativa). */
    fun alturaDp(): Int = when {
        enModoLola && size == KeyboardSize.EXPANDED -> EXPANDED_FILL_DP
        enModoLola -> LOLA_COMPACT_HEIGHT_DP
        else -> CHAT_HEIGHT_DP
    }

    /** Motor del teclado base (capa + shift + resolución de teclas). */
    val keyEngine = KeyEngine()

    /** Motor del panel de emojis (categorías + recientes). */
    val emojiEngine = EmojiEngine()

    /** Estado del popup de long-press (lo cierra el IME con Back, spec §8B). */
    val popupController = PopupController()

    /** Perfil del editor activo (para la etiqueta/acción del Enter). */
    var profile by mutableStateOf(EditorProfile.EMPTY)

    /** Candidatos actuales del motor de sugerencias (modo teclado normal). */
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set

    /** Privacidad: opt-out del usuario para desactivar las sugerencias. */
    var suggestionsEnabled by mutableStateOf(true)

    /** True cuando la barra de sugerencias debe mostrarse (solo modo CHAT). */
    val sugerenciasVisibles: Boolean
        get() = !enModoLola && capa != KeyboardLayer.EMOJI && capa != KeyboardLayer.CURSOR &&
            capa != KeyboardLayer.CLIPBOARD &&
            suggestionsEnabled && profile.suggestionsAllowed && suggestions.isNotEmpty()

    /** Capa visible, delegada al motor. */
    val capa: KeyboardLayer get() = keyEngine.layer

    private var productos: List<Product> = emptyList()

    /** Índice pre-tokenizado del catálogo para búsqueda rápida (build una vez). */
    private var catalogoIndex: List<SearchEngine.IndexedProduct> = emptyList()
    private var searchJob: Job? = null
    private var searchGen = 0

    /** Cache producto→imagen (el match es O(índice); se memoiza por codigoUnico). */
    private val imagenCache = mutableMapOf<String, ImageEntry?>()
    private var lastScannedUri: String? = null
    private var appContext: Context? = null

    // ── Mini portapapeles (local, opt-in) ──────────────────────────────────
    var clipboardEnabled by mutableStateOf(false)
        private set

    var clipboardItems by mutableStateOf<List<ClipboardRepository.ClipItem>>(emptyList())
        private set

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!clipboardEnabled) return@OnPrimaryClipChangedListener
        val ctx = appContext ?: return@OnPrimaryClipChangedListener
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@OnPrimaryClipChangedListener
        val text = try {
            cm.primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        } catch (e: SecurityException) {
            // Lectura en un momento no permitido (check de foreground en API 29+):
            // se ignora, el IME no debe crashear por un clipboard mis-timed.
            return@OnPrimaryClipChangedListener
        }
        if (!text.isNullOrBlank()) {
            val prefs = LolaPrefs.get(ctx)
            clipboardItems = ClipboardRepository.add(ClipboardRepository.load(prefs), text, System.currentTimeMillis())
                .also { ClipboardRepository.save(prefs, it) }
        }
    }

    val totalEfectivo: Int get() = Precio.totales(cart, descuento).second

    /** Carga inicial: seed offline si está vacío + intento de sync best-effort. */
    fun cargar(db: LolaDatabase, appContext: Context) {
        this.appContext = appContext
        val prefs = LolaPrefs.get(appContext)
        val recents = prefs.getString(LolaPrefs.KEY_EMOJI_RECENTS, null)
            ?.split(LolaPrefs.RECENTS_DELIMITER)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        emojiEngine.restoreRecents(recents)
        pedidoConfig = PedidoConfig(
            lectura = enumOf(Lectura.entries, prefs.getString(LolaPrefs.KEY_LECTURA, null), Lectura.LC),
            concepto = enumOf(Concepto.entries, prefs.getString(LolaPrefs.KEY_CONCEPTO, null), Concepto.N),
            textoDesc = prefs.getBoolean(LolaPrefs.KEY_TEXTO_DESC, true),
            valorModo = enumOf(ValorModo.entries, prefs.getString(LolaPrefs.KEY_VALOR_MODO, null), ValorModo.LISTA),
        )
        descuentoModo = enumOf(DescuentoModo.entries, prefs.getString(LolaPrefs.KEY_DESCUENTO_MODO, null), DescuentoModo.TODOS)
        defaultListaIndex = prefs.getInt(LolaPrefs.KEY_LISTA_DEFAULT, -1)
            .coerceIn(-1, Mensaje.LISTA_NAMES.size - 1)
        modo = enumOf(ModoResultado.entries, prefs.getString(LolaPrefs.KEY_MODO_RESULTADO, null), ModoResultado.PNG)
        clipboardEnabled = prefs.getBoolean(LolaPrefs.KEY_CLIPBOARD_ENABLED, true)
        if (clipboardEnabled) {
            clipboardItems = ClipboardRepository.load(prefs)
            (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.addPrimaryClipChangedListener(clipListener)
        }
        scope.launch {
            val count = withContext(Dispatchers.IO) { db.productDao().count() }
            if (count == 0) {
                SeedCatalog.sembrar(appContext, db)
            }
            var res: SyncRepository.Resultado? = null
            var errorSync: String? = null
            try {
                res = SyncRepository(
                    db.productDao(),
                    db.syncMetaDao(),
                    baseUrl = prefs.getString(LolaPrefs.KEY_BASE_URL, null) ?: SyncRepository.DEFAULT_BASE_URL,
                    secret = prefs.getString(LolaPrefs.KEY_SECRET, null),
                ).sync()
            } catch (e: SyncProtocolException) {
                // El backend respondió mal (HTTP no 2xx o JSON inválido): es un
                // bug de contrato, NO se silencia. Se expone para que se vea.
                errorSync = e.message
            } catch (e: SyncNetworkException) {
                // sin backend alcanzable → catálogo local (offline-first)
            }
            productos = withContext(Dispatchers.IO) { db.productDao().getAll() }
            catalogoIndex = withContext(Dispatchers.Default) { SearchEngine.index(productos) }
            estado = when {
                errorSync != null -> "Sync falló: $errorSync"
                res != null && res.motivo != null -> "${res.productos} productos · ${res.motivo}"
                res != null && res.sincronizado -> "${res.productos} productos · sincronizado"
                else -> "${productos.size} productos listos"
            }
            // Si el usuario ya estaba buscando cuando terminó de cargar, refrescá.
            if (query.isNotBlank()) onQueryChanged(query)
        }
    }

    fun onQueryChanged(q: String) {
        query = q
        searchJob?.cancel()
        val gen = ++searchGen
        if (q.isBlank()) {
            results = emptyList()
            return
        }
        // Búsqueda en background (Default); se descarta si llegó una query más nueva.
        searchJob = scope.launch {
            val found = withContext(Dispatchers.Default) { SearchEngine.searchIndexed(catalogoIndex, q) }
            if (gen == searchGen) results = found
        }
    }

    fun toggleExpanded(codigo: String) {
        expandedCodigo = if (expandedCodigo == codigo) null else codigo
    }

    fun addToCart(product: Product, listaIndex: Int) {
        val idx = listaIndex.coerceIn(-1, product.preciosList().lastIndex)
        cart = cart + CartItem(product, idx)
        expandedCodigo = null
        estado = "${cart.size} ítem(s) en el pedido"
    }

    fun removeFromCart(index: Int) {
        cart = cart.filterIndexed { i, _ -> i != index }
        if (editingCartIndex == index) {
            editingCartIndex = null
            pendingDescuento = 0
        }
    }

    fun limpiarCart() {
        cart = emptyList()
        editingCartIndex = null
        pendingDescuento = 0
        estado = "Pedido vacío"
    }

    fun toggleModoResultado() {
        modo = if (modo == ModoResultado.TEXTO) ModoResultado.PNG else ModoResultado.TEXTO
        appContext?.let { LolaPrefs.get(it).edit().putString(LolaPrefs.KEY_MODO_RESULTADO, modo.name).apply() }
    }

    fun enviar() {
        if (cart.isEmpty()) return
        when (modo) {
            ModoResultado.TEXTO -> onCommitText(Precio.buildMensaje(cart, descuento, pedidoConfig))
            ModoResultado.PNG -> onCommitImage(PrecioPngRenderer.renderPedido(cart, descuento, pedidoConfig))
        }
        cart = emptyList()
        editingCartIndex = null
        pendingDescuento = 0
        estado = "Pedido enviado — apretá Enviar en la app"
    }

    // ── Switches de formato (pedidoConfig) ──

    fun setLectura(l: Lectura) {
        pedidoConfig = pedidoConfig.copy(lectura = l)
        persistirPedidoConfig()
    }

    fun setConcepto(c: Concepto) {
        // U → N: todos los ítems vuelven a cantidad 1 (como la extensión).
        if (pedidoConfig.concepto == Concepto.U && c == Concepto.N) {
            cart = cart.map { it.copy(qty = 1) }
        }
        pedidoConfig = pedidoConfig.copy(concepto = c)
        persistirPedidoConfig()
    }

    fun setTextoDesc(on: Boolean) {
        pedidoConfig = pedidoConfig.copy(textoDesc = on)
        persistirPedidoConfig()
    }

    fun setValorModo(v: ValorModo) {
        pedidoConfig = pedidoConfig.copy(valorModo = v)
        persistirPedidoConfig()
    }

    /**
     * Fija la lista global del pedido: -1 = Todas (elegir por ítem al expandir),
     * 0..3 = Lista 1..4. Migra TODO el pedido a la lista elegida (cada ítem ya
     * agregado se reasigna); con -1 ("Todas") el carrito queda como está. Además
     * cierra cualquier producto expandido para que no quede el "▾" sin sus chips.
     */
    fun fijarLista(i: Int) {
        defaultListaIndex = i.coerceIn(-1, Mensaje.LISTA_NAMES.size - 1)
        if (defaultListaIndex >= 0) {
            // Clamp por producto: los productos con menos precios no crashean
            // `preciosList()[listaIndex]` en totales/mensaje/render (spec).
            cart = cart.map { it.copy(listaIndex = defaultListaIndex.coerceIn(-1, it.product.preciosList().lastIndex)) }
        }
        expandedCodigo = null
        persistirPedidoConfig()
    }

    // ── Descuento TODOS / MANUAL ──

    fun cambiarDescuentoModo(m: DescuentoModo) {
        // TODOS → MANUAL: cada ítem hereda el descuento global (como la extensión).
        if (m == DescuentoModo.MANUAL && descuentoModo == DescuentoModo.TODOS) {
            cart = cart.map { it.copy(descuento = descuento) }
        }
        // MANUAL → TODOS: se limpia el override por ítem para que el global
        // vuelva a mandar (sin esto, descuentoEfectivo seguiría usando el
        // valor individual "pegado").
        if (m == DescuentoModo.TODOS && descuentoModo == DescuentoModo.MANUAL) {
            cart = cart.map { it.copy(descuento = null) }
        }
        descuentoModo = m
        editingCartIndex = null
        pendingDescuento = 0
        persistirPedidoConfig()
    }

    /** Empieza a editar el descuento individual de un ítem (modo MANUAL). */
    fun editarItem(index: Int) {
        editingCartIndex = index
        pendingDescuento = cart.getOrNull(index)?.descuento ?: descuento
    }

    fun seleccionarDescuentoPendiente(v: Int) {
        pendingDescuento = v
    }

    /** Aplica el descuento pendiente al ítem en edición ("Recalcular"). */
    fun aplicarItemDescuento() {
        val i = editingCartIndex ?: return
        cart = cart.mapIndexed { idx, item -> if (idx == i) item.copy(descuento = pendingDescuento) else item }
        editingCartIndex = null
        pendingDescuento = 0
    }

    /** Ajusta la cantidad de un ítem (concepto U). Mínimo 1. */
    fun cambiarCantidad(index: Int, delta: Int) {
        cart = cart.mapIndexed { idx, item ->
            if (idx == index) item.copy(qty = (item.qty + delta).coerceAtLeast(1)) else item
        }
    }

    private fun persistirPedidoConfig() {
        appContext?.getSharedPreferences(LolaPrefs.NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(LolaPrefs.KEY_LECTURA, pedidoConfig.lectura.name)
            ?.putString(LolaPrefs.KEY_CONCEPTO, pedidoConfig.concepto.name)
            ?.putBoolean(LolaPrefs.KEY_TEXTO_DESC, pedidoConfig.textoDesc)
            ?.putString(LolaPrefs.KEY_VALOR_MODO, pedidoConfig.valorModo.name)
            ?.putString(LolaPrefs.KEY_DESCUENTO_MODO, descuentoModo.name)
            ?.putInt(LolaPrefs.KEY_LISTA_DEFAULT, defaultListaIndex)
            ?.apply()
    }

    /**
     * Escribe texto ya resuelto por el motor. En modo LOLA alimenta la búsqueda
     * del catálogo; en modo teclado normal (CHAT) tipea en la app anfitriona
     * con composición de palabra (para poder sugerir y reemplazar).
     */
    fun tecla(texto: String) {
        if (enModoLola) {
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
        if (enModoLola) {
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
        suggestions = if (!enModoLola && suggestionsEnabled && profile.suggestionsAllowed) {
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
        appContext?.getSharedPreferences(LolaPrefs.NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(LolaPrefs.KEY_EMOJI_RECENTS, emojiEngine.recentsList().joinToString(LolaPrefs.RECENTS_DELIMITER))
            ?.apply()
    }

    // ── Teclado base (delegado al motor) ──

    /** Layout de la capa actual, ya resuelto por el motor. */
    fun teclasActuales(): List<List<Key>> = keyEngine.layout()

    /** Fila inferior compartida (123 · coma · emoji · espacio · punto · enter). */
    fun filaInferior(): List<Key> = keyEngine.bottomRow()

    fun etiqueta(k: Key): String = keyEngine.label(k, profile)

    fun peso(k: Key): Float = keyEngine.weight(k)

    /** Resalta la tecla (shift activo, capa emoji, modo LOLA). */
    fun esActiva(k: Key): Boolean = keyEngine.isActive(k) || (k.type == KeyType.LOLA && enModoLola)

    fun alternativas(k: Key): List<String> = keyEngine.alternatives(k)

    /** Descripción legible de la tecla para accesibilidad (TalkBack). */
    fun descripcion(k: Key): String = keyEngine.contentDescription(k, profile)

    /** Estado a anunciar, incluyendo el modo LOLA (que no conoce el motor). */
    fun descripcionEstado(k: Key): String? = keyEngine.stateDescription(k)
        ?: if (k.type == KeyType.LOLA && enModoLola) "Modo LOLA activado" else null

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
            KeyEffect.ToggleLola -> toggleModo()
            null -> Unit
        }
    }

    /**
     * Cambia de modo CHAT ↔ LOLA. Al entrar en LOLA se desactivan las
     * predicciones (la cinta queda estable); al volver a CHAT se restauran
     * automáticamente. No reconstruye el teclado: solo cambia estado observable.
     */
    fun toggleModo() {
        mode = if (mode == KeyboardMode.CHAT) KeyboardMode.LOLA else KeyboardMode.CHAT
        if (!enModoLola) size = KeyboardSize.COMPACT
        // Finaliza cualquier composición activa y resincroniza el editor: al
        // entrar/salir de LOLA el texto queda completamente editable (spec §16).
        onSyncEditor()
        refreshSuggestions()
        onCambiarAltura?.invoke()
    }

    /**
     * Alterna el tamaño del área en modo LOLA (COMPACT ↔ EXPANDED), recalculado
     * desde [alturaDp] (adaptativo al viewport real, sin offsets fijos).
     */
    fun toggleExpandir() {
        if (!enModoLola) return
        size = if (size == KeyboardSize.COMPACT) KeyboardSize.EXPANDED else KeyboardSize.COMPACT
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

    /** Abre la pantalla de ajustes (BASE_URL, secret, modo de imágenes). */
    fun abrirConfig(context: Context) {
        val intent = Intent(context, ConfigActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            estado = "No se pudo abrir los ajustes"
        }
    }

    /** Relee la carpeta persistida y escanea (idempotente por URI). */
    fun rescanear(context: Context) {
        val prefs = LolaPrefs.get(context)
        imagenesEnabled = prefs.getBoolean(LolaPrefs.KEY_IMAGENES_ENABLED, true)
        // Mini portapapeles: se aplica en vivo (sin recrear el servicio). El
        // toggle lo cambia en Ajustes y acá se re-lee en cada onCreateInputView.
        clipboardEnabled = prefs.getBoolean(LolaPrefs.KEY_CLIPBOARD_ENABLED, true)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboardEnabled) {
            clipboardItems = ClipboardRepository.load(prefs)
            cm?.removePrimaryClipChangedListener(clipListener)
            cm?.addPrimaryClipChangedListener(clipListener)
        } else {
            cm?.removePrimaryClipChangedListener(clipListener)
            clipboardItems = emptyList()
            // Purga el historial persistido de 24h: deshabilitado = sin datos.
            ClipboardRepository.save(prefs, emptyList())
        }
        val uriStr = prefs.getString(LolaPrefs.KEY_FOLDER_URI, null) ?: return
        if (uriStr == lastScannedUri) return
        lastScannedUri = uriStr
        scope.launch {
            val uri = Uri.parse(uriStr)
            imagenes = withContext(Dispatchers.IO) { ImageRepository.scan(context, uri) }
            imagenCache.clear()
            estado = "${imagenes.size} imágenes listas"
        }
    }

    /** Quita el listener del portapapeles: evita listeners duplicados/leaks
     *  cuando el IME se destruye y recrea mientras el proceso sigue vivo. */
    fun cerrarPortapapeles() {
        (appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.removePrimaryClipChangedListener(clipListener)
    }

    fun imagenDe(product: Product): ImageEntry? {
        if (imagenes.isEmpty() || !imagenesEnabled) return null
        // Memoizado por codigoUnico: el match (O(índice)) corre UNA vez por
        // producto, no en cada recomposición de la fila.
        return imagenCache.getOrPut(product.codigoUnico) {
            ImageRepository.buscar(product, imagenes).firstOrNull()
        }
    }

    fun insertarImagen(product: Product) {
        val entry = imagenDe(product) ?: run {
            estado = "Sin imagen para este producto"
            return
        }
        onCommitImageFile(entry.uri)
    }

    // ── Mini portapapeles ──────────────────────────────────────────────────

    fun toggleClipboard() = keyEngine.toggleClipboard()

    fun pasteClip(text: String) {
        onCommitText(text)
    }

    fun removeClip(text: String) {
        val prefs = appContext?.let { LolaPrefs.get(it) } ?: return
        clipboardItems = ClipboardRepository.remove(clipboardItems, text)
            .also { ClipboardRepository.save(prefs, it) }
    }

    fun clearClipboard() {
        val prefs = appContext?.let { LolaPrefs.get(it) } ?: return
        clipboardItems = emptyList()
        ClipboardRepository.save(prefs, emptyList())
    }

    companion object {
        /** Pisos de la ventana del IME en dp (se escalan con la pantalla). */
        const val CHAT_HEIGHT_DP = 284
        const val LOLA_COMPACT_HEIGHT_DP = 480

        /** Fracción del alto de pantalla: el teclado crece en pantallas grandes
         *  (tablets) en vez de quedar clavado en un dp fijo. El piso es el dp de
         *  cada modo; `ImeRootView` clampa al área real (nunca desborda). */
        const val CHAT_SCREEN_FRACTION = 0.45f
        const val LOLA_SCREEN_FRACTION = 0.55f

        /**
         * Sentinel: en LOLA EXPANDIDO el IME pide TODO el alto disponible; el
         * `ImeRootView.onMeasure` lo clampa al viewport real (sin porcentajes
         * fijos ni offsets mágicos, spec §14).
         */
        const val EXPANDED_FILL_DP = -1

        /** Lee un enum de prefs con fallback (evita crash por valor corrupto). */
        private fun <T : Enum<T>> enumOf(entries: List<T>, name: String?, default: T): T =
            entries.firstOrNull { it.name == name } ?: default
    }
}

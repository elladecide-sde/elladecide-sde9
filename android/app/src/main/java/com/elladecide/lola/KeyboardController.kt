package com.elladecide.lola

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elladecide.lola.data.ImageEntry
import com.elladecide.lola.data.ImageRepository
import com.elladecide.lola.data.LolaDatabase
import com.elladecide.lola.data.Product
import com.elladecide.lola.data.SeedCatalog
import com.elladecide.lola.data.SyncRepository
import com.elladecide.lola.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModoResultado { TEXTO, PNG }

enum class CapaTeclado { LETRAS, SIMBOLOS, EMOJI }

enum class ShiftEstado { MINUSCULAS, MAYUSCULA, BLOQUEO }

/**
 * Estado del teclado (Compose state holder) + orquestación de catálogo/sync,
 * del pedido (carrito) y de la carpeta de imágenes.
 */
class KeyboardController(
    private val scope: CoroutineScope,
    private val onCommitText: (String) -> Unit,
    private val onCommitImage: (Bitmap) -> Unit,
    private val onCommitImageFile: (Uri) -> Unit,
    private val onDelete: () -> Unit,
    private val onEnter: () -> Unit,
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
    var shiftEstado by mutableStateOf(ShiftEstado.MINUSCULAS)
    var capa by mutableStateOf(CapaTeclado.LETRAS)

    /** True si las letras se muestran en mayúscula (shift temporal o bloqueado). */
    val shiftActivo: Boolean get() = shiftEstado != ShiftEstado.MINUSCULAS

    /** Callback que redimensiona la ventana del IME cuando cambia [expandida]. */
    var onCambiarAltura: (() -> Unit)? = null

    private var productos: List<Product> = emptyList()
    private var lastScannedUri: String? = null

    val totalEfectivo: Int get() = Precio.totales(cart, descuento).second

    /** Carga inicial: seed offline si está vacío + intento de sync best-effort. */
    fun cargar(db: LolaDatabase, appContext: Context) {
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
     * Escribe una tecla. En modo LOLA (expandida) alimenta la búsqueda; en modo
     * teclado normal (contraída) tipea en la app anfitriona.
     *
     * Capitaliza según el estado de shift y consume el shift temporal (una sola
     * letra en mayúscula) después de la primera letra.
     */
    fun tecla(texto: String) {
        val efectivo = if (shiftActivo && capa == CapaTeclado.LETRAS) texto.uppercase() else texto
        if (expandida) {
            onQueryChanged(query + efectivo)
        } else {
            onCommitText(efectivo)
        }
        if (shiftEstado == ShiftEstado.MAYUSCULA) {
            shiftEstado = ShiftEstado.MINUSCULAS
        }
    }

    /** Borra un carácter: de la búsqueda si estamos en modo LOLA, o de la app. */
    fun borrar() {
        if (expandida) {
            onQueryChanged(query.dropLast(1))
        } else {
            onDelete()
        }
    }

    /** Tecla Enter: siempre se envía a la app anfitriona (nueva línea). */
    fun enter() {
        onEnter()
    }

    fun toggleShift() {
        shiftEstado = when (shiftEstado) {
            ShiftEstado.MINUSCULAS -> ShiftEstado.MAYUSCULA
            ShiftEstado.MAYUSCULA -> ShiftEstado.MINUSCULAS
            ShiftEstado.BLOQUEO -> ShiftEstado.MINUSCULAS
        }
    }

    /** Fija el bloqueo de mayúsculas (long-press o doble tap en Shift). */
    fun fijarShift() {
        shiftEstado = ShiftEstado.BLOQUEO
    }

    fun toggleSimbolos() {
        capa = if (capa == CapaTeclado.SIMBOLOS) CapaTeclado.LETRAS else CapaTeclado.SIMBOLOS
    }

    fun toggleEmojis() {
        capa = if (capa == CapaTeclado.EMOJI) CapaTeclado.LETRAS else CapaTeclado.EMOJI
    }

    /** Inserta un emoji siempre en la app anfitriona (no alimenta la búsqueda). */
    fun emoji(e: String) {
        onCommitText(e)
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
}

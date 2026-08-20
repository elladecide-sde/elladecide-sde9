package com.elladecide.lola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elladecide.lola.CartItem
import com.elladecide.lola.KeyboardController
import com.elladecide.lola.Mensaje
import com.elladecide.lola.ModoResultado
import com.elladecide.lola.Precio
import com.elladecide.lola.data.ImageEntry
import com.elladecide.lola.data.Product
import com.elladecide.lola.keyboard.AutoRepeat
import com.elladecide.lola.keyboard.KeyboardMetrics
import com.elladecide.lola.keyboard.KeyType
import com.elladecide.lola.keyboard.KeyboardLayer
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.floor

// Paleta oscura de LOLA (coincide con la extensión: #1f2c34 / #00d4a8).
private val LolaBg = Color(0xFF1F2C34)
private val LolaSurface = Color(0xFF2A3942)
private val LolaAccent = Color(0xFF00D4A8)
private val LolaText = Color(0xFFE9EDEF)
private val LolaMuted = Color(0xFF8696A0)

private val LolaColors = darkColorScheme(
    primary = LolaAccent,
    background = LolaBg,
    surface = LolaSurface,
    onPrimary = Color.Black,
    onBackground = LolaText,
    onSurface = LolaText,
)

// ── Tiempos de gestos (no son geometría) ────────────────────────────────────

private val LONG_PRESS_MS = 400L
private val DOBLE_TAP_MS = 300L

// ── Pantalla principal ──────────────────────────────────────────────────────

@Composable
fun KeyboardScreen(controller: KeyboardController) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var popup by remember { mutableStateOf<PopupInfo?>(null) }
    MaterialTheme(colorScheme = LolaColors) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().fillMaxHeight().background(LolaBg),
        ) {
            // Las métricas se recalculan solo si cambia el alto real de la
            // ventana o el font scale (no en cada recomposición).
            val availableHeight = maxHeight.value
            val metrics = remember(availableHeight, density.fontScale) {
                KeyboardMetrics.compute(availableHeight, density.fontScale)
            }
            ContenidoTeclado(
                controller = controller,
                context = context,
                metrics = metrics,
                onPopupMostrar = { popup = it },
                onPopupSeleccion = { popup = popup?.copy(seleccion = it) },
                onPopupCerrar = { seleccion ->
                    val info = popup
                    if (info != null && seleccion >= 0) {
                        controller.comprometerAlternativa(info.alternativas[seleccion])
                    }
                    popup = null
                },
            )
            popup?.let { PopupAlternativas(it, metrics) }
        }
    }
}

@Composable
private fun ContenidoTeclado(
    controller: KeyboardController,
    context: android.content.Context,
    metrics: KeyboardMetrics,
    onPopupMostrar: (PopupInfo) -> Unit,
    onPopupSeleccion: (Int) -> Unit,
    onPopupCerrar: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(metrics.screenPaddingDp.dp),
    ) {
        if (controller.expandida) {
            Spacer(Modifier.height(metrics.spacingDp.dp))

            // Buscador (el QWERTY lo alimenta; no abre el IME propio).
            OutlinedTextField(
                value = controller.query,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar producto…", color = LolaMuted) },
                singleLine = true,
                readOnly = true,
            )

            Spacer(Modifier.height(metrics.spacingDp.dp))

            // Carpeta de imágenes · descuento · modo
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { controller.elegirCarpeta(context) }) {
                    Text(
                        text = if (controller.imagenes.isEmpty()) "📂" else "📂 ${controller.imagenes.size}",
                        color = LolaAccent,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Precio.DESCUENTOS.forEach { d ->
                        TextButton(onClick = { controller.descuento = d }) {
                            Text(
                                text = if (d == 0) "Sin" else "-$d%",
                                color = if (controller.descuento == d) LolaAccent else LolaMuted,
                            )
                        }
                    }
                }
                TextButton(onClick = controller::toggleModo) {
                    val label = if (controller.modo == ModoResultado.TEXTO) "TEXTO" else "PNG"
                    Text("PRECIOS → $label", color = LolaAccent)
                }
            }

            Spacer(Modifier.height(metrics.spacingDp.dp))

            // Área principal: resultados o carrito.
            if (controller.query.isNotBlank()) {
                Resultados(controller, Modifier.weight(1f))
            } else {
                Carrito(controller, Modifier.weight(1f))
            }

            Spacer(Modifier.height(metrics.spacingDp.dp + 2.dp))

            // Barra inferior: estado + total + enviar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(controller.estado, color = LolaMuted)
                    if (controller.cart.isNotEmpty()) {
                        Text(
                            "Total: ${Mensaje.fmt(controller.totalEfectivo)} EFEC/TRAN",
                            color = LolaAccent,
                        )
                    }
                }
                TextButton(
                    onClick = controller::limpiarCart,
                    enabled = controller.cart.isNotEmpty(),
                ) { Text("Limpiar", color = LolaMuted) }
                Button(
                    onClick = controller::enviar,
                    enabled = controller.cart.isNotEmpty(),
                ) { Text("ENVIAR") }
            }
        }

        if (controller.capa == KeyboardLayer.EMOJI) {
            EmojiGrid(controller, Modifier.weight(1f))
        } else {
            if (!controller.expandida) {
                Spacer(Modifier.weight(1f))
            }
            FilasTeclado(
                controller = controller,
                metrics = metrics,
                onPopupMostrar = onPopupMostrar,
                onPopupSeleccion = onPopupSeleccion,
                onPopupCerrar = onPopupCerrar,
            )
        }

        Spacer(Modifier.height(metrics.spacingDp.dp))

        FilaInferior(controller, metrics)
    }
}

// ── Teclado QWERTY (render a partir del KeyEngine) ─────────────────────────

private val EMOJIS: List<String> = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😜", "🤪", "🤩",
    "🥳", "😎", "🤓", "🥺", "😢", "😭", "😡", "🤯", "😱", "😴",
    "🤗", "🤔", "🤷", "🙃", "🥰", "😇", "🤫", "🤤", "😅", "😉",
    "👍", "👎", "👏", "🙏", "🤝", "💪", "👌", "✌️", "🤞", "👊",
    "❤️", "💔", "🔥", "⭐", "✨", "🎉", "🎁", "💰", "💵", "💸",
    "🛒", "📦", "🚚", "🏷️", "✅", "❌", "⚠️", "⏰", "📅", "📍",
    "📞", "💬", "🙂", "🙈",
)

@Composable
private fun FilasTeclado(
    controller: KeyboardController,
    metrics: KeyboardMetrics,
    onPopupMostrar: (PopupInfo) -> Unit,
    onPopupSeleccion: (Int) -> Unit,
    onPopupCerrar: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(metrics.spacingDp.dp)) {
        controller.teclasActuales().forEach { fila ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.spacingDp.dp),
            ) {
                fila.forEach { tecla ->
                    Tecla(
                        label = controller.etiqueta(tecla),
                        descripcion = controller.descripcion(tecla),
                        descripcionEstado = controller.descripcionEstado(tecla),
                        modifier = Modifier.weight(controller.peso(tecla)),
                        acento = tecla.type != KeyType.CHAR,
                        activo = controller.esActiva(tecla),
                        alternativas = controller.alternativas(tecla),
                        repetir = if (tecla.type == KeyType.BACKSPACE) ({ controller.borrar() }) else null,
                        onLongPress = if (tecla.type == KeyType.SHIFT) ({ controller.fijarShift() }) else null,
                        onDoubleTap = if (tecla.type == KeyType.SHIFT) ({ controller.fijarShift() }) else null,
                        onTap = { controller.press(tecla) },
                        metrics = metrics,
                        onPopupMostrar = onPopupMostrar,
                        onPopupSeleccion = onPopupSeleccion,
                        onPopupCerrar = onPopupCerrar,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilaInferior(controller: KeyboardController, metrics: KeyboardMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(metrics.spacingDp.dp),
    ) {
        controller.filaInferior().forEach { tecla ->
            Tecla(
                label = controller.etiqueta(tecla),
                descripcion = controller.descripcion(tecla),
                descripcionEstado = controller.descripcionEstado(tecla),
                modifier = Modifier.weight(controller.peso(tecla)),
                acento = true,
                activo = controller.esActiva(tecla),
                repetir = if (tecla.type == KeyType.SPACE) ({ controller.tecla(" ") }) else null,
                onTap = { controller.press(tecla) },
                metrics = metrics,
            )
        }
    }
}

@Composable
private fun EmojiGrid(controller: KeyboardController, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(EMOJIS) { e ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { controller.emoji(e) }
                    .semantics { contentDescription = "Emoji $e" }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(e, fontSize = 22.sp)
            }
        }
    }
}

// ── Popup de alternativas (long-press) ─────────────────────────────────────

private data class PopupInfo(
    val alternativas: List<String>,
    val centerX: Int,
    val topY: Int,
    val seleccion: Int = -1,
)

@Composable
private fun PopupAlternativas(info: PopupInfo, metrics: KeyboardMetrics) {
    val density = LocalDensity.current
    val celdaPx = with(density) { metrics.popupCellDp.dp.toPx() }
    val altoPx = with(density) { metrics.popupHeightDp.dp.toPx() }
    val teclaAltoPx = with(density) { metrics.keyHeightDp.dp.toPx() }
    val left = (info.centerX - (celdaPx * info.alternativas.size) / 2).toInt()
    val top = (if (info.topY - altoPx >= 0) info.topY - altoPx else info.topY + teclaAltoPx).toInt()
    Box(
        modifier = Modifier
            .offset { IntOffset(left, top) }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF3D4F59)),
    ) {
        Row {
            info.alternativas.forEachIndexed { i, alt ->
                val seleccionada = i == info.seleccion
                Box(
                    modifier = Modifier
                        .width(metrics.popupCellDp.dp)
                        .height(metrics.popupHeightDp.dp)
                        .background(if (seleccionada) LolaAccent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        alt,
                        color = if (seleccionada) Color.Black else LolaText,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Tecla con gestos (tap / doble tap / long-press / autorepeat) ───────────

/** True cuando el puntero se soltó o fue cancelado (dejó de estar presionado). */
private fun PointerInputChange.terminado(): Boolean = changedToUp() || !pressed

@Composable
private fun Tecla(
    label: String,
    modifier: Modifier = Modifier,
    acento: Boolean = false,
    activo: Boolean = false,
    alternativas: List<String> = emptyList(),
    repetir: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onTap: () -> Unit,
    descripcion: String? = null,
    descripcionEstado: String? = null,
    metrics: KeyboardMetrics,
    onPopupMostrar: (PopupInfo) -> Unit = {},
    onPopupSeleccion: (Int) -> Unit = {},
    onPopupCerrar: (Int) -> Unit = {},
) {
    val density = LocalDensity.current
    var posPx by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var lastDown by remember { mutableStateOf(0L) }
    var pressed by remember { mutableStateOf(false) }

    val altActuales by rememberUpdatedState(alternativas)
    val repetirActual by rememberUpdatedState(repetir)
    val longPressActual by rememberUpdatedState(onLongPress)
    val dobleTapActual by rememberUpdatedState(onDoubleTap)
    val tapActual by rememberUpdatedState(onTap)
    val mostrarActual by rememberUpdatedState(onPopupMostrar)
    val seleccionActual by rememberUpdatedState(onPopupSeleccion)
    val cerrarActual by rememberUpdatedState(onPopupCerrar)

    val bg = when {
        pressed -> LolaAccent
        activo -> Color(0xFF3D4F59)
        else -> LolaSurface
    }
    val fg = when {
        pressed -> Color.Black
        acento -> LolaAccent
        else -> LolaText
    }

    Box(
        modifier = modifier
            .height(metrics.keyHeightDp.dp)
            .onGloballyPositioned {
                posPx = it.positionInRoot()
                sizePx = it.size
            }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .semantics {
                descripcion?.let { contentDescription = it }
                descripcionEstado?.let { stateDescription = it }
                role = Role.Button
                // Activable desde TalkBack (double-tap) sin interferir con los
                // gestos de puntero reales (long-press/doble-tap/autorepeat).
                onClick(label = descripcion) { onTap(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    try {
                        val downTime = down.uptimeMillis
                        val repetirFn = repetirActual
                        val dobleTapFn = dobleTapActual
                        val longPressFn = longPressActual
                        val tapFn = tapActual
                        val mostrarFn = mostrarActual
                        val seleccionFn = seleccionActual
                        val cerrarFn = cerrarActual
                        val altList = altActuales

                        // Autorepeat: acción inmediata + repetición acelerada.
                        if (repetirFn != null) {
                            repetirFn()
                            var espera = AutoRepeat.INITIAL_DELAY_MS
                            while (true) {
                                val solto = withTimeoutOrNull(espera) {
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        if (e.changes.firstOrNull { it.id == down.id }?.terminado() == true) {
                                            return@withTimeoutOrNull true
                                        }
                                    }
                                }
                                if (solto == true) break
                                repetirFn()
                                espera = AutoRepeat.nextDelay(espera)
                            }
                            return@awaitEachGesture
                        }

                        // Esperar up (tap) o long-press.
                        val tap = withTimeoutOrNull(LONG_PRESS_MS) {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.firstOrNull { it.id == down.id }?.changedToUp() == true) {
                                    return@withTimeoutOrNull true
                                }
                            }
                        }

                        if (tap == true) {
                            if (dobleTapFn != null && downTime - lastDown < DOBLE_TAP_MS) {
                                dobleTapFn()
                            } else {
                                tapFn()
                            }
                            lastDown = downTime
                        } else {
                            // Long-press.
                            when {
                                altList.isNotEmpty() -> {
                                    val celdaPx = with(density) { metrics.popupCellDp.dp.toPx() }
                                    val centerX = posPx.x + sizePx.width / 2
                                    val topY = posPx.y
                                    val left = centerX - (altList.size * celdaPx) / 2
                                    var seleccion = -1
                                    mostrarFn(PopupInfo(altList, centerX.toInt(), topY.toInt()))
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val change = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.changedToUp()) break
                                        val dedoX = posPx.x + change.position.x
                                        val idx = floor((dedoX - left) / celdaPx).toInt()
                                        seleccion = idx.coerceIn(0, altList.size - 1)
                                        seleccionFn(seleccion)
                                    }
                                    cerrarFn(seleccion)
                                    if (seleccion == -1) tapFn()
                                }
                                longPressFn != null -> {
                                    longPressFn()
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        if (e.changes.firstOrNull { it.id == down.id }?.changedToUp() == true) break
                                    }
                                }
                            }
                        }
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = metrics.labelSizeSp.sp)
    }
}

// ── Resultados / Carrito (modo LOLA expandido) ──────────────────────────────

@Composable
private fun Resultados(controller: KeyboardController, modifier: Modifier = Modifier) {
    if (controller.results.isEmpty()) {
        Text(
            text = "Sin resultados para \"${controller.query}\"",
            color = LolaMuted,
            modifier = Modifier.padding(12.dp),
        )
        return
    }
    LazyColumn(modifier = modifier) {
        items(controller.results) { p ->
            ProductRow(
                product = p,
                expanded = controller.expandedCodigo == p.codigoUnico,
                imageEntry = controller.imagenDe(p),
                onToggle = { controller.toggleExpanded(p.codigoUnico) },
                onInsertImage = { controller.insertarImagen(p) },
                onLista = { controller.addToCart(p, it) },
            )
        }
    }
}

@Composable
private fun ProductRow(
    product: Product,
    expanded: Boolean,
    imageEntry: ImageEntry?,
    onToggle: () -> Unit,
    onInsertImage: () -> Unit,
    onLista: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(LolaSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (imageEntry != null) {
                AsyncImage(
                    model = imageEntry.uri,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(product.nombre, color = LolaText)
                Text(
                    product.preciosList().joinToString(" · ") { Mensaje.fmt(it) },
                    color = LolaAccent,
                )
            }
            if (imageEntry != null) {
                TextButton(onClick = onInsertImage) { Text("📷", color = LolaAccent) }
            }
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "▾" else "＋", color = LolaAccent)
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Mensaje.LISTA_NAMES.forEachIndexed { i, name ->
                    ListaChip(name, onClick = { onLista(i) })
                }
                ListaChip("Todas", onClick = { onLista(-1) })
            }
        }
    }
}

@Composable
private fun ListaChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = LolaAccent) }
}

@Composable
private fun Carrito(controller: KeyboardController, modifier: Modifier = Modifier) {
    if (controller.cart.isEmpty()) {
        Text(
            text = "Escribí para buscar y tocá un producto para agregarlo",
            color = LolaMuted,
            modifier = Modifier.padding(12.dp),
        )
        return
    }
    LazyColumn(modifier = modifier) {
        itemsIndexed(controller.cart) { i, item ->
            CartRow(item) { controller.removeFromCart(i) }
        }
    }
}

@Composable
private fun CartRow(item: CartItem, onRemove: () -> Unit) {
    val lista = if (item.listaIndex == -1) "Todas las listas" else "Lista ${item.listaIndex + 1}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LolaSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.product.nombre, color = LolaText)
            Text(lista, color = LolaMuted)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRemove) { Text("✕", color = LolaMuted) }
    }
}

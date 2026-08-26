package com.elladecide.lola.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.elladecide.lola.Concepto
import com.elladecide.lola.DescuentoModo
import com.elladecide.lola.KeyboardController
import com.elladecide.lola.Lectura
import com.elladecide.lola.Mensaje
import com.elladecide.lola.ModoResultado
import com.elladecide.lola.Precio
import com.elladecide.lola.ValorModo
import com.elladecide.lola.data.ImageEntry
import com.elladecide.lola.data.Product
import com.elladecide.lola.emoji.Emoji
import com.elladecide.lola.emoji.EmojiCategory
import com.elladecide.lola.emoji.EmojiData
import com.elladecide.lola.keyboard.AutoRepeat
import com.elladecide.lola.keyboard.Key
import com.elladecide.lola.keyboard.KeyboardLayer
import com.elladecide.lola.keyboard.KeyboardMetrics
import com.elladecide.lola.keyboard.KeyType
import com.elladecide.lola.keyboard.PopupController
import com.elladecide.lola.keyboard.PopupInfo
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
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

// ── Gestos de deslizamiento (swipe-delete y cursor glide) ───────────────────

private val SWIPE_THRESHOLD_DP = 40f
private val GLIDE_SLOP_DP = 12f
private val GLIDE_STEP_DP = 32f
private val TOUCH_SLOP_DP = 16f

// Resultados del bucle de autorepeat (withTimeoutOrNull): soltar vs deslizar.
private const val SOLTO = 1
private const val SWIPE_LEFT = 2

// ── Pantalla principal ──────────────────────────────────────────────────────

@Composable
fun KeyboardScreen(controller: KeyboardController) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // El popup vive en el controller (nivel IME) para que el servicio lo cierre
    // con el Back físico/software (spec §8B); la UI solo lo observa.
    val popupController = controller.popupController
    MaterialTheme(colorScheme = LolaColors) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().fillMaxHeight().background(LolaBg),
        ) {
            // Propiedades del BoxWithConstraintsScope (única fuente del tamaño
            // real del viewport del IME). Se leen directamente acá para que la
            // inspección de Compose vea el uso del scope (las lecturas dentro de
            // `with(density) { }` no se rastrean) y la resolución quede explícita.
            val availableHeight = maxHeight.value
            val viewportMaxWidth = maxWidth
            val viewportMaxHeight = maxHeight
            // FUENTE DE VERDAD ÚNICA: `availableForKeyboard` es el presupuesto
            // que el teclado reclama y sobre el que `compute()` dimensiona las
            // teclas; el panel de catálogo recibe `panelHeight` explícito (sin
            // weight), así NO compite por espacio con el teclado (spec §17).
            //
            // En modo LOLA el teclado se queda en su mínimo cómodo
            // (MIN_KEY_BODY_DP): solo escribe la búsqueda; el protagonista es el
            // catálogo, que toma todo el resto. Si el viewport es chico, el panel
            // baja hasta PANEL_MIN_DP (controles esenciales siempre accesibles,
            // spec §15) y el teclado encoge sus teclas vía `compute()`. En CHAT
            // no hay panel: el teclado usa todo el alto.
            val panelHeight = if (controller.enModoLola) {
                (availableHeight - KeyboardMetrics.MIN_KEY_BODY_DP)
                    .coerceAtLeast(KeyboardMetrics.PANEL_MIN_DP)
            } else {
                0f
            }
            val availableForKeyboard = (availableHeight - panelHeight).coerceAtLeast(0f)
            val metrics = remember(availableForKeyboard, density.fontScale) {
                KeyboardMetrics.compute(availableForKeyboard, density.fontScale)
            }
            val viewportW = with(density) { viewportMaxWidth.toPx() }
            val viewportH = with(density) { viewportMaxHeight.toPx() }

            ContenidoTeclado(
                controller = controller,
                context = context,
                metrics = metrics,
                viewportW = viewportW,
                viewportH = viewportH,
                popupController = popupController,
                panelHeight = panelHeight,
            )
            popupController.info?.let { PopupAlternativas(it) }
        }
    }
}

@Composable
private fun ContenidoTeclado(
    controller: KeyboardController,
    context: android.content.Context,
    metrics: KeyboardMetrics,
    viewportW: Float,
    viewportH: Float,
    popupController: PopupController,
    panelHeight: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(metrics.screenPaddingDp.dp),
    ) {
        // Cinta superior con geometría estable: en CHAT muestra herramientas o
        // sugerencias (una u otra, sin desplazar el teclado); en LOLA la cinta
        // propia (Modo Chat + Expandir).
        if (controller.enModoLola) {
            ToolbarLola(controller)
        } else if (controller.sugerenciasVisibles) {
            BarraSugerencias(controller)
        } else {
            ToolbarChat(controller)
        }

        // Modo LOLA: el catálogo ocupa un alto explícito (panelHeight, derivado
        // del presupuesto del teclado en KeyboardScreen). SIN weight: así no
        // compite por espacio con el teclado y las métricas coinciden con el
        // layout real (spec §17: una sola fuente de verdad).
        if (controller.enModoLola) {
            PanelLola(
                controller,
                context,
                Modifier
                    .height(panelHeight.dp)
                    .padding(vertical = metrics.spacingDp.dp),
            )
        }

        if (controller.capa == KeyboardLayer.EMOJI) {
            EmojiPanel(
                controller = controller,
                metrics = metrics,
                viewportW = viewportW,
                viewportH = viewportH,
                popupController = popupController,
                modifier = Modifier.weight(1f),
            )
        } else {
            FilasTeclado(
                controller = controller,
                metrics = metrics,
                viewportW = viewportW,
                viewportH = viewportH,
                popupController = popupController,
            )
        }

        Spacer(Modifier.height(metrics.spacingDp.dp))

        FilaInferior(controller, metrics)
    }
}

// ── Cinta superior ──────────────────────────────────────────────────────────

/** Herramientas del modo CHAT (solo herramientas, nunca controles de escritura):
 *  acceso al teclado de cursor (acrónimo) y al modo LOLA. 123 y 😀 viven en la
 *  fila inferior (spec §7-§10). */
@Composable
private fun ToolbarChat(controller: KeyboardController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardMetrics.STRIP_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton("◀▶", "Teclado de cursor", controller.capa == KeyboardLayer.CURSOR) {
            controller.press(Key.CURSOR)
        }
        Spacer(Modifier.weight(1f))
        ToolbarButton("LOLA", "Modo LOLA", controller.enModoLola) {
            controller.press(Key.LOLA)
        }
    }
}

/** Cinta estable del modo LOLA: volver a Chat + expandir/contraer. */
@Composable
private fun ToolbarLola(controller: KeyboardController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardMetrics.STRIP_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton("Modo Chat", "Volver al teclado normal", false) { controller.toggleModo() }
        Spacer(Modifier.weight(1f))
        ToolbarButton(
            if (controller.expandida) "Contraer" else "Expandir",
            "Cambiar el tamaño del teclado LOLA",
            controller.expandida,
        ) { controller.toggleExpandir() }
    }
}

@Composable
private fun ToolbarButton(text: String, desc: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) LolaAccent else LolaSurface)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = desc
                role = Role.Button
                onClick(label = desc) { onClick(); true }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) Color.Black else LolaText,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

// ── Sugerencias (solo modo CHAT) ────────────────────────────────────────────

@Composable
private fun BarraSugerencias(controller: KeyboardController) {
    // Cada sugerencia mide su contenido (sin weight): si no entran todas, la
    // fila scrollea en vez de recortar las palabras (spec §11: sin clipping).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardMetrics.STRIP_HEIGHT_DP.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.suggestions.forEach { palabra ->
            Text(
                text = palabra,
                color = LolaText,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(LolaSurface)
                    .clickable { controller.sugerir(palabra) }
                    .semantics { contentDescription = "Sugerencia $palabra" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ── Teclado (cuerpo de capa) ────────────────────────────────────────────────

@Composable
private fun FilasTeclado(
    controller: KeyboardController,
    metrics: KeyboardMetrics,
    viewportW: Float,
    viewportH: Float,
    popupController: PopupController,
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
                        onSwipeLeft = if (tecla.type == KeyType.BACKSPACE) ({ controller.borrarPalabra() }) else null,
                        onTap = { controller.press(tecla) },
                        metrics = metrics,
                        viewportW = viewportW,
                        viewportH = viewportH,
                        popupController = popupController,
                        onComprometerAlternativa = { controller.comprometerAlternativa(it) },
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
                // Los controles de escritura (coma/espacio/punto) en color normal;
                // los accesos (123/emoji/enter) resaltados en acento.
                acento = tecla.type == KeyType.LAYER ||
                    tecla.type == KeyType.EMOJI ||
                    tecla.type == KeyType.ENTER,
                activo = controller.esActiva(tecla),
                onGlide = if (tecla.type == KeyType.SPACE) ({ controller.moverCursor(it) }) else null,
                onTap = { controller.press(tecla) },
                metrics = metrics,
            )
        }
    }
}

// ── Panel LOLA (catálogo) ───────────────────────────────────────────────────

@Composable
private fun PanelLola(
    controller: KeyboardController,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Buscador (el QWERTY lo alimenta; no abre el IME propio).
        OutlinedTextField(
            value = controller.query,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar producto…", color = LolaMuted) },
            singleLine = true,
            readOnly = true,
        )

        Spacer(Modifier.height(4.dp))

        // Acciones: carpeta · ajustes · modo de resultado.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { controller.elegirCarpeta(context) }) {
                    Text(
                        text = if (controller.imagenes.isEmpty()) "📂" else "📂 ${controller.imagenes.size}",
                        color = LolaAccent,
                    )
                }
                TextButton(onClick = { controller.abrirConfig(context) }) {
                    Text("⚙", color = LolaMuted)
                }
            }
            TextButton(onClick = controller::toggleModoResultado) {
                val label = if (controller.modo == ModoResultado.TEXTO) "TEXTO" else "PNG"
                Text("PRECIOS → $label", color = LolaAccent)
            }
        }

        // Switches de formato del mensaje (LC/LL · N/U · TextoDesc · Valor).
        FormatoSwitches(controller)

        // Descuento: TODOS (global) o MANUAL (por ítem).
        SeccionDescuento(controller)

        Spacer(Modifier.height(4.dp))

        // Área principal: resultados o carrito.
        if (controller.query.isNotBlank()) {
            Resultados(controller, Modifier.weight(1f))
        } else {
            Carrito(controller, Modifier.weight(1f))
        }

        Spacer(Modifier.height(4.dp))

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
}

/** Switches de formato del pedido (port de LOLA v3.2): LC/LL · N/U · Txt · Valor. */
@Composable
private fun FormatoSwitches(controller: KeyboardController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatoSwitch("LC", controller.pedidoConfig.lectura == Lectura.LC) { controller.setLectura(Lectura.LC) }
        FormatoSwitch("LL", controller.pedidoConfig.lectura == Lectura.LL) { controller.setLectura(Lectura.LL) }
        Spacer(Modifier.width(4.dp))
        FormatoSwitch("N", controller.pedidoConfig.concepto == Concepto.N) { controller.setConcepto(Concepto.N) }
        FormatoSwitch("U", controller.pedidoConfig.concepto == Concepto.U) { controller.setConcepto(Concepto.U) }
        Spacer(Modifier.width(4.dp))
        val textoDesc = controller.pedidoConfig.textoDesc
        FormatoSwitch("Txt ${if (textoDesc) "ON" else "OFF"}", textoDesc) { controller.setTextoDesc(!textoDesc) }
        Spacer(Modifier.width(4.dp))
        val valor = controller.pedidoConfig.valorModo
        FormatoSwitch("LISTA", valor == ValorModo.LISTA) { controller.setValorModo(ValorModo.LISTA) }
        FormatoSwitch("DESC.", valor == ValorModo.DESCUENTO) { controller.setValorModo(ValorModo.DESCUENTO) }
    }
}

@Composable
private fun FormatoSwitch(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) LolaAccent else LolaSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Color.Black else LolaText,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/** Sección de descuento: TODOS (global) / MANUAL (por ítem). */
@Composable
private fun SeccionDescuento(controller: KeyboardController) {
    val manual = controller.descuentoModo == DescuentoModo.MANUAL
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormatoSwitch("TODOS", !manual) { controller.cambiarDescuentoModo(DescuentoModo.TODOS) }
            Spacer(Modifier.width(4.dp))
            FormatoSwitch("MANUAL", manual) { controller.cambiarDescuentoModo(DescuentoModo.MANUAL) }
            Spacer(Modifier.width(8.dp))
            if (!manual) {
                OpcionesDescuento(
                    values = controller.descuento,
                    onSelect = { controller.descuento = it },
                )
            }
        }
        if (manual) {
            Spacer(Modifier.height(2.dp))
            val editing = controller.editingCartIndex
            if (editing == null) {
                Text(
                    "Elegí el descuento por ítem: tocá Seleccionar en el carrito",
                    color = LolaMuted,
                    fontSize = 11.sp,
                )
            } else {
                val nombre = controller.cart.getOrNull(editing)?.product?.nombre ?: ""
                Text(
                    "Descuento: ${nombre.take(18)}${if (nombre.length > 18) "…" else ""}",
                    color = LolaText,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                OpcionesDescuento(
                    values = controller.pendingDescuento,
                    onSelect = controller::seleccionarDescuentoPendiente,
                )
            }
        }
    }
}

private val OPCIONES_DESCUENTO = listOf(0 to "Sin", 10 to "-10%", 15 to "-15%", 20 to "-20%", -15 to "+15%")

@Composable
private fun OpcionesDescuento(
    values: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OPCIONES_DESCUENTO.forEach { (v, label) ->
            FormatoSwitch(label, values == v) { onSelect(v) }
        }
    }
}

// ── Panel de emojis ─────────────────────────────────────────────────────────

@Composable
private fun EmojiPanel(
    controller: KeyboardController,
    metrics: KeyboardMetrics,
    viewportW: Float,
    viewportH: Float,
    popupController: PopupController?,
    modifier: Modifier = Modifier,
) {
    val engine = controller.emojiEngine
    val buscando = engine.query.isNotBlank()
    Column(modifier = modifier) {
        // Buscador por nombre (acentos/mayúsculas no importan).
        OutlinedTextField(
            value = engine.query,
            onValueChange = engine::buscar,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar emoji…", color = LolaMuted) },
            singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(color = LolaText),
        )

        if (buscando) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = engine::limpiarBusqueda) { Text("Limpiar", color = LolaAccent) }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                engine.categories().forEach { cat ->
                    val selected = engine.category == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) LolaAccent else Color.Transparent)
                            .clickable { engine.selectCategory(cat) }
                            .semantics {
                                contentDescription = "Categoría ${cat.name.lowercase()}"
                                if (selected) stateDescription = "Seleccionada"
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(cat.icon, fontSize = 18.sp)
                    }
                }
            }
        }

        val emojis = engine.emojis()
        if (emojis.isEmpty()) {
            Text(
                text = if (buscando) "Sin resultados para \"${engine.query}\"" else "Sin emojis recientes",
                color = LolaMuted,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(emojis) { emoji ->
                    EmojiCelda(
                        emoji = emoji,
                        metrics = metrics,
                        viewportW = viewportW,
                        viewportH = viewportH,
                        popupController = popupController,
                        onTap = { controller.emoji(emoji.glyph) },
                        onSelect = { controller.emoji(it) },
                    )
                }
            }
        }
    }
}

/**
 * Celda de emoji: tap inserta el glifo; long-press muestra el popup de tonos de
 * piel (si el emoji lo soporta) con el mismo gesto de swipe de las teclas.
 * Durante el long-press, moverse más del slop cancela (deja scrollear al grid).
 */
@Composable
private fun EmojiCelda(
    emoji: Emoji,
    metrics: KeyboardMetrics,
    viewportW: Float,
    viewportH: Float,
    popupController: PopupController?,
    onTap: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var posPx by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var pressed by remember { mutableStateOf(false) }

    val onTapActual by rememberUpdatedState(onTap)
    val onSelectActual by rememberUpdatedState(onSelect)
    val variantsActual by rememberUpdatedState(EmojiData.skinToneVariants(emoji.glyph))
    val vw by rememberUpdatedState(viewportW)
    val vh by rememberUpdatedState(viewportH)
    val popupCtlActual by rememberUpdatedState(popupController)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) LolaAccent else Color.Transparent)
            .onGloballyPositioned {
                posPx = it.positionInRoot()
                sizePx = it.size
            }
            .semantics { contentDescription = emoji.name }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    try {
                        val variants = variantsActual
                        val popupCtl = popupCtlActual
                        val slopPx = with(density) { TOUCH_SLOP_DP.dp.toPx() }
                        // Esperar up (tap) o long-press; moverse más del slop
                        // cancela (el grid scrollea, no se abre el popup).
                        val tap = withTimeoutOrNull(LONG_PRESS_MS) {
                            while (true) {
                                val e = awaitPointerEvent()
                                val change = e.changes.firstOrNull { it.id == down.id } ?: continue
                                if (change.changedToUp()) return@withTimeoutOrNull true
                                if ((change.position - down.position).getDistance() > slopPx) {
                                    return@withTimeoutOrNull false
                                }
                            }
                        }
                        when (tap) {
                            true -> onTapActual()
                            false -> Unit // scroll en curso; el grid lo maneja.
                            null -> {
                                // Long-press.
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (variants.isNotEmpty() && popupCtl != null) {
                                    when (val outcome = seleccionarAlternativa(
                                        down = down,
                                        posPx = posPx,
                                        sizePx = sizePx,
                                        viewportW = vw,
                                        viewportH = vh,
                                        alternativas = variants,
                                        popupCtl = popupCtl,
                                        metrics = metrics,
                                        density = density,
                                    )) {
                                        is PopupSwipeOutcome.Seleccionada -> onSelectActual(outcome.valor)
                                        PopupSwipeOutcome.Cancelado -> Unit
                                        PopupSwipeOutcome.SinSeleccion -> onTapActual()
                                    }
                                } else {
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        if (e.changes.firstOrNull { it.id == down.id }?.changedToUp() == true) break
                                    }
                                    onTapActual()
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
        Text(emoji.glyph, fontSize = 22.sp)
    }
}

// ── Popup de alternativas (long-press) ─────────────────────────────────────

@Composable
private fun PopupAlternativas(info: PopupInfo) {
    val scale = remember { Animatable(0.8f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(120)) }
    Box(
        modifier = Modifier
            .offset { IntOffset(info.leftPx, info.topPx) }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF3D4F59)),
    ) {
        Row {
            info.alternativas.forEachIndexed { i, alt ->
                val seleccionada = i == info.seleccion
                Box(
                    modifier = Modifier
                        .width(with(LocalDensity.current) { info.cellPx.toDp() })
                        .height(with(LocalDensity.current) { info.heightPx.toDp() })
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
    onSwipeLeft: (() -> Unit)? = null,
    onGlide: ((Int) -> Unit)? = null,
    onTap: () -> Unit,
    descripcion: String? = null,
    descripcionEstado: String? = null,
    metrics: KeyboardMetrics,
    viewportW: Float = 0f,
    viewportH: Float = 0f,
    popupController: PopupController? = null,
    onComprometerAlternativa: (String) -> Unit = {},
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var posPx by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var lastDown by remember { mutableStateOf(0L) }
    var pressed by remember { mutableStateOf(false) }

    val altActuales by rememberUpdatedState(alternativas)
    val repetirActual by rememberUpdatedState(repetir)
    val longPressActual by rememberUpdatedState(onLongPress)
    val dobleTapActual by rememberUpdatedState(onDoubleTap)
    val swipeLeftActual by rememberUpdatedState(onSwipeLeft)
    val glideActual by rememberUpdatedState(onGlide)
    val tapActual by rememberUpdatedState(onTap)
    val comprometerActual by rememberUpdatedState(onComprometerAlternativa)
    val viewportWActual by rememberUpdatedState(viewportW)
    val viewportHActual by rememberUpdatedState(viewportH)

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
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    try {
                        val downTime = down.uptimeMillis
                        val repetirFn = repetirActual
                        val dobleTapFn = dobleTapActual
                        val longPressFn = longPressActual
                        val tapFn = tapActual
                        val altList = altActuales
                        val swipeFn = swipeLeftActual
                        val glideFn = glideActual
                        val vw = viewportWActual
                        val vh = viewportHActual
                        val popupCtl = popupController
                        val comprometerFn = comprometerActual

                        // Cursor glide (barra espaciadora): arrastrar horizontal
                        // mueve el cursor; soltar sin arrastrar = tap (espacio).
                        if (glideFn != null) {
                            val downX = down.position.x
                            val slopPx = with(density) { GLIDE_SLOP_DP.dp.toPx() }
                            val stepPx = with(density) { GLIDE_STEP_DP.dp.toPx() }
                            var lastStep = 0
                            var dragged = false
                            while (true) {
                                val e = awaitPointerEvent()
                                val change = e.changes.firstOrNull { it.id == down.id } ?: continue
                                if (change.terminado()) {
                                    if (!dragged) tapFn()
                                    break
                                }
                                val dx = change.position.x - downX
                                if (abs(dx) >= slopPx) dragged = true
                                if (dragged) {
                                    val steps = floor(dx / stepPx).toInt()
                                    while (lastStep < steps) { lastStep++; glideFn(1) }
                                    while (lastStep > steps) { lastStep--; glideFn(-1) }
                                }
                            }
                            return@awaitEachGesture
                        }

                        // Autorepeat: acción inmediata + repetición acelerada,
                        // con swipe-left para borrar la palabra completa.
                        if (repetirFn != null) {
                            val downX = down.position.x
                            val swipePx = with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }
                            repetirFn()
                            var espera = AutoRepeat.INITIAL_DELAY_MS
                            while (true) {
                                val outcome = withTimeoutOrNull(espera) {
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val change = e.changes.firstOrNull { it.id == down.id } ?: continue
                                        if (change.terminado()) return@withTimeoutOrNull SOLTO
                                        if (swipeFn != null && (downX - change.position.x) >= swipePx) {
                                            return@withTimeoutOrNull SWIPE_LEFT
                                        }
                                    }
                                }
                                when (outcome) {
                                    SOLTO -> break
                                    SWIPE_LEFT -> { swipeFn?.invoke(); break }
                                    null -> {
                                        repetirFn()
                                        espera = AutoRepeat.nextDelay(espera)
                                    }
                                }
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
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            when {
                                altList.isNotEmpty() && popupCtl != null -> {
                                    when (val outcome = seleccionarAlternativa(
                                        down = down,
                                        posPx = posPx,
                                        sizePx = sizePx,
                                        viewportW = vw,
                                        viewportH = vh,
                                        alternativas = altList,
                                        popupCtl = popupCtl,
                                        metrics = metrics,
                                        density = density,
                                    )) {
                                        is PopupSwipeOutcome.Seleccionada -> comprometerFn(outcome.valor)
                                        PopupSwipeOutcome.Cancelado -> Unit
                                        PopupSwipeOutcome.SinSeleccion -> tapFn()
                                    }
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

// ── Resultados / Carrito (modo LOLA) ────────────────────────────────────────

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
            CartRow(item, i, controller) { controller.removeFromCart(i) }
        }
    }
}

@Composable
private fun CartRow(item: CartItem, index: Int, controller: KeyboardController, onRemove: () -> Unit) {
    val lista = if (item.listaIndex == -1) "Todas las listas" else "Lista ${item.listaIndex + 1}"
    val manual = controller.descuentoModo == DescuentoModo.MANUAL && item.listaIndex != -1
    val editando = controller.editingCartIndex == index
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LolaSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.product.nombre, color = LolaText)
            Text(
                lista + if (item.qty > 1) " × ${item.qty}" else "",
                color = LolaMuted,
                fontSize = 11.sp,
            )
        }
        if (controller.pedidoConfig.concepto == Concepto.U && item.listaIndex != -1) {
            QtyStepper(
                qty = item.qty,
                onMinus = { controller.cambiarCantidad(index, -1) },
                onPlus = { controller.cambiarCantidad(index, 1) },
            )
        }
        if (manual) {
            TextButton(
                onClick = {
                    if (editando) controller.aplicarItemDescuento() else controller.editarItem(index)
                },
            ) {
                Text(
                    if (editando) "Recalcular" else "Seleccionar",
                    color = if (editando) LolaAccent else LolaMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRemove) { Text("✕", color = LolaMuted) }
    }
}

/** Stepper de cantidad (solo visible en concepto U). Mínimo 1. */
@Composable
private fun QtyStepper(qty: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onMinus, enabled = qty > 1) {
            Text("−", color = LolaAccent, fontSize = 14.sp)
        }
        Text("$qty", color = LolaText, fontSize = 13.sp)
        TextButton(onClick = onPlus) {
            Text("+", color = LolaAccent, fontSize = 14.sp)
        }
    }
}

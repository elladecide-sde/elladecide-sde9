package com.elladecide.lola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elladecide.lola.CartItem
import com.elladecide.lola.KeyboardController
import com.elladecide.lola.Mensaje
import com.elladecide.lola.ModoResultado
import com.elladecide.lola.Precio
import com.elladecide.lola.data.ImageEntry
import com.elladecide.lola.data.Product

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

@Composable
fun KeyboardScreen(controller: KeyboardController) {
    val context = LocalContext.current
    MaterialTheme(colorScheme = LolaColors) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(LolaBg)
                .padding(8.dp),
        ) {
            // Buscador
            OutlinedTextField(
                value = controller.query,
                onValueChange = controller::onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar producto…", color = LolaMuted) },
                singleLine = true,
            )

            Spacer(Modifier.height(4.dp))

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

            Spacer(Modifier.height(4.dp))

            // Área principal: resultados o carrito.
            if (controller.query.isNotBlank()) {
                Resultados(controller, Modifier.weight(1f))
            } else {
                Carrito(controller, Modifier.weight(1f))
            }

            Spacer(Modifier.height(6.dp))

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
}

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

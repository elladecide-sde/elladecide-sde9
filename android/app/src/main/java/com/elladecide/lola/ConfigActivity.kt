package com.elladecide.lola

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elladecide.lola.data.SyncRepository

private val ConfigBg = Color(0xFF1F2C34)
private val ConfigSurface = Color(0xFF2A3942)
private val ConfigAccent = Color(0xFF00D4A8)
private val ConfigText = Color(0xFFE9EDEF)
private val ConfigMuted = Color(0xFF8696A0)

private val ConfigColors = darkColorScheme(
    primary = ConfigAccent,
    background = ConfigBg,
    surface = ConfigSurface,
    onPrimary = Color.Black,
    onBackground = ConfigText,
    onSurface = ConfigText,
)

/**
 * Pantalla de configuración del teclado LOLA: URL del backend (BASE_URL),
 * secret (Bearer para el sync) y modo de imágenes de productos. Los cambios se
 * persisten en SharedPreferences y se aplican la próxima vez que se abre el
 * teclado (el IME re-lee la config en cada `onCreateInputView`).
 */
class ConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = LolaPrefs.get(this)
        setContent {
            MaterialTheme(colorScheme = ConfigColors) {
                ConfigScreen(prefs) { finish() }
            }
        }
    }

    @Composable
    private fun ConfigScreen(prefs: SharedPreferences, onClose: () -> Unit) {
        val baseUrlInicial = remember { prefs.getString(LolaPrefs.KEY_BASE_URL, SyncRepository.DEFAULT_BASE_URL) ?: "" }
        val secretInicial = remember { prefs.getString(LolaPrefs.KEY_SECRET, null) ?: "" }
        val imagenesInicial = remember { prefs.getBoolean(LolaPrefs.KEY_IMAGENES_ENABLED, true) }

        var baseUrl by remember { mutableStateOf(baseUrlInicial) }
        var secret by remember { mutableStateOf(secretInicial) }
        var imagenes by remember { mutableStateOf(imagenesInicial) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Ajustes de LOLA", color = ConfigText, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Se aplican la próxima vez que se abre el teclado.",
                color = ConfigMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))

            Text("URL del backend", color = ConfigMuted, fontSize = 13.sp)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(SyncRepository.DEFAULT_BASE_URL, color = ConfigMuted) },
            )
            Text(
                "HTTP sin TLS solo funciona para 10.0.2.2 (dev). Producción: usá HTTPS.",
                color = ConfigMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))

            Text("Secret (Bearer)", color = ConfigMuted, fontSize = 13.sp)
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                placeholder = { Text("Opcional — si el sync requiere auth", color = ConfigMuted) },
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Modo imágenes", color = ConfigText, fontSize = 16.sp)
                    Text(
                        "Mostrar fotos de productos y botón 📷",
                        color = ConfigMuted,
                        fontSize = 13.sp,
                    )
                }
                Switch(
                    checked = imagenes,
                    onCheckedChange = { imagenes = it },
                )
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = {
                    prefs.edit()
                        .putString(LolaPrefs.KEY_BASE_URL, baseUrl.trim().ifEmpty { SyncRepository.DEFAULT_BASE_URL })
                        .putString(LolaPrefs.KEY_SECRET, secret.trim())
                        .putBoolean(LolaPrefs.KEY_IMAGENES_ENABLED, imagenes)
                        .apply()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("GUARDAR", color = Color.Black) }
        }
    }
}
package com.elladecide.lola

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Actividad auxiliar (transparente) que abre el selector de carpeta (SAF) y
 * persiste el URI elegido. Se lanza desde el teclado; al terminar, el IME
 * relee la carpeta y escanea las imágenes.
 */
class FolderPickerActivity : ComponentActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getSharedPreferences("lola", MODE_PRIVATE)
                    .edit()
                    .putString("folder_uri", uri.toString())
                    .apply()
            } catch (e: Exception) {
                // Sin permiso persistible → no guardamos nada.
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker.launch(null)
    }
}

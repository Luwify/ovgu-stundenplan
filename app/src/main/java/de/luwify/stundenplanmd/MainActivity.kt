package de.luwify.stundenplanmd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import de.luwify.stundenplanmd.data.IcsSource
import de.luwify.stundenplanmd.ui.MainScreen
import de.luwify.stundenplanmd.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Opened with / shared an .ics file. Not again after a configuration change.
        if (savedInstanceState == null) handleFile(intent)
        setContent {
            // Many file managers label .ics as octet-stream or plain text: the content is checked on import.
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.import(uri)
            }
            MaterialTheme {
                MainScreen(vm, onImport = { picker.launch(PICKER_TYPES) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleFile(intent)
    }

    private fun handleFile(intent: Intent?) {
        intent?.let(IcsSource::uriOf)?.let(vm::import)
    }

    private companion object {
        val PICKER_TYPES = arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain")
    }
}

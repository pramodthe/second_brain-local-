package com.secondbrain.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val viewModel: BrainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle Share Sheet intent (sharing article / text from other apps)
        handleShareIntent(intent)

        setContent {
            val isDark = isSystemInDarkTheme()
            val colors = if (isDark) {
                darkColorScheme(
                    primary = Color(0xFFB388FF),
                    secondary = Color(0xFF00E5FF),
                    surface = Color(0xFF1E1E2E),
                    surfaceVariant = Color(0xFF282A36)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF6200EE),
                    secondary = Color(0xFF03DAC6),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFF5F5F7)
                )
            }

            MaterialTheme(colorScheme = colors) {
                BrainApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared Note"
            viewModel.saveNote(sharedSubject, sharedText)
        }
    }
}

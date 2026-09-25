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
        handleNavigationIntent(intent)

        setContent {
            val isDark = isSystemInDarkTheme()
            val colors = if (isDark) {
                darkColorScheme(
                    primary = Color(0xFFBEC5FF),
                    onPrimary = Color(0xFF24306B),
                    primaryContainer = Color(0xFF3B477F),
                    onPrimaryContainer = Color(0xFFDDE1FF),
                    secondary = Color(0xFF82D5C6),
                    secondaryContainer = Color(0xFF174F48),
                    background = Color(0xFF111318),
                    surface = Color(0xFF111318),
                    surfaceVariant = Color(0xFF45464F),
                    outline = Color(0xFF90909A)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF4C5DAA),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFDDE1FF),
                    onPrimaryContainer = Color(0xFF06164B),
                    secondary = Color(0xFF356A62),
                    secondaryContainer = Color(0xFFB8F0E5),
                    background = Color(0xFFFBF8FF),
                    surface = Color(0xFFFBF8FF),
                    surfaceVariant = Color(0xFFE4E1EC),
                    outline = Color(0xFF767680)
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
        handleNavigationIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared Note"
            viewModel.saveNote(sharedSubject, sharedText)
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ACTIONS, false) == true) {
            viewModel.requestOpenActions()
            intent.removeExtra(EXTRA_OPEN_ACTIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_ACTIONS = "open_actions"
    }
}

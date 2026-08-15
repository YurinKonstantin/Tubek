package ru.tubek.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.tubek.app.ui.TubekNavHost
import ru.tubek.app.ui.theme.TubekTheme
import ru.tubek.app.ui.viewmodel.AppViewModel
import ru.tubek.app.ui.viewmodel.AppViewModelFactory

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SHARED_URL = "extra_shared_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedUrl = extractSharedUrl(intent)

        setContent {
            val viewModel: AppViewModel = viewModel(
                factory = AppViewModelFactory(application)
            )
            val consentAccepted by viewModel.consentAccepted.collectAsStateWithLifecycle()

            TubekTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TubekNavHost(
                        consentAccepted = consentAccepted,
                        initialUrl = sharedUrl,
                        onAcceptConsent = viewModel::acceptConsent,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        val fromExtra = intent.getStringExtra(EXTRA_SHARED_URL)
        if (!fromExtra.isNullOrBlank()) return fromExtra.trim()
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim()?.takeIf { it.contains("youtu", ignoreCase = true) }
    }
}

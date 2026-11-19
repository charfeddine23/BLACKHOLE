package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.example.ui.navigation.BlackholeNavGraph
import com.example.ui.theme.BlackholeTheme
import com.example.ui.viewmodel.BlackholeViewModel

class MainActivity : FragmentActivity() {

    private val blackholeViewModel: BlackholeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            BlackholeTheme {
                BlackholeNavGraph(blackholeViewModel = blackholeViewModel)
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
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (sharedText.isNotBlank()) {
                blackholeViewModel.onSharedUrlReceived(sharedText)
            }
        }
    }
}

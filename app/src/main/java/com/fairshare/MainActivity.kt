package com.fairshare

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fairshare.presentation.navigation.FairShareNavGraph
import com.fairshare.presentation.theme.FairShareTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity host.
 *
 * Also captures `fairshare://join` invitation deep links (manifest
 * intent-filter) and forwards them to the nav graph as a one-shot
 * `deepLink` parameter; the graph navigates to the JoinEvent screen
 * and calls back to clear the slot so the same link cannot be
 * re-consumed by recompositions.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDeepLink = intent.extractFairshareLink()
        setContent {
            FairShareTheme {
                FairShareNavGraph(
                    deepLink = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.extractFairshareLink()?.let { pendingDeepLink = it }
    }

    private fun Intent?.extractFairshareLink(): String? {
        val data = this?.dataString ?: return null
        // Accept both the legacy custom scheme and the https mirror
        // emitted by the webapp QR generator. The codec itself parses
        // either form, so we just gate the routing here.
        return data.takeIf {
            it.startsWith("fairshare://join?") ||
                Regex("^https?://[^/?#]+/join\\?").containsMatchIn(it)
        }
    }
}

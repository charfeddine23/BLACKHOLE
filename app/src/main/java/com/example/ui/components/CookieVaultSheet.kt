package com.example.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.network.ClientSpoofer
import com.example.ui.theme.ChromeSilver
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyanGlowSoft
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.GlassStrokeBright
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CookieVaultDialog(
    initialAuthUrl: String?,
    syncedDomains: List<String>,
    statusMessage: String?,
    onImportCookiesUri: (Uri) -> Unit,
    onSyncWebViewSession: (String, Boolean) -> Unit,
    onClearCookies: () -> Unit,
    onDismiss: () -> Unit,
) {
    var browserMode by remember(initialAuthUrl) { mutableStateOf(!initialAuthUrl.isNullOrBlank()) }
    var currentUrl by remember(initialAuthUrl) {
        mutableStateOf(
            initialAuthUrl?.takeIf { it.isNotBlank() }
                ?: "https://www.instagram.com/accounts/login/",
        )
    }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    val cookiesFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportCookiesUri(uri)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (browserMode) {
            WebViewSessionSyncScreen(
                initialUrl = currentUrl,
                onUrlNavigated = { currentUrl = it },
                onWebViewReady = { webViewInstance = it },
                onSyncAndPull = {
                    val activeUrl = webViewInstance?.url ?: currentUrl
                    onSyncWebViewSession(activeUrl, !initialAuthUrl.isNullOrBlank())
                },
                onBackToVault = { browserMode = false },
                onClose = onDismiss,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianVoid.copy(alpha = 0.85f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                LiquidGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    highlighted = true,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Cookie,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "SESSION & COOKIE VAULT",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = NeonCyan,
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close session vault",
                                    tint = ChromeSilver,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Bypass login-gated links by importing a browser cookies.txt file or signing in via the synced WebView.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedSlate,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    cookiesFileLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = ObsidianVoid,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("import_cookies_txt_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FileOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "COOKIES.TXT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ObsidianVoid,
                                )
                            }

                            OutlinedButton(
                                onClick = { browserMode = true },
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_webview_auth_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "WEBVIEW LOGIN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonCyan,
                                )
                            }
                        }

                        if (!statusMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmeraldPulse,
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "SYNCED DOMAINS (${syncedDomains.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlate,
                            )
                            if (syncedDomains.isNotEmpty()) {
                                TextButton(
                                    onClick = onClearCookies,
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteSweep,
                                        contentDescription = null,
                                        tint = CrimsonAlert,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "CLEAR",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CrimsonAlert,
                                    )
                                }
                            }
                        }

                        if (syncedDomains.isEmpty()) {
                            Text(
                                text = "No custom cookies imported yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedSlate.copy(alpha = 0.75f),
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                syncedDomains.take(8).forEach { domain ->
                                    Surface(
                                        color = CyanGlowSoft.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(50),
                                        border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.4f)),
                                    ) {
                                        Text(
                                            text = domain,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ChromeSilver,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewSessionSyncScreen(
    initialUrl: String,
    onUrlNavigated: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onSyncAndPull: () -> Unit,
    onBackToVault: () -> Unit,
    onClose: () -> Unit,
) {
    var addressInput by remember(initialUrl) { mutableStateOf(initialUrl) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onBackToVault,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Back",
                    tint = ChromeSilver,
                )
            }

            Surface(
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.75.dp, GlassStrokeBright.copy(alpha = 0.35f)),
                modifier = Modifier.weight(1f),
            ) {
                BasicTextField(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium.copy(color = ChromeSilver),
                    cursorBrush = SolidColor(NeonCyan),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val normalized = if (addressInput.startsWith("http")) addressInput else "https://$addressInput"
                            activeWebView?.loadUrl(normalized)
                        },
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            Button(
                onClick = onSyncAndPull,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = ObsidianVoid,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("sync_webview_session_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "SYNC",
                    style = MaterialTheme.typography.labelSmall,
                    color = ObsidianVoid,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val quickPlatforms = listOf(
                "IG" to "https://www.instagram.com/accounts/login/",
                "FB" to "https://m.facebook.com/login/",
                "YT" to "https://www.youtube.com/",
                "TT" to "https://www.tiktok.com/login",
                "X" to "https://x.com/i/flow/login",
            )
            quickPlatforms.forEach { (label, targetUrl) ->
                val isSelected = addressInput.contains(
                    when (label) {
                        "IG" -> "instagram"
                        "FB" -> "facebook"
                        "YT" -> "youtube"
                        "TT" -> "tiktok"
                        else -> "x.com"
                    },
                    ignoreCase = true,
                )
                Surface(
                    color = if (isSelected) CyanGlowSoft.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        0.75.dp,
                        if (isSelected) NeonCyan else GlassStrokeBright.copy(alpha = 0.25f),
                    ),
                    onClick = {
                        addressInput = targetUrl
                        activeWebView?.loadUrl(targetUrl)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) NeonCyan else ChromeSilver,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            userAgentString = ClientSpoofer.profiles.first().userAgent
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (!url.isNullOrBlank()) {
                                    addressInput = url
                                    onUrlNavigated(url)
                                }
                            }
                        }
                        loadUrl(initialUrl)
                        activeWebView = this
                        onWebViewReady(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

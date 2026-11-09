package com.edureminder.quicktube.feature.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons

/**
 * The browser's identity: Chrome's own, without the "; wv" (WebView) mark and the "Version/4.0" token. Google refuses
 * "Continue with Google" in anything marked as a WebView ("This browser or app may not be secure").
 */
internal fun browserUserAgent(webViewUa: String): String =
    webViewUa.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")

/** Settings both the page and a sign-in window share: same identity, same cookies (so a sign-in sticks). */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyBrowserSettings() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.userAgentString = browserUserAgent(settings.userAgentString)
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    // Without the app's package in X-Requested-With, sign-in pages see an ordinary browser.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
    }
}

/** Addresses of sign-in pages ("Continue with Google/Facebook/Apple", a site's own login pop-up). */
internal fun isSignInUrl(url: String): Boolean {
    val u = url.lowercase()
    val host = Urls.host(u).orEmpty()
    return host.startsWith("accounts.") || host.startsWith("login.") || host.startsWith("auth.") || host.startsWith("sso.") ||
        host == "appleid.apple.com" || host.endsWith("facebook.com") && ("/dialog/" in u || "/login" in u || "oauth" in u) ||
        listOf("oauth", "/login", "/signin", "/sign-in", "/sign_in", "/auth", "/connect/", "openid").any { it in u } ||
        // A site's own hop to a provider ("/social/google?…").
        u.substringAfter(host).let { path -> listOf("google", "facebook", "apple", "twitter", "microsoft").any { it in path } }
}

/**
 * A window a page opened from a tap (window.open): the sign-in pop-up of "Continue with Google" and the like. It
 * shares the page's cookies, so the sign-in stays after the app is closed. Anything else (an ad pop-up) is closed
 * as soon as its address shows it isn't a sign-in.
 */
internal class SignInWindows(private val context: Context, private val onChange: (WebView?) -> Unit) {
    var current: WebView? = null
        private set

    /** Hands the new window to the page that asked for it; false when it shouldn't open. */
    fun open(resultMsg: Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        close()
        val window = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            applyBrowserSettings()
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.javaScriptCanOpenWindowsAutomatically = true
            webChromeClient = object : WebChromeClient() {
                // The sign-in page closes itself when it's done; the page behind then knows the user.
                override fun onCloseWindow(window: WebView) = close()
            }
            webViewClient = object : WebViewClient() {
                private var checked = false

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (checked || !url.startsWith("http")) return
                    checked = true
                    if (!isSignInUrl(url)) close()
                }

                // Other apps (intent:, market:) stay closed from here.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !request.url.toString().startsWith("http")

                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                }
            }
        }
        transport.webView = window
        resultMsg.sendToTarget()
        current = window
        onChange(window)
        return true
    }

    fun close() {
        val w = current ?: return
        current = null
        CookieManager.getInstance().flush()
        onChange(null)
        w.post { w.stopLoading(); w.destroy() }
    }
}

/** The sign-in window over the page, with its own close button; Back goes back inside it first. */
@Composable
internal fun SignInOverlay(window: WebView, onClose: () -> Unit) {
    var title by remember(window) { mutableStateOf(Urls.host(window.url.orEmpty()).orEmpty()) }
    BackHandler { if (window.canGoBack()) window.goBack() else onClose() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QtIconButton(QtIcons.Close, "Close sign-in", onClose)
            Column(Modifier.padding(start = 4.dp)) {
                Text("Sign in", style = MaterialTheme.typography.titleMedium)
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AndroidView({ window }, Modifier.fillMaxWidth().weight(1f), update = { title = Urls.host(it.url.orEmpty()).orEmpty().ifBlank { title } })
    }
}

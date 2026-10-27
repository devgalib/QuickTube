package com.edureminder.quicktube.feature.browser

import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.PillButton
import androidx.compose.foundation.background
import com.edureminder.quicktube.core.designsystem.pressable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.playerui.findActivity
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

private val YT_WATCH = Regex("""youtube\.com/(watch\?|shorts/)|youtu\.be/""")

/**
 * Reports every <video> on the page (playing state, size, duration, sources) plus the page title/image.
 * Also hooks play events so a video is reported the moment it starts.
 */
private const val SNIFF_JS = """
(function(){try{
 if(!window.__qtHooked){window.__qtHooked=true; window.__qtId=Math.random().toString(36).slice(2); window.__qtTouch=0;
  ['touchstart','pointerdown','click','keydown'].forEach(function(e){document.addEventListener(e,function(ev){window.__qtTouch=Date.now();
   // A tap on a video (or its controls overlay) means the user picked that video.
   var t=ev.touches&&ev.touches[0]?ev.touches[0]:ev; if(t.clientX==null||!window.__qtSeen)return;
   window.__qtSeen.forEach(function(v){var r=v.getBoundingClientRect();
    if(t.clientX>=r.left&&t.clientX<=r.right&&t.clientY>=r.top&&t.clientY<=r.bottom)v.__qtUser=true;});},true);});
  window.__qtSeen=new Set();
  function mark(v){try{window.__qtSeen.add(v);var ua=navigator.userActivation;
   if((ua&&ua.isActive)||Date.now()-window.__qtTouch<5000)v.__qtUser=true;}catch(x){}}
  document.addEventListener('play',function(e){mark(e.target);},true);
  // Players inside shadow DOM don't send events to the document; catch them when they call play().
  try{var op=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){mark(this);setTimeout(function(){window.__qtScan&&window.__qtScan();},300);return op.apply(this,arguments);};}catch(x){}
  ['play','playing','loadedmetadata'].forEach(function(e){document.addEventListener(e,function(){setTimeout(function(){window.__qtScan&&window.__qtScan();},250);},true);});
  setInterval(function(){window.__qtScan&&window.__qtScan();},2000);}
 window.__qtScan=function(){try{
  var vw=window.innerWidth||document.documentElement.clientWidth||0, vids=[], all=new Set();
  function walk(root){root.querySelectorAll('video').forEach(function(v){all.add(v);});
   root.querySelectorAll('*').forEach(function(el){if(el.shadowRoot)walk(el.shadowRoot);});}
  walk(document);
  if(window.__qtSeen)window.__qtSeen.forEach(function(v){if(v.isConnected&&v.tagName==='VIDEO')all.add(v);});
  all.forEach(function(v){ if(window.__qtSeen)window.__qtSeen.add(v);
   var r=v.getBoundingClientRect(), s=[];
   if(v.currentSrc)s.push(v.currentSrc); if(v.src)s.push(v.src);
   v.querySelectorAll('source').forEach(function(x){if(x.src)s.push(x.src);});
   vids.push({s:s,u:!!v.__qtUser,p:!v.paused&&!v.ended,t:v.currentTime||0,d:isFinite(v.duration)?v.duration:-1,m:!!v.muted,l:!!v.loop,w:r.width||0,vw:vw,po:v.poster||''});
  });
  function meta(p){var m=document.querySelector('meta[property="'+p+'"],meta[name="'+p+'"]');return m?m.content:null;}
  if(!vids.length&&window!==window.top)return;
  QtSniffer.report(JSON.stringify({f:window.__qtId,top:window===window.top,v:vids,t:meta('og:title')||document.title,i:meta('og:image')}));
 }catch(e){}};
 window.__qtScan();
}catch(e){}})();
"""

/**
 * For the invisible browser behind the share sheet: every video plays at volume 0 (nothing is heard) and counts
 * as started by the user, so the page's main video is detected exactly as when the user taps play.
 */
private const val SILENT_JS = """
(function(){try{
 if(window.__qtSilent)return; window.__qtSilent=true;
 function hush(v){try{v.__qtUser=true; if(v.volume!==0)v.volume=0;}catch(x){}}
 ['play','playing','volumechange','loadedmetadata'].forEach(function(e){document.addEventListener(e,function(ev){
  if(ev.target&&ev.target.tagName&&/^(VIDEO|AUDIO)$/.test(ev.target.tagName))hush(ev.target);},true);});
 try{var op=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){hush(this);return op.apply(this,arguments);};}catch(x){}
 document.querySelectorAll('video,audio').forEach(hush);
 // Nobody can tap play on an invisible page, so start the player like the user would (silently). Many players
 // (e.g. Vimeo's) only load the video once play is pressed.
 function kick(){try{
  var vs=document.querySelectorAll('video'), playing=false;
  vs.forEach(function(v){if(!v.paused&&!v.ended)playing=true;});
  if(playing)return;
  vs.forEach(function(v){hush(v);var p=v.play();if(p&&p.catch)p.catch(function(){});});
  var b=document.querySelector('[aria-label="Play"],[aria-label^="Play video"],[data-play-button],.vp-big-play-button,.vjs-big-play-button,.ytp-large-play-button,.jw-display-icon-display,.plyr__control--overlaid');
  if(b)b.click();
 }catch(x){}}
 setTimeout(kick,800); setInterval(kick,2500);
}catch(e){}})();
"""

/** Hands the rendered page (after scripts, with the user's session) to the page parser. */
private fun grabHtml(view: WebView, vm: BrowserViewModel) {
    val url = view.url ?: return
    view.evaluateJavascript("document.documentElement.outerHTML") { quoted ->
        val html = runCatching { org.json.JSONArray("[$quoted]").getString(0) }.getOrNull() ?: return@evaluateJavascript
        vm.onPageHtml(url, html)
    }
}

/**
 * Runs fetch() inside the page so requests carry the page's own cookies, origin and TLS session —
 * used for quality lists and playlists that refuse requests from outside the browser.
 */
private class PageFetcher {
    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()
    var webView: WebView? = null

    @JavascriptInterface
    fun fetchResult(id: String, text: String?) {
        pending.remove(id)?.complete(text)
    }

    suspend fun fetch(url: String): String? {
        val view = webView ?: return null
        val id = java.util.UUID.randomUUID().toString()
        val d = kotlinx.coroutines.CompletableDeferred<String?>()
        pending[id] = d
        val js = "fetch(" + org.json.JSONObject.quote(url) + ",{credentials:'include'}).then(function(r){return r.ok?r.text():null})" +
            ".then(function(t){QtFetch.fetchResult('" + id + "',t)}).catch(function(){QtFetch.fetchResult('" + id + "',null)});"
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { view.evaluateJavascript(js, null) }
        return kotlinx.coroutines.withTimeoutOrNull(15_000) { d.await() }.also { pending.remove(id) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    startUrl: String,
    autoDownload: Boolean = false,
    onClose: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    /** Invisible and muted (behind the share sheet): no sound, no history. */
    silent: Boolean = false,
    /** Separates browser states that open the same URL (each share gets a fresh one). */
    stateKey: String = startUrl,
) {
    val vm = koinViewModel<BrowserViewModel>(key = stateKey)
    val fetcher = remember { PageFetcher() }
    LaunchedEffect(Unit) { vm.autoDownload = autoDownload; vm.pageFetch = fetcher::fetch; vm.recordHistory = !silent }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val saved by vm.bookmarked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    val openVideo by rememberUpdatedState(onOpenVideo)

    val isNative = YT_WATCH.containsMatchIn(ui.url)
    // Only when the user is on a video and it is playing — never on listing/home pages.
    val canDownload = isNative || ui.mainVideo != null || ui.pageMedia != null

    BackHandler {
        when {
            customView != null -> webView?.webChromeClient?.onHideCustomView()
            editing -> editing = false
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onClose()
        }
    }

    // Periodically re-scan the page for players that load lazily.
    LaunchedEffect(webView, ui.url) {
        while (true) {
            delay(2500)
            if (silent) webView?.evaluateJavascript(SILENT_JS, null)
            webView?.evaluateJavascript(SNIFF_JS, null)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                QtIconButton(QtIcons.Close, "Close", onClose)
                if (editing) {
                    TextField(
                        address, { address = it }, Modifier.weight(1f), singleLine = true,
                        shape = RoundedCornerShape(50),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val t = address.trim()
                            webView?.loadUrl(if (Urls.looksLikeUrl(t)) Urls.normalize(t) else Urls.googleSearch(t))
                            editing = false
                        }),
                    )
                } else {
                    Row(
                        Modifier.weight(1f).padding(vertical = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .pressable(scaleDown = 0.98f, onClick = { address = ui.url; editing = true }).padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (ui.url.startsWith("https")) QtIcons.Lock else QtIcons.Public, null, Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(ui.title.ifBlank { Urls.host(ui.url).orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge)
                            Text(Urls.host(ui.url).orEmpty(), maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                QtIconButton(QtIcons.Refresh, "Reload", { webView?.reload() })
                Box {
                    QtIconButton(QtIcons.MoreVert, "More", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (saved) "Saved" else "Save page") }, leadingIcon = {
                            Icon(if (saved) QtIcons.Bookmark else QtIcons.BookmarkBorder, null)
                        }, onClick = { vm.toggleBookmark(); menu = false })
                        DropdownMenuItem(text = { Text("Saved pages") }, leadingIcon = { Icon(QtIcons.Collections, null) },
                            onClick = { menu = false; onOpenBookmarks() })
                        DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(QtIcons.History, null) },
                            onClick = { menu = false; onOpenHistory() })
                        DropdownMenuItem(text = { Text("Copy link") }, leadingIcon = { Icon(QtIcons.ContentCopy, null) }, onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", ui.url))
                            menu = false
                        })
                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(QtIcons.Share, null) }, onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ui.url), "Share"))
                            menu = false
                        })
                        DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { Icon(QtIcons.OpenInNew, null) }, onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ui.url))) }
                            menu = false
                        })
                    }
                }
            }
            if (ui.progress in 1..99) LinearProgressIndicator(progress = { ui.progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // Ad scripts open pop-ups/pop-unders on any tap; only real links the user tapped may open.
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // Run the detector in every frame (embedded players live in iframes) from the very start.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(this, SNIFF_JS, setOf("*"))
                            if (silent) WebViewCompat.addDocumentStartJavaScript(this, SILENT_JS, setOf("*"))
                        }
                        vm.userAgent = settings.userAgentString
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun report(json: String) {
                                val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
                                val arr = o.optJSONArray("v") ?: org.json.JSONArray()
                                val videos = (0 until arr.length()).map { i ->
                                    val v = arr.getJSONObject(i)
                                    val srcs = v.optJSONArray("s")
                                    PageVideo(
                                        sources = (0 until (srcs?.length() ?: 0)).map { srcs!!.getString(it) }.distinct(),
                                        userStarted = v.optBoolean("u"),
                                        playing = v.optBoolean("p"), currentTime = v.optDouble("t", 0.0), duration = v.optDouble("d", -1.0),
                                        muted = v.optBoolean("m"), loop = v.optBoolean("l"), width = v.optDouble("w", 0.0),
                                        viewportWidth = v.optDouble("vw", 0.0), poster = v.optString("po").ifBlank { null },
                                    )
                                }
                                val title = o.optString("t").ifBlank { null }
                                val image = if (o.isNull("i")) null else o.optString("i").ifBlank { null }
                                val frame = o.optString("f")
                                val top = o.optBoolean("top", true)
                                post { vm.onPageReport(frame, top, videos, if (top) title else null, if (top) image else null) }
                            }
                        }, "QtSniffer")
                        addJavascriptInterface(fetcher, "QtFetch")
                        fetcher.webView = this
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                if (u.startsWith("http")) return false
                                // The invisible page behind the share sheet must never open another app (e.g. Facebook's
                                // "open in app" bounce); it stays on the web page.
                                if (silent) {
                                    runCatching { Intent.parseUri(u, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
                                        .getOrNull()?.takeIf { it.startsWith("http") }?.let(view::loadUrl)
                                    return true
                                }
                                val intent = runCatching {
                                    if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(u))
                                }.getOrNull() ?: return true
                                // Sites try to bounce visitors into their app; stay in the browser unless the user tapped a link.
                                val fallback = intent.getStringExtra("browser_fallback_url")
                                if (!request.hasGesture()) {
                                    if (fallback != null) view.loadUrl(fallback)
                                    return true
                                }
                                runCatching { view.context.startActivity(intent) }.onFailure { if (fallback != null) view.loadUrl(fallback) }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = vm.onPageStarted(url)

                            override fun onPageFinished(view: WebView, url: String) {
                                vm.onPageFinished(url, view.title)
                                if (silent) view.evaluateJavascript(SILENT_JS, null)
                                view.evaluateJavascript(SNIFF_JS, null)
                                grabHtml(view, vm)
                                // Players often inject their config a moment after load.
                                view.postDelayed({ if (view.url == url) grabHtml(view, vm) }, 2500)
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                vm.onUrlChanged(url, view.title)
                                view.postDelayed({ if (view.url == url) grabHtml(view, vm) }, 1500)
                                // Same as the original app: tapping a YouTube video opens the native video page.
                                if (YT_WATCH.containsMatchIn(url) && !url.contains("/shorts/")) {
                                    view.post {
                                        openVideo(url)
                                        if (view.canGoBack()) view.goBack()
                                    }
                                }
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url.toString()
                                val path = request.url.path.orEmpty().lowercase()
                                if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".m4a") || path.endsWith(".mp3") ||
                                    path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".flv") || path.endsWith(".m3u8") ||
                                    u.contains("mime_type=video_mp4") || u.contains(".mp4?") || u.contains(".m3u8?")
                                ) view.post { vm.onMediaRequest(u) }
                                return null
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) = vm.onProgress(newProgress, view.canGoBack())

                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                // target=_blank links the user tapped open here; script pop-ups are dropped.
                                val hit = view.hitTestResult
                                val link = if (isUserGesture && (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                                        hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) hit.extra else null
                                if (link != null && link.startsWith("http")) view.loadUrl(link)
                                return false
                            }
                            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                val decor = ctx.findActivity()?.window?.decorView as? FrameLayout ?: return
                                decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                customView = view
                            }
                            override fun onHideCustomView() {
                                val decor = ctx.findActivity()?.window?.decorView as? FrameLayout ?: return
                                customView?.let { decor.removeView(it) }
                                customView = null
                            }
                        }
                        // The invisible page behind the share sheet never offers files (an ad could trigger one).
                        if (!silent) setDownloadListener { url, ua, disposition, mime, _ ->
                            vm.downloadFile(url, mime, URLUtil.guessFileName(url, disposition, mime))
                        }
                        if (silent && WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(this, true)
                        loadUrl(startUrl)
                        webView = this
                    }
                },
            )
            // Own bar under the page (never over it), so page elements and ad overlays can't steal the tap.
            AnimatedVisibility(canDownload, enter = expandVertically(), exit = shrinkVertically()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val d = ui.pageMedia?.durationSec ?: ui.mainVideo?.duration?.takeIf { it > 0 }
                    Column(Modifier.weight(1f)) {
                        Text("Video found on this page", style = MaterialTheme.typography.labelLarge)
                        Text(
                            listOfNotNull(d?.let { Format.duration(it.toLong()) }, Urls.host(ui.url)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    PillButton("Download", { vm.download(isNative) }, icon = QtIcons.Download)
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fetcher.webView = null
            vm.pageFetch = null
            webView?.run { stopLoading(); destroy() }
        }
    }
}

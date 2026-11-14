package dev.pleos.starter.teum.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/** Offline app document. Only typed intents from its top frame reach native code. */
class TeumWebViewHost(
    context: Context,
    private val onRequest: (TeumWebRequest) -> Unit,
    private val onDocumentChanged: () -> Unit = {},
    private val onRendererGone: () -> Unit
) {
    val view = WebView(context)
    @Volatile
    var epoch: Long = nextEpoch()
        private set

    private var foreground = false
    private var closed = false
    private var loaded = false
    private var latestState: JsonObject? = null
    private val seenRequests = mutableSetOf<String>()
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    init {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.toString().startsWith("data:image/")) return null
                if (!isAppOrigin(uri)) return blocked()
                if (uri.path == "/native/bootstrap.js") {
                    return WebResourceResponse(
                        "text/javascript", "UTF-8",
                        ByteArrayInputStream("window.__TEUM_EPOCH__=$epoch;".toByteArray(Charsets.UTF_8))
                    ).apply { responseHeaders = HEADERS }
                }
                val path = uri.path ?: return blocked()
                if (!path.startsWith("/assets/teum/") || uri.pathSegments.any { it == ".." }) {
                    return blocked()
                }
                return loader.shouldInterceptRequest(uri)?.apply {
                    responseHeaders = HEADERS
                } ?: blocked()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (closed || url != INDEX_URL) return
                loaded = false
                epoch = nextEpoch()
                seenRequests.clear()
                onDocumentChanged()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (closed || url != INDEX_URL) return
                loaded = true
                latestState?.let { publishState(it) }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                close()
                onRendererGone()
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "teum", setOf(ORIGIN)) {
                sourceView, message, sourceOrigin, isMainFrame, _ ->
            if (closed || !foreground || sourceView !== view || !isMainFrame || !isAppOrigin(sourceOrigin)) {
                return@addWebMessageListener
            }
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val request = TeumWebProtocol.decode(message.data ?: return@addWebMessageListener)
                ?: return@addWebMessageListener
            if (request.epoch != epoch || request.requestId in seenRequests || seenRequests.size >= 4096) {
                return@addWebMessageListener
            }
            seenRequests.add(request.requestId)
            onRequest(request)
            }
        } else {
            error("The installed WebView does not support the native message boundary")
        }
    }

    fun load() {
        check(!closed)
        view.loadUrl(INDEX_URL)
    }

    fun resume() {
        check(!closed)
        foreground = true
        view.onResume()
        latestState?.let { publishState(it) }
    }

    fun suspend() {
        if (closed) return
        foreground = false
        epoch = nextEpoch()
        seenRequests.clear()
        onDocumentChanged()
        view.onPause()
    }

    fun publishState(state: JsonObject, previous: PreviousTransition? = null) {
        if (closed) return
        val cached = state.deepCopy()
        latestState = cached
        if (!loaded || !foreground) return
        val envelope = TeumWebProtocol.stateEnvelope(cached, epoch, previous)
        view.evaluateJavascript("window.teumReceive($envelope);", null)
    }

    fun close() {
        if (closed) return
        foreground = false
        closed = true
        epoch = nextEpoch()
        seenRequests.clear()
        onDocumentChanged()
        latestState = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(view, "teum")
        }
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val INDEX_URL = "$ORIGIN/assets/teum/index.html"
        private val epochCounter = AtomicLong()
        private const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "font-src 'self'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'"
        private val HEADERS = mapOf(
            "Content-Security-Policy" to CSP,
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff"
        )

        private fun nextEpoch(): Long =
            epochCounter.updateAndGet { maxOf(it + 1, SystemClock.elapsedRealtime()) }

        private fun isAppOrigin(uri: Uri): Boolean =
            uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
                (uri.port == -1 || uri.port == 443) && uri.userInfo.isNullOrEmpty()

        private fun blocked(): WebResourceResponse = WebResourceResponse(
            "text/plain", "UTF-8", 403, "Forbidden", HEADERS, ByteArrayInputStream(ByteArray(0))
        )
    }
}

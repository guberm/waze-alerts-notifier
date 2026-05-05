package com.mg.wazealerts.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mg.wazealerts.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

private val mainHandler = Handler(Looper.getMainLooper())

class WazeWebViewFetcher(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private val initMutex = Mutex()
    private val pendingResult = AtomicReference<CompletableDeferred<String>?>()

    // Cache the last successfully intercepted Waze response (including calls during warmup)
    @Volatile private var lastCapturedResponse: String? = null
    @Volatile private var lastCapturedAt: Long = 0

    inner class JsBridge {
        @JavascriptInterface
        fun onResult(text: String) {
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                AppLogger.d(TAG, "Intercepted Waze georss response (${text.length} chars)")
                lastCapturedResponse = trimmed
                lastCapturedAt = System.currentTimeMillis()
                pendingResult.get()?.complete(trimmed)
            } else {
                AppLogger.e(TAG, "Waze georss response not JSON: ${trimmed.take(120)}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureReady(lat: Double, lng: Double) {
        if (pageReady && webView != null) return
        initMutex.withLock {
            if (pageReady && webView != null) return
            pageReady = false
            val initDeferred = CompletableDeferred<Unit>()
            // Clear stale cache when reinitializing
            lastCapturedResponse = null

            withContext(Dispatchers.Main) {
                val pageUrl = "https://www.waze.com/live-map/"
                AppLogger.i(TAG, "Initializing WebView — loading $pageUrl")

                val wv = webView ?: WebView(appContext).also { webView = it }
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = USER_AGENT
                if (wv.tag == null) {
                    wv.addJavascriptInterface(JsBridge(), "WazeAndroid")
                    wv.tag = "init"
                }

                // Grant geolocation so Waze can center on the real device position
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String, callback: GeolocationPermissions.Callback
                    ) = callback.invoke(origin, true, false)
                }

                var debounceRunnable: Runnable? = null
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        AppLogger.i(TAG, "Warmup page finished: $url")
                        // Inject interceptor on every page finish — Waze calls georss ~800ms after
                        view.evaluateJavascript(INTERCEPTOR_SCRIPT, null)
                        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                        debounceRunnable = Runnable {
                            if (!initDeferred.isCompleted) initDeferred.complete(Unit)
                        }
                        mainHandler.postDelayed(debounceRunnable!!, 800)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView, request: WebResourceRequest
                    ): WebResourceResponse? {
                        if ("georss" in request.url.toString()) {
                            AppLogger.i(TAG, "Waze native georss call: ${request.url}")
                        }
                        return null
                    }
                }
                wv.loadUrl(pageUrl)
            }

            withTimeout(30_000) { initDeferred.await() }
            pageReady = true
            AppLogger.i(TAG, "WebView ready — interceptor active")
        }
    }

    suspend fun fetch(url: String): String {
        val (lat, lng) = extractCenter(url) ?: (DEFAULT_LAT to DEFAULT_LNG)
        ensureReady(lat, lng)

        // Use response already captured during warmup if it's fresh
        val cached = lastCapturedResponse
        if (cached != null && System.currentTimeMillis() - lastCapturedAt < 60_000) {
            AppLogger.d(TAG, "Returning Waze georss captured during warmup")
            return cached
        }

        // Wait for the next intercepted Waze call
        val deferred = CompletableDeferred<String>()
        pendingResult.set(deferred)
        return try {
            withTimeout(30_000) { deferred.await() }
        } catch (e: Exception) {
            throw IOException("Timed out waiting for Waze georss response")
        }
    }

    fun invalidate() {
        pageReady = false
        lastCapturedResponse = null
        AppLogger.i(TAG, "WebView session invalidated — will reinit on next fetch")
    }

    fun destroy() {
        pageReady = false
        lastCapturedResponse = null
        Handler(Looper.getMainLooper()).post {
            webView?.destroy()
            webView = null
            AppLogger.i(TAG, "WebView destroyed")
        }
    }

    private fun extractCenter(georssUrl: String): Pair<Double, Double>? = try {
        val params = georssUrl.substringAfter("?").split("&")
            .mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }
            .toMap()
        val lat = ((params["top"]?.toDouble() ?: return null) + (params["bottom"]?.toDouble() ?: return null)) / 2
        val lng = ((params["left"]?.toDouble() ?: return null) + (params["right"]?.toDouble() ?: return null)) / 2
        lat to lng
    } catch (_: Exception) { null }

    companion object {
        private const val TAG = "WazeWebView"
        private const val DEFAULT_LAT = 43.45
        private const val DEFAULT_LNG = -79.75
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        // Monkey-patches window.fetch AND XMLHttpRequest to forward georss responses to the Android bridge.
        private const val INTERCEPTOR_SCRIPT = """
(function() {
    if (window._wazeIntercepted) return;
    window._wazeIntercepted = true;
    var _orig = window.fetch;
    window.fetch = function(input, init) {
        var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
        var p = _orig.apply(this, arguments);
        if (url.indexOf('georss') !== -1) {
            p.then(function(r) {
                if (r.ok) {
                    r.clone().text().then(function(t) {
                        if (window.WazeAndroid) window.WazeAndroid.onResult(t);
                    });
                }
            }).catch(function(){});
        }
        return p;
    };
    var _origOpen = XMLHttpRequest.prototype.open;
    var _origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this._waze_url = (typeof url === 'string') ? url : '';
        return _origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
        if (this._waze_url && this._waze_url.indexOf('georss') !== -1) {
            var xhr = this;
            this.addEventListener('load', function() {
                if (xhr.status === 200 && window.WazeAndroid) window.WazeAndroid.onResult(xhr.responseText);
            });
        }
        return _origSend.apply(this, arguments);
    };
})();
"""
    }
}

package com.mg.wazealerts.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mg.wazealerts.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class WazeWebViewFetcher(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private val initMutex = Mutex()
    private val pendingResult = AtomicReference<CompletableDeferred<String>?>()

    inner class JsBridge {
        @JavascriptInterface
        fun onResult(json: String) {
            AppLogger.d(TAG, "JS result received (${json.length} chars)")
            pendingResult.get()?.complete(json)
        }

        @JavascriptInterface
        fun onError(msg: String) {
            AppLogger.e(TAG, "JS fetch error: $msg")
            pendingResult.get()?.completeExceptionally(IOException("JS fetch error: $msg"))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureReady() {
        if (pageReady && webView != null) return
        initMutex.withLock {
            if (pageReady && webView != null) return
            pageReady = false
            val initDeferred = CompletableDeferred<Unit>()

            withContext(Dispatchers.Main) {
                AppLogger.i(TAG, "Initializing WebView — loading $WARMUP_URL")
                val wv = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = USER_AGENT
                    addJavascriptInterface(JsBridge(), "WazeAndroid")
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        AppLogger.i(TAG, "Page finished: $url")
                        if (!initDeferred.isCompleted) initDeferred.complete(Unit)
                    }
                }
                wv.loadUrl(WARMUP_URL)
                webView = wv
            }

            withTimeout(30_000) { initDeferred.await() }
            // Give JS time to run and set cookies
            delay(2_000)
            pageReady = true
            AppLogger.i(TAG, "WebView ready")
        }
    }

    suspend fun fetch(url: String): String {
        ensureReady()

        val deferred = CompletableDeferred<String>()
        pendingResult.set(deferred)

        withContext(Dispatchers.Main) {
            val wv = webView ?: throw IOException("WebView not available")
            val escapedUrl = url.replace("'", "\\'")
            AppLogger.d(TAG, "Executing JS fetch: $escapedUrl")
            wv.evaluateJavascript("""
                (function() {
                    fetch('$escapedUrl', {
                        method: 'GET',
                        credentials: 'include',
                        headers: {
                            'Accept': 'application/json, text/plain, */*',
                            'Referer': 'https://www.waze.com/live-map/'
                        }
                    })
                    .then(function(r) {
                        if (!r.ok) { WazeAndroid.onError('HTTP ' + r.status); return; }
                        return r.text();
                    })
                    .then(function(t) { if (t) WazeAndroid.onResult(t); })
                    .catch(function(e) { WazeAndroid.onError(e.message || String(e)); });
                })();
            """.trimIndent(), null)
        }

        return withTimeout(30_000) { deferred.await() }
    }

    fun invalidate() {
        pageReady = false
        AppLogger.i(TAG, "WebView session invalidated — will reinit on next fetch")
    }

    fun destroy() {
        pageReady = false
        Handler(Looper.getMainLooper()).post {
            webView?.destroy()
            webView = null
            AppLogger.i(TAG, "WebView destroyed")
        }
    }

    companion object {
        private const val TAG = "WazeWebView"
        private const val WARMUP_URL = "https://www.waze.com/live-map/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    }
}

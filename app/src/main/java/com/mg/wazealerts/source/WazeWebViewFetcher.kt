package com.mg.wazealerts.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

private val mainHandler = Handler(Looper.getMainLooper())

class WazeWebViewFetcher(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private val initMutex = Mutex()
    private val pendingResult = AtomicReference<CompletableDeferred<String>?>()

    inner class JsBridge {
        @JavascriptInterface
        fun onResult(text: String) {
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                AppLogger.d(TAG, "Navigation response received (${text.length} chars)")
                pendingResult.get()?.complete(text)
            } else {
                AppLogger.e(TAG, "Navigation response is not JSON: ${trimmed.take(120)}")
                pendingResult.get()?.completeExceptionally(IOException("Not JSON: ${trimmed.take(80)}"))
            }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            AppLogger.e(TAG, "JS error: $msg")
            pendingResult.get()?.completeExceptionally(IOException(msg))
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
                val wv = webView ?: WebView(appContext).also { webView = it }
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = USER_AGENT
                if (wv.tag == null) {
                    wv.addJavascriptInterface(JsBridge(), "WazeAndroid")
                    wv.tag = "initialized"
                }

                var debounceRunnable: Runnable? = null
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        AppLogger.i(TAG, "Warmup page finished: $url")
                        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                        debounceRunnable = Runnable {
                            if (!initDeferred.isCompleted) initDeferred.complete(Unit)
                        }
                        mainHandler.postDelayed(debounceRunnable!!, 800)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // Diagnostic: log any georss calls Waze makes on its own
                        if ("georss" in request.url.toString()) {
                            AppLogger.i(TAG, "Waze native georss call detected: ${request.url}")
                        }
                        return null
                    }
                }
                wv.loadUrl(WARMUP_URL)
            }

            withTimeout(30_000) { initDeferred.await() }
            // Give Waze JS time to initialize and set session cookies
            delay(5_000)
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
            AppLogger.d(TAG, "Navigating WebView to georss URL: $url")

            // Navigate the WebView directly to the georss URL (Sec-Fetch-Mode: navigate,
            // not cors). The warmup session's cookies are still active.
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String) {
                    if ("georss" !in pageUrl) return
                    // Extract the page body — for JSON responses the WebView shows raw text
                    view.evaluateJavascript(
                        "(function(){ WazeAndroid.onResult(document.body.innerText); })()", null
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        val code = errorResponse.statusCode
                        AppLogger.d(TAG, "Navigation HTTP error: $code")
                        pendingResult.get()?.completeExceptionally(IOException("HTTP $code"))
                    }
                }
            }

            // Leaving the warmup page — must re-init next time
            pageReady = false
            wv.loadUrl(url)
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

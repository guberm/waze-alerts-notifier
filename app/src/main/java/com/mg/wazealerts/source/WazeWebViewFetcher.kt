package com.mg.wazealerts.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private val mainHandler = Handler(Looper.getMainLooper())

class WazeWebViewFetcher(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private val initMutex = Mutex()

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
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                var debounceRunnable: Runnable? = null
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        AppLogger.i(TAG, "Page finished: $url")
                        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                        debounceRunnable = Runnable {
                            if (!initDeferred.isCompleted) initDeferred.complete(Unit)
                        }
                        mainHandler.postDelayed(debounceRunnable!!, 800)
                    }
                }
                wv.loadUrl(WARMUP_URL)
                webView = wv
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

        // Extract cookies Waze set during WebView warmup, then use them in a direct HTTP call
        val cookies = withContext(Dispatchers.Main) {
            CookieManager.getInstance().getCookie("https://www.waze.com")
        }
        AppLogger.d(TAG, "Cookies: ${if (cookies.isNullOrBlank()) "none" else "${cookies.length} chars"}")

        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpsURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Referer", "https://www.waze.com/live-map/")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (!cookies.isNullOrBlank()) {
                    conn.setRequestProperty("Cookie", cookies)
                }
                val code = conn.responseCode
                AppLogger.d(TAG, "Direct HTTP response: $code")
                if (code != 200) throw IOException("HTTP $code")
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }
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

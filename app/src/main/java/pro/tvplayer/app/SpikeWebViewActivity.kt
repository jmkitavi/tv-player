package pro.tvplayer.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 0 validation spike.
 *
 * Single-screen WebView that loads a vidfast.pro embed and applies the smooth-UX
 * hardening described in the plan (autoplay, popup/ad blocking, fullscreen, D-pad back).
 * Goal: install on a real Android TV (home network) and confirm end-to-end playback.
 *
 * Override the content via adb, e.g.:
 *   adb shell am start -n pro.tvplayer.app/.SpikeWebViewActivity \
 *     -e url "https://vidfast.pro/movie/tt1645170"
 */
class SpikeWebViewActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Hosts we never want to load (popup/ad/resolver domains observed during testing).
    // Top-level navigations to these are cancelled; sub-resource requests are blocked.
    private val adHostSuffixes = listOf(
        ".cfd", ".cyou", ".sbs", ".top", ".click", ".life", ".quest", ".bond",
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "popads.net", "propellerads.com", "onclickads", "adnxs.com"
    )

    // The streaming content must stay on these hosts.
    private val allowedNavHosts = listOf("vidfast.pro")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this)
        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(rootLayout)

        configureWebView()

        val url = intent?.getStringExtra("url") ?: DEFAULT_URL
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // allow autoplay
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Block popups that need user gesture; we also reject onCreateWindow below.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host ?: return false
                // Cancel any top-level navigation away from the streaming site (ad redirects).
                val allowed = allowedNavHosts.any { host == it || host.endsWith(".$it") }
                return if (allowed) false else true // true = we handled it (i.e. blocked)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null
                if (isAdHost(host)) {
                    // Return an empty 200 so the page logic continues without the ad.
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return null
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Reject popups / new windows entirely.
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean = false

            // Fullscreen video support (player requests fullscreen).
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                rootLayout.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                val view = customView ?: return
                rootLayout.removeView(view)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                webView.visibility = View.VISIBLE
            }
        }
    }

    private fun isAdHost(host: String): Boolean =
        adHostSuffixes.any { host == it.trimStart('.') || host.endsWith(it) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // If in fullscreen video, exit fullscreen first.
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        rootLayout.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_URL = "https://vidfast.pro/movie/tt1645170"
    }
}

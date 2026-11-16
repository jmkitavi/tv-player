package pro.tvplayer.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Generalized embed player for ad/popup-heavy stream pages (e.g. strikeout.im).
 *
 * Flow:
 *  1. PAGE phase  - load the given page URL; injected JS finds the player <iframe> and
 *                   reports its src back via a JS bridge.
 *  2. EMBED phase - navigate straight into that embed URL (skipping the host page's ad
 *                   overlays), block popups/ad hosts, auto-start playback and go fullscreen.
 *
 * Popups/new windows are rejected at the engine level; once in EMBED phase, any top-frame
 * navigation away from the embed host is cancelled (kills ad redirects).
 *
 * Launch:
 *   adb shell am start -n pro.tvplayer.app/.StreamPlayerActivity \
 *     -e url "https://strikeout.im/pga-tour/2/the-travelers-championship-nbc-stream"
 *   optional: -e drill false   (load the page as-is, don't jump into the embed)
 */
class StreamPlayerActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var drill = true
    private var phase = Phase.PAGE
    private var embedHost: String? = null
    private var embedHandled = false

    private enum class Phase { PAGE, EMBED }

    // Desktop UA matched what successfully resolved the stream during testing.
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // Known ad/popup hosts to drop. Player/stream hosts (ninguno.cc, owledge.cc) are NOT here.
    private val adHosts = listOf(
        "chatmate.tv", "obqj2.com", "adcash", "voluum", "propellerads", "popads",
        "poptm", "onclickads", "adsco.re", "doubleclick.net", "googlesyndication.com",
        "adnxs.com", "tsyndicate.com", "popcash", "hilltopads", "mgid.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        drill = intent?.getStringExtra("drill")?.lowercase() != "false"

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        webView = WebView(this)
        rootLayout.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(40, 30, 40, 30)
            text = "Loading…"
        }
        rootLayout.addView(
            statusView,
            FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
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
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = desktopUa
        }
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                val host = request.url.host ?: return false
                if (isAdHost(host)) return true // block ad redirect
                if (phase == Phase.EMBED) {
                    val eh = embedHost
                    val ok = eh != null && (host == eh || host.endsWith(".$eh"))
                    return !ok // once playing, stay on the embed host only
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: return null
                return if (isAdHost(host)) {
                    WebResourceResponse("text/plain", "utf-8", null)
                } else null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                when (phase) {
                    Phase.PAGE -> {
                        if (drill) {
                            setStatus("Finding stream…")
                            view.evaluateJavascript(JS_FIND_EMBED, null)
                        } else {
                            setStatus("")
                            startAutoPlay()
                        }
                    }
                    Phase.EMBED -> {
                        setStatus("")
                        startAutoPlay()
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean = false // reject all popups / new windows

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                rootLayout.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                rootLayout.removeView(v)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                webView.visibility = View.VISIBLE
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onEmbedFound(src: String) {
            handler.post {
                if (embedHandled || src.isBlank()) return@post
                embedHandled = true
                embedHost = try { android.net.Uri.parse(src).host } catch (e: Exception) { null }
                phase = Phase.EMBED
                setStatus("Opening player…")
                webView.loadUrl(src)
            }
        }
    }

    /** Auto-start playback: JS play() + a real center tap that crosses cross-origin iframes. */
    private fun startAutoPlay() {
        var attempts = 0
        val r = object : Runnable {
            override fun run() {
                webView.evaluateJavascript(JS_TRY_PLAY, null)
                simulateCenterTap()
                attempts++
                if (attempts < 8) handler.postDelayed(this, 1800)
            }
        }
        handler.postDelayed(r, 1200)
    }

    /** Dispatches a real touch at the view centre (works through cross-origin iframes). */
    private fun simulateCenterTap() {
        if (customView != null) return // already fullscreen/playing
        val x = webView.width / 2f
        val y = webView.height / 2f
        if (x <= 0f || y <= 0f) return
        val t = SystemClock.uptimeMillis()
        webView.dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        )
        webView.dispatchTouchEvent(
            MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        )
    }

    private fun setStatus(text: String) {
        statusView.text = text
        statusView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun isAdHost(host: String): Boolean =
        adHosts.any { host == it || host.endsWith(".$it") || host.contains(it) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                webView.evaluateJavascript(JS_TOGGLE_PLAY, null)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) { webView.webChromeClient?.onHideCustomView(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        rootLayout.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_URL =
            "https://strikeout.im/pga-tour/2/the-travelers-championship-nbc-stream"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        // Find the most likely player iframe and report it once.
        private const val JS_FIND_EMBED = """
            (function(){
              if(window.__embedReported) return;
              function pick(){
                var ifr = Array.prototype.slice.call(document.querySelectorAll('iframe'));
                var best=null, bestArea=0;
                ifr.forEach(function(f){
                  var s=f.src||''; if(!s||s.indexOf('http')!==0) return;
                  if(/about:blank|partytown|google|doubleclick|recaptcha/.test(s)) return;
                  var r=f.getBoundingClientRect(); var area=r.width*r.height;
                  // prefer cross-origin player-looking iframes
                  var score=area + (/embed|player|stream|sd0/.test(s)?1e7:0);
                  if(score>bestArea){bestArea=score;best=s;}
                });
                return best;
              }
              var tries=0;
              var t=setInterval(function(){
                var s=pick();
                if(s){window.__embedReported=true;clearInterval(t);AndroidBridge.onEmbedFound(s);}
                if(++tries>20)clearInterval(t);
              },700);
            })();
        """

        private const val JS_TRY_PLAY = """
            (function(){
              try{
                var v=document.querySelector('video');
                if(v){ v.muted=false; var p=v.play&&v.play(); if(p&&p.catch)p.catch(function(){}); }
                var sels=['.vjs-big-play-button','button.play','.play-button','.jw-icon-display','[class*=play]'];
                sels.forEach(function(q){document.querySelectorAll(q).forEach(function(e){try{e.click()}catch(_){}})});
              }catch(_){}
            })();
        """

        private const val JS_TOGGLE_PLAY = """
            (function(){var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}})();
        """
    }
}

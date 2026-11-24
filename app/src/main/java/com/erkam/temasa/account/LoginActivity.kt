package com.erkam.temasa.account

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.erkam.temasa.R
import com.erkam.temasa.data.Store

/** Google'ın kendi giriş sayfası (YouTube'a devam etmek için). */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var done = false
    private var bridgeTries = 0
    private var lastBridge = 0L
    private var ytWithoutCookie = 0 // Google girişinden sonra YouTube çerezi olmadan geçen saniye

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0F0F0F.toInt()) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        }
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundColor(0)
            contentDescription = "Kapat"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Oturum aç"
            textSize = 18f
            setTextColor(0xFFF1F1F1.toInt())
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
            text = "Google hesabınla oturum aç"
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(status)
        }
        bar.addView(close, LinearLayout.LayoutParams((48 * dp).toInt(), (56 * dp).toInt()))
        bar.addView(titles)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        web = WebView(this)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * dp).toInt()))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(FrameLayout(this).apply { addView(root) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, s.top, 0, s.bottom); ins
        }

        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Gömülü tarayıcı işaretini kaldır (Google bazen gömülü tarayıcıda girişi engeller)
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet()) }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                // "intent://", "vnd.youtube:" gibi bağlantılar dışarı (YouTube uygulamasına) gider: içeride tut
                if (scheme != "http" && scheme != "https") { bridge(force = true); return true }
                return false
            }
            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = android.view.View.GONE
                check(url)
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progress.visibility = android.view.View.VISIBLE
                check(url)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        web.loadUrl(LOGIN)
    }

    companion object {
        private const val CONT = "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Dtr%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"
        const val LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=tr$CONT"
        /** Google'a girilmişken YouTube çerezlerini sessizce oluşturur (ara sayfaları atlar) */
        const val BRIDGE = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&hl=tr$CONT"
    }

    private fun cookies(url: String) = CookieManager.getInstance().getCookie(url) ?: ""

    private fun has(c: String, vararg names: String) = names.any { n -> Regex("(^|;\\s*)" + Regex.escape(n) + "=").containsMatchIn(c) }

    /** Google hesabına girilmiş mi (YouTube'a değil) */
    private fun googleSignedIn() = has(cookies("https://accounts.google.com"), "SID", "__Secure-1PSID", "__Secure-3PSID")

    private fun youtubeCookie(): String? = cookies("https://www.youtube.com").takeIf { has(it, "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID") }

    /** Google girişi tamam ama YouTube'a geçilmediyse YouTube girişini kendimiz başlat */
    private fun bridge(force: Boolean = false) {
        if (done || !googleSignedIn()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastBridge < 6000) return
        if (bridgeTries >= 4) {
            status.text = "YouTube'a bağlanılamadı. Kapatıp tekrar dene."
            return
        }
        bridgeTries++; lastBridge = now
        status.text = "YouTube hesabına bağlanıyor..."
        web.loadUrl(BRIDGE)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            check(web.url ?: "", fromPoll = true)
            web.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        web.postDelayed(poll, 1000)
    }

    override fun onPause() {
        web.removeCallbacks(poll)
        super.onPause()
    }

    private fun check(url: String, fromPoll: Boolean = false) {
        if (done) return
        val yt = youtubeCookie()
        if (yt != null) { success(yt); return }
        if (!googleSignedIn()) return
        // Google çerezi ancak şifre ve doğrulama bittikten sonra oluşur. Bundan sonra gelen her şey
        // (güvenlik kontrolü sayfası, "YouTube'a devam et", site) ara sayfadır: 3 sn bekle, sonra
        // YouTube girişini kendimiz tamamla.
        status.text = "Giriş tamamlanıyor..."
        if (fromPoll && ++ytWithoutCookie >= 3) { ytWithoutCookie = 0; bridge() }
    }

    private fun success(c: String) {
        done = true
        status.text = "Oturum açıldı"
        CookieManager.getInstance().flush()
        Store.setAccount(c)
        // Hemen kapan; profil ve abonelikler arka planda gelir
        Account.afterLogin()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

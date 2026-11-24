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
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.erkam.temasa.R
import com.erkam.temasa.data.Store
import kotlinx.coroutines.launch

/** Google'ın kendi giriş sayfası (YouTube'a devam etmek için). */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var done = false

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
        bar.addView(close, LinearLayout.LayoutParams((48 * dp).toInt(), (56 * dp).toInt()))
        bar.addView(title)
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
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
        web.loadUrl(
            "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=tr" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Dtr%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"
        )
    }

    private fun check(url: String) {
        if (done) return
        if (!url.startsWith("https://www.youtube.com") && !url.startsWith("https://m.youtube.com")) return
        val c = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return
        if (!c.contains("SAPISID=") && !c.contains("__Secure-3PAPISID=")) return
        done = true
        CookieManager.getInstance().flush()
        Store.setAccount(c)
        lifecycleScope.launch {
            Account.refreshProfile()
            Account.syncSubs()
            setResult(RESULT_OK)
            finish()
        }
    }


    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

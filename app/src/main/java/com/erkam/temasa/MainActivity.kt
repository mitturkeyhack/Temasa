package com.erkam.temasa

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.erkam.temasa.data.Store
import com.erkam.temasa.play.PlaybackService
import com.erkam.temasa.play.Player
import com.erkam.temasa.ui.MainViewModel
import com.erkam.temasa.ui.Tab
import com.erkam.temasa.ui.TemasaApp
import com.erkam.temasa.yt.Yt
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var controller: ListenableFuture<MediaController>? = null
    private var lastClip: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0),
            navigationBarStyle = SystemBarStyle.dark(0xFF0F0F0F.toInt()),
        )
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handle(intent)
        setContent { TemasaApp(vm) }

        // Tam ekran düğmesi: yataya çevir; çıkınca dikeye dön
        lifecycleScope.launch {
            vm.fullscreen.collect { fs ->
                if (fs && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                    requestedOrientation = if (Player.videoAspect.value < 1f) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
        lifecycleScope.launch {
            vm.forcePortrait.collect {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                window.decorView.postDelayed({
                    if (!vm.fullscreen.value) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }, 3000)
            }
        }
        // Küçük pencere ayarı (Android 12+: ana ekrana dönünce kendiliğinden)
        lifecycleScope.launch {
            combine(vm.expanded, Player.playing, Player.audioOnly, Store.settings) { e, p, a, s -> s.pip && e && p && !a }
                .collect { updatePip(it) }
        }
        lifecycleScope.launch { Player.videoAspect.collect { updatePip(canPip()) } }
    }

    private fun canPip() = Store.settings.value.pip && vm.expanded.value && Player.playing.value && !Player.audioOnly.value

    private fun pipParams(auto: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val a = Player.videoAspect.value.coerceIn(0.42f, 2.38f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((a * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(auto).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePip(auto: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) runCatching { pipParams(auto)?.let { setPictureInPictureParams(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && canPip()) {
            runCatching { pipParams(false)?.let { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip.value = isInPictureInPictureMode
        // Küçük pencere kapatılınca videoyu durdur
        if (!isInPictureInPictureMode && lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) {
            if (!Store.settings.value.background) Player.exo.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        // Oynatma hizmetine bağlan (bildirim ve kilit ekranı kontrolleri için)
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        super.onStop()
        val pip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!pip && !Store.settings.value.background && !isChangingConfigurations) Player.exo.pause()
        Player.savePos()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    /** Panoda YouTube bağlantısı varsa küçük bir bildirim göster */
    private fun checkClipboard() {
        if (!Store.settings.value.clipboard) return
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: return
        if (text == lastClip) return
        lastClip = text
        if (!text.contains("youtu")) return
        val id = Yt.idFromUrl(text) ?: return
        if (id == Player.video.value?.id) return
        vm.clip.value = text
    }

    private fun handle(i: Intent?) {
        i ?: return
        when {
            i.getBooleanExtra("watch", false) -> if (Player.video.value != null) vm.expanded.value = true
            i.getBooleanExtra("downloads", false) -> vm.selectTab(Tab.DOWNLOADS)
            i.action == Intent.ACTION_VIEW && i.data != null -> {
                if (!vm.handleUrl(i.data.toString())) vm.toast("Bu bağlantı açılamadı")
            }
            i.action == Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                if (!vm.handleUrl(t)) vm.toast("Paylaşılan metinde YouTube bağlantısı yok")
            }
        }
    }
}

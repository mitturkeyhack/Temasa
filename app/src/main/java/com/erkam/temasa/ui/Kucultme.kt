package com.erkam.temasa.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Video sayfası ↔ mini oynatıcı geçişi (YouTube gibi).
 * p = 0: tam sayfa, p = 1: mini oynatıcının yerinde.
 * Parmakla çekerken p parmağı izler; bırakınca yavaş ve yumuşak tamamlanır.
 */
class Kucultme(private val scope: CoroutineScope, private val vm: MainViewModel) {
    val p = Animatable(0f)
    /** Sayfanın açılışta aşağıdan kayması (0 = yerinde) */
    val giris = Animatable(0f)
    /** Geçiş başlangıç konumuna oturdu mu (ilk karede yanlış yerde görünmesin) */
    var gorunsun by androidx.compose.runtime.mutableStateOf(false)
    /** Parmağın gideceği yol (piksel): videonun tepesinden mini oynatıcıya */
    var yol = 1000f

    private val yavas = tween<Float>(420, easing = FastOutSlowInEasing)

    fun surukle(dy: Float) {
        scope.launch { p.snapTo((p.value + dy / yol).coerceIn(0f, 1f)) }
    }

    /** Parmak bırakıldı: hıza ve konuma göre küçült ya da geri aç */
    fun birak(hiz: Float) {
        scope.launch {
            val kucultsun = hiz > 1400f || (hiz > -700f && p.value > 0.35f)
            val v = (hiz / yol).coerceIn(-8f, 8f)
            if (kucultsun) {
                p.animateTo(1f, spring(dampingRatio = 1f, stiffness = 180f), initialVelocity = v)
                vm.expanded.value = false
            } else p.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 220f), initialVelocity = v)
        }
    }

    /** Geri tuşu, aşağı ok vb. */
    fun kucult() {
        if (p.isRunning && p.targetValue == 1f) return
        scope.launch {
            p.animateTo(1f, yavas)
            vm.expanded.value = false
        }
    }

    /** Mini oynatıcıdan açılış */
    suspend fun buyut() {
        giris.snapTo(0f)
        if (p.value < 1f) p.snapTo(1f)
        gorunsun = true
        p.animateTo(0f, yavas)
    }

    /** Listeden ilk açılış: aşağıdan yukarı kayarak */
    suspend fun ac() {
        p.snapTo(0f)
        giris.snapTo(1f)
        gorunsun = true
        giris.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
    }
}

val LocalKucultme = staticCompositionLocalOf<Kucultme?> { null }

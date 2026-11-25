package com.erkam.temasa

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import com.erkam.temasa.data.AkilliIsci
import com.erkam.temasa.data.YeniVideoIsci
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CokmeTesti {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun rapor(ad: String) {
        val f = File(ctx.filesDir, "son_hata.txt")
        println("=== $ad: " + if (f.exists()) "HATA KAYDI:\n" + f.readText() else "hata kaydı yok")
    }

    @Test fun acilis() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(100) }
            s.onActivity { println("=== açılış: etkinlik durumu ${it.lifecycle.currentState}") }
        }
        rapor("açılış")
    }

    @Test fun bildirimIscisi() {
        val w = TestListenableWorkerBuilder<YeniVideoIsci>(ctx).build()
        val r = runBlocking { w.doWork() }
        println("=== bildirim işçisi sonucu: $r")
        rapor("bildirim işçisi")
    }

    @Test fun akilliIscisi() {
        val w = TestListenableWorkerBuilder<AkilliIsci>(ctx).build()
        val r = runBlocking { w.doWork() }
        println("=== akıllı işçi sonucu: $r")
        rapor("akıllı işçi")
    }
}

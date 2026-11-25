package com.erkam.temasa

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama beklenmedik şekilde kapanırsa sebebini telefona yazar.
 * Bir sonraki açılışta kullanıcıya gösterilir; kopyalayıp gönderebilir.
 */
object HataKaydi {
    private lateinit var dosya: File
    /** Gösterilecek rapor (varsa) */
    val rapor = MutableStateFlow<String?>(null)

    fun kur(ctx: Context) {
        dosya = File(ctx.filesDir, "son_hata.txt")
        // Önceki kapanmanın raporu: 3 günden yeniyse göster
        runCatching {
            if (dosya.exists() && System.currentTimeMillis() - dosya.lastModified() < 3 * 86400_000L) rapor.value = dosya.readText()
        }
        val onceki = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            yaz("Uygulama kapandı", t.name, e)
            onceki?.uncaughtException(t, e)
        }
    }

    fun yaz(baslik: String, nerede: String, e: Throwable) {
        runCatching {
            val zaman = SimpleDateFormat("d MMMM yyyy HH:mm:ss", Locale.forLanguageTag("tr")).format(Date())
            dosya.writeText(buildString {
                append("$baslik\n")
                append("Sürüm: ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Zaman: $zaman · Yer: $nerede\n\n")
                append(Log.getStackTraceString(e).lines().take(60).joinToString("\n"))
            })
        }
    }

    /** Çalıştır; hata verirse kaydet ama uygulamayı kapatma */
    inline fun guvenli(ad: String, f: () -> Unit) {
        try { f() } catch (e: Throwable) { yaz("Hata (uygulama kapanmadı)", ad, e) }
    }

    fun goruldu() {
        rapor.value = null
        runCatching { dosya.renameTo(File(dosya.parentFile, "son_hata_eski.txt")) }
    }
}

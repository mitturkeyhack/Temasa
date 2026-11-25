package com.erkam.temasa.data

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.erkam.temasa.yt.Inner
import com.erkam.temasa.yt.Rss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Akıllı indirmeler (YouTube Premium gibi): Wi-Fi'deyken, izlediklerine ve aboneliklerine göre
 * birkaç video kendiliğinden iner. İzlediklerin silinir, yerine yenileri gelir. Mobil veride hiç indirmez.
 */
object Akilli {
    private lateinit var prefs: SharedPreferences
    val acik = MutableStateFlow(false)
    val adet = MutableStateFlow(8)
    val kalite = MutableStateFlow(720)
    val sonCalisma = MutableStateFlow(0L)
    /** Akıllı inen videolar */
    val idler = MutableStateFlow<Set<String>>(emptySet())
    val calisiyor = MutableStateFlow(false)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("akilli", Context.MODE_PRIVATE)
        acik.value = prefs.getBoolean("acik", false)
        adet.value = prefs.getInt("adet", 8)
        kalite.value = prefs.getInt("kalite", 720)
        sonCalisma.value = prefs.getLong("son", 0)
        idler.value = prefs.getStringSet("idler", emptySet()).orEmpty().toSet()
        planla(ctx)
    }

    private fun kosullar() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)   // sadece Wi-Fi
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    private fun planla(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!acik.value) { wm.cancelUniqueWork("akilli-indirme"); return }
        wm.enqueueUniquePeriodicWork("akilli-indirme", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AkilliIsci>(4, TimeUnit.HOURS, 1, TimeUnit.HOURS).setConstraints(kosullar()).build())
    }

    fun acikSec(ctx: Context, on: Boolean) {
        acik.value = on; prefs.edit().putBoolean("acik", on).apply()
        planla(ctx)
        if (on) simdi(ctx)
    }

    fun adetSec(n: Int) { adet.value = n; prefs.edit().putInt("adet", n).apply() }
    fun kaliteSec(h: Int) { kalite.value = h; prefs.edit().putInt("kalite", h).apply() }

    /** Hemen bir tur (Wi-Fi gelince başlar) */
    fun simdi(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("akilli-simdi", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AkilliIsci>().setConstraints(kosullar()).build())
    }

    private fun idKaydet(s: Set<String>) { idler.value = s; prefs.edit().putStringSet("idler", s).apply() }

    fun akilliMi(id: String) = id in idler.value

    private fun izlendi(id: String): Boolean = (Store.progress(id) ?: 0f) >= 0.9f

    private suspend fun benzer(id: String): List<Video> = withContext(Dispatchers.IO) {
        runCatching { Inner.parse(Inner.post("next", JSONObject().put("videoId", id), false)).videos }.getOrDefault(emptyList())
    }

    /** Bir tur: temizle, eksikleri tamamla. Yaklaşık 9 dakikada biter, kalanı sonraki tura kalır. */
    suspend fun calis() = coroutineScope {
        if (!acik.value || calisiyor.value) return@coroutineScope
        calisiyor.value = true
        try {
            // 1) İzlenen ya da elle silinen akıllı indirmeleri temizle
            var set = idler.value.toMutableSet()
            for (id in set.toList()) {
                val d = Downloads.state(id)
                if (d == null) { set -= id; continue }
                if (d.failed) { Downloads.delete(id); set -= id; continue }
                if (izlendi(id)) { Downloads.delete(id); set -= id }
            }
            idKaydet(set)

            val hazir = set.count { Downloads.state(it)?.let { d -> !d.failed } == true }
            var eksik = adet.value - hazir
            if (eksik <= 0) return@coroutineScope

            // 2) Adaylar: aboneliklerden son 4 günün videoları + son izlediklerine benzerler
            val gecmis = Store.history.value
            val izlenenler = gecmis.map { it.id }.toSet()
            val indirilen = Downloads.items.value.map { it.video.id }.toSet()
            val simdi = System.currentTimeMillis()
            val aboneIs = async {
                runCatching { Rss.zamanli(Store.subs.value.take(60)) }.getOrDefault(emptyList())
                    .filter { simdi - it.second < 4 * 86400_000L }.sortedByDescending { it.second }.map { it.first }
            }
            val benzerIs = gecmis.take(8).map { v -> async { benzer(v.id).take(6) } }
            val abone = aboneIs.await()
            val benzerler = benzerIs.awaitAll().flatten()
            val adaylar = ArrayList<Video>(); val goruldu = HashSet<String>()
            val a = abone.iterator(); val b = benzerler.iterator()
            while (a.hasNext() || b.hasNext()) {
                for (it in listOf(a, b)) if (it.hasNext()) {
                    val v = it.next()
                    val uygun = !v.live && v.id !in izlenenler && v.id !in indirilen && Store.onerilebilir(v) &&
                        (v.duration == 0L || v.duration in 61..2700)
                    if (uygun && goruldu.add(v.id)) adaylar += v
                }
            }

            // 3) Sırayla indir (aynı anda bir tane), süre sınırı içinde
            val bitis = System.currentTimeMillis() + 9 * 60_000L
            for (v in adaylar) {
                if (eksik <= 0 || System.currentTimeMillis() > bitis || !acik.value) break
                set += v.id; idKaydet(set.toSet())
                Downloads.start(v, kalite.value, servis = false)
                while (System.currentTimeMillis() < bitis) {
                    val d = Downloads.state(v.id) ?: break
                    if (d.done || d.failed) break
                    delay(1500)
                }
                val d = Downloads.state(v.id)
                if (d?.done == true) eksik--
                else if (d?.failed == true) { Downloads.delete(v.id); set -= v.id; idKaydet(set.toSet()) }
            }
        } finally {
            sonCalisma.value = System.currentTimeMillis(); prefs.edit().putLong("son", sonCalisma.value).apply()
            calisiyor.value = false
        }
    }
}

class AkilliIsci(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        try { Akilli.calis() } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.erkam.temasa.HataKaydi.yaz("Hata (uygulama kapanmadı)", "Akıllı indirme", e)
        }
        return Result.success()
    }
}

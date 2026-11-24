package com.erkam.temasa.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.erkam.temasa.MainActivity
import com.erkam.temasa.R
import com.erkam.temasa.yt.Net
import com.erkam.temasa.yt.Rss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Abone olunan kanallar yeni video yükleyince bildirim (YouTube gibi).
 * Her kanal için zil: Tümü / Kişiselleştirilmiş (günde en fazla bir) / Yok.
 * Arka planda yaklaşık saatte bir bakılır (telefon pil tasarrufu için biraz geciktirebilir).
 */
object Bildirimler {
    enum class Zil(val ad: String, val aciklama: String) {
        TUM("Tümü", "Her yeni videoda bildirim"),
        KISI("Kişiselleştirilmiş", "Ara sıra, günde en fazla bir"),
        YOK("Yok", "Bildirim gelmez"),
    }

    const val KANAL = "yeni_videolar"
    private lateinit var prefs: SharedPreferences
    val acik = MutableStateFlow(true)
    val ziller = MutableStateFlow<Map<String, Zil>>(emptyMap())

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("bildirimler", Context.MODE_PRIVATE)
        acik.value = prefs.getBoolean("acik", true)
        ziller.value = runCatching {
            val o = JSONObject(prefs.getString("ziller", "{}")!!)
            o.keys().asSequence().associateWith { Zil.valueOf(o.getString(it)) }
        }.getOrDefault(emptyMap())
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(KANAL, "Abonelik videoları", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Abone olduğun kanallar yeni video yükleyince"
                }
            )
        }
        planla(ctx)
    }

    fun zil(kanal: String): Zil = ziller.value[kanal] ?: Zil.KISI

    fun zilSec(kanal: String, z: Zil) {
        ziller.value = ziller.value + (kanal to z)
        val o = JSONObject(); ziller.value.forEach { (k, v) -> o.put(k, v.name) }
        prefs.edit().putString("ziller", o.toString()).apply()
    }

    fun acikSec(ctx: Context, on: Boolean) {
        acik.value = on
        prefs.edit().putBoolean("acik", on).apply()
        planla(ctx)
    }

    private fun planla(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!acik.value) { wm.cancelUniqueWork("yeni-videolar"); return }
        val is_ = PeriodicWorkRequestBuilder<YeniVideoIsci>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("yeni-videolar", ExistingPeriodicWorkPolicy.KEEP, is_)
    }

    /** Arka plan kontrolü */
    suspend fun kontrol(ctx: Context) {
        if (!acik.value) return
        val kanallar = Store.subs.value.filter { zil(it.id) != Zil.YOK }
        if (kanallar.isEmpty()) return
        val sonGorulen = JSONObject(prefs.getString("son", "{}")!!)
        val sonBildirim = JSONObject(prefs.getString("sonBildirim", "{}")!!)
        val simdi = System.currentTimeMillis()
        val izlenen = Store.history.value.map { it.id }.toSet()
        val gelen = Rss.zamanli(kanallar).groupBy { it.first.channelId }
        var sayac = 0
        for ((kanal, l) in gelen) {
            val enYeni = l.maxOf { it.second }
            if (!sonGorulen.has(kanal)) { sonGorulen.put(kanal, enYeni); continue }  // ilk kez: sadece kaydet
            val son = sonGorulen.optLong(kanal)
            val yeniler = l.filter { it.second > son && it.second > simdi - 2 * 86400_000L && it.first.id !in izlenen }.sortedBy { it.second }
            sonGorulen.put(kanal, maxOf(son, enYeni))
            if (yeniler.isEmpty()) continue
            val z = zil(kanal)
            val secilen = when (z) {
                Zil.TUM -> yeniler.takeLast(3)
                Zil.KISI -> if (simdi - sonBildirim.optLong(kanal) > 20 * 3600_000L) yeniler.takeLast(1) else emptyList()
                Zil.YOK -> emptyList()
            }
            for ((v, _) in secilen) {
                if (sayac >= 6) break
                goster(ctx, v); sayac++
                sonBildirim.put(kanal, simdi)
            }
        }
        prefs.edit().putString("son", sonGorulen.toString()).putString("sonBildirim", sonBildirim.toString()).apply()
    }

    private fun resim(url: String?): Bitmap? = url?.let {
        runCatching {
            Net.client.newCall(Request.Builder().url(it).build()).execute().use { r -> if (r.isSuccessful) BitmapFactory.decodeStream(r.body.byteStream()) else null }
        }.getOrNull()
    }

    private fun goster(ctx: Context, v: Video) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val no = v.id.hashCode()
        val ac = PendingIntent.getActivity(ctx, no, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("video", v.toJson().toString()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sonra = PendingIntent.getBroadcast(ctx, no + 1, Intent(ctx, BildirimAlici::class.java)
            .setAction("sonra_izle").putExtra("video", v.toJson().toString()).putExtra("no", no),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val kapak = resim(v.thumbSmall)
        val avatar = resim(v.channelAvatar ?: Store.subs.value.firstOrNull { it.id == v.channelId }?.avatar)
        val b = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle("${v.channel} yeni video yükledi")
            .setContentText(v.title)
            .setAutoCancel(true)
            .setContentIntent(ac)
            .addAction(0, "İzle", ac)
            .addAction(0, "Sonra izle", sonra)
            .setGroup("yeni_videolar")
        avatar?.let { b.setLargeIcon(it) }
        if (kapak != null) b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(kapak).bigLargeIcon(null as Bitmap?).setSummaryText(v.title))
        runCatching { nm.notify(no, b.build()) }
    }
}

class YeniVideoIsci(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        runCatching { Bildirimler.kontrol(applicationContext) }
        return Result.success()
    }
}

/** Bildirimdeki "Sonra izle" */
class BildirimAlici : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != "sonra_izle") return
        val v = runCatching { Video.fromJson(JSONObject(i.getStringExtra("video")!!)) }.getOrNull() ?: return
        NotificationManagerCompat.from(ctx).cancel(i.getIntExtra("no", 0))
        val bitir = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { com.erkam.temasa.account.Account.watchLater(v, true) }
            bitir.finish()
        }
    }
}

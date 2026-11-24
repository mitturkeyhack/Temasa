package com.erkam.temasa.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.erkam.temasa.MainActivity
import com.erkam.temasa.R
import com.erkam.temasa.data.Downloads
import com.erkam.temasa.data.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** İndirmeler sürerken uygulama kapansa bile devam etsin. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("indir", "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        }
        val n = build("İndirme hazırlanıyor", 0, 0, true)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, 7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(7, n)
        scope.launch {
            delay(1500)
            while (isActive) {
                val l = Downloads.items.value
                val running = l.filter { !it.done && !it.failed }
                if (running.isEmpty() && !Downloads.active) {
                    val doneCount = l.count { it.done }
                    nm.notify(8, build("İndirme tamamlandı", 0, 0, false, "$doneCount video İndirilenler'de", ongoing = false))
                    stopSelf()
                    break
                }
                val cur = running.firstOrNull()
                if (cur != null) {
                    val pct = (cur.progress * 100).toInt()
                    val more = if (running.size > 1) " · sırada ${running.size - 1}" else ""
                    nm.notify(7, build(cur.video.title, 100, pct, cur.total == 0L, "%$pct · ${Fmt.bytes(cur.speed)}/sn$more"))
                }
                delay(1000)
            }
        }
    }

    private fun build(title: String, max: Int, p: Int, indeterminate: Boolean, text: String? = null, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).putExtra("downloads", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "indir")
            .setSmallIcon(if (ongoing) R.drawable.ic_stat_download else R.drawable.ic_stat_play)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, p, indeterminate && ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(!ongoing)
            .setContentIntent(open)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

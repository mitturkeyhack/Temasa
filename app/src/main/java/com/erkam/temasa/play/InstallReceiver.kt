package com.erkam.temasa.play

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.erkam.temasa.data.Updater

/** Kurulum sonucu: onay gerekiyorsa Android'in "Yükle" ekranını aç */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Uygulama yeni sürümle yeniden açılır
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.failed("Kurulum iptal edildi. Yükle'ye tekrar bas.")
            else -> Updater.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Kurulamadı: $it" })
        }
    }
}

package com.erkam.temasa

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.erkam.temasa.account.Account
import com.erkam.temasa.data.Downloads
import com.erkam.temasa.data.Store
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.Net
import com.erkam.temasa.yt.Yt

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        HataKaydi.kur(this)
        // Temel parçalar: bunlar olmadan uygulama çalışamaz
        Store.init(this)
        Account.init()
        Yt.init()
        Downloads.init(this)
        Player.init(this)
        // Ek parçalar: biri hata verse bile uygulama kapanmasın, hata kaydedilsin
        HataKaydi.guvenli("Altyazı") { com.erkam.temasa.play.Altyazi.init(this) }
        HataKaydi.guvenli("Bildirimler") { com.erkam.temasa.data.Bildirimler.init(this) }
        HataKaydi.guvenli("Akıllı indirme") { com.erkam.temasa.data.Akilli.init(this) }
        HataKaydi.guvenli("Yapay zekâ") { com.erkam.temasa.yz.Evren.init(this) }
        HataKaydi.guvenli("Yapay zekâ 2") { com.erkam.temasa.yz.Yz.init(this) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Net.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("resimler")).maxSizeBytes(200L * 1024 * 1024).build() }
            .crossfade(150)
            .build()
}

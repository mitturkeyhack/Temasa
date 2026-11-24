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
        Store.init(this)
        Account.init()
        Yt.init()
        Downloads.init(this)
        Player.init(this)
        com.erkam.temasa.filtre.Evren.init(this)
        com.erkam.temasa.filtre.Filtre.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Net.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("resimler")).maxSizeBytes(200L * 1024 * 1024).build() }
            .crossfade(150)
            .build()
}

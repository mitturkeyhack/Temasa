package com.erkam.temasa.account

import android.webkit.CookieManager
import com.erkam.temasa.data.ChannelRef
import com.erkam.temasa.data.Store
import com.erkam.temasa.data.Video
import com.erkam.temasa.yt.Inner
import com.erkam.temasa.yt.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Google hesabı: abone olma, beğenme, Sonra izle YouTube hesabına işlenir. */
object Account {
    fun init() {
        Inner.cookieProvider = { Store.cookie.value }
    }

    val signedIn get() = Inner.signedIn

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Girişten hemen sonra: profil ve abonelikleri arka planda al */
    fun afterLogin() {
        bg.launch {
            refreshProfile()
            syncSubs()
        }
    }

    suspend fun refreshProfile() = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext
        runCatching { Inner.profile() }.getOrNull()?.let { Store.setProfile(it.name, it.photo, it.handle) }
    }

    fun signOut() {
        Store.setAccount(null)
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
    }

    suspend fun state(videoId: String): VideoState? = withContext(Dispatchers.IO) {
        if (!signedIn) null else runCatching { Inner.state(videoId) }.getOrNull()
    }

    /** status: LIKE, DISLIKE, INDIFFERENT */
    suspend fun rate(v: Video, status: String): Boolean = withContext(Dispatchers.IO) {
        Store.setLiked(v, status == "LIKE")
        if (!signedIn) return@withContext true
        runCatching {
            when (status) {
                "LIKE" -> Inner.like(v.id)
                "DISLIKE" -> Inner.dislike(v.id)
                else -> Inner.removeLike(v.id)
            }
        }.isSuccess
    }

    suspend fun subscribe(c: ChannelRef, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        Store.setSub(c, on)
        if (!signedIn) return@withContext true
        runCatching { if (on) Inner.subscribe(c.id) else Inner.unsubscribe(c.id) }.isSuccess
    }

    suspend fun watchLater(v: Video, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (on != Store.isLater(v.id)) Store.toggleLater(v)
        if (!signedIn) return@withContext true
        runCatching { if (on) Inner.watchLaterAdd(v.id) else Inner.watchLaterRemove(v.id) }.isSuccess
    }

    /** Girişliyse YouTube'daki abonelikleri telefona da al */
    suspend fun syncSubs() = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext
        runCatching {
            var f = Inner.subscribedChannels()
            val all = f.channels.toMutableList()
            var n = 0
            while (f.next != null && n < 15) { f = Inner.more(f.next!!); all += f.channels; n++ }
            if (all.isNotEmpty()) Store.setSubs(all)
        }
    }
}

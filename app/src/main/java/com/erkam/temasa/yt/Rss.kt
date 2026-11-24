package com.erkam.temasa.yt

import com.erkam.temasa.data.ChannelRef
import com.erkam.temasa.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.time.OffsetDateTime

/** Girişsiz abonelik akışı: kanalların RSS'i (çok hızlı, aynı anda 8 kanal) */
object Rss {
    private data class Item(val v: Video, val at: OffsetDateTime)

    suspend fun feed(channels: List<ChannelRef>): List<Video> = coroutineScope {
        val sem = Semaphore(8)
        channels.map { c -> async(Dispatchers.IO) { sem.withPermit { runCatching { one(c) }.getOrDefault(emptyList()) } } }
            .awaitAll().flatten()
            .sortedByDescending { it.at }
            .map { it.v }
    }

    /** Yayın zamanıyla birlikte (bildirimler için): (video, kanal kimliği, yayın anı ms) */
    suspend fun zamanli(channels: List<ChannelRef>): List<Pair<Video, Long>> = coroutineScope {
        val sem = Semaphore(6)
        channels.map { c -> async(Dispatchers.IO) { sem.withPermit { runCatching { one(c) }.getOrDefault(emptyList()) } } }
            .awaitAll().flatten()
            .map { it.v to it.at.toInstant().toEpochMilli() }
    }

    private fun unesc(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun one(c: ChannelRef): List<Item> {
        val r = Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=${c.id}").header("User-Agent", Net.BROWSER_UA).build()
        val xml = Net.client.newCall(r).execute().use { if (it.isSuccessful) it.body.string() else return emptyList() }
        return xml.split("<entry>").drop(1).mapNotNull { e ->
            val id = Regex("<yt:videoId>([^<]+)</yt:videoId>").find(e)?.groupValues?.get(1) ?: return@mapNotNull null
            val link = Regex("<link rel=\"alternate\" href=\"([^\"]+)\"").find(e)?.groupValues?.get(1) ?: ""
            if (link.contains("/shorts/")) return@mapNotNull null
            val title = unesc(Regex("<title>([^<]*)</title>").find(e)?.groupValues?.get(1) ?: "")
            val pub = Regex("<published>([^<]+)</published>").find(e)?.groupValues?.get(1)
            val at = runCatching { OffsetDateTime.parse(pub) }.getOrNull() ?: return@mapNotNull null
            val views = Regex("<media:statistics views=\"(\\d+)\"").find(e)?.groupValues?.get(1)?.toLongOrNull() ?: -1
            val author = unesc(Regex("<name>([^<]*)</name>").find(e)?.groupValues?.get(1) ?: c.name)
            Item(
                Video(id, title, author, c.id, c.avatar, 0, views,
                    viewsText = if (views >= 0) com.erkam.temasa.data.Fmt.views(views) else null,
                    ago = Yt.agoOf(at, null)),
                at
            )
        }
    }
}

package com.erkam.temasa.yt

import com.erkam.temasa.data.ChannelRef
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.PlaylistRef
import com.erkam.temasa.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/** Bir sayfa sonuç + devamı */
data class Paged<T>(val items: List<T>, val next: Page?)

data class Comment(
    val author: String,
    val avatar: String?,
    val text: String,
    val ago: String,
    val likes: String,
    val replies: Int,
    val pinned: Boolean,
    val hearted: Boolean,
)

data class ChannelPage(
    val ref: ChannelRef,
    val banner: String?,
    val subscribers: Long,
    val description: String,
    val tabs: Map<String, ListLinkHandler>,
)

/** YouTube motoru: arama, video bilgisi, kanallar, yorumlar. Hepsi reklamsız. */
object Yt {
    val yt get() = ServiceList.YouTube

    fun init() {
        NewPipe.init(NpDownloader(), Localization("tr", "TR"), ContentCountry("TR"))
    }

    private class Cached(val info: StreamInfo, val at: Long)

    private val infoCache = ConcurrentHashMap<String, Cached>()
    private const val TTL = 4L * 60 * 60 * 1000 // bağlantılar ~6 saatte bozulur

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun channelUrl(id: String) = "https://www.youtube.com/channel/$id"

    /** Engelleyici; IO iş parçacığında çağır. */
    fun infoBlocking(id: String, fresh: Boolean = false): StreamInfo {
        if (!fresh) infoCache[id]?.let { if (System.currentTimeMillis() - it.at < TTL) return it.info }
        val i = StreamInfo.getInfo(yt, watchUrl(id))
        infoCache[id] = Cached(i, System.currentTimeMillis())
        return i
    }

    suspend fun info(id: String, fresh: Boolean = false): StreamInfo = withContext(Dispatchers.IO) { infoBlocking(id, fresh) }

    /** Kullanıcı karta dokunmadan önce bilgiyi hazırla (açılış anında başlasın). */
    suspend fun prefetch(id: String) {
        withContext(Dispatchers.IO) { runCatching { infoBlocking(id) } }
    }

    fun invalidate(id: String) {
        infoCache.remove(id)
    }

    // ---------- Arama ----------

    enum class Filter(val label: String, val np: String?) {
        ALL("Tümü", null),
        VIDEOS("Videolar", YoutubeSearchQueryHandlerFactory.VIDEOS),
        CHANNELS("Kanallar", YoutubeSearchQueryHandlerFactory.CHANNELS),
        PLAYLISTS("Listeler", YoutubeSearchQueryHandlerFactory.PLAYLISTS),
    }

    suspend fun search(q: String, filter: Filter, page: Page? = null): Paged<Any> = withContext(Dispatchers.IO) {
        val qh = yt.searchQHFactory.fromQuery(q, listOfNotNull(filter.np), "")
        if (page == null) {
            val si = SearchInfo.getInfo(yt, qh)
            Paged(convert(si.relatedItems), si.nextPage)
        } else {
            val p = SearchInfo.getMoreItems(yt, qh, page)
            Paged(convert(p.items), p.nextPage)
        }
    }

    suspend fun suggestions(q: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { yt.suggestionExtractor.suggestionList(q).take(10) }.getOrDefault(emptyList())
    }

    // ---------- Öneriler ----------

    suspend fun related(id: String): List<Video> = withContext(Dispatchers.IO) {
        convert(infoBlocking(id).relatedItems).filterIsInstance<Video>().filter { it.id != id }
    }

    /** Giriş yapılmamışken ana sayfa: izlediklerine benzer + popüler. */
    suspend fun discover(seeds: List<String>): List<Video> = coroutineScope {
        val fromSeeds = seeds.take(4).map { id ->
            async(Dispatchers.IO) { runCatching { related(id).take(12) }.getOrDefault(emptyList()) }
        }
        val kiosks = listOf("trending_podcasts_episodes", "trending_gaming", "live").map { k ->
            async(Dispatchers.IO) { runCatching { kiosk(k).take(if (seeds.isEmpty()) 25 else 8) }.getOrDefault(emptyList()) }
        }
        // İlk açılışta (geçmiş yokken) çeşitli konulardan popüler videolar
        val starters = if (seeds.isEmpty()) listOf("müzik", "belgesel", "komik videolar", "yemek tarifi", "teknoloji").map { q ->
            async(Dispatchers.IO) { runCatching { search(q, Filter.VIDEOS).items.filterIsInstance<Video>().take(8) }.getOrDefault(emptyList()) }
        } else emptyList()
        val a = fromSeeds.awaitAll() + starters.awaitAll()
        val b = kiosks.awaitAll()
        // Tohumlardan gelenleri harmanla (hep aynı kanal üst üste gelmesin)
        val mixed = mutableListOf<Video>()
        val lists = (a + b).map { it.toMutableList() }.filter { it.isNotEmpty() }
        var i = 0
        while (lists.any { it.isNotEmpty() } && i < 400) {
            lists.forEach { l -> if (l.isNotEmpty()) mixed += l.removeAt(0) }
            i++
        }
        mixed.distinctBy { it.id }.filter { it.id !in seeds }
    }

    suspend fun kiosk(id: String): List<Video> = withContext(Dispatchers.IO) {
        val url = yt.kioskList.getListLinkHandlerFactoryByType(id).fromId(id).url
        convert(KioskInfo.getInfo(yt, url).relatedItems).filterIsInstance<Video>()
    }

    // ---------- Kanal ----------

    /** Kanal: kimlik (UC...) ya da bağlantı (@kullanıcı) */
    suspend fun channel(id: String): ChannelPage = withContext(Dispatchers.IO) {
        val c = ChannelInfo.getInfo(yt, if (id.startsWith("http")) id else channelUrl(id))
        ChannelPage(
            ChannelRef(c.id ?: id, c.name ?: "", best(c.avatars), Fmt.subs(c.subscriberCount)),
            best(c.banners),
            c.subscriberCount,
            c.description ?: "",
            c.tabs.associateBy { it.contentFilters.firstOrNull() ?: "" },
        )
    }

    suspend fun channelTab(tab: ListLinkHandler, page: Page? = null): Paged<Any> = withContext(Dispatchers.IO) {
        if (page == null) {
            val t = ChannelTabInfo.getInfo(yt, tab)
            Paged(convert(t.relatedItems), t.nextPage)
        } else {
            val p = ChannelTabInfo.getMoreItems(yt, tab, page)
            Paged(convert(p.items), p.nextPage)
        }
    }

    // ---------- Oynatma listesi ----------

    suspend fun playlist(id: String, page: Page? = null): Pair<PlaylistRef, Paged<Video>> = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/playlist?list=$id"
        if (page == null) {
            val p = PlaylistInfo.getInfo(yt, url)
            PlaylistRef(id, p.name ?: "", p.uploaderName ?: "", if (p.streamCount >= 0) "${p.streamCount} video" else null, best(p.thumbnails)) to
                Paged(convert(p.relatedItems).filterIsInstance<Video>(), p.nextPage)
        } else {
            val p = PlaylistInfo.getMoreItems(yt, url, page)
            PlaylistRef(id, "") to Paged(convert(p.items).filterIsInstance<Video>(), p.nextPage)
        }
    }

    // ---------- Yorumlar ----------

    suspend fun comments(id: String, page: Page? = null): Pair<Paged<Comment>, Int> = withContext(Dispatchers.IO) {
        val url = watchUrl(id)
        if (page == null) {
            val c = CommentsInfo.getInfo(yt, url)
            Paged(c.relatedItems.map(::comment), c.nextPage) to c.commentsCount
        } else {
            val p = CommentsInfo.getMoreItems(yt, url, page)
            Paged(p.items.map(::comment), p.nextPage) to -1
        }
    }

    private fun comment(c: CommentsInfoItem) = Comment(
        author = c.uploaderName ?: "",
        avatar = best(c.uploaderAvatars),
        text = htmlToText(c.commentText?.content ?: ""),
        ago = Fmt.ago(c.textualUploadDate) ?: "",
        likes = if (c.likeCount > 0) Fmt.count(c.likeCount.toLong()) else trLikes(c.textualLikeCount),
        replies = c.replyCount.coerceAtLeast(0),
        pinned = c.isPinned,
        hearted = c.isHeartedByUploader,
    )

    private fun trLikes(t: String?): String {
        if (t.isNullOrBlank() || t == "0") return ""
        return t.replace(Regex("(?i)([0-9.]+)k$")) { it.groupValues[1].replace('.', ',') + " B" }
            .replace(Regex("(?i)([0-9.]+)m$")) { it.groupValues[1].replace('.', ',') + " Mn" }
    }

    fun htmlToText(s: String): String = s
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    // ---------- Dönüştürme ----------

    fun idFromUrl(url: String): String? {
        Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        return null
    }

    fun channelIdFromUrl(url: String?): String {
        if (url == null) return ""
        return Regex("/channel/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: ""
    }

    fun playlistIdFromUrl(url: String?): String? =
        url?.let { Regex("[?&]list=([A-Za-z0-9_-]+)").find(it)?.groupValues?.get(1) }

    fun best(images: List<org.schabi.newpipe.extractor.Image>?): String? =
        images?.maxByOrNull { it.height }?.url

    /** Shorts'lar hiçbir yerde gösterilmez. */
    fun isShort(it: StreamInfoItem): Boolean =
        it.isShortFormContent || it.url.contains("/shorts/")

    fun convert(items: List<InfoItem>): List<Any> = items.mapNotNull { item ->
        when (item) {
            is StreamInfoItem -> if (isShort(item)) null else toVideo(item)
            is ChannelInfoItem -> ChannelRef(
                channelIdFromUrl(item.url).ifEmpty { item.url.substringAfterLast('/') },
                item.name ?: "", best(item.thumbnails),
                listOfNotNull(
                    Fmt.subs(item.subscriberCount).takeIf { item.subscriberCount >= 0 },
                ).joinToString(" · ").ifEmpty { null },
            )
            is PlaylistInfoItem -> playlistIdFromUrl(item.url)?.let { pid ->
                PlaylistRef(pid, item.name ?: "", item.uploaderName ?: "",
                    if (item.streamCount >= 0) "${item.streamCount} video" else null, best(item.thumbnails))
            }
            else -> null
        }
    }

    fun toVideo(it: StreamInfoItem): Video? {
        val id = idFromUrl(it.url) ?: return null
        val live = it.streamType == StreamType.LIVE_STREAM
        return Video(
            id = id,
            title = it.name ?: "",
            channel = it.uploaderName ?: "",
            channelId = channelIdFromUrl(it.uploaderUrl),
            channelAvatar = best(it.uploaderAvatars),
            duration = it.duration.coerceAtLeast(0),
            views = it.viewCount,
            viewsText = if (it.viewCount >= 0) (if (live) Fmt.count(it.viewCount) + " izleyici" else Fmt.views(it.viewCount)) else null,
            ago = if (live) "CANLI" else agoOf(it.uploadDate?.offsetDateTime(), it.textualUploadDate),
            live = live,
        )
    }

    fun agoOf(date: OffsetDateTime?, textual: String?): String? {
        if (date == null) return Fmt.ago(textual)
        val d = Duration.between(date, OffsetDateTime.now())
        if (d.isNegative) return Fmt.ago(textual)
        val s = d.seconds
        return when {
            s < 60 -> "az önce"
            s < 3600 -> "${s / 60} dakika önce"
            s < 86400 -> "${s / 3600} saat önce"
            s < 7 * 86400 -> "${s / 86400} gün önce"
            s < 30 * 86400 -> "${s / (7 * 86400)} hafta önce"
            s < 365 * 86400 -> "${s / (30 * 86400)} ay önce"
            else -> "${s / (365 * 86400)} yıl önce"
        }
    }

    /** StreamInfo → kart (geçmişe/indirilenlere yazmak için) */
    fun videoOf(i: StreamInfo): Video {
        val live = i.streamType == StreamType.LIVE_STREAM
        return Video(
            id = i.id,
            title = i.name ?: "",
            channel = i.uploaderName ?: "",
            channelId = channelIdFromUrl(i.uploaderUrl),
            channelAvatar = best(i.uploaderAvatars),
            duration = i.duration.coerceAtLeast(0),
            views = i.viewCount,
            viewsText = if (i.viewCount >= 0) Fmt.views(i.viewCount) else null,
            ago = agoOf(i.uploadDate?.offsetDateTime(), i.textualUploadDate),
            live = live,
        )
    }
}

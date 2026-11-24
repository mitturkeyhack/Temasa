package com.erkam.temasa.yt

import com.erkam.temasa.data.ChannelRef
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.PlaylistRef
import com.erkam.temasa.data.Video
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** InnerTube sonucu: videolar, kanallar, listeler ve devamı */
data class Feed(
    val videos: List<Video> = emptyList(),
    val channels: List<ChannelRef> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val next: String? = null,
)

data class VideoState(val likeStatus: String?, val subscribed: Boolean?)

data class Profile(val name: String, val handle: String?, val photo: String?)

/**
 * YouTube'un kendi web arayüzünün konuştuğu uç noktalar.
 * Google hesabıyla giriş yapılınca (çerezlerle) kişisel ana sayfa, abonelik akışı,
 * abone olma, beğenme ve Sonra izle buradan çalışır.
 */
object Inner {
    private const val ORIGIN = "https://www.youtube.com"
    @Volatile var clientVersion = "2.20261123.00.00"
    @Volatile private var versionCheckedAt = 0L

    /** Giriş çerezleri (yoksa null) */
    @Volatile var cookieProvider: () -> String? = { null }

    val signedIn get() = sapisid(cookieProvider()) != null

    private fun cookieValue(cookie: String, name: String) = cookie.split(";").map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.takeIf { it.isNotEmpty() }

    private fun sapisid(cookie: String?): String? {
        if (cookie == null) return null
        return cookieValue(cookie, "SAPISID") ?: cookieValue(cookie, "__Secure-3PAPISID")
    }

    /** YouTube web'in Authorization başlığı (SAPISIDHASH + 1P + 3P) */
    private fun authHeader(cookie: String, ts: Long): String? {
        val parts = mutableListOf<String>()
        fun add(scheme: String, v: String?) { if (v != null) parts += "$scheme ${ts}_${sha1("$ts $v $ORIGIN")}" }
        add("SAPISIDHASH", sapisid(cookie))
        add("SAPISID1PHASH", cookieValue(cookie, "__Secure-1PAPISID"))
        add("SAPISID3PHASH", cookieValue(cookie, "__Secure-3PAPISID"))
        return parts.joinToString(" ").ifEmpty { null }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun refreshVersion() {
        if (System.currentTimeMillis() - versionCheckedAt < 12 * 3600 * 1000L) return
        versionCheckedAt = System.currentTimeMillis()
        runCatching {
            val r = Request.Builder().url("$ORIGIN/?hl=tr").header("User-Agent", Net.BROWSER_UA).build()
            Net.client.newCall(r).execute().use { res ->
                val html = res.body.string()
                Regex("\"INNERTUBE_CLIENT_VERSION\":\"([0-9.]+)\"").find(html)?.groupValues?.get(1)?.let { clientVersion = it }
            }
        }
    }

    private fun context() = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", clientVersion)
            .put("hl", "tr").put("gl", "TR").put("timeZone", "Europe/Istanbul").put("utcOffsetMinutes", 180)
    ).put("user", JSONObject().put("lockedSafetyMode", false))

    /** Engelleyici POST; IO iş parçacığında çağır. */
    fun post(endpoint: String, body: JSONObject, auth: Boolean = true): JSONObject {
        refreshVersion()
        body.put("context", context())
        val b = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", Net.BROWSER_UA)
            .header("Origin", ORIGIN).header("X-Origin", ORIGIN).header("Referer", "$ORIGIN/")
            .header("X-Youtube-Client-Name", "1").header("X-Youtube-Client-Version", clientVersion)
            .header("Accept-Language", "tr-TR,tr;q=0.9")
        val cookie = cookieProvider()
        val authValue = cookie?.let { authHeader(it, System.currentTimeMillis() / 1000) }
        if (auth && cookie != null && authValue != null) {
            b.header("Cookie", cookie)
                .header("Authorization", authValue)
                .header("X-Goog-AuthUser", "0")
        }
        Net.client.newCall(b.build()).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw IllegalStateException("YouTube ${r.code}")
            return JSONObject(text)
        }
    }

    // ---------- Akışlar ----------

    fun browse(browseId: String, params: String? = null): Feed =
        parse(post("browse", JSONObject().put("browseId", browseId).apply { params?.let { put("params", it) } }))

    fun more(token: String): Feed = parse(post("browse", JSONObject().put("continuation", token)))

    fun home() = browse("FEwhat_to_watch")
    fun subscriptionsFeed() = browse("FEsubscriptions")
    fun subscribedChannels() = browse("FEchannels")
    fun watchLater() = browse("VLWL")
    fun likedVideos() = browse("VLLL")
    fun history() = browse("FEhistory")
    fun library() = browse("FEplaylist_aggregation")

    // ---------- Durum ve işlemler ----------

    fun state(videoId: String): VideoState {
        val o = post("next", JSONObject().put("videoId", videoId))
        var like: String? = null
        var sub: Boolean? = null
        walk(o) { k, v ->
            if (like == null && k == "likeStatus" && v is String) like = v
            if (sub == null && k == "subscribeButtonRenderer" && v is JSONObject && v.has("subscribed")) sub = v.optBoolean("subscribed")
        }
        return VideoState(like, sub)
    }

    fun like(videoId: String) = post("like/like", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    fun dislike(videoId: String) = post("like/dislike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    fun removeLike(videoId: String) = post("like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    fun subscribe(channelId: String) = post("subscription/subscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))
    fun unsubscribe(channelId: String) = post("subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))

    fun watchLaterAdd(videoId: String) = post(
        "browse/edit_playlist",
        JSONObject().put("playlistId", "WL").put("actions", JSONArray().put(JSONObject().put("addedVideoId", videoId).put("action", "ACTION_ADD_VIDEO")))
    )

    fun watchLaterRemove(videoId: String) = post(
        "browse/edit_playlist",
        JSONObject().put("playlistId", "WL").put("actions", JSONArray().put(JSONObject().put("removedVideoId", videoId).put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")))
    )

    fun profile(): Profile? {
        val o = post("account/account_menu", JSONObject())
        var name: String? = null
        var handle: String? = null
        var photo: String? = null
        walk(o) { k, v ->
            if (k == "activeAccountHeaderRenderer" && v is JSONObject) {
                name = text(v.optJSONObject("accountName"))
                handle = text(v.optJSONObject("channelHandle"))
                photo = thumb(v.optJSONObject("accountPhoto"))
            }
        }
        return name?.let { Profile(it, handle, photo) }
    }

    // ---------- Ayrıştırma ----------

    /** JSON ağacını gez; her anahtar-değer için çağır. */
    fun walk(o: Any?, f: (String, Any?) -> Unit) {
        when (o) {
            is JSONObject -> o.keys().forEach { k -> val v = o.opt(k); f(k, v); walk(v, f) }
            is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i), f)
        }
    }

    private val SKIP = setOf(
        "reelShelfRenderer", "reelItemRenderer", "shortsLockupViewModel", "adSlotRenderer",
        "promotedSparklesWebRenderer", "statementBannerRenderer", "brandVideoShelfRenderer",
        "brandVideoSingletonRenderer", "promotedVideoRenderer", "inFeedAdLayoutRenderer",
    )

    fun parse(root: JSONObject): Feed {
        val videos = LinkedHashMap<String, Video>()
        val channels = LinkedHashMap<String, ChannelRef>()
        val playlists = LinkedHashMap<String, PlaylistRef>()
        var next: String? = null

        fun visit(o: Any?) {
            when (o) {
                is JSONObject -> {
                    val keys = o.keys().asSequence().toList()
                    for (k in keys) {
                        val v = o.opt(k)
                        if (k in SKIP) continue
                        // Shorts rafları ("Shorts" başlıklı) atlanır
                        if (k == "richShelfRenderer" && v is JSONObject && (v.optBoolean("isShorts") ||
                                text(v.optJSONObject("title"))?.contains("Shorts", true) == true)) continue
                        if (v is JSONObject) {
                            when (k) {
                                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
                                    renderer(v)?.let { videos.putIfAbsent(it.id, it) }
                                "lockupViewModel" -> {
                                    when (v.optString("contentType")) {
                                        "LOCKUP_CONTENT_TYPE_VIDEO" -> lockup(v)?.let { videos.putIfAbsent(it.id, it) }
                                        "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST" ->
                                            lockupPlaylist(v)?.let { playlists.putIfAbsent(it.id, it) }
                                    }
                                }
                                "channelRenderer", "gridChannelRenderer" -> channel(v)?.let { channels.putIfAbsent(it.id, it) }
                                "continuationCommand" -> v.optString("token").takeIf { it.isNotEmpty() }?.let { next = it }
                                "nextContinuationData" -> v.optString("continuation").takeIf { it.isNotEmpty() }?.let { next = it }
                                else -> visit(v)
                            }
                        } else if (v is JSONArray) visit(v)
                    }
                }
                is JSONArray -> for (i in 0 until o.length()) visit(o.opt(i))
            }
        }
        visit(root)
        return Feed(videos.values.toList(), channels.values.toList(), playlists.values.toList(), next)
    }

    fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        o.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") }.ifEmpty { null }
    }

    private fun thumb(o: JSONObject?): String? {
        val a = o?.optJSONArray("thumbnails") ?: o?.optJSONArray("sources") ?: return null
        if (a.length() == 0) return null
        var best = a.getJSONObject(0)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optInt("width") > best.optInt("width")) best = a.getJSONObject(i)
        val u = best.optString("url")
        return if (u.startsWith("//")) "https:$u" else u.ifEmpty { null }
    }

    private fun renderer(v: JSONObject): Video? {
        val id = v.optString("videoId").ifEmpty { return null }
        if (v.toString().contains("reelWatchEndpoint")) return null
        val owner = v.optJSONObject("ownerText") ?: v.optJSONObject("shortBylineText") ?: v.optJSONObject("longBylineText")
        val ownerRun = owner?.optJSONArray("runs")?.optJSONObject(0)
        val channelId = ownerRun?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
        var live = false
        v.optJSONArray("badges")?.let { b ->
            for (i in 0 until b.length()) if (b.optJSONObject(i)?.optJSONObject("metadataBadgeRenderer")?.optString("style") == "BADGE_STYLE_TYPE_LIVE_NOW") live = true
        }
        var length = text(v.optJSONObject("lengthText"))
        if (length == null) {
            v.optJSONArray("thumbnailOverlays")?.let { ov ->
                for (i in 0 until ov.length()) {
                    val t = ov.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
                    if (t.optString("style") == "LIVE") live = true
                    if (t.optString("style") == "SHORTS") return null
                    length = text(t.optJSONObject("text"))
                }
            }
        }
        val avatar = v.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")?.optJSONObject("thumbnail")?.let { thumb(it) }
            ?: v.optJSONObject("channelThumbnail")?.let { thumb(it) }
        val views = text(v.optJSONObject("shortViewCountText")) ?: text(v.optJSONObject("viewCountText"))
        return Video(
            id = id,
            title = text(v.optJSONObject("title")) ?: "",
            channel = ownerRun?.optString("text") ?: text(owner) ?: "",
            channelId = channelId,
            channelAvatar = avatar,
            duration = Fmt.parseTime(length),
            viewsText = views?.let { if (it.contains("görüntüleme") || it.contains("izleyici") || live) it else "$it görüntüleme" },
            ago = if (live) "CANLI" else text(v.optJSONObject("publishedTimeText")),
            live = live,
        )
    }

    private fun lockup(v: JSONObject): Video? {
        val id = v.optString("contentId").ifEmpty { return null }
        val meta = v.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content") ?: ""
        val avatarVm = meta.optJSONObject("image")?.optJSONObject("decoratedAvatarViewModel")
        val avatar = avatarVm?.optJSONObject("avatar")?.optJSONObject("avatarViewModel")?.optJSONObject("image")?.let { thumb(it) }
        var channelId = ""
        avatarVm?.let { a -> walk(a) { k, x -> if (k == "browseId" && x is String && channelId.isEmpty()) channelId = x } }
        val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        val parts = mutableListOf<List<String>>()
        if (rows != null) for (i in 0 until rows.length()) {
            val ps = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            parts += (0 until ps.length()).mapNotNull { ps.optJSONObject(it)?.optJSONObject("text")?.optString("content")?.takeIf { s -> s.isNotEmpty() } }
        }
        if (channelId.isEmpty() && rows != null) walk(rows) { k, x -> if (k == "browseId" && x is String && channelId.isEmpty()) channelId = x }
        var length: String? = null
        var live = false
        v.optJSONObject("contentImage")?.let { img ->
            walk(img) { k, x ->
                if (k == "thumbnailBadgeViewModel" && x is JSONObject) {
                    val t = x.optString("text")
                    if (x.optString("badgeStyle").contains("LIVE") || t.equals("CANLI", true) || t.equals("LIVE", true)) live = true
                    else if (t.contains(":")) length = t
                }
            }
        }
        // Kanal sayfasında satırlarda kanal adı olmaz: ilk satır doğrudan görüntülenme olur
        val first = parts.getOrNull(0) ?: emptyList()
        val noChannel = first.firstOrNull()?.let { it.contains("görüntüleme") || it.contains("izleyici") || it.contains("views") } == true
        val channel = if (noChannel) "" else first.firstOrNull() ?: ""
        val second = if (noChannel) first else parts.getOrNull(1) ?: emptyList()
        val views = second.getOrNull(0)
        return Video(
            id = id, title = title, channel = channel, channelId = channelId, channelAvatar = avatar,
            duration = Fmt.parseTime(length),
            viewsText = views?.let { if (it.contains("görüntüleme") || it.contains("izleyici") || live) it else "$it görüntüleme" },
            ago = if (live) "CANLI" else second.getOrNull(1),
            live = live,
        )
    }

    private fun lockupPlaylist(v: JSONObject): PlaylistRef? {
        val id = v.optString("contentId").ifEmpty { return null }
        val meta = v.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content") ?: ""
        var thumbUrl: String? = null
        var count: String? = null
        v.optJSONObject("contentImage")?.let { img ->
            walk(img) { k, x ->
                if (thumbUrl == null && k == "image" && x is JSONObject) thumbUrl = thumb(x)
                if (count == null && k == "thumbnailBadgeViewModel" && x is JSONObject) count = x.optString("text").ifEmpty { null }
            }
        }
        var channel = ""
        meta?.let { m -> walk(m) { k, x -> if (channel.isEmpty() && k == "metadataParts" && x is JSONArray) channel = x.optJSONObject(0)?.optJSONObject("text")?.optString("content") ?: "" } }
        return PlaylistRef(id, title, channel, count, thumbUrl)
    }

    private fun channel(v: JSONObject): ChannelRef? {
        val id = v.optString("channelId").ifEmpty { return null }
        val subs = text(v.optJSONObject("videoCountText"))?.takeIf { it.contains("abone") }
            ?: text(v.optJSONObject("subscriberCountText"))
        return ChannelRef(id, text(v.optJSONObject("title")) ?: "", thumb(v.optJSONObject("thumbnail")), subs)
    }
}

// ---------------- Yorumlar (YouTube'un kendi yorum sistemi) ----------------

data class YtComment(
    val id: String,
    val author: String,
    val authorId: String,
    val avatar: String?,
    val text: String,
    val ago: String,
    val likes: String,
    val replyCount: Int,
    val hearted: Boolean,
    val pinned: Boolean,
    val creator: Boolean,
    val mine: Boolean,
    var likeState: String,          // LIKE, DISLIKE, INDIFFERENT
    val likeAction: String?,
    val unlikeAction: String?,
    val dislikeAction: String?,
    val undislikeAction: String?,
    val replyParams: String?,
    val repliesToken: String?,
    val isReply: Boolean,
)

data class CommentPage(
    val items: List<YtComment>,
    val next: String?,
    val count: String? = null,
    val createParams: String? = null,
    val sorts: List<Pair<String, String>> = emptyList(), // (ad, token)
)

object Comments {
    private fun find(o: Any?, key: String): Any? {
        var r: Any? = null
        Inner.walk(o) { k, v -> if (r == null && k == key) r = v }
        return r
    }

    private fun findString(o: Any?, key: String): String? = (find(o, key) as? String)?.takeIf { it.isNotEmpty() }

    /** İlk sayfa: video sayfasından yorum bölümünün anahtarını al, sonra yorumları çek */
    fun first(videoId: String): CommentPage {
        val next = Inner.post("next", JSONObject().put("videoId", videoId))
        var token: String? = null
        Inner.walk(next) { k, v ->
            if (token == null && k == "itemSectionRenderer" && v is JSONObject && v.optString("sectionIdentifier") == "comment-item-section") {
                token = findString(v, "token")
            }
        }
        val t = token ?: return CommentPage(emptyList(), null)
        return page(t)
    }

    /** Devam sayfası, sıralama ya da yanıtlar */
    fun page(token: String): CommentPage = parse(Inner.post("next", JSONObject().put("continuation", token)))

    fun create(params: String, text: String) =
        Inner.post("comment/create_comment", JSONObject().put("createCommentParams", params).put("commentText", text))

    fun reply(params: String, text: String) =
        Inner.post("comment/create_comment_reply", JSONObject().put("createReplyParams", params).put("commentText", text))

    fun action(action: String) =
        Inner.post("comment/perform_comment_action", JSONObject().put("actions", JSONArray().put(action)))

    fun parse(root: JSONObject): CommentPage {
        // Yorum içerikleri ayrı bir "varlık" listesinde gelir: anahtar → içerik
        val entities = HashMap<String, JSONObject>()
        root.optJSONObject("frameworkUpdates")?.optJSONObject("entityBatchUpdate")?.optJSONArray("mutations")?.let { m ->
            for (i in 0 until m.length()) {
                val mu = m.optJSONObject(i) ?: continue
                val p = mu.optJSONObject("payload") ?: continue
                val k = p.keys().asSequence().firstOrNull() ?: continue
                entities[mu.optString("entityKey")] = p.getJSONObject(k)
            }
        }
        val items = mutableListOf<YtComment>()
        var next: String? = null
        var count: String? = null
        var create: String? = null
        val sorts = mutableListOf<Pair<String, String>>()

        val lists = mutableListOf<JSONArray>()
        Inner.walk(root) { k, v -> if (k == "continuationItems" && v is JSONArray) lists += v }
        for (list in lists) for (i in 0 until list.length()) {
            val it = list.optJSONObject(i) ?: continue
            it.optJSONObject("commentsHeaderRenderer")?.let { h ->
                count = Inner.text(h.optJSONObject("countText"))
                create = findString(h, "createCommentParams")
                h.optJSONObject("sortMenu")?.let { sm ->
                    Inner.walk(sm) { k, v ->
                        if (k == "subMenuItems" && v is JSONArray) for (j in 0 until v.length()) {
                            val s = v.optJSONObject(j) ?: continue
                            val tok = findString(s, "token") ?: continue
                            sorts += s.optString("title") to tok
                        }
                    }
                }
            }
            it.optJSONObject("commentThreadRenderer")?.let { th ->
                val vm = th.optJSONObject("commentViewModel")?.optJSONObject("commentViewModel") ?: th.optJSONObject("commentViewModel")
                val rt = th.optJSONObject("replies")?.let { findString(it, "token") }
                vm?.let { comment(it, entities, rt, false) }?.let { c -> items += c }
            }
            it.optJSONObject("commentViewModel")?.let { cv ->
                comment(cv, entities, null, true)?.let { c -> items += c }
            }
            it.optJSONObject("continuationItemRenderer")?.let { ci -> findString(ci, "token")?.let { tok -> next = tok } }
        }
        return CommentPage(items, next, count, create, sorts)
    }

    private fun comment(vm: JSONObject, ent: Map<String, JSONObject>, repliesToken: String?, isReply: Boolean): YtComment? {
        val e = ent[vm.optString("commentKey")] ?: return null
        val props = e.optJSONObject("properties") ?: return null
        val author = e.optJSONObject("author")
        val tb = e.optJSONObject("toolbar")
        val state = ent[vm.optString("toolbarStateKey")]
        val surface = ent[vm.optString("toolbarSurfaceKey")]
        fun act(name: String) = surface?.optJSONObject(name)?.let { findString(it, "action") }
        val likeState = when (state?.optString("likeState")) {
            "TOOLBAR_LIKE_STATE_LIKED" -> "LIKE"
            "TOOLBAR_LIKE_STATE_DISLIKED" -> "DISLIKE"
            else -> "INDIFFERENT"
        }
        val pinned = vm.optJSONObject("pinnedText") != null || props.has("pinnedText") || vm.toString().contains("\"pinnedText\"")
        return YtComment(
            id = props.optString("commentId"),
            author = author?.optString("displayName") ?: "",
            authorId = author?.optString("channelId") ?: "",
            avatar = author?.optString("avatarThumbnailUrl")?.ifEmpty { null },
            text = props.optJSONObject("content")?.optString("content") ?: "",
            ago = props.optString("publishedTime"),
            likes = tb?.optString(if (likeState == "LIKE") "likeCountLiked" else "likeCountNotliked")?.trim() ?: "",
            replyCount = tb?.optString("replyCount")?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
            hearted = state?.optString("heartState") == "TOOLBAR_HEART_STATE_HEARTED",
            pinned = pinned,
            creator = author?.optBoolean("isCreator") == true,
            mine = author?.optBoolean("isCurrentUser") == true,
            likeState = likeState,
            likeAction = act("likeCommand"),
            unlikeAction = act("unlikeCommand"),
            dislikeAction = act("dislikeCommand"),
            undislikeAction = act("undislikeCommand"),
            replyParams = surface?.optJSONObject("replyCommand")?.let { findString(it, "createReplyParams") },
            repliesToken = repliesToken,
            isReply = isReply || props.optInt("replyLevel") > 0,
        )
    }
}

package com.erkam.temasa.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val qualityWifi: Int = 0,        // 0 = otomatik, yoksa yükseklik (1080 vb.)
    val qualityMobile: Int = 480,
    val speed: Float = 1f,           // varsayılan hız
    val rememberChannelSpeed: Boolean = true,
    val autoplay: Boolean = true,
    val background: Boolean = true,  // uygulama kapalıyken çalmaya devam
    val pip: Boolean = true,         // çıkınca küçük pencere
    val sponsorBlock: Boolean = true,
    val dislikes: Boolean = true,    // beğenmeme sayısını göster
    val subtitles: Boolean = false,  // varsayılan altyazı
    val subtitleLang: String = "tr",
    val downloadQuality: Int = 720,
    val resume: Boolean = true,      // kaldığın yerden devam
    val accent: Long = 0xFF22C3B3,
    val clipboard: Boolean = true,   // panodaki bağlantıyı yakala
)

/** Videoda kalınan yer */
data class Pos(val ms: Long, val dur: Long, val at: Long)

/** Geçmiş, kaldığın yer, abonelikler, listeler ve ayarlar. Hepsi telefonda saklanır. */
object Store {
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val history = MutableStateFlow<List<Video>>(emptyList())
    val positions = MutableStateFlow<Map<String, Pos>>(emptyMap())
    val watchLater = MutableStateFlow<List<Video>>(emptyList())
    val liked = MutableStateFlow<List<Video>>(emptyList())
    val subs = MutableStateFlow<List<ChannelRef>>(emptyList())
    val lists = MutableStateFlow<List<LocalList>>(emptyList())
    val searches = MutableStateFlow<List<String>>(emptyList())
    val channelSpeed = MutableStateFlow<Map<String, Float>>(emptyMap())
    val homeCache = MutableStateFlow<List<Video>>(emptyList())

    /** Google girişi çerezleri */
    val cookie = MutableStateFlow<String?>(null)
    val profileName = MutableStateFlow<String?>(null)
    val profilePhoto = MutableStateFlow<String?>(null)
    val profileHandle = MutableStateFlow<String?>(null)

    fun init(ctx: Context) {
        dir = ctx.filesDir
        prefs = ctx.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        val p = prefs
        _settings.value = Settings(
            qualityWifi = p.getInt("qWifi", 0),
            qualityMobile = p.getInt("qMobile", 480),
            speed = p.getFloat("speed", 1f),
            rememberChannelSpeed = p.getBoolean("chSpeed", true),
            autoplay = p.getBoolean("autoplay", true),
            background = p.getBoolean("background", true),
            pip = p.getBoolean("pip", true),
            sponsorBlock = p.getBoolean("sponsor", true),
            dislikes = p.getBoolean("dislikes", true),
            subtitles = p.getBoolean("subs", false),
            subtitleLang = p.getString("subLang", "tr") ?: "tr",
            downloadQuality = p.getInt("dlQ", 720),
            resume = p.getBoolean("resume", true),
            accent = p.getLong("accent", 0xFF22C3B3),
            clipboard = p.getBoolean("clipboard", true),
        )
        cookie.value = p.getString("cookie", null)
        profileName.value = p.getString("pName", null)
        profilePhoto.value = p.getString("pPhoto", null)
        profileHandle.value = p.getString("pHandle", null)

        val f = File(dir, "kitaplik.json")
        if (f.exists()) runCatching {
            val o = JSONObject(f.readText())
            history.value = Video.listFromJson(o.optJSONArray("history"))
            watchLater.value = Video.listFromJson(o.optJSONArray("later"))
            liked.value = Video.listFromJson(o.optJSONArray("liked"))
            homeCache.value = Video.listFromJson(o.optJSONArray("home"))
            subs.value = ChannelRef.listFromJson(o.optJSONArray("subs"))
            o.optJSONArray("lists")?.let { a -> lists.value = (0 until a.length()).map { LocalList.fromJson(a.getJSONObject(it)) } }
            o.optJSONArray("searches")?.let { a -> searches.value = (0 until a.length()).map { a.getString(it) } }
            o.optJSONObject("pos")?.let { po ->
                positions.value = po.keys().asSequence().associateWith { k ->
                    val x = po.getJSONArray(k); Pos(x.getLong(0), x.getLong(1), x.optLong(2))
                }
            }
            o.optJSONObject("chSpeed")?.let { cs ->
                channelSpeed.value = cs.keys().asSequence().associateWith { cs.getDouble(it).toFloat() }
            }
        }
    }

    fun updateSettings(block: (Settings) -> Settings) {
        val s = block(_settings.value)
        _settings.value = s
        prefs.edit()
            .putInt("qWifi", s.qualityWifi).putInt("qMobile", s.qualityMobile)
            .putFloat("speed", s.speed).putBoolean("chSpeed", s.rememberChannelSpeed)
            .putBoolean("autoplay", s.autoplay).putBoolean("background", s.background)
            .putBoolean("pip", s.pip).putBoolean("sponsor", s.sponsorBlock)
            .putBoolean("dislikes", s.dislikes).putBoolean("subs", s.subtitles)
            .putString("subLang", s.subtitleLang).putInt("dlQ", s.downloadQuality)
            .putBoolean("resume", s.resume).putLong("accent", s.accent)
            .putBoolean("clipboard", s.clipboard)
            .apply()
    }

    fun setAccount(c: String?) {
        cookie.value = c
        prefs.edit().putString("cookie", c).apply()
        if (c == null) setProfile(null, null, null)
    }

    fun setProfile(name: String?, photo: String?, handle: String?) {
        profileName.value = name; profilePhoto.value = photo; profileHandle.value = handle
        prefs.edit().putString("pName", name).putString("pPhoto", photo).putString("pHandle", handle).apply()
    }

    private var pending: Job? = null

    /** Kayıt toplu yapılır (art arda değişikliklerde diske bir kez yazılır). */
    private fun save() {
        pending?.cancel()
        pending = scope.launch {
            delay(400)
            lock.withLock {
                val o = JSONObject()
                    .put("history", Video.listToJson(history.value))
                    .put("later", Video.listToJson(watchLater.value))
                    .put("liked", Video.listToJson(liked.value))
                    .put("home", Video.listToJson(homeCache.value))
                    .put("subs", ChannelRef.listToJson(subs.value))
                    .put("lists", JSONArray().apply { lists.value.forEach { put(it.toJson()) } })
                    .put("searches", JSONArray(searches.value))
                    .put("pos", JSONObject().apply {
                        positions.value.forEach { (k, v) -> put(k, JSONArray().put(v.ms).put(v.dur).put(v.at)) }
                    })
                    .put("chSpeed", JSONObject().apply { channelSpeed.value.forEach { (k, v) -> put(k, v.toDouble()) } })
                val tmp = File(dir, "kitaplik.json.tmp")
                tmp.writeText(o.toString())
                tmp.renameTo(File(dir, "kitaplik.json"))
            }
        }
    }

    // ---------- Geçmiş ve kaldığın yer ----------

    fun addHistory(v: Video) {
        if (v.id.isEmpty()) return
        history.update { l -> (listOf(v) + l.filter { it.id != v.id }).take(500) }
        save()
    }

    fun removeHistory(id: String) {
        history.update { l -> l.filter { it.id != id } }
        positions.update { it - id }
        save()
    }

    fun clearHistory() {
        history.value = emptyList(); positions.value = emptyMap(); save()
    }

    fun setPos(id: String, ms: Long, dur: Long) {
        if (dur <= 0) return
        positions.update { m ->
            val n = m + (id to Pos(ms, dur, System.currentTimeMillis()))
            if (n.size > 1500) n.entries.sortedByDescending { it.value.at }.take(1200).associate { it.key to it.value } else n
        }
        save()
    }

    /** Kaldığı yer (bitmek üzereyse ya da hiç başlamadıysa yok) */
    fun resumeAt(id: String): Long? {
        val p = positions.value[id] ?: return null
        if (p.ms < 10_000 || p.dur - p.ms < 20_000) return null
        return p.ms
    }

    /** Kartlardaki kırmızı çizgi için 0..1 */
    fun progress(id: String): Float? {
        val p = positions.value[id] ?: return null
        if (p.dur <= 0 || p.ms < 5_000) return null
        return (p.ms.toFloat() / p.dur).coerceIn(0f, 1f).let { if (p.dur - p.ms < 20_000) 1f else it }
    }

    // ---------- Sonra izle, beğenilenler ----------

    fun isLater(id: String) = watchLater.value.any { it.id == id }

    fun toggleLater(v: Video): Boolean {
        val has = isLater(v.id)
        watchLater.update { l -> if (has) l.filter { it.id != v.id } else listOf(v) + l }
        save()
        return !has
    }

    fun setLiked(v: Video, on: Boolean) {
        liked.update { l -> if (on) listOf(v) + l.filter { it.id != v.id } else l.filter { it.id != v.id } }
        save()
    }

    // ---------- Abonelikler (girişsiz) ----------

    fun isSub(id: String) = subs.value.any { it.id == id }

    fun setSub(c: ChannelRef, on: Boolean) {
        subs.update { l -> if (on) (l.filter { it.id != c.id } + c) else l.filter { it.id != c.id } }
        save()
    }

    fun setSubs(l: List<ChannelRef>) {
        subs.value = l.distinctBy { it.id }; save()
    }

    // ---------- Listeler ----------

    fun createList(name: String, first: Video? = null): LocalList {
        val p = LocalList(System.currentTimeMillis().toString(), name.trim().ifEmpty { "Yeni liste" }, listOfNotNull(first))
        lists.update { it + p }
        save()
        return p
    }

    fun addToList(lid: String, v: Video) {
        lists.update { l -> l.map { if (it.id == lid && it.videos.none { x -> x.id == v.id }) it.copy(videos = it.videos + v) else it } }
        save()
    }

    fun removeFromList(lid: String, vid: String) {
        lists.update { l -> l.map { if (it.id == lid) it.copy(videos = it.videos.filter { x -> x.id != vid }) else it } }
        save()
    }

    fun deleteList(lid: String) {
        lists.update { l -> l.filter { it.id != lid } }
        save()
    }

    // ---------- Arama geçmişi ----------

    fun addSearch(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        searches.update { l -> (listOf(t) + l.filter { !it.equals(t, true) }).take(40) }
        save()
    }

    fun removeSearch(q: String) {
        searches.update { l -> l.filter { it != q } }; save()
    }

    fun clearSearches() {
        searches.value = emptyList(); save()
    }

    // ---------- Kanal hızı ----------

    fun speedFor(channelId: String): Float =
        if (settings.value.rememberChannelSpeed) channelSpeed.value[channelId] ?: settings.value.speed else settings.value.speed

    fun setChannelSpeed(channelId: String, s: Float) {
        if (channelId.isEmpty()) return
        channelSpeed.update { it + (channelId to s) }
        save()
    }

    fun setHome(l: List<Video>) {
        homeCache.value = l.take(60); save()
    }
}

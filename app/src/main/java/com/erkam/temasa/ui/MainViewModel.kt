package com.erkam.temasa.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.erkam.temasa.account.Account
import com.erkam.temasa.data.Store
import com.erkam.temasa.data.Video
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.Inner
import com.erkam.temasa.yt.Rss
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

sealed interface Screen {
    data class Search(val q: String? = null) : Screen
    data class Channel(val id: String, val name: String = "") : Screen
    data class Playlist(val id: String, val title: String = "") : Screen
    /** Uygulama içi liste: "history", "later", "liked", "lists" ya da liste kimliği */
    data class Library(val id: String, val title: String = "") : Screen
    data object Settings : Screen
    data object Yz : Screen
}

enum class Tab { HOME, SUBS, DOWNLOADS, YOU }

/** Ekranlar arası ortak durum: sekme, yığın, akışlar */
class MainViewModel : ViewModel() {
    val tab = MutableStateFlow(Tab.HOME)
    val stack = MutableStateFlow<List<Screen>>(emptyList())
    val expanded = MutableStateFlow(false)
    /** Mini oynatıcı sağda mı (sola da sürüklenebilir) */
    val miniSagda = MutableStateFlow(true)
    val fullscreen = MutableStateFlow(false)
    val inPip = MutableStateFlow(false)
    val clip = MutableStateFlow<String?>(null)
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val signedIn = MutableStateFlow(Account.signedIn)

    // Ortak pencereler
    val menu = MutableStateFlow<Video?>(null)          // videonun "⋮" menüsü
    val saveTo = MutableStateFlow<Video?>(null)        // listeye kaydet
    val downloadFor = MutableStateFlow<Video?>(null)   // indirme kalitesi seç
    val yzVideo = MutableStateFlow<Video?>(null)        // videonun "Sor" penceresi
    val anahtarSor = MutableStateFlow(false)            // yapay zekâ anahtarı girme penceresi

    // Ana sayfa
    val chip = MutableStateFlow(0)
    val home = MutableStateFlow(Store.homeCache.value)
    /** Son yenilemelerde en üstte gösterilenler: bir dahakinde geriye atılır (açılışta önceki ekran da sayılır) */
    private val gosterilen = LinkedHashMap<String, Long>().apply {
        val t = System.currentTimeMillis(); Store.homeCache.value.take(16).forEach { put(it.id, t) }
    }
    val homeLoading = MutableStateFlow(false)
    val homeError = MutableStateFlow<String?>(null)
    private var homeNext: String? = null
    private var homePage: Page? = null
    private var homeQuery: String? = null
    private var homeJob: Job? = null

    // Abonelikler
    val subsFeed = MutableStateFlow<List<Video>>(emptyList())
    val subsLoading = MutableStateFlow(false)
    private var subsNext: String? = null
    private var subsLoadedAt = 0L

    val chips = listOf(
        "Tümü" to null, "Müzik" to "müzik", "Oyun" to "oyun", "Canlı" to "#live", "Podcast" to "#podcast",
        "Haberler" to "haberler son dakika", "Belgesel" to "belgesel", "Yemek" to "yemek tarifi",
        "Spor" to "spor özet", "Sohbet" to "sohbet", "Bilim" to "bilim", "Komedi" to "komedi",
    )

    init {
        refreshHome()
        viewModelScope.launch { if (Account.signedIn) Account.refreshProfile() }
        // Çerez değişince (giriş / çıkış) her yer kendini yenilesin
        viewModelScope.launch {
            Store.cookie.collect {
                val now = Account.signedIn
                if (now != signedIn.value) { signedIn.value = now; refreshHome(); refreshSubs(force = true) }
            }
        }
    }

    /** Etkinlik: ekranı dikey yap (tam ekrandan çıkış) */
    val forcePortrait = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    fun exitFullscreen() {
        fullscreen.value = false
        forcePortrait.tryEmit(Unit)
    }

    fun toast(s: String) { toast.tryEmit(s) }

    fun selectTab(t: Tab) {
        if (tab.value == t && stack.value.isEmpty()) {
            if (t == Tab.HOME) refreshHome()
            if (t == Tab.SUBS) refreshSubs(force = true)
        }
        tab.value = t
        stack.value = emptyList()
        expanded.value = false
        if (t == Tab.SUBS) refreshSubs()
    }

    fun push(s: Screen) {
        expanded.value = false
        stack.value = stack.value + s
    }

    fun pop(): Boolean {
        if (stack.value.isEmpty()) return false
        stack.value = stack.value.dropLast(1); return true
    }

    fun open(v: Video, list: List<Video>? = null, startMs: Long? = null) {
        Player.play(v, startMs, list)
        expanded.value = true
    }

    fun onSignedChanged() {
        signedIn.value = Account.signedIn
        refreshHome(); refreshSubs(force = true)
    }

    // ---------- Bağlantıdan açma ----------

    /** YouTube bağlantısını uygun ekranda aç. Tanınırsa true. */
    fun handleUrl(raw: String): Boolean {
        val url = Regex("https?://\\S+").find(raw)?.value ?: raw.trim()
        val u = runCatching { Uri.parse(url) }.getOrNull()
        if (url.startsWith("vnd.youtube:")) {
            val id = url.removePrefix("vnd.youtube:").removePrefix("//").take(11)
            open(Video(id, "")); return true
        }
        val vid = Yt.idFromUrl(url)
        val list = Yt.playlistIdFromUrl(url)
        val t = u?.getQueryParameter("t")?.let { parseT(it) }
        when {
            vid != null -> { open(Video(vid, ""), startMs = t); return true }
            list != null -> { push(Screen.Playlist(list)); return true }
            url.contains("/channel/") -> { push(Screen.Channel(Yt.channelIdFromUrl(url))); return true }
            url.contains("youtube.com/@") -> { push(Screen.Channel(url.substringBefore("?"))); return true }
        }
        return false
    }

    private fun parseT(t: String): Long? {
        t.toLongOrNull()?.let { return it * 1000 }
        val m = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matchEntire(t) ?: return null
        val (h, mi, s) = m.destructured
        return ((h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
    }

    // ---------- Ana sayfa ----------

    fun selectChip(i: Int) {
        if (chip.value == i) return
        chip.value = i
        home.value = emptyList()
        refreshHome()
    }

    fun refreshHome() {
        homeJob?.cancel()
        homeLoading.value = true
        homeError.value = null
        homeNext = null; homePage = null
        val q = chips.getOrNull(chip.value)?.second
        homeQuery = q
        homeJob = viewModelScope.launch {
            val r = runCatching {
                when {
                    q == null -> {
                        val list = karisikAkis()
                        Store.setHome(list)
                        list
                    }
                    q == "#live" -> Yt.kiosk("live")
                    q == "#podcast" -> Yt.kiosk("trending_podcasts_episodes")
                    else -> {
                        val p = Yt.search(q, Yt.Filter.VIDEOS)
                        homePage = p.next
                        p.items.filterIsInstance<Video>()
                    }
                }
            }
            r.onSuccess { home.value = it }
            r.onFailure { if (home.value.isEmpty()) homeError.value = "Bağlantı sorunu. Yenilemek için dokun." }
            homeLoading.value = false
        }
    }

    // ---------- Ana sayfa: her yenilemede taze karışım ----------

    private suspend fun ilgili(id: String): List<Video> = withContext(Dispatchers.IO) {
        runCatching { Inner.parse(Inner.post("next", org.json.JSONObject().put("videoId", id), false)).videos }
            .getOrElse { runCatching { Yt.related(id) }.getOrDefault(emptyList()) }
            .filter { it.id != id && !it.live }
    }

    /**
     * Ana sayfa akışı, birkaç kaynaktan:
     * YouTube'un sana önerdikleri (oturum açıksa, iki sayfa), son izlediklerine benzerler (her seferinde farklı tohumlar),
     * aboneliklerden yeniler ve hiç izlemediğin konulardan biraz keşif.
     * İzleyip bitirdiklerin ve son yenilemelerde gösterilenler geriye atılır; böylece her yenilemede ekran değişir.
     */
    private suspend fun karisikAkis(): List<Video> = kotlinx.coroutines.coroutineScope {
        val gecmis = Store.history.value
        val izlenen = gecmis.take(300).filter { (Store.progress(it.id) ?: 0f) >= 0.85f }.map { it.id }.toSet()
        // Tohumlar: son 40 izlenenden rastgele, yenilere daha çok şans
        val adaylar = gecmis.take(40)
        val tohumlar = adaylar.mapIndexed { i, v -> v to (Math.random() * (1.0 + (40 - i) / 10.0)) }
            .sortedByDescending { it.second }.map { it.first.id }.distinct().take(5)

        val ytIs = async(Dispatchers.IO) {
            if (!Account.signedIn) return@async emptyList<Video>()
            runCatching {
                val f = Inner.home()
                var l = f.videos
                var n = f.next
                // İkinci sayfa da: seçenek çoğalsın
                if (n != null) runCatching { Inner.more(n) }.getOrNull()?.let { m -> l = l + m.videos; n = m.next }
                homeNext = n
                l
            }.getOrDefault(emptyList())
        }
        val benzerIs = tohumlar.map { id -> async { ilgili(id).shuffled().take(10) } }
        val aboneIs = async {
            val f = subsFeed.value.ifEmpty {
                runCatching {
                    if (Account.signedIn) withContext(Dispatchers.IO) { Inner.subscriptionsFeed().videos }
                    else Rss.feed(Store.subs.value)
                }.getOrDefault(emptyList())
            }
            f.filter { !it.live }.take(25).shuffled().take(8)
        }
        val kesifIs = async {
            // Geçmiş azsa ya da hiç yoksa: farklı konulardan popüler videolar
            val konular = listOf("belgesel", "bilim", "tarih", "doğa", "teknoloji", "yemek tarifi", "seyahat", "komedi", "spor özet", "müzik", "podcast", "sohbet")
            val n = if (gecmis.size < 5) 5 else 2
            konular.shuffled().take(n).map { k ->
                async { runCatching { Yt.search(k, Yt.Filter.VIDEOS).items.filterIsInstance<Video>().filter { !it.live }.shuffled().take(6) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }

        val yt = ytIs.await()
        val benzer = benzerIs.awaitAll()
        val abone = aboneIs.await()
        val kesif = kesifIs.await()

        // Kaynakları sırayla harmanla: YouTube önerisi, benzer, YouTube, abonelik, benzer, keşif…
        val kuyruklar = (listOf(yt.toMutableList()) + benzer.map { it.toMutableList() } + listOf(abone.toMutableList(), kesif.toMutableList()))
            .filter { it.isNotEmpty() }
        val karisik = ArrayList<Video>()
        val gorulmus = HashSet<String>()
        var tur = 0
        while (kuyruklar.any { it.isNotEmpty() } && tur < 200) {
            kuyruklar.forEachIndexed { i, l ->
                // YouTube'un kendi önerilerinden her turda iki tane al
                repeat(if (i == 0 && yt.isNotEmpty()) 2 else 1) {
                    while (l.isNotEmpty()) {
                        val v = l.removeAt(0)
                        if (v.id !in gorulmus && v.id !in izlenen) { gorulmus += v.id; karisik += v; break }
                    }
                }
            }
            tur++
        }
        // Son yenilemelerde üstte gösterilenler sona
        val simdi = System.currentTimeMillis()
        gosterilen.entries.removeAll { simdi - it.value > 6 * 3600_000L }
        val (eski, yeni) = karisik.partition { it.id in gosterilen }
        val sonuc = yeni + eski
        sonuc.take(16).forEach { gosterilen[it.id] = simdi }
        while (gosterilen.size > 600) gosterilen.remove(gosterilen.keys.first())
        sonuc
    }

    private var moreJob: Job? = null

    fun moreHome() {
        if (moreJob?.isActive == true || homeLoading.value) return
        val next = homeNext
        val page = homePage
        val q = homeQuery
        moreJob = viewModelScope.launch {
            runCatching {
                when {
                    next != null -> {
                        val f = withContext(Dispatchers.IO) { Inner.more(next) }
                        homeNext = f.next
                        f.videos
                    }
                    page != null && q != null && !q.startsWith("#") -> {
                        val p = Yt.search(q, Yt.Filter.VIDEOS, page)
                        homePage = p.next
                        p.items.filterIsInstance<Video>()
                    }
                    q == null -> {
                        // Sonsuz akış: izlenen son videolara benzerlerden devam
                        val seeds = (home.value.takeLast(15).map { it.id } + Store.history.value.take(20).map { it.id }).shuffled().take(4)
                        kotlinx.coroutines.coroutineScope { seeds.map { id -> async { ilgili(id).shuffled().take(8) } }.awaitAll() }.flatten().shuffled()
                    }
                    else -> emptyList()
                }
            }.onSuccess { l -> home.value = (home.value + l).distinctBy { it.id } }
        }
    }

    // ---------- Abonelikler ----------

    fun refreshSubs(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - subsLoadedAt < 5 * 60_000 && subsFeed.value.isNotEmpty()) return
        subsLoading.value = true
        viewModelScope.launch {
            runCatching {
                if (Account.signedIn) {
                    launch { Account.syncSubs() }
                    val f = withContext(Dispatchers.IO) { Inner.subscriptionsFeed() }
                    subsNext = f.next
                    f.videos
                } else {
                    subsNext = null
                    Rss.feed(Store.subs.value)
                }
            }.onSuccess { subsFeed.value = it; subsLoadedAt = System.currentTimeMillis() }
            subsLoading.value = false
        }
    }

    private var subsMoreJob: Job? = null

    fun moreSubs() {
        val n = subsNext ?: return
        if (subsMoreJob?.isActive == true) return
        subsMoreJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Inner.more(n) } }.onSuccess { f ->
                subsNext = f.next
                subsFeed.value = (subsFeed.value + f.videos).distinctBy { it.id }
            }
        }
    }
}

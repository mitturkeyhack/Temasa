package com.erkam.temasa.play

import android.content.Context
import android.content.SharedPreferences
import com.erkam.temasa.yt.Net
import com.erkam.temasa.yz.Evren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.io.File
import java.util.Collections

/**
 * Temâşâ altyazıları: YouTube altyazısını kendimiz indirip kendimiz çizeriz (oynatıcıya karışmaz, hata vermez).
 * Çift dil: üstte orijinal, altta Türkçe.
 * Türkçe kaynağı, en iyiden başlayarak: videonun kendi Türkçe altyazısı → yapay zekâ çevirisi → YouTube'un otomatik çevirisi.
 */
object Altyazi {
    enum class Mod(val ad: String) { CIFT("Çift dil"), TURKCE("Sadece Türkçe"), ORIJINAL("Sadece orijinal"), KAPALI("Kapalı") }
    enum class Kaynak { YOK, VIDEONUN, YAPAY_ZEKA, YOUTUBE }

    data class Satir(val bas: Long, val son: Long, val metin: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: SharedPreferences
    private lateinit var dizin: File

    /** Görünüm tercihleri */
    val mod = MutableStateFlow(Mod.CIFT)
    val boyut = MutableStateFlow(2)            // 0..4

    /** Şu anki video */
    val videoId = MutableStateFlow<String?>(null)
    val yukleniyor = MutableStateFlow(false)
    val yok = MutableStateFlow(false)          // bu videoda altyazı yok
    val orijinal = MutableStateFlow<List<Satir>>(emptyList())
    val orijinalDil = MutableStateFlow("")      // "İngilizce"
    val orijinalTurkce = MutableStateFlow(false)
    /** Hazır Türkçe liste (videonun kendisi ya da YouTube çevirisi), zamanla eşleşir */
    val turkceListe = MutableStateFlow<List<Satir>>(emptyList())
    val turkceKaynak = MutableStateFlow(Kaynak.YOK)
    /** Yapay zekâ çevirisi: orijinal satır sırası → Türkçe */
    val yzCeviri = MutableStateFlow<Map<Int, String>>(emptyMap())
    val yzCalisiyor = MutableStateFlow(false)

    private var is_: Job? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("altyazi", Context.MODE_PRIVATE)
        dizin = File(ctx.filesDir, "altyazi").apply { mkdirs() }
        mod.value = runCatching { Mod.valueOf(prefs.getString("mod", Mod.CIFT.name)!!) }.getOrDefault(Mod.CIFT)
        boyut.value = prefs.getInt("boyut", 2).coerceIn(0, 4)
        // Eski önbellek temizliği: 60 günden eski çeviriler
        scope.launch(Dispatchers.IO) {
            runCatching { dizin.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60L * 86400_000 }?.forEach { it.delete() } }
        }
    }

    /** Altyazı açık mı (bu oturumda) */
    val acik = MutableStateFlow(false)
    /** Altyazı ayar penceresi açık mı */
    val panel = MutableStateFlow(false)

    fun modSec(m: Mod) {
        if (m == Mod.KAPALI) { acik.value = false } else { mod.value = m; acik.value = true; prefs.edit().putString("mod", m.name).apply() }
        com.erkam.temasa.data.Store.updateSettings { it.copy(subtitles = m != Mod.KAPALI) }
    }

    fun boyutSec(b: Int) { boyut.value = b.coerceIn(0, 4); prefs.edit().putInt("boyut", boyut.value).apply() }

    /** Görünen mod (kapalıysa KAPALI) */
    fun etkinMod(): Mod = if (acik.value) mod.value else Mod.KAPALI

    // ======================= Yükleme =======================

    fun yukle(id: String, info: StreamInfo) {
        if (videoId.value == id && orijinal.value.isNotEmpty()) return
        is_?.cancel()
        videoId.value = id
        acik.value = com.erkam.temasa.data.Store.settings.value.subtitles
        orijinal.value = emptyList(); turkceListe.value = emptyList(); yzCeviri.value = emptyMap()
        turkceKaynak.value = Kaynak.YOK; yok.value = false; orijinalTurkce.value = false; orijinalDil.value = ""
        yukleniyor.value = true
        is_ = scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { izleriHazirla(id, info) } }
            yukleniyor.value = false
            if (r.isFailure || orijinal.value.isEmpty()) { yok.value = true; return@launch }
            cevirmen(id, info.name ?: "")
        }
    }

    fun temizle() { is_?.cancel(); videoId.value = null; orijinal.value = emptyList(); turkceListe.value = emptyList(); yzCeviri.value = emptyMap() }

    private fun json3(s: SubtitlesStream): String =
        s.content.let { u -> if (u.contains("fmt=")) u.replace(Regex("fmt=[a-z0-9]+"), "fmt=json3") else "$u&fmt=json3" }

    private fun indir(url: String): JSONObject? = runCatching {
        Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val b = r.body.string()
            if (b.trimStart().startsWith("{")) JSONObject(b) else null
        }
    }.getOrNull()

    private fun izleriHazirla(id: String, info: StreamInfo) {
        val izler = info.subtitles.filter { it.isUrl && it.content.contains("timedtext") }
            .distinctBy { (it.languageTag ?: "") + it.isAutoGenerated }
        if (izler.isEmpty()) return
        // Konuşulan dil: otomatik altyazının dili; yoksa ilk el yazması altyazı
        val oto = izler.firstOrNull { it.isAutoGenerated }
        val konusulan = (oto?.languageTag ?: izler.first().languageTag ?: "").substringBefore('-')
        val el = izler.filter { !it.isAutoGenerated }
        // Orijinal: konuşulan dilde el yazması (noktalamalı, düzgün) > otomatik > İngilizce el yazması > ilki
        val orjIz = el.firstOrNull { it.languageTag?.substringBefore('-') == konusulan }
            ?: oto ?: el.firstOrNull { it.languageTag?.startsWith("en") == true } ?: izler.first()
        val orjDil = orjIz.languageTag?.substringBefore('-') ?: konusulan
        val o = indir(json3(orjIz)) ?: return
        val satirlar = if (orjIz.isAutoGenerated) otomatikCoz(o) else elYazmasiCoz(o)
        if (satirlar.isEmpty()) return
        orijinalDil.value = runCatching { java.util.Locale.forLanguageTag(orjDil).getDisplayLanguage(java.util.Locale.forLanguageTag("tr")).replaceFirstChar { it.uppercase() } }.getOrDefault(orjDil)
        orijinal.value = satirlar
        if (orjDil == "tr") { orijinalTurkce.value = true; return }

        // Türkçe: videonun kendi altyazısı
        el.firstOrNull { it.languageTag?.startsWith("tr") == true && it !== orjIz }?.let { tr ->
            indir(json3(tr))?.let { elYazmasiCoz(it) }?.takeIf { it.isNotEmpty() }?.let {
                turkceListe.value = it; turkceKaynak.value = Kaynak.VIDEONUN; return
            }
        }
        // Önbellekteki yapay zekâ çevirisi
        onbellekOku(id, satirlar.size)?.let { yzCeviri.value = it }
        // YouTube'un otomatik çevirisi (ara çözüm; olmazsa da sorun değil)
        indir(json3(orjIz) + "&tlang=tr")?.let { if (orjIz.isAutoGenerated) otomatikCoz(it).ifEmpty { elYazmasiCoz(it) } else elYazmasiCoz(it) }
            ?.takeIf { it.isNotEmpty() }?.let { turkceListe.value = it; turkceKaynak.value = Kaynak.YOUTUBE }
    }

    private fun temizMetin(s: String) = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    /** El yazması altyazı: her olay bir satır */
    private fun elYazmasiCoz(o: JSONObject): List<Satir> {
        val ev = o.optJSONArray("events") ?: return emptyList()
        val l = ArrayList<Satir>()
        for (i in 0 until ev.length()) {
            val e = ev.optJSONObject(i) ?: continue
            val segs = e.optJSONArray("segs") ?: continue
            val t = temizMetin((0 until segs.length()).joinToString("") { segs.optJSONObject(it)?.optString("utf8").orEmpty() })
            if (t.isEmpty()) continue
            val bas = e.optLong("tStartMs"); val sure = e.optLong("dDurationMs", 3000)
            l += Satir(bas, bas + sure, t)
        }
        return l.sortedBy { it.bas }
    }

    /** Otomatik altyazı: kelime kelime gelir; okunur cümle parçalarına böl */
    private fun otomatikCoz(o: JSONObject): List<Satir> {
        val ev = o.optJSONArray("events") ?: return emptyList()
        data class K(val t: Long, val w: String)
        val kelimeler = ArrayList<K>()
        for (i in 0 until ev.length()) {
            val e = ev.optJSONObject(i) ?: continue
            if (e.optInt("aAppend") == 1) continue
            val segs = e.optJSONArray("segs") ?: continue
            val bas = e.optLong("tStartMs")
            for (j in 0 until segs.length()) {
                val s = segs.optJSONObject(j) ?: continue
                val w = s.optString("utf8").replace('\n', ' ')
                if (w.isBlank()) continue
                kelimeler += K(bas + s.optLong("tOffsetMs"), w)
            }
        }
        if (kelimeler.isEmpty()) return emptyList()
        kelimeler.sortBy { it.t }
        val parcalar = ArrayList<Pair<Long, StringBuilder>>()
        val sonlar = ArrayList<Long>()
        var onceki = -1L
        for (k in kelimeler) {
            val sb = parcalar.lastOrNull()?.second
            val uzun = sb != null && sb.length >= 72
            val cumleSonu = sb != null && sb.length >= 28 && sb.trimEnd().lastOrNull()?.let { it in ".?!" } == true
            val bosluk = onceki >= 0 && k.t - onceki > 1100
            if (sb == null || uzun || cumleSonu || bosluk) { parcalar += k.t to StringBuilder(); sonlar += k.t }
            val p = parcalar.last().second
            if (p.isEmpty()) p.append(k.w.trimStart()) else if (k.w.startsWith(" ")) p.append(k.w) else p.append(' ').append(k.w)
            sonlar[sonlar.lastIndex] = k.t
            onceki = k.t
        }
        return parcalar.mapIndexed { i, (bas, sb) ->
            val sonraki = parcalar.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
            Satir(bas, minOf(sonraki, sonlar[i] + 1800), temizMetin(sb.toString()))
        }.filter { it.metin.isNotEmpty() }
    }

    /** Verilen anda görünecek satırın sırası (yoksa -1) */
    fun sira(l: List<Satir>, ms: Long): Int {
        var lo = 0; var hi = l.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (l[m].bas <= ms) { r = m; lo = m + 1 } else hi = m - 1 }
        return if (r >= 0 && ms < l[r].son) r else -1
    }

    // ======================= Yapay zekâ çevirisi =======================

    private const val PARCA = 16

    private val CEVIRI = """
Sen profesyonel bir altyazı çevirmenisin. Numaralı altyazı satırlarını akıcı, doğal ve doğru Türkçeye çevir.
- Her satırı kendi numarasıyla ayrı satırda yaz: "12| çeviri". Numaraları ve satır sayısını koru; birleştirme ya da atlama yapma.
- Satırlar bir cümlenin parçaları olabilir: bağlamı gözet, ama her parçanın çevirisi kendi satırında kalsın ve tek başına okunabilsin.
- Kısa ve okunur altyazı dili kullan. Özel isimleri, sayıları ve birimleri doğru aktar; deyimleri Türkçedeki karşılığıyla ver.
- Konuşma tanıma hatası gibi duran kelimeleri bağlama göre düzelterek çevir.
- [Music], [Applause] gibi etiketleri [Müzik], [Alkış] diye yaz.
Sadece çeviri satırlarını yaz, başka hiçbir şey ekleme.
""".trimIndent()

    private val yapilan = Collections.synchronizedSet(HashSet<Int>())
    private val bitti = Collections.synchronizedSet(HashSet<Int>())
    private val deneme = HashMap<Int, Int>()

    /** Ne zaman Türkçeye yapay zekâ gerekir */
    private fun yzGerekli(): Boolean =
        !orijinalTurkce.value && turkceKaynak.value != Kaynak.VIDEONUN && acik.value && mod.value != Mod.ORIJINAL && Evren.hazir

    private suspend fun cevirmen(id: String, baslik: String) {
        yapilan.clear(); bitti.clear(); deneme.clear()
        // Önbellekten gelen parçalar hazır sayılsın
        val l = orijinal.value
        val parcaSayisi = (l.size + PARCA - 1) / PARCA
        for (p in 0 until parcaSayisi) {
            val r = p * PARCA until minOf(l.size, (p + 1) * PARCA)
            if (r.all { it in yzCeviri.value }) bitti += p
        }
        while (scope.isActive && videoId.value == id) {
            if (yzGerekli()) {
                val ms = runCatching { Player.exo.currentPosition }.getOrDefault(0L)
                // İzlenen yerden 6 dakika ilerisine kadar, en yakından başla
                val simdiki = l.indexOfFirst { it.son >= ms - 3000 }.coerceAtLeast(0) / PARCA
                val sinir = l.indexOfLast { it.bas <= ms + 6 * 60_000 }.coerceAtLeast(0) / PARCA
                val sira = (simdiki..sinir).filter { it !in bitti && it !in yapilan && (deneme[it] ?: 0) < 3 }
                for (p in sira) {
                    if (yapilan.size >= 3) break
                    yapilan += p
                    scope.launch { parcaCevir(id, baslik, p) }
                }
                yzCalisiyor.value = yapilan.isNotEmpty()
            } else yzCalisiyor.value = false
            delay(600)
        }
    }

    private suspend fun parcaCevir(id: String, baslik: String, p: Int) {
        val l = orijinal.value
        val bas = p * PARCA; val son = minOf(l.size, (p + 1) * PARCA)
        val baglam = (maxOf(0, bas - 4) until bas).joinToString("\n") { i -> "${l[i].metin}" + (yzCeviri.value[i]?.let { " → $it" } ?: "") }
        val metin = buildString {
            append("Video: $baslik\nKaynak dil: ${orijinalDil.value}\n")
            if (baglam.isNotBlank()) append("Önceki satırlar (sadece bağlam, çevirme):\n$baglam\n\n")
            append("Çevrilecek satırlar:\n")
            for (i in bas until son) append("$i| ${l[i].metin}\n")
        }
        val desen = Regex("^\\s*(\\d+)\\s*[|:.)\\-]\\s*(.+)$")
        fun isle(yazi: String, tamam: Boolean) {
            val satirlar = yazi.lines().let { if (tamam) it else it.dropLast(1) }
            val yeni = HashMap<Int, String>()
            for (s in satirlar) {
                val m = desen.find(s) ?: continue
                val i = m.groupValues[1].toIntOrNull() ?: continue
                if (i in bas until son) yeni[i] = m.groupValues[2].trim()
            }
            if (yeni.isNotEmpty() && videoId.value == id) yzCeviri.update { it + yeni }
        }
        val r = runCatching {
            val t = Evren.akis(CEVIRI, metin, maxToken = 60 + (son - bas) * 70) { k -> isle(k, false) }
            isle(t, true)
        }
        if (videoId.value != id) return
        val eksik = (bas until son).count { it !in yzCeviri.value }
        if (r.isSuccess && eksik <= 1) { bitti += p; onbellekYaz(id) }
        else deneme[p] = (deneme[p] ?: 0) + 1
        yapilan -= p
    }

    private fun onbellekDosya(id: String) = File(dizin, "$id.tr.json")

    private fun onbellekOku(id: String, adet: Int): Map<Int, String>? = runCatching {
        val o = JSONObject(onbellekDosya(id).readText())
        if (o.optInt("adet") != adet) return null
        val m = o.getJSONObject("tr")
        m.keys().asSequence().associate { it.toInt() to m.getString(it) }
    }.getOrNull()

    private fun onbellekYaz(id: String) {
        val m = yzCeviri.value; val adet = orijinal.value.size
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tr = JSONObject(); m.forEach { (k, v) -> tr.put(k.toString(), v) }
                onbellekDosya(id).writeText(JSONObject().put("adet", adet).put("tr", tr).toString())
            }
        }
    }

    /** Ayar penceresindeki durum satırı */
    fun durumMetni(): String? = when {
        yukleniyor.value -> "Altyazı yükleniyor…"
        yok.value -> "Bu videoda altyazı yok"
        orijinalTurkce.value -> "Video Türkçe; altyazı videonun kendisinden"
        turkceKaynak.value == Kaynak.VIDEONUN -> "Türkçe: videonun kendi altyazısı"
        !Evren.hazir -> if (turkceKaynak.value == Kaynak.YOUTUBE) "Türkçe: YouTube çevirisi (yapay zekâ bağlı değil)" else "Yapay zekâ bağlı değil; Türkçe çeviri yok"
        else -> {
            val l = orijinal.value
            val ms = runCatching { Player.exo.currentPosition }.getOrDefault(0L)
            val i = l.indexOfFirst { it.son >= ms }.coerceAtLeast(0)
            var j = i
            while (j < l.size && j in yzCeviri.value) j++
            val dk = if (j >= l.size) -1L else ((l[j].bas - ms) / 60_000).coerceAtLeast(0)
            when {
                j >= l.size && yzCeviri.value.isNotEmpty() -> "Yapay zekâ çevirisi: buradan sonrası hazır"
                dk >= 1 -> "Yapay zekâ çevirisi: ileriki $dk dk hazır"
                yzCalisiyor.value -> "Yapay zekâ çeviriyor…"
                else -> "Yapay zekâ çevirisi"
            }
        }
    }
}

package com.erkam.temasa.yz

import android.content.Context
import com.erkam.temasa.data.Video
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.Comments
import com.erkam.temasa.yt.Net
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Temâşâ yapay zekâsı (EVREN):
 * - Ne izlemek istediğini anlatınca YouTube'da arar, en uygun videoları nedenleriyle seçer
 * - Videoyu altyazısından özetler, önemli anları dakikalarıyla çıkarır, sorulara cevap verir
 * - Yorumları okuyup özetler
 */
object Yz {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) {}

    private fun hataMetni(e: Throwable): String = (e as? Evren.EvrenHatasi)?.kullaniciya
        ?: "Yapay zekâya ulaşılamadı. İnternetini kontrol edip tekrar dene."

    // =============== Video bul (sohbet) ===============

    data class Bulunan(val video: Video, val neden: String)
    data class Mesaj(
        val ben: Boolean,
        val metin: String,
        val videolar: List<Bulunan> = emptyList(),
        val not: String? = null,
        val hata: Boolean = false,
    )

    val sohbet = MutableStateFlow<List<Mesaj>>(emptyList())
    /** Çalışırken ne yaptığı ("Arıyorum…"); boşsa boşta */
    val calisiyor = MutableStateFlow<String?>(null)

    fun yeniSohbet() { if (calisiyor.value == null) sohbet.value = emptyList() }

    private val PLAN = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Kullanıcı ne izlemek istediğini anlatır; sen YouTube'da aranacak sorguları hazırlarsın.
- 2 ile 4 arasında kısa, birbirinden farklı arama sorgusu yaz. Kullanıcının dilini ve konusunu koru; gerekirse eş anlamlı ve ilgili ifadeler kullan.
- Kullanıcı süre belirttiyse dakika olarak "en_fazla_dk" / "en_az_dk" ver, yoksa null.
- "Daha fazla bul" gibi isteklerde önceki konuyla devam et, öncekilerden farklı sorgular üret.
- Kullanıcı video aramıyorsa (selam, teşekkür, genel soru) "aramalar" boş olsun ve "cevap" alanında kısa, nazik bir Türkçe cevap ver.
Sadece JSON döndür: {"aramalar": ["..."], "en_fazla_dk": null, "en_az_dk": null, "cevap": ""}
""".trimIndent()

    private val SECIM = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Kullanıcının isteği ve numaralı aday videolar verilecek.
- İsteğe en uygun 3 ile 6 videoyu seç; en uygun olan önce gelsin. Uygun olan azsa daha az seç.
- Başlık, kanal, süre, izlenme ve tarihe bakarak karar ver. Tıklama tuzağı gibi duran başlıkları ve konu dışı olanları seçme.
- Her seçim için en fazla 8 kelimelik Türkçe "neden" yaz (ör. "Konuyu üç başlıkta, sade anlatıyor").
- "cevap": seçimleri tanıtan tek kısa Türkçe cümle. Uygun video yoksa bunu söyle ve "secim" boş olsun.
Sadece JSON döndür: {"cevap": "...", "secim": [{"n": 0, "neden": "..."}]}
""".trimIndent()

    fun gonder(metin: String) {
        val t = metin.trim()
        if (t.isEmpty() || calisiyor.value != null) return
        sohbet.update { it + Mesaj(true, t) }
        scope.launch { bul(t) }
    }

    private suspend fun bul(istek: String) {
        calisiyor.value = "Düşünüyorum…"
        val sonuc = runCatching {
            // Önceki konuşma (kısaca)
            val gecmis = sohbet.value.dropLast(1).takeLast(8).joinToString("\n") { m ->
                if (m.ben) "Kullanıcı: ${m.metin}"
                else "Sen: ${m.metin}" + if (m.videolar.isNotEmpty()) " [gösterilen: ${m.videolar.joinToString("; ") { it.video.title.take(60) }}]" else ""
            }
            val planCevap = Evren.json(Evren.sor(PLAN, (if (gecmis.isNotBlank()) "Önceki konuşma:\n$gecmis\n\n" else "") + "Kullanıcının son mesajı: $istek", maxToken = 400))
                ?: throw IllegalStateException("plan")
            val aramalar = planCevap.optJSONArray("aramalar")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } } }.orEmpty().take(4)
            if (aramalar.isEmpty()) {
                sohbet.update { it + Mesaj(false, planCevap.optString("cevap").ifBlank { "Ne izlemek istediğini biraz anlatır mısın? Senin için bulayım." }) }
                return@runCatching
            }
            val enFazla = planCevap.optInt("en_fazla_dk", 0).takeIf { it > 0 }
            val enAz = planCevap.optInt("en_az_dk", 0).takeIf { it > 0 }

            calisiyor.value = "YouTube'da arıyorum…"
            val gosterilen = sohbet.value.flatMap { m -> m.videolar.map { it.video.id } }.toSet()
            val bulunanlar = withContext(Dispatchers.IO) {
                aramalar.map { q -> async { runCatching { Yt.search(q, Yt.Filter.VIDEOS).items.filterIsInstance<Video>() }.getOrDefault(emptyList()) } }.awaitAll()
            }
            // Sorgulardan sırayla birer birer al: her sorgunun iyileri öne çıksın
            val adaylar = LinkedHashMap<String, Video>()
            val enUzun = bulunanlar.maxOfOrNull { it.size } ?: 0
            for (i in 0 until enUzun) for (l in bulunanlar) l.getOrNull(i)?.let { v ->
                val dk = v.duration / 60.0
                val uygun = !v.live && v.id !in gosterilen && (v.duration == 0L || v.duration > 61) &&
                    (enFazla == null || v.duration == 0L || dk <= enFazla + 0.5) && (enAz == null || dk >= enAz)
                if (uygun) adaylar.putIfAbsent(v.id, v)
            }
            val liste = adaylar.values.take(40)
            if (liste.isEmpty()) {
                sohbet.update { it + Mesaj(false, "Bu isteğe uygun video bulamadım. Biraz farklı anlatır mısın?", not = notMetni(aramalar, bulunanlar.sumOf { it.size })) }
                return@runCatching
            }

            calisiyor.value = "${liste.size} videoya bakıyorum…"
            val adayMetni = liste.mapIndexed { i, v ->
                "$i) ${v.title} | Kanal: ${v.channel}" +
                    (if (v.duration > 0) " | ${v.duration / 60} dk ${v.duration % 60} sn" else "") +
                    (v.viewsText?.let { " | $it" } ?: if (v.views >= 0) " | ${v.views} izlenme" else "") +
                    (v.ago?.let { " | $it" } ?: "")
            }.joinToString("\n")
            val secim = Evren.json(Evren.sor(SECIM, "Kullanıcının isteği: $istek\n" + (if (gecmis.isNotBlank()) "Önceki konuşma:\n$gecmis\n" else "") + "\nAdaylar:\n$adayMetni", maxToken = 700))
                ?: throw IllegalStateException("secim")
            val secilen = secim.optJSONArray("secim")?.let { a ->
                (0 until a.length()).mapNotNull { j ->
                    val o = a.optJSONObject(j) ?: return@mapNotNull null
                    val v = liste.getOrNull(o.optInt("n", -1)) ?: return@mapNotNull null
                    Bulunan(v, o.optString("neden").trim())
                }
            }.orEmpty().distinctBy { it.video.id }.take(6)
            sohbet.update {
                it + Mesaj(
                    false,
                    secim.optString("cevap").ifBlank { if (secilen.isEmpty()) "Uygun video bulamadım." else "Senin için ${secilen.size} video buldum:" },
                    secilen,
                    not = notMetni(aramalar, liste.size),
                )
            }
        }
        sonuc.onFailure { e -> sohbet.update { it + Mesaj(false, hataMetni(e), hata = true) } }
        calisiyor.value = null
    }

    private fun notMetni(aramalar: List<String>, incelenen: Int): String {
        val ilk = aramalar.take(2).joinToString(", ") { "\"$it\"" }
        val kalan = aramalar.size - 2
        return "$ilk" + (if (kalan > 0) " ve $kalan arama daha" else "") + " yapıldı · $incelenen video incelendi"
    }

    // =============== Videoyu anla: özet, önemli anlar, soru ===============

    data class An(val ms: Long, val baslik: String, val aciklama: String)
    data class Ozet(val ozet: String, val onecikanlar: List<String>, val anlar: List<An>, val sorular: List<String>, val altyazidan: Boolean)
    data class Soru(val soru: String, val cevap: String? = null, val ms: Long? = null, val hata: Boolean = false)

    class VideoBilgisi {
        val ozet = MutableStateFlow<Ozet?>(null)
        val ozetHata = MutableStateFlow<String?>(null)
        val ozetleniyor = MutableStateFlow(false)
        val sorular = MutableStateFlow<List<Soru>>(emptyList())
        val soruyor = MutableStateFlow(false)
        @Volatile var metin: String? = null
        @Volatile var altyazili = false
    }

    private val videolar = object : LinkedHashMap<String, VideoBilgisi>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoBilgisi>?) = size > 30
    }

    fun bilgi(id: String): VideoBilgisi = synchronized(videolar) { videolar.getOrPut(id) { VideoBilgisi() } }

    private val OZET = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Bir videonun başlığı, açıklaması ve (varsa) zaman damgalı altyazısı verilecek.
Türkçe olarak şunları hazırla (video başka dilde olsa da cevap Türkçe olsun):
- "ozet": videonun ne anlattığını 3-5 cümleyle, sade ve akıcı anlat. Uydurma; sadece videoda olanı yaz.
- "onecikanlar": en önemli 3-5 nokta, her biri tek kısa cümle.
- "anlar": videonun bölümleri, 4-8 tane, sırayla. "t" başladığı yer ("m:ss" ya da "s:dd:ss"), "baslik" en fazla 5 kelime, "aciklama" tek kısa cümle. Altyazı yoksa boş bırak.
- "sorular": izleyicinin bu videoya özel sorabileceği 3 kısa soru.
Sadece JSON döndür: {"ozet": "...", "onecikanlar": ["..."], "anlar": [{"t": "0:00", "baslik": "...", "aciklama": "..."}], "sorular": ["..."]}
""".trimIndent()

    private val SORU = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Bir videonun bilgisi ve zaman damgalı altyazısı verilecek; kullanıcı bu video hakkında soru soruyor.
- Cevabı videodan bul; Türkçe, açık ve en fazla 5-6 cümleyle yaz.
- Cevap videonun belli bir yerindeyse "t" alanına o yerin zamanını yaz ("m:ss"); yoksa null.
- Videoda olmayan bir şey sorulursa bunu dürüstçe söyle; istersen genel bilgini "videoda geçmiyor ama" diye belirterek ekle.
Sadece JSON döndür: {"cevap": "...", "t": "m:ss"}
""".trimIndent()

    fun ozetle(v: Video, yeniden: Boolean = false) {
        val b = bilgi(v.id)
        if (b.ozetleniyor.value || (b.ozet.value != null && !yeniden)) return
        b.ozetleniyor.value = true; b.ozetHata.value = null
        scope.launch {
            val r = runCatching {
                val m = metinHazirla(v, b)
                val o = Evren.json(Evren.sor(OZET, m, maxToken = 1600)) ?: throw IllegalStateException("ozet")
                val anlar = o.optJSONArray("anlar")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val x = a.optJSONObject(i) ?: return@mapNotNull null
                        val ms = zaman(x.optString("t")) ?: return@mapNotNull null
                        An(ms, x.optString("baslik").trim(), x.optString("aciklama").trim())
                    }
                }.orEmpty().filter { b.altyazili }.sortedBy { it.ms }
                Ozet(
                    o.optString("ozet").trim(),
                    liste(o.optJSONArray("onecikanlar")),
                    anlar,
                    liste(o.optJSONArray("sorular")).take(3),
                    b.altyazili,
                )
            }
            r.onSuccess { b.ozet.value = it }
            r.onFailure { e -> b.ozetHata.value = hataMetni(e) }
            b.ozetleniyor.value = false
        }
    }

    fun sor(v: Video, soru: String) {
        val t = soru.trim()
        val b = bilgi(v.id)
        if (t.isEmpty() || b.soruyor.value) return
        b.sorular.update { it + Soru(t) }
        b.soruyor.value = true
        scope.launch {
            val r = runCatching {
                val m = metinHazirla(v, b)
                val onceki = b.sorular.value.dropLast(1).filter { it.cevap != null }.takeLast(4)
                    .joinToString("\n") { "Soru: ${it.soru}\nCevap: ${it.cevap}" }
                val o = Evren.json(Evren.sor(SORU, m + (if (onceki.isNotBlank()) "\n\nÖnceki sorular:\n$onceki" else "") + "\n\nKullanıcının sorusu: $t", maxToken = 700))
                    ?: throw IllegalStateException("soru")
                o.optString("cevap").trim() to (o.optString("t").takeIf { b.altyazili }?.let { zaman(it) })
            }
            b.sorular.update { l ->
                val son = l.lastOrNull() ?: return@update l
                l.dropLast(1) + r.fold({ (c, ms) -> son.copy(cevap = c.ifBlank { "Bunu videodan çıkaramadım." }, ms = ms) }, { e -> son.copy(cevap = hataMetni(e), hata = true) })
            }
            b.soruyor.value = false
        }
    }

    private fun liste(a: JSONArray?): List<String> =
        a?.let { x -> (0 until x.length()).mapNotNull { x.optString(it).trim().ifBlank { null } } }.orEmpty()

    /** "6:10", "1:02:30", "370" → milisaniye */
    fun zaman(s: String?): Long? {
        val p = s?.trim()?.removePrefix("[")?.removeSuffix("]")?.split(':')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        if (p.isEmpty() || p.size > 3) return null
        return p.fold(0L) { acc, x -> acc * 60 + x } * 1000
    }

    fun zamanYaz(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Yapay zekâya verilecek video metni: başlık, açıklama, zaman damgalı altyazı */
    private suspend fun metinHazirla(v: Video, b: VideoBilgisi): String {
        b.metin?.let { return it }
        return withContext(Dispatchers.IO) {
            val info: StreamInfo = Player.info.value?.takeIf { it.id == v.id } ?: Yt.info(v.id)
            val aciklama = runCatching { info.description?.content.orEmpty() }.getOrDefault("")
                .replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").take(2500)
            val altyazi = runCatching { altyaziCek(info) }.getOrNull()
            b.altyazili = !altyazi.isNullOrBlank()
            val dakika = if (info.duration > 0) "${info.duration / 60} dk ${info.duration % 60} sn" else "bilinmiyor"
            val m = buildString {
                append("Başlık: ${info.name}\nKanal: ${info.uploaderName}\nSüre: $dakika\n")
                if (aciklama.isNotBlank()) append("Açıklama:\n$aciklama\n")
                if (!altyazi.isNullOrBlank()) append("\nAltyazı (zaman damgalı):\n$altyazi")
                else append("\n(Bu videonun altyazısı yok; sadece başlık ve açıklamaya göre konuş, bunu belirt.)")
            }
            b.metin = m
            m
        }
    }

    /** YouTube altyazısını (json3) indirip "[m:ss] metin" satırlarına çevirir */
    private fun altyaziCek(info: StreamInfo): String? {
        val subs = info.subtitles.filter { it.isUrl && it.content.contains("timedtext") }
        if (subs.isEmpty()) return null
        // Önce konuşulan dilin otomatik altyazısı (en doğru zamanlama), yoksa Türkçe, yoksa ilki
        val sec = subs.firstOrNull { it.isAutoGenerated }
            ?: subs.firstOrNull { it.languageTag?.startsWith("tr") == true }
            ?: subs.first()
        val url = sec.content.let { u -> if (u.contains("fmt=")) u.replace(Regex("fmt=[a-z0-9]+"), "fmt=json3") else "$u&fmt=json3" }
        val govde = Net.client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else null } ?: return null
        val olaylar = JSONObject(govde).optJSONArray("events") ?: return null
        // 20 saniyelik bloklar
        val bloklar = ArrayList<Pair<Long, StringBuilder>>()
        for (i in 0 until olaylar.length()) {
            val e = olaylar.optJSONObject(i) ?: continue
            val segs = e.optJSONArray("segs") ?: continue
            val t = e.optLong("tStartMs")
            val yazi = (0 until segs.length()).joinToString("") { segs.optJSONObject(it)?.optString("utf8").orEmpty() }
                .replace('\n', ' ').trim()
            if (yazi.isEmpty()) continue
            if (bloklar.isEmpty() || t - bloklar.last().first >= 20_000) bloklar.add(t to StringBuilder())
            val sb = bloklar.last().second
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(yazi)
        }
        if (bloklar.isEmpty()) return null
        // Çok uzunsa her bloğu orantılı kısalt (yaklaşık 28 bin karakter)
        val toplam = bloklar.sumOf { it.second.length }
        val sinir = 28_000
        val oran = if (toplam > sinir) sinir.toDouble() / toplam else 1.0
        return bloklar.joinToString("\n") { (t, sb) ->
            val s = sb.toString().replace(Regex("\\s+"), " ")
            "[${zamanYaz(t)}] " + if (oran < 1.0) s.take((s.length * oran).toInt().coerceAtLeast(40)) else s
        }
    }

    // =============== Yorumların özeti ===============

    data class YorumOzeti(val ozet: String, val etiketler: List<String>, val okunan: Int)

    val yorumOzetleri = MutableStateFlow<Map<String, YorumOzeti>>(emptyMap())
    val yorumOzetleniyor = MutableStateFlow<Set<String>>(emptySet())
    val yorumHata = MutableStateFlow<Map<String, String>>(emptyMap())

    private val YORUM = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Bir videonun başlığı ve izleyici yorumları verilecek.
- "ozet": izleyicilerin genel olarak ne düşündüğünü, sık tekrarlanan görüşleri ve soruları 2-4 cümleyle Türkçe özetle. Tek tek kişileri değil genel eğilimi anlat.
- "etiketler": en fazla 3 kısa etiket (ör. "Çok olumlu", "Sık soru: kaynak kitap").
Sadece JSON döndür: {"ozet": "...", "etiketler": ["..."]}
""".trimIndent()

    fun yorumlariOzetle(videoId: String, baslik: String, yeniden: Boolean = false) {
        if (videoId in yorumOzetleniyor.value || (videoId in yorumOzetleri.value && !yeniden)) return
        yorumOzetleniyor.update { it + videoId }
        yorumHata.update { it - videoId }
        scope.launch {
            val r = runCatching {
                val yorumlar = withContext(Dispatchers.IO) {
                    val l = ArrayList<String>()
                    var p = Comments.first(videoId)
                    l += p.items.map { it.text }
                    var n = 0
                    while (p.next != null && l.size < 200 && n < 9) { p = Comments.page(p.next!!); l += p.items.map { it.text }; n++ }
                    l
                }
                if (yorumlar.size < 3) throw Evren.EvrenHatasi("Özetlenecek kadar yorum yok.")
                val metin = yorumlar.mapIndexed { i, s -> "${i + 1}. ${s.replace('\n', ' ').take(300)}" }.joinToString("\n").take(24_000)
                val o = Evren.json(Evren.sor(YORUM, "Video: $baslik\n\nYorumlar:\n$metin", maxToken = 500)) ?: throw IllegalStateException("yorum")
                YorumOzeti(o.optString("ozet").trim(), liste(o.optJSONArray("etiketler")).take(3), yorumlar.size)
            }
            r.onSuccess { o -> yorumOzetleri.update { it + (videoId to o) } }
            r.onFailure { e -> yorumHata.update { it + (videoId to hataMetni(e)) } }
            yorumOzetleniyor.update { it - videoId }
        }
    }
}

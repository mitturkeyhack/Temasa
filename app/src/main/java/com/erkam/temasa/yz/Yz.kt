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
        /** Yapay zekânın sunduğu seçenekler (dokununca gönderilir) */
        val secenekler: List<String> = emptyList(),
    )

    val sohbet = MutableStateFlow<List<Mesaj>>(emptyList())
    /** Çalışırken ne yaptığı ("Arıyorum…"); boşsa boşta */
    val calisiyor = MutableStateFlow<String?>(null)

    fun yeniSohbet() { if (calisiyor.value == null) sohbet.value = emptyList() }

    private val PLAN = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın; kullanıcıya YouTube'dan izleyeceği şeyi bulursun. Samimi, kısa ve Türkçe konuş.
Her mesajda üç şeyden birini yap:

1) "soru": İstek genişse (ör. "bilim kurgu film", "film öner", "bir şey izlemek istiyorum", "komik bir şey") önce ne tür istediğini sor.
   - "cevap": tek kısa soru (ör. "Nasıl bir bilim kurgu olsun?").
   - "secenekler": isteğe özel 4-6 somut seçenek (ör. "Uzay macerası", "Zaman yolculuğu", "Yapay zekâ ve robotlar", "Distopya", "Uzaylı istilası") ve en sona "Fark etmez, sen seç".
   - Biçim belli değilse (film mi, fragman mı, özet mi) bunu da seçeneklere katabilirsin; ama izleme geçmişi zaten belli ediyorsa sorma.
   - Arka arkaya ikiden fazla soru sorma. Kullanıcı bir seçenek seçtiyse ya da "fark etmez" dediyse artık ara.
2) "ara": İstek yeterince belliyse YouTube'da aranacak 3-4 kısa, birbirinden farklı sorgu yaz.
   - Film istiyorsa tam filmi bul: sorgulara "tam film", "full izle", "türkçe dublaj tek parça" gibi ifadeler ekle; "en_az_dk" 60 olsun; "bicim" "tam film" olsun.
   - Dizi bölümü, belgesel, sohbet, ders, müzik gibi isteklerde "bicim"i ona göre yaz ve uygun süre sınırı koy.
   - Kullanıcı süre söylediyse "en_fazla_dk" / "en_az_dk" ver.
   - İzleme geçmişine bak: kullanıcı önerdiğin uzun videoları sonuna kadar izlediyse aynı biçimde (ör. tam film) devam et. Kısa izleyip bıraktıklarına benzerlerini önerme.
   - "Daha fazla bul" denirse aynı konu ve biçimde, öncekilerden farklı sorgular üret.
3) "sohbet": Video aramıyorsa (selam, teşekkür, genel soru) "cevap" alanında kısa bir cevap ver.

Sadece JSON döndür:
{"tur": "soru|ara|sohbet", "cevap": "", "secenekler": [], "aramalar": [], "bicim": "", "en_az_dk": null, "en_fazla_dk": null}
""".trimIndent()

    private val SECIM = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Kullanıcının isteği, istenen biçim ve numaralı aday videolar verilecek.
- İsteğe en uygun 3 ile 6 videoyu seç; en uygun olan önce gelsin. Uygun olan azsa daha az seç.
- Biçime sıkı uy: "tam film" isteniyorsa fragman, inceleme, özet, sahne, "en iyi 10" listesi ve kısa klipleri SEÇME; yalnızca filmin kendisi gibi duran uzun videoları seç.
- Başlık, kanal, süre, izlenme ve tarihe bakarak karar ver. Tıklama tuzağı gibi duran ve konu dışı olanları seçme.
- İzleme geçmişi verilmişse kullanıcının zevkine yakın olanları öne al; daha önce izlediklerini önerme.
- Her seçim için en fazla 8 kelimelik Türkçe "neden" yaz (ör. "Tek parça, Türkçe dublaj, 2 saat").
- "cevap": seçimleri tanıtan tek kısa Türkçe cümle. Uygun video yoksa bunu söyle ve "secim" boş olsun.
Sadece JSON döndür: {"cevap": "...", "secim": [{"n": 0, "neden": "..."}]}
""".trimIndent()

    fun gonder(metin: String) {
        val t = metin.trim()
        if (t.isEmpty() || calisiyor.value != null) return
        sohbet.update { it + Mesaj(true, t) }
        scope.launch { bul(t) }
    }

    /** Kullanıcının son izledikleri: ne kadarını izlediğiyle */
    private fun izlemeGecmisi(): String {
        val l = com.erkam.temasa.data.Store.history.value.take(12)
        if (l.isEmpty()) return ""
        return l.joinToString("\n") { v ->
            val p = com.erkam.temasa.data.Store.progress(v.id)
            "- ${v.title.take(80)} | ${v.channel}" + (if (v.duration > 0) " | ${v.duration / 60} dk" else "") +
                (p?.let { " | %${(it * 100).toInt()} izlendi" } ?: "")
        }
    }

    private suspend fun bul(istek: String) {
        calisiyor.value = "Düşünüyorum…"
        val sonuc = runCatching {
            // Önceki konuşma: gösterilen videolar ve ne kadarı izlendi
            val gecmis = sohbet.value.dropLast(1).takeLast(10).joinToString("\n") { m ->
                if (m.ben) "Kullanıcı: ${m.metin}"
                else "Sen: ${m.metin}" +
                    (if (m.secenekler.isNotEmpty()) " [seçenekler: ${m.secenekler.joinToString(", ")}]" else "") +
                    (if (m.videolar.isNotEmpty()) " [önerdiklerin: " + m.videolar.joinToString("; ") { b ->
                        val p = com.erkam.temasa.data.Store.progress(b.video.id)
                        b.video.title.take(60) + (if (b.video.duration > 0) " (${b.video.duration / 60} dk)" else "") +
                            (p?.let { " → kullanıcı %${(it * 100).toInt()} izledi" } ?: "")
                    } + "]" else "")
            }
            val izlenen = izlemeGecmisi()
            val baglam = buildString {
                if (izlenen.isNotBlank()) append("Kullanıcının son izledikleri:\n$izlenen\n\n")
                if (gecmis.isNotBlank()) append("Önceki konuşma:\n$gecmis\n\n")
            }
            val plan = Evren.json(Evren.sor(PLAN, baglam + "Kullanıcının son mesajı: $istek", maxToken = 350))
                ?: throw IllegalStateException("plan")
            val tur = plan.optString("tur")
            val aramalar = liste(plan.optJSONArray("aramalar")).take(4)
            if (tur == "soru" || (tur != "ara" && aramalar.isEmpty()) || aramalar.isEmpty()) {
                val sec = liste(plan.optJSONArray("secenekler")).take(7)
                sohbet.update { it + Mesaj(false, plan.optString("cevap").ifBlank { "Ne izlemek istediğini biraz anlatır mısın?" }, secenekler = sec) }
                return@runCatching
            }
            val bicim = plan.optString("bicim").trim()
            val enFazla = plan.optInt("en_fazla_dk", 0).takeIf { it > 0 }
            val enAz = plan.optInt("en_az_dk", 0).takeIf { it > 0 }

            calisiyor.value = "YouTube'da arıyorum…"
            val gosterilen = sohbet.value.flatMap { m -> m.videolar.map { it.video.id } }.toSet() +
                com.erkam.temasa.data.Store.history.value.take(50).map { it.id }
            val bulunanlar = withContext(Dispatchers.IO) {
                aramalar.map { q -> async { runCatching { Yt.search(q, Yt.Filter.VIDEOS).items.filterIsInstance<Video>() }.getOrDefault(emptyList()) } }.awaitAll()
            }
            fun uygunMu(v: Video, gevsek: Boolean): Boolean {
                val dk = v.duration / 60.0
                return !v.live && v.id !in gosterilen && (v.duration == 0L || v.duration > 61) &&
                    (enFazla == null || v.duration == 0L || dk <= enFazla + 0.5) &&
                    (enAz == null || (if (gevsek) dk >= enAz * 0.6 else dk >= enAz))
            }
            val adaylar = LinkedHashMap<String, Video>()
            val enUzun = bulunanlar.maxOfOrNull { it.size } ?: 0
            for (gevsek in listOf(false, true)) {
                for (i in 0 until enUzun) for (l in bulunanlar) l.getOrNull(i)?.let { v -> if (uygunMu(v, gevsek)) adaylar.putIfAbsent(v.id, v) }
                if (adaylar.size >= 6) break
            }
            val liste = adaylar.values.take(36)
            val incelenen = bulunanlar.sumOf { it.size }
            if (liste.isEmpty()) {
                sohbet.update { it + Mesaj(false, "Bu isteğe uygun video bulamadım. Biraz farklı anlatır mısın?", not = notMetni(aramalar, incelenen)) }
                return@runCatching
            }

            calisiyor.value = "${liste.size} videoyu inceliyorum…"
            val adayMetni = liste.mapIndexed { i, v ->
                "$i) ${v.title} | Kanal: ${v.channel}" +
                    (if (v.duration > 0) " | ${v.duration / 60} dk" else "") +
                    (v.viewsText?.let { " | $it" } ?: if (v.views >= 0) " | ${v.views} izlenme" else "") +
                    (v.ago?.let { " | $it" } ?: "")
            }.joinToString("\n")
            val secim = Evren.json(Evren.sor(SECIM,
                "Kullanıcının isteği: $istek\nİstenen biçim: ${bicim.ifBlank { "belirtilmedi" }}\n" + baglam + "Adaylar:\n$adayMetni", maxToken = 450))
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
                    not = notMetni(aramalar, incelenen),
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
    data class Ozet(val ozet: String, val onecikanlar: List<String>, val sorular: List<String>, val altyazidan: Boolean, val bitti: Boolean)
    data class Soru(val soru: String, val cevap: String? = null, val ms: Long? = null, val hata: Boolean = false, val bitti: Boolean = false)

    class VideoBilgisi {
        val ozet = MutableStateFlow<Ozet?>(null)
        val ozetHata = MutableStateFlow<String?>(null)
        val ozetleniyor = MutableStateFlow(false)
        val anlar = MutableStateFlow<List<An>?>(null)
        val anlarYukleniyor = MutableStateFlow(false)
        val sorular = MutableStateFlow<List<Soru>>(emptyList())
        val soruyor = MutableStateFlow(false)
        @Volatile var metinIs: kotlinx.coroutines.Deferred<String>? = null
        @Volatile var altyazili = false
    }

    private val videolar = object : LinkedHashMap<String, VideoBilgisi>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoBilgisi>?) = size > 30
    }

    fun bilgi(id: String): VideoBilgisi = synchronized(videolar) { videolar.getOrPut(id) { VideoBilgisi() } }

    private val OZET = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Bir videonun başlığı, açıklaması ve (varsa) zaman damgalı altyazısı verilecek.
Cevabı Türkçe ve tam olarak şu biçimde yaz (başka hiçbir şey ekleme, JSON kullanma):
ÖZET: videonun ne anlattığını 3-4 cümleyle, tek paragrafta, sade anlat. Uydurma; sadece videoda olanı yaz.
• en önemli nokta (tek kısa cümle)
• ikinci nokta
• üçüncü nokta
SORU: izleyicinin bu videoya özel sorabileceği kısa bir soru
SORU: ikinci soru
SORU: üçüncü soru
""".trimIndent()

    private val ANLAR = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Zaman damgalı altyazıdan videonun bölümlerini çıkar: 4-8 bölüm, sırayla.
Her satır tam olarak şu biçimde olsun (başka hiçbir şey yazma):
m:ss | en fazla 5 kelimelik başlık | tek kısa cümlelik açıklama
""".trimIndent()

    private val SORU = """
Sen Temâşâ adlı video uygulamasının yapay zekâsısın. Bir videonun bilgisi ve zaman damgalı altyazısı verilecek; kullanıcı bu video hakkında soru soruyor.
- Cevabı videodan bul; Türkçe, açık ve en fazla 4-5 cümleyle yaz. Hemen cevaba gir, giriş cümlesi kurma.
- Videoda geçmeyen bir şey sorulursa bunu dürüstçe söyle; istersen genel bilgini "videoda geçmiyor ama" diye belirterek ekle.
- En son satıra, cevap videonun belli bir yerindeyse "ZAMAN: m:ss", değilse "ZAMAN: yok" yaz.
""".trimIndent()

    /** Video açılınca altyazıyı önceden hazırla (yapay zekâya sormaz, ücretsiz) */
    fun hazirla(v: Video) { if (Evren.hazir) scope.launch { runCatching { metinHazirla(v, bilgi(v.id)) } } }

    fun ozetle(v: Video, yeniden: Boolean = false) {
        val b = bilgi(v.id)
        if (b.ozetleniyor.value || (b.ozet.value?.bitti == true && !yeniden)) return
        b.ozetleniyor.value = true; b.ozetHata.value = null
        if (yeniden) { b.ozet.value = null; b.anlar.value = null }
        scope.launch {
            val r = runCatching {
                val m = metinHazirla(v, b)
                // Önemli anlar ayrı ve aynı anda hazırlansın
                if (b.altyazili && b.anlar.value == null && !b.anlarYukleniyor.value) launch { anlariCikar(m, b) }
                else if (!b.altyazili) b.anlar.value = emptyList()
                Evren.akis(OZET, m, maxToken = 600) { t -> b.ozet.value = ozetCoz(t, b.altyazili, false) }
                    .let { t -> ozetCoz(t, b.altyazili, true) }
            }
            r.onSuccess { o -> if (o.ozet.isBlank()) b.ozetHata.value = "Özet çıkarılamadı. Tekrar dene." else b.ozet.value = o }
            r.onFailure { e -> if (b.ozet.value?.ozet.isNullOrBlank()) b.ozetHata.value = hataMetni(e) else b.ozet.value = b.ozet.value?.copy(bitti = true) }
            b.ozetleniyor.value = false
        }
    }

    private suspend fun anlariCikar(m: String, b: VideoBilgisi) {
        b.anlarYukleniyor.value = true
        runCatching {
            val t = Evren.sor(ANLAR, m, maxToken = 450)
            b.anlar.value = t.lines().mapNotNull { l ->
                val p = l.trim().trimStart('-', '•', '*', ' ').split('|').map { it.trim() }
                if (p.size < 2) return@mapNotNull null
                val ms = zaman(p[0]) ?: return@mapNotNull null
                An(ms, p[1], p.getOrNull(2).orEmpty())
            }.sortedBy { it.ms }.distinctBy { it.ms }
        }.onFailure { b.anlar.value = emptyList() }
        b.anlarYukleniyor.value = false
    }

    /** "ÖZET: … • … SORU: …" metnini (yarım olsa da) çöz */
    private fun ozetCoz(t: String, altyazili: Boolean, bitti: Boolean): Ozet {
        val ozet = StringBuilder(); val noktalar = ArrayList<String>(); val sorular = ArrayList<String>()
        var bolum = 0
        for (ham in t.lines()) {
            val l = ham.trim().replace("**", "").trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("ÖZET:", true) || l.startsWith("OZET:", true) -> { bolum = 0; ozet.append(l.substringAfter(':').trim()) }
                l.startsWith("SORU:", true) -> { bolum = 2; sorular += l.substringAfter(':').trim() }
                l.startsWith("•") || l.startsWith("- ") || l.startsWith("* ") -> { bolum = 1; noktalar += l.drop(1).trim() }
                bolum == 0 -> { if (ozet.isNotEmpty()) ozet.append(' '); ozet.append(l) }
                bolum == 1 && noktalar.isNotEmpty() -> noktalar[noktalar.lastIndex] = noktalar.last() + " " + l
            }
        }
        return Ozet(ozet.toString().trim(), noktalar.filter { it.isNotBlank() }, sorular.filter { it.isNotBlank() }.take(3), altyazili, bitti)
    }

    fun sor(v: Video, soru: String) {
        val t = soru.trim()
        val b = bilgi(v.id)
        if (t.isEmpty() || b.soruyor.value) return
        b.sorular.update { it + Soru(t) }
        b.soruyor.value = true
        fun sonuGuncelle(f: (Soru) -> Soru) = b.sorular.update { l -> l.lastOrNull()?.let { l.dropLast(1) + f(it) } ?: l }
        scope.launch {
            val r = runCatching {
                val m = metinHazirla(v, b)
                val onceki = b.sorular.value.dropLast(1).filter { it.cevap != null && !it.hata }.takeLast(4)
                    .joinToString("\n") { "Soru: ${it.soru}\nCevap: ${it.cevap}" }
                Evren.akis(SORU, m + (if (onceki.isNotBlank()) "\n\nÖnceki sorular:\n$onceki" else "") + "\n\nKullanıcının sorusu: $t", maxToken = 450) { yazi ->
                    val c = yazi.lines().filterNot { it.trim().startsWith("ZAMAN", true) }.joinToString("\n").trim()
                    if (c.isNotEmpty()) sonuGuncelle { it.copy(cevap = c) }
                }
            }
            r.onSuccess { yazi ->
                val satirlar = yazi.lines()
                val zSatir = satirlar.lastOrNull { it.trim().startsWith("ZAMAN", true) }
                val ms = zSatir?.substringAfter(':')?.trim()?.takeIf { b.altyazili }?.let { zaman(it) }
                val c = satirlar.filterNot { it.trim().startsWith("ZAMAN", true) }.joinToString("\n").trim()
                sonuGuncelle { it.copy(cevap = c.ifBlank { "Bunu videodan çıkaramadım." }, ms = ms, bitti = true) }
            }
            r.onFailure { e -> sonuGuncelle { it.copy(cevap = hataMetni(e), hata = true, bitti = true) } }
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

    /** Yapay zekâya verilecek video metni: başlık, açıklama, zaman damgalı altyazı (bir kez hazırlanır) */
    private suspend fun metinHazirla(v: Video, b: VideoBilgisi): String {
        val is_ = synchronized(b) {
            b.metinIs?.takeIf { !it.isCompleted || !it.isCancelled }
                ?: scope.async(Dispatchers.IO) { metinOlustur(v, b) }.also { b.metinIs = it }
        }
        return runCatching { is_.await() }.getOrElse { e -> synchronized(b) { if (b.metinIs === is_) b.metinIs = null }; throw e }
    }

    private suspend fun metinOlustur(v: Video, b: VideoBilgisi): String {
        val info: StreamInfo = Player.info.value?.takeIf { it.id == v.id } ?: Yt.info(v.id)
        val aciklama = runCatching { info.description?.content.orEmpty() }.getOrDefault("")
            .replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").take(1500)
        val altyazi = runCatching { altyaziCek(info) }.getOrNull()
        b.altyazili = !altyazi.isNullOrBlank()
        val dakika = if (info.duration > 0) "${info.duration / 60} dk ${info.duration % 60} sn" else "bilinmiyor"
        return buildString {
            append("Başlık: ${info.name}\nKanal: ${info.uploaderName}\nSüre: $dakika\n")
            if (aciklama.isNotBlank()) append("Açıklama:\n$aciklama\n")
            if (!altyazi.isNullOrBlank()) append("\nAltyazı (zaman damgalı):\n$altyazi")
            else append("\n(Bu videonun altyazısı yok; sadece başlık ve açıklamaya göre konuş, bunu belirt.)")
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
        val sinir = 20_000
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

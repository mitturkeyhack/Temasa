package com.erkam.temasa.filtre

import android.content.Context
import android.content.SharedPreferences
import com.erkam.temasa.data.Video
import com.erkam.temasa.yt.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Yapay zekânın kullanıcının cümlesinden çıkardığı kural */
data class Kural(
    val id: String,
    val baslik: String,
    val aciklama: String,
    val tur: String,      // metin | gorsel | ses
    val talimat: String,  // sınıflandırıcıya ayrıntılı talimat
    val acik: Boolean = true,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("b", baslik).put("a", aciklama).put("t", tur).put("tl", talimat).put("o", acik)
    companion object {
        fun from(o: JSONObject) = Kural(o.optString("id"), o.optString("b"), o.optString("a"), o.optString("t", "metin"), o.optString("tl"), o.optBoolean("o", true))
    }
}

data class Mesaj(val ben: Boolean, val metin: String)

/** Videonun takılma nedeni */
data class Takilma(val videoId: String, val neden: String)

/**
 * Filtreleme: kullanıcı neyi görmek istemediğini kendi cümleleriyle yazar, EVREN bunu kurallara
 * çevirir; her video gösterilmeden önce bu kurallara göre süzülür.
 */
object Filtre {
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val kurallar = MutableStateFlow<List<Kural>>(emptyList())
    val sohbet = MutableStateFlow<List<Mesaj>>(emptyList())
    val dusunuyor = MutableStateFlow(false)
    /** videoId → gizlensin mi */
    val kararlar = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val nedenler = HashMap<String, String>()
    val engelliKanallar = MutableStateFlow<Map<String, String>>(emptyMap()) // id → ad
    val bugunGizlenen = MutableStateFlow(0)
    val calismiyor = MutableStateFlow<String?>(null)
    /** Video açılınca sesten takıldıysa */
    val takilma = MutableStateFlow<Takilma?>(null)
    val kilit = MutableStateFlow(false)

    private val bekleyen = LinkedHashMap<String, Video>()
    private val soruluyor = HashSet<String>()
    private var isJob: Job? = null
    private val kanalSayac = HashMap<String, Int>()
    private val izinli = HashSet<String>() // "Yine de izle" denenler

    val aktif get() = Evren.hazir && kurallar.value.any { it.acik }
    private fun imza() = kurallar.value.filter { it.acik }.joinToString("|") { it.talimat }.hashCode().toString()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("filtre", Context.MODE_PRIVATE)
        dir = ctx.filesDir
        runCatching {
            kurallar.value = JSONArray(prefs.getString("kurallar", "[]")).let { a -> (0 until a.length()).map { Kural.from(a.getJSONObject(it)) } }
            sohbet.value = JSONArray(prefs.getString("sohbet", "[]")).let { a -> (0 until a.length()).map { val o = a.getJSONObject(it); Mesaj(o.optBoolean("ben"), o.optString("m")) } }
            engelliKanallar.value = JSONObject(prefs.getString("kanallar", "{}")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            JSONArray(prefs.getString("izinli", "[]")).let { a -> for (i in 0 until a.length()) izinli += a.getString(i) }
        }
        kilit.value = prefs.getString("pin", null) != null
        val bugun = LocalDate.now().toString()
        bugunGizlenen.value = if (prefs.getString("gun", "") == bugun) prefs.getInt("sayac", 0) else 0
        // Kararlar kural imzasıyla saklanır
        runCatching {
            val f = File(dir, "filtre_kararlar.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                if (o.optString("imza") == imza()) {
                    val k = o.getJSONObject("k")
                    kararlar.value = k.keys().asSequence().associateWith { k.getBoolean(it) }
                }
            }
        }
    }

    private fun kaydet() {
        prefs.edit()
            .putString("kurallar", JSONArray().apply { kurallar.value.forEach { put(it.json()) } }.toString())
            .putString("sohbet", JSONArray().apply { sohbet.value.takeLast(40).forEach { put(JSONObject().put("ben", it.ben).put("m", it.metin)) } }.toString())
            .putString("kanallar", JSONObject(engelliKanallar.value as Map<*, *>).toString())
            .putString("izinli", JSONArray(izinli.toList().takeLast(500)).toString())
            .apply()
    }

    private var kayitJob: Job? = null
    private fun kararlariKaydet() {
        kayitJob?.cancel()
        kayitJob = scope.launch {
            delay(1500)
            runCatching {
                val m = kararlar.value.entries.toList().takeLast(4000)
                val k = JSONObject().apply { m.forEach { put(it.key, it.value) } }
                File(dir, "filtre_kararlar.json").writeText(JSONObject().put("imza", imza()).put("k", k).toString())
            }
        }
    }

    private fun gizlendiSay(n: Int) {
        if (n <= 0) return
        val bugun = LocalDate.now().toString()
        if (prefs.getString("gun", "") != bugun) bugunGizlenen.value = 0
        bugunGizlenen.update { it + n }
        prefs.edit().putString("gun", bugun).putInt("sayac", bugunGizlenen.value).apply()
    }

    // ---------------- Kilit ----------------

    fun pinVar() = prefs.getString("pin", null) != null
    fun pinDogru(p: String) = prefs.getString("pin", null) == p
    fun pinKoy(p: String?) {
        prefs.edit().apply { if (p == null) remove("pin") else putString("pin", p) }.apply()
        kilit.value = p != null
    }

    // ---------------- Kurallar ----------------

    fun kuralAc(id: String, acik: Boolean) {
        kurallar.update { l -> l.map { if (it.id == id) it.copy(acik = acik) else it } }
        kurallarDegisti()
    }

    fun kuralSil(id: String) {
        kurallar.update { l -> l.filter { it.id != id } }
        kurallarDegisti()
    }

    private fun kurallarDegisti() {
        kararlar.value = emptyMap(); nedenler.clear(); kanalSayac.clear()
        synchronized(bekleyen) { bekleyen.clear(); soruluyor.clear() }
        kaydet(); kararlariKaydet()
    }

    fun sohbetiTemizle() {
        sohbet.value = emptyList(); kaydet()
    }

    private val KURAL_SISTEM = """
Sen Temâşâ adlı video uygulamasının filtreleme yapay zekâsısın. Kullanıcı neyi görmek istemediğini kendi cümleleriyle yazar.
Görevin bunu kısa, açık kurallara çevirmek. Mevcut kuralları da veriyorum: kullanıcı bir şeyi değiştirmek, kaldırmak ya da eklemek isterse listeyi güncelle.
Kural türleri:
- "metin": başlık, kanal adı ve konuya bakılarak anlaşılır (ör. konu, dil, argo, sadece manevi içerik).
- "gorsel": kapak resmine bakılarak anlaşılır (ör. kadın görseli olmasın).
- "ses": videodaki sesle ilgilidir (ör. kadın sesi olmasın, çalgılı müzik olmasın); başlık ve kanaldan da tahmin edilir.
Sadece JSON döndür, başka hiçbir şey yazma:
{"cevap": "kullanıcıya kısa, sıcak Türkçe cevap", "kurallar": [{"baslik": "en fazla 5 kelime", "aciklama": "neye bakılacağı, en fazla 8 kelime", "tur": "metin|gorsel|ses", "talimat": "bir video sınıflandırıcısına ayrıntılı, net talimat"}]}
"kurallar" her zaman TAM ve GÜNCEL listedir. Kullanıcı sadece sohbet ediyorsa listeyi aynen geri ver.
""".trimIndent()

    private fun mevcutKurallar() = kurallar.value.joinToString("\n") { "- ${it.baslik} (${it.tur}): ${it.talimat}" }.ifEmpty { "(henüz yok)" }

    private fun kurallariAyikla(o: JSONObject): List<Kural>? {
        val a = o.optJSONArray("kurallar") ?: return null
        val eski = kurallar.value
        return (0 until a.length()).mapNotNull { i ->
            val k = a.optJSONObject(i) ?: return@mapNotNull null
            val b = k.optString("baslik").trim().ifEmpty { return@mapNotNull null }
            val onceki = eski.firstOrNull { it.baslik.equals(b, true) }
            Kural(
                id = onceki?.id ?: (System.currentTimeMillis() + i).toString(36),
                baslik = b,
                aciklama = k.optString("aciklama").trim(),
                tur = k.optString("tur", "metin").let { if (it in listOf("metin", "gorsel", "ses")) it else "metin" },
                talimat = k.optString("talimat").trim().ifEmpty { b },
                acik = onceki?.acik ?: true,
            )
        }
    }

    /** Kullanıcının cümlesini kurallara çevir */
    suspend fun yaz(metin: String): Boolean {
        val t = metin.trim(); if (t.isEmpty()) return false
        sohbet.update { it + Mesaj(true, t) }
        dusunuyor.value = true
        val gecmis = sohbet.value.takeLast(8).dropLast(1).joinToString("\n") { (if (it.ben) "Kullanıcı: " else "Sen: ") + it.metin }
        val ok = runCatching {
            val c = Evren.sor(KURAL_SISTEM, "Mevcut kurallar:\n${mevcutKurallar()}\n\nÖnceki konuşma:\n$gecmis\n\nKullanıcının yeni mesajı: $t")
            val o = Evren.json(c) ?: throw Evren.EvrenHatasi("Cevap anlaşılamadı, tekrar dener misin?")
            kurallariAyikla(o)?.let { yeni -> if (yeni != kurallar.value) { kurallar.value = yeni; kurallarDegisti() } }
            sohbet.update { it + Mesaj(false, o.optString("cevap").ifBlank { "Tamam, kuralları güncelledim." }) }
        }
        ok.onFailure { e -> sohbet.update { it + Mesaj(false, (e as? Evren.EvrenHatasi)?.kullaniciya ?: "Yapay zekâya ulaşamadım. İnternetini kontrol edip tekrar yaz.") } }
        dusunuyor.value = false
        kaydet()
        return ok.isSuccess
    }

    /** "Bunun gibileri gösterme" */
    fun bununGibileri(v: Video, sonuc: (String) -> Unit) {
        kararlar.update { it + (v.id to true) }; gizlendiSay(1)
        scope.launch {
            val ok = runCatching {
                val c = Evren.sor(KURAL_SISTEM, "Mevcut kurallar:\n${mevcutKurallar()}\n\nKullanıcı şu videoyu görmek istemiyor ve benzerlerinin de gösterilmemesini istiyor:\nBaşlık: ${v.title}\nKanal: ${v.channel}\nNedenini tahmin et; mevcut kurallara uygun bir kural ekle ya da birini genişlet. Cevapta neyi eklediğini kısaca söyle.")
                val o = Evren.json(c) ?: throw IllegalStateException()
                kurallariAyikla(o)?.let { yeni -> if (yeni != kurallar.value) { kurallar.value = yeni; kurallarDegisti(); kararlar.update { it + (v.id to true) } } }
                sohbet.update { it + Mesaj(true, "\"${v.title.take(60)}\" gibilerini gösterme") + Mesaj(false, o.optString("cevap")) }
                kaydet()
            }
            sonuc(if (ok.isSuccess) "Anlaşıldı. Benzer videolar artık gösterilmeyecek." else "Bu video gizlendi.")
        }
    }

    fun kanaliEngelle(id: String, ad: String) {
        if (id.isEmpty()) return
        engelliKanallar.update { it + (id to ad) }
        kaydet()
    }

    fun kanalEngeliniKaldir(id: String) {
        engelliKanallar.update { it - id }
        kanalSayac.remove(id)
        kaydet()
    }

    // ---------------- Süzme ----------------

    /** Ekranda gösterilecekler: kararı "göster" olanlar. Kararı bekleyenler gösterilmez. */
    fun suz(list: List<Video>, k: Map<String, Boolean> = kararlar.value, kanallar: Map<String, String> = engelliKanallar.value): List<Video> {
        val l = list.filter { it.channelId.isEmpty() || it.channelId !in kanallar }
        if (!aktif) return l
        return l.filter { k[it.id] == false || it.id in izinli }
    }

    fun bekleyenVar(list: List<Video>): Boolean = aktif && list.any { it.id !in kararlar.value && it.id !in izinli && it.channelId !in engelliKanallar.value }

    fun gizliMi(id: String) = aktif && kararlar.value[id] == true && id !in izinli

    /** Bu videolara karar verilsin (zaten verilmişse bir şey yapmaz) */
    fun iste(list: List<Video>) {
        if (!aktif) return
        val k = kararlar.value
        synchronized(bekleyen) {
            list.forEach { v ->
                if (v.id !in k && v.id !in soruluyor && v.id !in izinli && v.channelId !in engelliKanallar.value) bekleyen[v.id] = v
            }
        }
        if (isJob?.isActive != true) isJob = scope.launch { isle() }
    }

    private val sinir = Semaphore(3)

    private suspend fun isle() {
        delay(120)
        while (true) {
            val parti = synchronized(bekleyen) {
                val p = bekleyen.values.take(15)
                p.forEach { bekleyen.remove(it.id); soruluyor += it.id }
                p
            }
            if (parti.isEmpty()) return
            scope.launch { sinir.withPermit { parti(parti) } }
        }
    }

    private val SINIF_SISTEM = """
Sen bir video filtreleme yapay zekâsısın. Kullanıcının kurallarına göre her videonun gizlenip gizlenmeyeceğine karar verirsin.
- Bir video kurala açıkça ya da büyük ihtimalle aykırıysa gizle.
- "Sadece ..." türündeki kurallarda o türden olmayan her videoyu gizle.
- Ses kuralları için başlık, kanal ve konudan tahmin et (ör. kadın şarkıcı, düet, kadın sunucu).
- Emin değilsen ve kural kullanıcıyı korumakla ilgiliyse gizle.
Sadece JSON döndür: {"gizle": [numaralar], "neden": {"numara": "en fazla 5 kelime"}}
""".trimIndent()

    private suspend fun parti(p: List<Video>) {
        val acik = kurallar.value.filter { it.acik }
        val imzaNow = imza()
        val kuralMetni = acik.joinToString("\n") { "- ${it.baslik}: ${it.talimat}" }
        val liste = p.mapIndexed { i, v ->
            "$i) Başlık: ${v.title} | Kanal: ${v.channel}" + (if (v.duration > 0) " | Süre: ${v.duration / 60} dk" else "") + (if (v.live) " | canlı yayın" else "")
        }.joinToString("\n")
        val sonuc = HashMap<String, Boolean>()
        val r = runCatching {
            val c = Evren.sor(SINIF_SISTEM, "Kurallar:\n$kuralMetni\n\nVideolar:\n$liste")
            val o = Evren.json(c) ?: throw IllegalStateException("json")
            val giz = o.optJSONArray("gizle")?.let { a -> (0 until a.length()).mapNotNull { a.optInt(it, -1).takeIf { x -> x >= 0 } } }?.toSet() ?: emptySet()
            val n = o.optJSONObject("neden")
            p.forEachIndexed { i, v ->
                sonuc[v.id] = i in giz
                if (i in giz) n?.optString(i.toString())?.takeIf { it.isNotBlank() }?.let { nedenler[v.id] = it }
            }
        }
        if (r.isFailure) {
            // Çalışmıyorsa göster ama uyar
            calismiyor.value = "Filtreleme şu an çalışmıyor: yapay zekâya ulaşılamadı"
            p.forEach { sonuc[it.id] = false }
        } else calismiyor.value = null

        // Görsel kuralları: metinden geçen videoların kapaklarına bak
        val gorselKural = acik.filter { it.tur == "gorsel" }
        if (r.isSuccess && gorselKural.isNotEmpty() && Evren.gorselVar.value) {
            val adaylar = p.filter { sonuc[it.id] == false }
            adaylar.chunked(6).map { grup -> scope.async { runCatching { gorsel(grup, gorselKural) }.getOrNull() } }.awaitAll()
                .filterNotNull().forEach { m -> m.forEach { (id, giz) -> if (giz) { sonuc[id] = true; nedenler.putIfAbsent(id, "Kapak resmi") } } }
        }
        if (imzaNow != imza()) return // bu sırada kurallar değişti
        synchronized(bekleyen) { p.forEach { soruluyor.remove(it.id) } }
        kararlar.update { it + sonuc }
        gizlendiSay(sonuc.count { it.value })
        // Çok sık takılan kanalı tamamen gizle
        p.filter { sonuc[it.id] == true && it.channelId.isNotEmpty() }.forEach { v ->
            val n = (kanalSayac[v.channelId] ?: 0) + 1
            kanalSayac[v.channelId] = n
            if (n >= 3) kanaliEngelle(v.channelId, v.channel)
        }
        kararlariKaydet()
    }

    private suspend fun gorsel(grup: List<Video>, kural: List<Kural>): Map<String, Boolean> {
        val resimler = grup.mapIndexedNotNull { i, v ->
            runCatching {
                Net.client.newCall(Request.Builder().url("https://i.ytimg.com/vi/${v.id}/mqdefault.jpg").build()).execute().use { r ->
                    if (r.isSuccessful) "Görsel $i:" to r.body.bytes() else null
                }
            }.getOrNull()
        }
        if (resimler.isEmpty()) return emptyMap()
        val c = Evren.sor(
            "Video kapak resimlerine bakıp kurallara aykırı olanları bulursun. Sadece JSON döndür: {\"gizle\": [numaralar]}",
            "Kurallar:\n" + kural.joinToString("\n") { "- ${it.baslik}: ${it.talimat}" } + "\n\nHer görselin numarası resmin hemen önünde yazıyor.",
            resimler, model = Evren.gorselModel(), maxToken = 300,
        )
        val giz = Evren.json(c)?.optJSONArray("gizle")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }?.toSet() ?: emptySet()
        return grup.mapIndexed { i, v -> v.id to (i in giz) }.toMap()
    }

    // ---------------- Video açılınca ses kontrolü ----------------

    fun sesKurali(): Kural? = if (aktif) kurallar.value.firstOrNull { it.acik && it.tur == "ses" && (it.talimat.contains("kadın", true) || it.baslik.contains("kadın", true) || it.talimat.contains("kız", true) || it.baslik.contains("kız", true)) } else null

    fun sestenTakildi(v: Video) {
        if (v.id in izinli) return
        kararlar.update { it + (v.id to true) }
        nedenler[v.id] = "Kadın sesi algılandı"
        gizlendiSay(1)
        if (v.channelId.isNotEmpty()) {
            val n = (kanalSayac[v.channelId] ?: 0) + 2
            kanalSayac[v.channelId] = n
            if (n >= 3) kanaliEngelle(v.channelId, v.channel)
        }
        takilma.value = Takilma(v.id, "Kadın sesi algılandı")
        kararlariKaydet()
    }

    /** "Yine de izle": bu video için kuralı geç, yapay zekâ da öğrensin */
    fun yineDeIzle(v: Video) {
        izinli += v.id
        kararlar.update { it + (v.id to false) }
        if (v.channelId.isNotEmpty()) kanalSayac[v.channelId] = ((kanalSayac[v.channelId] ?: 0) - 2).coerceAtLeast(0)
        takilma.value = null
        kaydet(); kararlariKaydet()
    }

    fun izinliMi(id: String) = id in izinli
}

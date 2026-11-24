package com.erkam.temasa.filtre

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.erkam.temasa.yt.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** EVREN yapay zekâ bağlantısı (Cezerî ile aynı servis, OpenAI uyumlu). */
object Evren {
    const val VARSAYILAN_ADRES = "https://evren-llmapi.ssyz.org.tr/v1"
    private lateinit var prefs: SharedPreferences

    enum class Durum { ANAHTAR_YOK, BAGLANIYOR, HAZIR, HATA }

    val durum = MutableStateFlow(Durum.ANAHTAR_YOK)
    val hata = MutableStateFlow<String?>(null)
    val modeller = MutableStateFlow<List<String>>(emptyList())
    /** Görsel anlayan model kapak resimlerini kabul etmezse false olur */
    val gorselVar = MutableStateFlow(true)

    private val client by lazy {
        Net.client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    }

    class EvrenHatasi(val kullaniciya: String) : IOException(kullaniciya)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("evren", Context.MODE_PRIVATE)
        modeller.value = prefs.getString("modeller", "").orEmpty().split(',').filter { it.isNotBlank() }
        gorselVar.value = prefs.getBoolean("gorsel", true)
        durum.value = if (anahtar().isBlank()) Durum.ANAHTAR_YOK else Durum.HAZIR
    }

    fun anahtar(): String = prefs.getString("anahtar", "").orEmpty()
    fun anahtarSonu(): String = anahtar().takeLast(4)
    private fun adres() = (prefs.getString("adres", VARSAYILAN_ADRES) ?: VARSAYILAN_ADRES).trimEnd('/')
    val hazir get() = anahtar().isNotBlank()

    fun anahtarKaydet(a: String) {
        prefs.edit().putString("anahtar", a.trim()).remove("sartlar").putBoolean("gorsel", true).apply()
        gorselVar.value = true
        durum.value = if (a.isBlank()) Durum.ANAHTAR_YOK else Durum.HAZIR
    }

    /** Hızlı metin modeli (flash), görsel anlayan büyük model (gemma) */
    fun hizliModel(): String {
        val l = modeller.value
        return l.firstOrNull { it.contains("flash", true) } ?: l.firstOrNull { it == "auto" } ?: l.firstOrNull() ?: "qwen3.8-flash-next"
    }

    fun gorselModel(): String {
        val l = modeller.value
        return l.firstOrNull { it.contains("gemma", true) } ?: l.firstOrNull { it.contains("vl", true) || it.contains("vision", true) } ?: "gemma-4-31b"
    }

    private fun istek(yol: String, govde: String?): Request {
        val b = Request.Builder().url(adres() + yol)
            .header("Authorization", "Bearer " + anahtar())
            .header("Accept", "application/json")
        if (govde != null) b.post(govde.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return b.build()
    }

    private fun sartlariKabulEt(): Boolean = runCatching {
        client.newCall(istek("/terms/accept", "{}")).execute().use { it.isSuccessful }.also { ok ->
            if (ok) prefs.edit().putBoolean("sartlar", true).apply()
        }
    }.getOrDefault(false)

    private fun hataCevir(kod: Int, g: String) = EvrenHatasi(
        when {
            kod == 401 || (kod == 403 && !g.contains("terms")) -> "EVREN anahtarı kabul edilmedi. Anahtarı kontrol et."
            kod == 402 || g.contains("credit", true) || g.contains("kredi", true) || g.contains("balance", true) -> "EVREN kredin bitmiş olabilir."
            kod == 429 -> "EVREN şu an yoğun ya da kullanım sınırına gelindi."
            kod >= 500 -> "EVREN şu an cevap vermiyor."
            else -> "EVREN'den beklenmedik cevap ($kod)."
        }
    )

    /** Bağlantıyı dener, model listesini alır, şartları kabul eder */
    suspend fun baglan(): Boolean = withContext(Dispatchers.IO) {
        if (anahtar().isBlank()) { durum.value = Durum.ANAHTAR_YOK; return@withContext false }
        durum.value = Durum.BAGLANIYOR
        val r = runCatching {
            var (kod, g) = client.newCall(istek("/models", null)).execute().use { it.code to it.body.string() }
            if (g.contains("terms_not_accepted") && sartlariKabulEt()) {
                val x = client.newCall(istek("/models", null)).execute().use { it.code to it.body.string() }
                kod = x.first; g = x.second
            }
            if (kod !in 200..299) throw hataCevir(kod, g)
            val d = JSONObject(g).optJSONArray("data") ?: JSONArray()
            val l = (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("id")?.ifBlank { null } }
            if (l.isNotEmpty()) { modeller.value = l; prefs.edit().putString("modeller", l.joinToString(",")).apply() }
            if (!prefs.getBoolean("sartlar", false)) sartlariKabulEt()
        }
        r.onSuccess { durum.value = Durum.HAZIR; hata.value = null }
        r.onFailure { e -> durum.value = Durum.HATA; hata.value = (e as? EvrenHatasi)?.kullaniciya ?: "EVREN'e ulaşılamadı. İnternetini kontrol et." }
        r.isSuccess
    }

    /**
     * Tek seferlik soru; cevap metni döner. gorseller: (etiket, jpeg baytları)
     * Engelleyici değil; IO'da çalışır.
     */
    suspend fun sor(sistem: String, kullanici: String, gorseller: List<Pair<String, ByteArray>> = emptyList(), model: String = hizliModel(), maxToken: Int = 1200): String =
        withContext(Dispatchers.IO) {
            if (anahtar().isBlank()) throw EvrenHatasi("EVREN anahtarı girilmemiş.")
            val icerik: Any = if (gorseller.isEmpty()) kullanici else JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", kullanici))
                gorseller.forEach { (etiket, bayt) ->
                    put(JSONObject().put("type", "text").put("text", etiket))
                    put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(bayt, Base64.NO_WRAP))))
                }
            }
            val govde = JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sistem))
                    .put(JSONObject().put("role", "user").put("content", icerik)))
                .put("stream", false)
                .put("thinking", false)
                .put("temperature", 0.2)
                .put("max_tokens", maxToken)
                .toString()
            var denendi = false
            while (true) {
                val (kod, g) = client.newCall(istek("/chat/completions", govde)).execute().use { it.code to it.body.string() }
                if (kod !in 200..299) {
                    if (!denendi && g.contains("terms_not_accepted") && sartlariKabulEt()) { denendi = true; continue }
                    if (gorseller.isNotEmpty() && kod in 400..422) {
                        // Bu model görsel kabul etmiyor
                        gorselVar.value = false; prefs.edit().putBoolean("gorsel", false).apply()
                    }
                    throw hataCevir(kod, g)
                }
                val m = JSONObject(g).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                return@withContext temizle(m?.optString("content").orEmpty())
            }
            @Suppress("UNREACHABLE_CODE") ""
        }

    /** <think> bloklarını at */
    fun temizle(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    /** Cevabın içinden ilk JSON nesnesini çıkar */
    fun json(s: String): JSONObject? {
        val t = s.replace("```json", "```").let { x -> Regex("(?s)```(.*?)```").find(x)?.groupValues?.get(1) ?: x }
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(t.substring(a, b + 1)) }.getOrNull()
    }
}

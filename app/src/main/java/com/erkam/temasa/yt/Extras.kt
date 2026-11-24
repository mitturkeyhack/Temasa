package com.erkam.temasa.yt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Atlanacak kısım (SponsorBlock) */
data class Skip(val start: Long, val end: Long, val category: String) {
    val label get() = when (category) {
        "sponsor" -> "Sponsor"
        "selfpromo" -> "Tanıtım"
        "interaction" -> "Abone ol hatırlatması"
        "intro" -> "Giriş"
        "outro" -> "Bitiş kartları"
        else -> "Kısım"
    }
}

data class Votes(val likes: Long, val dislikes: Long)

object Extras {
    private val CATS = listOf("sponsor", "selfpromo", "interaction")

    suspend fun sponsors(id: String): List<Skip> = withContext(Dispatchers.IO) {
        runCatching {
            val cats = URLEncoder.encode(JSONArray(CATS).toString(), "UTF-8")
            val r = Request.Builder().url("https://sponsor.ajay.app/api/skipSegments?videoID=$id&categories=$cats")
                .header("User-Agent", "Temasa/1.0").build()
            Net.client.newCall(r).execute().use { res ->
                if (!res.isSuccessful) return@runCatching emptyList<Skip>()
                val a = JSONArray(res.body.string())
                (0 until a.length()).mapNotNull { i ->
                    val o = a.getJSONObject(i)
                    if (o.optString("actionType", "skip") != "skip") return@mapNotNull null
                    val seg = o.getJSONArray("segment")
                    Skip((seg.getDouble(0) * 1000).toLong(), (seg.getDouble(1) * 1000).toLong(), o.optString("category"))
                }.filter { it.end - it.start > 1000 }.sortedBy { it.start }
            }
        }.getOrDefault(emptyList())
    }

    /** Beğenmeme sayısı (Return YouTube Dislike) */
    suspend fun votes(id: String): Votes? = withContext(Dispatchers.IO) {
        runCatching {
            val r = Request.Builder().url("https://returnyoutubedislikeapi.com/votes?videoId=$id")
                .header("User-Agent", "Temasa/1.0").build()
            Net.client.newCall(r).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                val o = JSONObject(res.body.string())
                Votes(o.optLong("likes", -1), o.optLong("dislikes", -1))
            }
        }.getOrNull()
    }
}

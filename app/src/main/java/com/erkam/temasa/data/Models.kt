package com.erkam.temasa.data

import org.json.JSONArray
import org.json.JSONObject

/** Bir video kartı için gereken her şey. */
data class Video(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelId: String = "",
    val channelAvatar: String? = null,
    val duration: Long = 0,          // saniye; 0 = bilinmiyor
    val views: Long = -1,
    val viewsText: String? = null,   // "1,2 Mn görüntüleme"
    val ago: String? = null,         // "3 gün önce"
    val live: Boolean = false,
) {
    val thumb get() = "https://i.ytimg.com/vi/$id/hq720.jpg"
    val thumbSmall get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val url get() = "https://www.youtube.com/watch?v=$id"

    fun meta(withChannel: Boolean = true): String = listOfNotNull(
        channel.takeIf { withChannel && it.isNotBlank() },
        viewsText ?: views.takeIf { it >= 0 }?.let { Fmt.views(it) },
        ago?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", title).put("c", channel).put("ci", channelId)
        .put("ca", channelAvatar ?: "").put("d", duration).put("v", views)
        .put("vt", viewsText ?: "").put("a", ago ?: "").put("l", live)

    companion object {
        fun fromJson(o: JSONObject) = Video(
            id = o.getString("id"), title = o.optString("t"), channel = o.optString("c"),
            channelId = o.optString("ci"), channelAvatar = o.optString("ca").ifEmpty { null },
            duration = o.optLong("d"), views = o.optLong("v", -1),
            viewsText = o.optString("vt").ifEmpty { null }, ago = o.optString("a").ifEmpty { null },
            live = o.optBoolean("l"),
        )

        fun listToJson(l: List<Video>) = JSONArray().apply { l.forEach { put(it.toJson()) } }
        fun listFromJson(a: JSONArray?): List<Video> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() }
    }
}

data class ChannelRef(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val subs: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("a", avatar ?: "").put("s", subs ?: "")

    companion object {
        fun fromJson(o: JSONObject) = ChannelRef(o.getString("id"), o.optString("n"), o.optString("a").ifEmpty { null }, o.optString("s").ifEmpty { null })
        fun listToJson(l: List<ChannelRef>) = JSONArray().apply { l.forEach { put(it.toJson()) } }
        fun listFromJson(a: JSONArray?): List<ChannelRef> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() }
    }
}

data class PlaylistRef(
    val id: String,
    val title: String,
    val channel: String = "",
    val count: String? = null,
    val thumb: String? = null,
)

/** Uygulama içinde tutulan liste */
data class LocalList(val id: String, val name: String, val videos: List<Video>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("v", Video.listToJson(videos))

    companion object {
        fun fromJson(o: JSONObject) = LocalList(o.getString("id"), o.optString("n"), Video.listFromJson(o.optJSONArray("v")))
    }
}

/** Sayı ve süre yazımı (YouTube Türkçe biçimi) */
object Fmt {
    fun views(n: Long): String = count(n) + " görüntüleme"

    fun count(n: Long): String = when {
        n < 0 -> ""
        n < 1_000 -> n.toString()
        n < 1_000_000 -> short(n / 1_000.0) + " B"
        n < 1_000_000_000 -> short(n / 1_000_000.0) + " Mn"
        else -> short(n / 1_000_000_000.0) + " Mr"
    }

    private fun short(d: Double): String {
        if (d >= 10) return d.toLong().toString()
        val t = (d * 10).toLong() / 10.0
        return if (t == t.toLong().toDouble()) t.toLong().toString() else t.toString().replace('.', ',')
    }

    fun time(sec: Long): String {
        if (sec <= 0) return "0:00"
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun ms(ms: Long) = time(ms / 1000)

    fun parseTime(t: String?): Long {
        if (t.isNullOrBlank()) return 0
        val p = t.trim().split(":").mapNotNull { it.trim().toLongOrNull() }
        if (p.isEmpty()) return 0
        return p.fold(0L) { acc, x -> acc * 60 + x }
    }

    /** "3 days ago" → "3 gün önce" */
    fun ago(en: String?): String? {
        if (en.isNullOrBlank()) return null
        val s = en.trim()
        if (s.contains("önce") || s.contains("Yayın")) return s
        val m = Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE).find(s)
            ?: return s.replace("Streamed", "Yayınlandı:").replace("Premiered", "İlk gösterim:")
        val u = when (m.groupValues[2].lowercase()) {
            "second" -> "saniye"; "minute" -> "dakika"; "hour" -> "saat"; "day" -> "gün"
            "week" -> "hafta"; "month" -> "ay"; else -> "yıl"
        }
        val prefix = if (s.startsWith("Streamed", true)) "Yayın: " else ""
        return "$prefix${m.groupValues[1]} $u önce"
    }

    fun bytes(b: Long): String = when {
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
        else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024)).replace('.', ',')
    }

    /** 1.25 → "1.25", 2.0 → "2" */
    fun speed(s: Float): String {
        val r = Math.round(s * 100) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    fun subs(n: Long): String = if (n < 0) "" else count(n) + " abone"
}

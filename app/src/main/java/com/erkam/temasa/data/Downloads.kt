package com.erkam.temasa.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.erkam.temasa.play.DownloadService
import com.erkam.temasa.yt.Net
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** İndirme durumu */
data class DItem(
    val video: Video,
    val height: Int,
    val bytes: Long = 0,
    val total: Long = 0,
    val done: Boolean = false,
    val failed: Boolean = false,
    val speed: Long = 0, // bayt/sn
    val at: Long = System.currentTimeMillis(),
) {
    val progress get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** İndirme seçeneği (kalite + yaklaşık boyut) */
data class DOption(val height: Int, val label: String, val size: Long)

/**
 * Uygulama içi indirme: dosyalar uygulamanın kendi klasörüne iner,
 * galeride görünmez, sadece Temâşâ açar.
 */
object Downloads {
    data class Files(val video: File, val audio: File?, val height: Int)

    private lateinit var root: File
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val items = MutableStateFlow<List<DItem>>(emptyList())
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val jobs = mutableMapOf<String, Job>()
    @Volatile var active = false
        private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
        root = File(app.filesDir, "indirilenler").apply { mkdirs() }
        val list = root.listFiles()?.mapNotNull { d ->
            runCatching {
                val m = JSONObject(File(d, "meta.json").readText())
                DItem(Video.fromJson(m.getJSONObject("v")), m.optInt("h"), m.optLong("b"), m.optLong("t"),
                    done = m.optBoolean("done"), failed = !m.optBoolean("done"), at = m.optLong("at"))
            }.getOrNull()
        }?.sortedByDescending { it.at } ?: emptyList()
        items.value = list
        scope.launch { for (id in queue) runOne(id) }
    }

    private fun dir(id: String) = File(root, id)

    fun state(id: String) = items.value.firstOrNull { it.video.id == id }

    fun completed(id: String): Files? {
        val it = state(id)?.takeIf { s -> s.done } ?: return null
        val d = dir(id)
        val v = d.listFiles()?.firstOrNull { f -> f.name.startsWith("v.") } ?: return null
        val a = d.listFiles()?.firstOrNull { f -> f.name.startsWith("a.") }
        return Files(v, a, it.height)
    }

    fun totalBytes(): Long = items.value.filter { it.done }.sumOf { it.total }

    /** Bu video için kalite seçenekleri */
    fun options(i: StreamInfo): List<DOption> {
        val a = audio(i)
        val aSize = a?.itagItem?.contentLength ?: 0
        return videoCandidates(i).groupBy { minOf(it.width, it.height) }
            .map { (h, l) -> val s = l.first(); DOption(h, "${h}p" + if (s.fps > 30) "${s.fps}" else "", (s.itagItem?.contentLength ?: 0) + aSize) }
            .sortedByDescending { it.height }
    }

    private fun videoCandidates(i: StreamInfo): List<VideoStream> {
        val all = i.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // En uyumlu: H.264 (mp4); yoksa VP9
        val avc = all.filter { it.codec.orEmpty().startsWith("avc") }
        val vp9 = all.filter { it.codec.orEmpty().startsWith("vp9") || it.codec.orEmpty().startsWith("vp09") }
        val byH = (avc + vp9.filter { v -> avc.none { it.height == v.height } }).distinctBy { it.height }
        return byH.sortedByDescending { it.height }
    }

    private fun audio(i: StreamInfo): AudioStream? {
        val all = i.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val orig = all.filter { it.audioTrackType == null || it.audioTrackType.toString() == "ORIGINAL" }.ifEmpty { all }
        return orig.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate } ?: orig.maxByOrNull { it.averageBitrate }
    }

    /** servis = false: arka plan işçisi (akıllı indirme) kendisi bekler, bildirimli hizmet açılmaz */
    fun start(v: Video, height: Int, servis: Boolean = true) {
        val d = dir(v.id).apply { mkdirs() }
        val item = DItem(v, height)
        items.update { l -> listOf(item) + l.filter { it.video.id != v.id } }
        writeMeta(d, item)
        active = true
        if (servis) runCatching {
            val i = Intent(app, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }
        queue.trySend(v.id)
    }

    fun retry(id: String) {
        val s = state(id) ?: return
        start(s.video, s.height)
    }

    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        items.update { l -> l.filter { it.video.id != id } }
        scope.launch { dir(id).deleteRecursively() }
    }

    fun deleteAll() {
        jobs.values.forEach { it.cancel() }; jobs.clear()
        items.value = emptyList()
        scope.launch { root.listFiles()?.forEach { it.deleteRecursively() } }
    }

    private fun writeMeta(d: File, it: DItem) {
        runCatching {
            File(d, "meta.json").writeText(
                JSONObject().put("v", it.video.toJson()).put("h", it.height).put("b", it.bytes)
                    .put("t", it.total).put("done", it.done).put("at", it.at).toString()
            )
        }
    }

    private fun update(id: String, f: (DItem) -> DItem) {
        items.update { l -> l.map { if (it.video.id == id) f(it) else it } }
    }

    private suspend fun runOne(id: String) {
        if (state(id)?.done == true) { checkIdle(); return }
        val job = scope.launch {
            val d = dir(id)
            try {
                val i = runCatching { Yt.info(id) }.getOrElse { Yt.info(id, fresh = true) }
                val want = state(id)?.height ?: 720
                val cands = videoCandidates(i)
                val vs = cands.filter { minOf(it.width, it.height) <= want }.maxByOrNull { it.height } ?: cands.lastOrNull()
                val a = audio(i)
                val full = Yt.videoOf(i)
                val vLen = vs?.let { len(it.content, it.itagItem?.contentLength) } ?: 0
                val aLen = a?.let { len(it.content, it.itagItem?.contentLength) } ?: 0
                val total = vLen + aLen
                update(id) { it.copy(video = full.copy(channelAvatar = full.channelAvatar ?: it.video.channelAvatar), total = total, failed = false, height = vs?.let { s -> minOf(s.width, s.height) } ?: want) }
                val done = AtomicLong(0)
                val speedJob = launch {
                    var last = 0L
                    while (isActive) {
                        delay(1000)
                        val now = done.get()
                        update(id) { it.copy(bytes = now, speed = now - last) }
                        last = now
                    }
                }
                d.listFiles()?.filter { it.name.startsWith("v.") || it.name.startsWith("a.") }?.forEach { it.delete() }
                if (vs != null) {
                    val ext = if (vs.format == MediaFormat.WEBM) "webm" else "mp4"
                    fetch(vs.content, vLen, File(d, "v.$ext.part"), done)
                    File(d, "v.$ext.part").renameTo(File(d, "v.$ext"))
                } else {
                    val muxed = i.videoStreams.filter { it.isUrl }.maxByOrNull { it.height } ?: throw IllegalStateException("akış yok")
                    val l = len(muxed.content, null)
                    fetch(muxed.content, l, File(d, "v.mp4.part"), done)
                    File(d, "v.mp4.part").renameTo(File(d, "v.mp4"))
                }
                if (a != null && vs != null) {
                    val ext = if (a.format == MediaFormat.M4A) "m4a" else "webm"
                    fetch(a.content, aLen, File(d, "a.$ext.part"), done)
                    File(d, "a.$ext.part").renameTo(File(d, "a.$ext"))
                }
                speedJob.cancel()
                // Kapak resmi (internetsiz de görünsün)
                runCatching {
                    Net.client.newCall(Request.Builder().url(full.thumb).build()).execute().use { r ->
                        if (r.isSuccessful) File(d, "kapak.jpg").writeBytes(r.body.bytes())
                    }
                }
                update(id) { it.copy(bytes = it.total.coerceAtLeast(done.get()), total = it.total.coerceAtLeast(done.get()), done = true, speed = 0) }
                state(id)?.let { writeMeta(d, it) }
            } catch (e: Exception) {
                if (!coroutineContext.isActive) return@launch
                update(id) { it.copy(failed = true, speed = 0) }
                state(id)?.let { writeMeta(d, it) }
            }
        }
        jobs[id] = job
        job.join()
        jobs.remove(id)
        checkIdle()
    }

    private fun checkIdle() {
        if (jobs.isEmpty() && items.value.none { !it.done && !it.failed }) active = false
    }

    fun coverFile(id: String): File? = File(dir(id), "kapak.jpg").takeIf { it.exists() }

    private fun len(url: String, known: Long?): Long {
        if (known != null && known > 0) return known
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        val r = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        Net.client.newCall(r).execute().use { res ->
            res.header("Content-Range")?.substringAfter("/")?.toLongOrNull()?.let { return it }
            return res.header("Content-Length")?.toLongOrNull() ?: 0
        }
    }

    private const val CHUNK = 4L * 1024 * 1024
    private const val WORKERS = 4

    /** Hızlı indirme: dosyayı parçalara bölüp aynı anda 4 bağlantıyla çeker. */
    private suspend fun fetch(url: String, total: Long, out: File, done: AtomicLong) {
        if (total <= 0) {
            Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                out.outputStream().use { o ->
                    val inp = r.body.byteStream(); val buf = ByteArray(64 * 1024)
                    while (true) { coroutineContext.ensureActive(); val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); done.addAndGet(n.toLong()) }
                }
            }
            return
        }
        RandomAccessFile(out, "rw").use { it.setLength(total) }
        val ranges = Channel<LongRange>(Channel.UNLIMITED)
        var s = 0L
        while (s < total) { val e = minOf(s + CHUNK, total) - 1; ranges.trySend(s..e); s = e + 1 }
        ranges.close()
        coroutineScope {
            (0 until WORKERS).map {
                async(Dispatchers.IO) {
                    RandomAccessFile(out, "rw").use { raf ->
                        for (r in ranges) {
                            var attempt = 0
                            while (true) {
                                coroutineContext.ensureActive()
                                var got = 0L
                                try {
                                    val req = Request.Builder().url(url).header("Range", "bytes=${r.first}-${r.last}").build()
                                    Net.client.newCall(req).execute().use { res ->
                                        if (res.code != 206 && res.code != 200) throw IllegalStateException("HTTP ${res.code}")
                                        val inp = res.body.byteStream()
                                        val buf = ByteArray(64 * 1024)
                                        var pos = r.first
                                        while (pos <= r.last) {
                                            coroutineContext.ensureActive()
                                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), r.last - pos + 1).toInt())
                                            if (n < 0) break
                                            raf.seek(pos); raf.write(buf, 0, n)
                                            pos += n; got += n
                                            done.addAndGet(n.toLong())
                                        }
                                        if (pos <= r.last) throw IllegalStateException("eksik")
                                    }
                                    break
                                } catch (e: Exception) {
                                    done.addAndGet(-got)
                                    if (!coroutineContext.isActive || ++attempt > 4) throw e
                                    delay(800L * attempt)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }
}

package com.erkam.temasa.filtre

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.erkam.temasa.yt.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Videonun ilk ~40 saniyesinin sesine bakıp baskın sesin perdesini ölçer.
 * Kadın sesi genelde 170–300 Hz, erkek sesi 85–160 Hz civarındadır.
 * Tahmindir: müzik aletleri yanıltabilir. Bu yüzden temkinli eşikler kullanılır.
 */
object SesKontrol {
    private val sonuc = HashMap<String, Boolean?>()

    suspend fun kadinSesiMi(info: StreamInfo, tmpDir: File): Boolean? = withContext(Dispatchers.IO) {
        sonuc[info.id]?.let { return@withContext it }
        val a = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.format == org.schabi.newpipe.extractor.MediaFormat.M4A }
            .minByOrNull { it.averageBitrate } ?: return@withContext null
        val f = File(tmpDir, "ses_${info.id}.m4a")
        val r = runCatching {
            Net.client.newCall(Request.Builder().url(a.content).header("Range", "bytes=0-700000").build()).execute().use { res ->
                if (res.code !in 200..299) return@runCatching null
                f.outputStream().use { o -> res.body.byteStream().copyTo(o) }
            }
            olc(f)
        }.getOrNull()
        f.delete()
        sonuc[info.id] = r
        r
    }

    private fun olc(f: File): Boolean? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            // 4'te 1'e indirip (≈11 kHz) tek kanala çevir
            val step = (rate / 11025).coerceAtLeast(1)
            val fs = rate / step
            val maxSamples = fs * 40
            val pcm = FloatArray(maxSamples)
            var n = 0
            var cnt = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var guard = 0
            while (!outDone && n < maxSamples && guard++ < 20000) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val size = runCatching { ex.readSampleData(buf, 0) }.getOrDefault(-1)
                        if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val total = ob.remaining()
                    var k = 0
                    while (k + ch <= total && n < maxSamples) {
                        if (cnt % step == 0) {
                            var s = 0f
                            for (c in 0 until ch) s += ob.get(k + c)
                            pcm[n++] = s / ch / 32768f
                        }
                        cnt++
                        k += ch
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop(); codec.release()
            return perde(pcm, n, fs)
        } catch (_: Exception) {
            return null
        } finally {
            ex.release()
        }
    }

    /** Otokorelasyonla perde: kadın sesi mi (null: karar verilemedi) */
    private fun perde(x: FloatArray, n: Int, fs: Int): Boolean? {
        val frame = 1024
        val hop = 512
        val start = fs * 3 // ilk 3 sn'yi atla (giriş müziği/sessizlik)
        if (n < start + frame * 20) return null
        val minLag = fs / 350
        val maxLag = fs / 75
        val energies = ArrayList<Float>()
        var p = start
        while (p + frame + maxLag < n) { var e = 0f; for (i in 0 until frame) e += x[p + i] * x[p + i]; energies += e; p += hop }
        if (energies.isEmpty()) return null
        val esik = energies.sorted()[energies.size / 2] * 0.8f
        val f0s = ArrayList<Float>()
        var energetic = 0
        p = start
        var idx = 0
        while (p + frame + maxLag < n) {
            val e = energies[idx++]
            if (e > esik && e > 1e-4f) {
                energetic++
                var best = 0f; var bestLag = 0
                for (lag in minLag..maxLag) {
                    var c = 0f; var e2 = 0f
                    for (i in 0 until frame) { c += x[p + i] * x[p + i + lag]; e2 += x[p + i + lag] * x[p + i + lag] }
                    val r = c / sqrt(e * e2 + 1e-9f)
                    if (r > best) { best = r; bestLag = lag }
                }
                if (best > 0.75f && bestLag > 0) f0s += fs.toFloat() / bestLag
            }
            p += hop
        }
        if (energetic < 20 || f0s.size < energetic * 0.3f) return null
        val sorted = f0s.sorted()
        val median = sorted[sorted.size / 2]
        val kadinBandi = f0s.count { it in 175f..330f }.toFloat() / f0s.size
        val erkekBandi = f0s.count { it in 80f..160f }.toFloat() / f0s.size
        return when {
            median in 185f..320f && kadinBandi > 0.55f -> true
            erkekBandi > 0.45f -> false
            else -> null
        }
    }
}

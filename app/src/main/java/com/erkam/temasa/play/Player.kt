package com.erkam.temasa.play

import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as P
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.erkam.temasa.data.Downloads
import com.erkam.temasa.data.Store
import com.erkam.temasa.data.Video
import com.erkam.temasa.yt.Extras
import com.erkam.temasa.yt.Net
import com.erkam.temasa.yt.Skip
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream
import java.io.File

data class Chapter(val title: String, val startMs: Long)

data class SubTrack(val label: String, val url: String, val lang: String, val mime: String)

/** Tek oynatıcı: video ekranı, küçük oynatıcı, bildirim ve küçük pencere hepsi bunu kullanır. */
@OptIn(UnstableApi::class)
object Player {
    lateinit var exo: ExoPlayer
        private set
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val video = MutableStateFlow<Video?>(null)
    val info = MutableStateFlow<StreamInfo?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val qualities = MutableStateFlow<List<Int>>(emptyList())
    val quality = MutableStateFlow(0)          // 0 = otomatik
    val playingHeight = MutableStateFlow(0)
    val speed = MutableStateFlow(1f)
    val audioOnly = MutableStateFlow(false)
    val loop = MutableStateFlow(false)
    val local = MutableStateFlow(false)       // indirilmiş dosyadan mı

    val chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val skips = MutableStateFlow<List<Skip>>(emptyList())
    val skipped = MutableSharedFlow<Skip>(extraBufferCapacity = 4)
    val frames = MutableStateFlow<Frameset?>(null)

    val subTracks = MutableStateFlow<List<SubTrack>>(emptyList())
    val subtitle = MutableStateFlow<SubTrack?>(null)

    val related = MutableStateFlow<List<Video>>(emptyList())
    val queue = MutableStateFlow<List<Video>>(emptyList())
    val queueIndex = MutableStateFlow(-1)

    /** Uyku zamanlayıcı: saat (ms) ya da END_OF_VIDEO */
    val sleepAt = MutableStateFlow<Long?>(null)
    const val END_OF_VIDEO = -1L

    val playing = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val buffered = MutableStateFlow(0L)
    val ended = MutableStateFlow(false)
    val videoAspect = MutableStateFlow(16f / 9f)

    private lateinit var cache: SimpleCache
    private lateinit var dataSource: CacheDataSource.Factory
    private var loadJob: Job? = null
    private var useDash = true
    private var retries = 0
    private val ignoredSkips = mutableSetOf<Long>()
    private var lastSave = 0L
    private var vp9: Boolean = true

    fun init(ctx: Context) {
        if (::exo.isInitialized) return
        app = ctx.applicationContext
        vp9 = hasHardwareVp9()
        cache = SimpleCache(File(app.cacheDir, "video"), LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024), StandaloneDatabaseProvider(app))
        val http = OkHttpDataSource.Factory(Net.client)
        dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val selector = DefaultTrackSelector(app).apply {
            setParameters(buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setPreferredAudioLanguage(null)
                .setPreferredTextLanguage("tr"))
        }
        // Hızlı açılış: az veriyle başla, arkada bol arabellek doldur
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 700, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exo = ExoPlayer.Builder(app)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exo.addListener(object : P.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
                if (!isPlaying) savePos()
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering.value = state == P.STATE_BUFFERING
                ended.value = state == P.STATE_ENDED
                if (state == P.STATE_READY) { loading.value = false; retries = 0 }
                if (state == P.STATE_ENDED) onEnded()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    playingHeight.value = minOf(size.width, size.height)
                    videoAspect.value = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }.forEach { g ->
                    for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                        val f = g.getTrackFormat(i)
                        if (f.height > 0) playingHeight.value = minOf(f.width, f.height)
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) = handleError(e)
        })
        ticker()
    }

    private fun hasHardwareVp9(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            !ci.isEncoder && ci.supportedTypes.any { it.equals(MimeTypes.VIDEO_VP9, true) } &&
                if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
                else !ci.name.startsWith("OMX.google") && !ci.name.startsWith("c2.android")
        }
    }.getOrDefault(false)

    // ---------- Oynatma ----------

    /** Bir videoyu aç. Kuyruk verilirse oradan devam eder. */
    fun play(v: Video, startMs: Long? = null, list: List<Video>? = null) {
        if (list != null) {
            queue.value = list
            queueIndex.value = list.indexOfFirst { it.id == v.id }
        } else if (queue.value.none { it.id == v.id }) {
            queue.value = emptyList(); queueIndex.value = -1
        } else queueIndex.value = queue.value.indexOfFirst { it.id == v.id }

        if (video.value?.id == v.id && exo.mediaItemCount > 0 && error.value == null) {
            if (startMs != null) exo.seekTo(startMs)
            if (ended.value) exo.seekTo(0)
            exo.play()
            return
        }
        savePos()
        loadJob?.cancel()
        video.value = v
        info.value = null
        error.value = null
        loading.value = true
        chapters.value = emptyList(); skips.value = emptyList(); related.value = emptyList()
        frames.value = null; subTracks.value = emptyList(); subtitle.value = null
        qualities.value = emptyList(); quality.value = 0; playingHeight.value = 0
        ignoredSkips.clear(); retries = 0; useDash = true
        ended.value = false; position.value = 0; duration.value = v.duration * 1000; buffered.value = 0
        exo.stop(); exo.clearMediaItems()
        val sp = Store.speedFor(v.channelId)
        speed.value = sp
        exo.setPlaybackSpeed(sp)
        exo.repeatMode = if (loop.value) P.REPEAT_MODE_ONE else P.REPEAT_MODE_OFF

        loadJob = scope.launch {
            val start = startMs ?: if (Store.settings.value.resume) Store.resumeAt(v.id) else null
            val dl = Downloads.completed(v.id)
            if (dl != null && !online()) {
                playLocal(v, dl, start ?: 0)
                return@launch
            }
            val i = runCatching { Yt.info(v.id) }.recoverCatching { Yt.info(v.id, fresh = true) }.getOrElse { e ->
                if (dl != null) { playLocal(v, dl, start ?: 0); return@launch }
                loading.value = false
                error.value = if (!online()) "İnternet bağlantısı yok" else "Video açılamadı. Tekrar dene."
                return@launch
            }
            if (video.value?.id != v.id) return@launch
            info.value = i
            val full = Yt.videoOf(i).let { f -> if (v.channelAvatar != null && f.channelAvatar == null) f.copy(channelAvatar = v.channelAvatar) else f }
            video.value = full
            Store.addHistory(full)
            if (full.channelId.isNotEmpty() && full.channelId != v.channelId) {
                val s2 = Store.speedFor(full.channelId); speed.value = s2; exo.setPlaybackSpeed(s2)
            }
            chapters.value = i.streamSegments.map { Chapter(it.title ?: "", it.startTimeSeconds * 1000L) }
            frames.value = i.previewFrames.filter { it.frameWidth in 1..400 }.maxByOrNull { it.frameWidth }
                ?: i.previewFrames.minByOrNull { it.frameWidth }
            subTracks.value = subtitleTracks(i)
            if (Store.settings.value.subtitles) subtitle.value = pickDefaultSub(subTracks.value)
            related.value = Yt.convert(i.relatedItems).filterIsInstance<Video>().filter { it.id != v.id }

            if (dl != null) playLocal(full, dl, start ?: 0)
            else prepare(i, start ?: 0, autoPlay = true)

            if (Store.settings.value.sponsorBlock && i.streamType != StreamType.LIVE_STREAM) {
                launch { skips.value = Extras.sponsors(v.id) }
            }
        }
    }

    private fun mediaItem(v: Video): MediaItem = MediaItem.Builder()
        .setMediaId(v.id)
        .setUri(v.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(v.title)
                .setArtist(v.channel)
                .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/${v.id}/hqdefault.jpg"))
                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .build()
        ).build()

    private fun playLocal(v: Video, f: Downloads.Files, start: Long) {
        info.value?.let { } ?: run { video.value = v; Store.addHistory(v) }
        local.value = true
        val item = mediaItem(v)
        val pf = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(app))
        val sources = mutableListOf<MediaSource>(pf.createMediaSource(MediaItem.fromUri(Uri.fromFile(f.video))))
        f.audio?.let { sources += pf.createMediaSource(MediaItem.fromUri(Uri.fromFile(it))) }
        val src = if (sources.size == 1) pf.createMediaSource(item.buildUpon().setUri(Uri.fromFile(f.video)).build())
        else MergingMediaSource(true, *sources.toTypedArray())
        qualities.value = listOf(f.height)
        exo.setMediaSource(wrapItem(src, item), start)
        exo.prepare(); exo.play()
    }

    /** Merging kaynağı bildirim için başlık taşımaz; MediaItem'i ayrıca ver. */
    private fun wrapItem(src: MediaSource, item: MediaItem): MediaSource =
        object : androidx.media3.exoplayer.source.WrappingMediaSource(src) {
            override fun getMediaItem(): MediaItem = item
        }

    private fun prepare(i: StreamInfo, startMs: Long, autoPlay: Boolean) {
        local.value = false
        val v = video.value ?: return
        val item = mediaItem(v)
        val heights = videoStreams(i).map { minOf(it.width, it.height).takeIf { h -> h > 0 } ?: it.height }.distinct().sortedDescending()
        qualities.value = heights
        val src: MediaSource = when {
            i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM -> {
                val hls = i.hlsUrl
                if (!hls.isNullOrEmpty()) HlsMediaSource.Factory(dataSource).createMediaSource(item.buildUpon().setUri(hls).build())
                else DashMediaSource.Factory(dataSource).createMediaSource(item.buildUpon().setUri(i.dashMpdUrl).build())
            }
            useDash -> dash(i, item) ?: progressive(i, item).also { useDash = false }
            else -> progressive(i, item)
        }
        val withSubs = subtitle.value?.let { s ->
            val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(s.mime).setLanguage(s.lang).setLabel(s.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            MergingMediaSource(src, SingleSampleMediaSource.Factory(dataSource).createMediaSource(cfg, C.TIME_UNSET))
        } ?: src
        applyTrackParams()
        exo.setMediaSource(wrapItem(withSubs, item), startMs)
        exo.prepare()
        if (autoPlay) exo.play()
    }

    /** Videonun ses dışı akışları: cihaza uygun kodek ailesi */
    private fun videoStreams(i: StreamInfo): List<VideoStream> {
        val all = i.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.codec.orEmpty().startsWith("av01") }
        val v = all.filter { it.codec.orEmpty().startsWith("vp9") || it.codec.orEmpty().startsWith("vp09") }
        val a = all.filter { it.codec.orEmpty().startsWith("avc") }
        val chosen = if (vp9 && v.isNotEmpty()) v else a.ifEmpty { all }
        return chosen.distinctBy { it.height }.sortedByDescending { it.height }
    }

    private fun audioStreams(i: StreamInfo): List<AudioStream> {
        val all = i.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val orig = all.filter { it.audioTrackType == null || it.audioTrackType.toString() == "ORIGINAL" }.ifEmpty { all }
        val opus = orig.filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA }
        val m4a = orig.filter { it.format == MediaFormat.M4A }
        return opus.ifEmpty { m4a }.ifEmpty { orig }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun mime(f: MediaFormat?, video: Boolean) = when (f) {
        MediaFormat.WEBM -> "video/webm"
        MediaFormat.MPEG_4 -> "video/mp4"
        MediaFormat.M4A -> "audio/mp4"
        MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS -> "audio/webm"
        else -> if (video) "video/mp4" else "audio/mp4"
    }

    /**
     * Tüm kaliteleri tek bir DASH tanımında birleştir: otomatik kalite, takılmadan kalite değiştirme
     * ve hızlı ileri sarma bu sayede olur.
     */
    private fun dash(i: StreamInfo, item: MediaItem): MediaSource? = runCatching {
        val vs = videoStreams(i).filter { it.indexEnd > 0 && it.initEnd > 0 && it.itagItem != null }
        val aus = audioStreams(i).filter { it.indexEnd > 0 && it.initEnd > 0 && it.itagItem != null }
        if (vs.isEmpty() || aus.isEmpty()) return null
        val durMs = vs.first().itagItem?.approxDurationMs?.takeIf { it > 0 } ?: (i.duration * 1000)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT${durMs / 1000.0}S\"><Period>")
        sb.append("<AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"${mime(vs.first().format, true)}\" subsegmentAlignment=\"true\">")
        vs.forEach { s ->
            sb.append("<Representation id=\"v${s.itag}\" codecs=\"${esc(s.codec ?: "")}\" bandwidth=\"${s.bitrate.coerceAtLeast(1)}\" width=\"${s.width}\" height=\"${s.height}\"")
            if (s.fps > 0) sb.append(" frameRate=\"${s.fps}\"")
            sb.append("><BaseURL>${esc(s.content)}</BaseURL><SegmentBase indexRange=\"${s.indexStart}-${s.indexEnd}\"><Initialization range=\"${s.initStart}-${s.initEnd}\"/></SegmentBase></Representation>")
        }
        sb.append("</AdaptationSet>")
        sb.append("<AdaptationSet id=\"1\" contentType=\"audio\" mimeType=\"${mime(aus.first().format, false)}\" subsegmentAlignment=\"true\">")
        aus.forEach { s ->
            val ii = s.itagItem!!
            sb.append("<Representation id=\"a${s.itag}\" codecs=\"${esc(s.codec ?: "")}\" bandwidth=\"${(ii.bitrate.takeIf { it > 0 } ?: s.averageBitrate * 1000).coerceAtLeast(1)}\" audioSamplingRate=\"${ii.sampleRate.takeIf { it > 0 } ?: 48000}\">")
            sb.append("<AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"${ii.audioChannels.takeIf { it > 0 } ?: 2}\"/>")
            sb.append("<BaseURL>${esc(s.content)}</BaseURL><SegmentBase indexRange=\"${s.indexStart}-${s.indexEnd}\"><Initialization range=\"${s.initStart}-${s.initEnd}\"/></SegmentBase></Representation>")
        }
        sb.append("</AdaptationSet></Period></MPD>")
        val manifest = DashManifestParser().parse(Uri.parse("https://www.youtube.com/"), ByteArrayInputStream(sb.toString().toByteArray()))
        DashMediaSource.Factory(dataSource).createMediaSource(manifest, item)
    }.getOrNull()

    /** Yedek yol: seçili kalitedeki görüntü + ses ayrı ayrı */
    private fun progressive(i: StreamInfo, item: MediaItem): MediaSource {
        val vs = videoStreams(i)
        val target = targetHeight()
        val v = vs.filter { target == null || minOf(it.width, it.height) <= target }.maxByOrNull { it.height } ?: vs.lastOrNull()
        val a = audioStreams(i).maxByOrNull { it.averageBitrate }
        val pf = ProgressiveMediaSource.Factory(dataSource)
        if (v == null || a == null) {
            val muxed = i.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
                ?: throw IllegalStateException("Akış yok")
            return pf.createMediaSource(item.buildUpon().setUri(muxed.content).build())
        }
        return MergingMediaSource(true,
            pf.createMediaSource(item.buildUpon().setUri(v.content).build()),
            pf.createMediaSource(MediaItem.fromUri(a.content)))
    }

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Seçili kaliteye göre hedef yükseklik (null = sınırsız) */
    private fun targetHeight(): Int? {
        val q = quality.value
        if (q > 0) return q
        val s = Store.settings.value
        return (if (onWifi()) s.qualityWifi else s.qualityMobile).takeIf { it > 0 }
    }

    private fun applyTrackParams() {
        val b = exo.trackSelectionParameters.buildUpon()
        val q = quality.value
        if (q > 0) {
            b.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).setMinVideoSize(0, 0)
            // Yatay videoda yükseklik, dikeyde genişlik kaliteyi belirler
            b.setMaxVideoSize(if (videoAspect.value < 1f) q else Int.MAX_VALUE, if (videoAspect.value < 1f) Int.MAX_VALUE else q)
            b.setMinVideoSize(if (videoAspect.value < 1f) q else 0, if (videoAspect.value < 1f) 0 else q)
            b.setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
        } else {
            val cap = targetHeight()
            b.setMinVideoSize(0, 0)
            if (cap != null) b.setMaxVideoSize(Int.MAX_VALUE, cap) else b.clearVideoSizeConstraints()
            b.setViewportSizeToPhysicalDisplaySize(app, true)
        }
        b.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly.value)
        b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitle.value == null)
        exo.trackSelectionParameters = b.build()
    }

    // ---------- Ayarlar ----------

    fun setQuality(h: Int) {
        quality.value = h
        if (local.value) return
        if (useDash) applyTrackParams()
        else info.value?.let { prepare(it, exo.currentPosition, exo.playWhenReady) }
    }

    fun setSpeed(s: Float, remember: Boolean = Store.settings.value.rememberChannelSpeed) {
        val v = (Math.round(s.coerceIn(0.25f, 4f) * 100) / 100f)
        speed.value = v
        exo.setPlaybackSpeed(v)
        if (remember) video.value?.channelId?.let { Store.setChannelSpeed(it, v) }
    }

    /** Basılı tutunca geçici 2x */
    fun tempSpeed(on: Boolean) {
        exo.setPlaybackSpeed(if (on) maxOf(2f, speed.value) else speed.value)
    }

    fun setAudioOnly(on: Boolean) {
        audioOnly.value = on; applyTrackParams()
    }

    fun setLoop(on: Boolean) {
        loop.value = on; exo.repeatMode = if (on) P.REPEAT_MODE_ONE else P.REPEAT_MODE_OFF
    }

    fun setSubtitle(s: SubTrack?) {
        subtitle.value = s
        val i = info.value ?: return
        if (local.value) return
        prepare(i, exo.currentPosition, exo.playWhenReady)
    }

    private fun subtitleTracks(i: StreamInfo): List<SubTrack> {
        val list = i.subtitles.filter { it.isUrl }.mapNotNull { s ->
            val mime = when (s.format) {
                MediaFormat.VTT -> MimeTypes.TEXT_VTT
                MediaFormat.TTML -> MimeTypes.APPLICATION_TTML
                MediaFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
                else -> return@mapNotNull null
            }
            SubTrack((s.displayLanguageName ?: s.languageTag) + if (s.isAutoGenerated) " (otomatik)" else "", s.content, s.languageTag ?: "", mime)
        }.distinctBy { it.label }
        val vtt = list.filter { it.mime == MimeTypes.TEXT_VTT }.ifEmpty { list }
        val out = vtt.toMutableList()
        if (out.none { it.lang.startsWith("tr") }) {
            out.firstOrNull { it.url.contains("timedtext") }?.let { base ->
                out.add(0, SubTrack("Türkçe (otomatik çeviri)", base.url + "&tlang=tr", "tr", base.mime))
            }
        }
        return out
    }

    private fun pickDefaultSub(l: List<SubTrack>): SubTrack? {
        val lang = Store.settings.value.subtitleLang
        return l.firstOrNull { it.lang.startsWith(lang) } ?: l.firstOrNull()
    }

    fun undoSkip(s: Skip) {
        ignoredSkips += s.start
        exo.seekTo(s.start)
    }

    fun setSleep(minutes: Int?) {
        sleepAt.value = when {
            minutes == null -> null
            minutes < 0 -> END_OF_VIDEO
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
    }

    // ---------- Kontroller ----------

    fun toggle() {
        if (ended.value) { exo.seekTo(0); exo.play(); return }
        if (exo.isPlaying) exo.pause() else {
            if (error.value != null) retry() else exo.play()
        }
    }

    fun seekBy(ms: Long) {
        val d = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + ms).coerceIn(0, d))
    }

    fun seekTo(ms: Long) = exo.seekTo(ms.coerceAtLeast(0))

    fun retry() {
        val v = video.value ?: return
        Yt.invalidate(v.id)
        val pos = exo.currentPosition
        video.value = null
        play(v, pos)
    }

    fun hasNext() = (queueIndex.value >= 0 && queueIndex.value < queue.value.size - 1) || related.value.isNotEmpty()
    fun hasPrev() = queueIndex.value > 0

    fun next() {
        val q = queue.value; val i = queueIndex.value
        if (i >= 0 && i < q.size - 1) { play(q[i + 1], 0); return }
        val recent = Store.history.value.take(30).map { it.id }.toSet()
        val n = related.value.firstOrNull { it.id !in recent && !it.live } ?: related.value.firstOrNull() ?: return
        play(n, 0)
    }

    fun prev() {
        if (exo.currentPosition > 5_000 || !hasPrev()) { exo.seekTo(0); return }
        play(queue.value[queueIndex.value - 1], 0)
    }

    fun close() {
        savePos()
        loadJob?.cancel()
        exo.stop(); exo.clearMediaItems()
        video.value = null; info.value = null; error.value = null; loading.value = false
        queue.value = emptyList(); queueIndex.value = -1
    }

    private fun onEnded() {
        video.value?.let { Store.setPos(it.id, exo.duration, exo.duration) }
        if (sleepAt.value == END_OF_VIDEO) { sleepAt.value = null; return }
        if (loop.value) return
        val inQueue = queueIndex.value >= 0 && queueIndex.value < queue.value.size - 1
        if (inQueue || Store.settings.value.autoplay) scope.launch { delay(1200); if (ended.value) next() }
    }

    private fun handleError(e: PlaybackException) {
        val v = video.value ?: return
        val pos = exo.currentPosition
        val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        retries++
        if (retries > 3) {
            loading.value = false
            error.value = "Oynatma hatası. Tekrar dene."
            return
        }
        scope.launch {
            if (useDash && retries == 1 && http == null) {
                useDash = false
                info.value?.let { prepare(it, pos, true) }
                return@launch
            }
            // Bağlantının süresi dolduysa (403) yenisini al
            val fresh = runCatching { Yt.info(v.id, fresh = true) }.getOrNull()
            if (fresh == null) {
                loading.value = false
                error.value = "Video açılamadı. Tekrar dene."
                return@launch
            }
            info.value = fresh
            prepare(fresh, pos, true)
        }
    }

    fun savePos() {
        if (!::exo.isInitialized) return
        val v = video.value ?: return
        if (v.live) return
        val d = exo.duration
        if (d > 0 && exo.mediaItemCount > 0) Store.setPos(v.id, exo.currentPosition, d)
    }

    private fun ticker() {
        scope.launch {
            while (isActive) {
                if (video.value != null) {
                    val p = exo.currentPosition
                    position.value = p
                    exo.duration.takeIf { it > 0 }?.let { duration.value = it }
                    buffered.value = exo.bufferedPosition
                    if (exo.isPlaying) {
                        val now = System.currentTimeMillis()
                        if (now - lastSave > 5000) { lastSave = now; savePos() }
                        if (Store.settings.value.sponsorBlock) {
                            skips.value.firstOrNull { p >= it.start && p < it.end - 400 && it.start !in ignoredSkips }?.let { s ->
                                ignoredSkips += s.start
                                exo.seekTo(s.end)
                                skipped.tryEmit(s)
                            }
                        }
                        sleepAt.value?.let { t -> if (t > 0 && now >= t) { exo.pause(); sleepAt.value = null } }
                    }
                }
                delay(if (exo.isPlaying) 200 else 500)
            }
        }
    }

    /** Ön bellek dosyalarını sil */
    fun clearCache() {
        runCatching { cache.keys.toList().forEach { cache.removeResource(it) } }
    }

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) { runCatching { cache.cacheSpace }.getOrDefault(0) }
}

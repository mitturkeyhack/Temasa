package com.erkam.temasa.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.play.Chapter
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.Skip
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Videonun çizildiği yüzey. Aynı anda birden çok yüzey ekranda olabilir (geçiş sırasında),
 * ama videoyu sadece "active" olan alır. texture: küçük/hareketli pencereler için.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(modifier: Modifier = Modifier, zoom: Boolean = false, active: Boolean = true, texture: Boolean = false) {
    val playing by Player.playing.collectAsState()
    AndroidView(
        factory = { ctx ->
            val v = if (texture) LayoutInflater.from(ctx).inflate(com.erkam.temasa.R.layout.player_texture, null) as PlayerView
            else PlayerView(ctx)
            v.apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                subtitleView?.setApplyEmbeddedStyles(false)
                subtitleView?.setFractionalTextSize(0.055f)
            }
        },
        update = { v ->
            v.keepScreenOn = playing && active
            v.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            val was = v.tag as? Boolean
            if (active && (was != true || v.player == null)) {
                // Videoyu bu yüzeye al (diğer yüzey bıraksa da bırakmasa da)
                v.player = null
                v.player = Player.exo
            } else if (!active && was != false) {
                v.player = null
            }
            v.tag = active
        },
        onRelease = { it.player = null },
        modifier = modifier.background(Color.Black),
    )
}

/** Oynatıcı + üzerindeki kontroller ve dokunma hareketleri */
@Composable
fun PlayerBox(
    vm: MainViewModel,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onMinimize: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val video by Player.video.collectAsState()
    val playing by Player.playing.collectAsState()
    val buffering by Player.buffering.collectAsState()
    val loading by Player.loading.collectAsState()
    val error by Player.error.collectAsState()
    val ended by Player.ended.collectAsState()
    val chapters by Player.chapters.collectAsState()
    val skips by Player.skips.collectAsState()
    val speed by Player.speed.collectAsState()
    val subtitle by Player.subtitle.collectAsState()
    val subTracks by Player.subTracks.collectAsState()
    val queueIdx by Player.queueIndex.collectAsState()

    var controls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableLongStateOf(0L) }
    var seekSide by remember { mutableIntStateOf(0) }      // -1 sol, 1 sağ
    var seekAmount by remember { mutableIntStateOf(0) }
    var seekAt by remember { mutableLongStateOf(0L) }
    var fast by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    var gestureInfo by remember { mutableStateOf<Pair<Boolean, Float>?>(null) } // (parlaklık mı, değer)
    var skippedMsg by remember { mutableStateOf<Skip?>(null) }

    // Kontroller 3 sn sonra gizlenir
    LaunchedEffect(controls, playing, lastTouch, scrubbing) {
        if (controls && playing && !scrubbing) { delay(3000); controls = false }
    }
    LaunchedEffect(Unit) { Player.skipped.collect { skippedMsg = it; delay(5000); if (skippedMsg == it) skippedMsg = null } }
    LaunchedEffect(seekAt) { if (seekAt > 0) { delay(750); seekSide = 0; seekAmount = 0 } }
    LaunchedEffect(gestureInfo) { if (gestureInfo != null) { delay(900); gestureInfo = null } }

    fun touch() { lastTouch = System.currentTimeMillis() }

    BoxWithConstraints(modifier.background(Color.Black)) {
        val width = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        VideoSurface(Modifier.fillMaxSize(), zoom = zoom && fullscreen, active = active)

        // Dokunma: tek = kontroller, çift = 10 sn, basılı tut = 2x
        Box(
            Modifier.fillMaxSize()
                .pointerInput(fullscreen) {
                    detectTapGestures(
                        onTap = { o ->
                            val side = if (o.x < width / 2) -1 else 1
                            if (seekSide != 0 && side == seekSide) {
                                Player.seekBy(side * 10_000L); seekAmount += 10; seekAt = System.currentTimeMillis()
                            } else { controls = !controls; touch() }
                        },
                        onDoubleTap = { o ->
                            val side = if (o.x < width / 2) -1 else 1
                            Player.seekBy(side * 10_000L)
                            seekAmount = if (seekSide == side) seekAmount + 10 else 10
                            seekSide = side; seekAt = System.currentTimeMillis()
                            controls = false
                        },
                        onLongPress = { fast = true; Player.tempSpeed(true); controls = false },
                        onPress = {
                            tryAwaitRelease()
                            if (fast) { fast = false; Player.tempSpeed(false) }
                        }
                    )
                }
                .pointerInput(fullscreen) {
                    if (fullscreen) {
                        // Tam ekranda aşağı kaydır: tam ekrandan çık ve küçük oynatıcıya geç
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total > 120) { vm.exitFullscreen(); vm.expanded.value = false } },
                            onVerticalDrag = { ch, dy -> ch.consume(); total += dy }
                        )
                    } else {
                        // Aşağı kaydır = küçült, yukarı kaydır = tam ekran
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total > 120) onMinimize() else if (total < -120) vm.fullscreen.value = true
                            },
                            onVerticalDrag = { ch, dy -> ch.consume(); total += dy }
                        )
                    }
                }
                .pointerInput(fullscreen) {
                    if (!fullscreen) return@pointerInput
                    // İki parmakla yakınlaştır / uzaklaştır
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var start = 0f
                        do {
                            val ev = awaitPointerEvent()
                            val ps = ev.changes.filter { it.pressed }
                            if (ps.size == 2) {
                                val d = (ps[0].position - ps[1].position).getDistance()
                                if (start == 0f) start = d
                                else if (abs(d - start) > 80) { zoom = d > start; start = d; ps.forEach { if (it.positionChange() != Offset.Zero) it.consume() } }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
        )

        // Çift dokunma: dalga + "10 saniye"
        if (seekSide != 0) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(0.4f)
                    .align(if (seekSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .clip(if (seekSide < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (seekSide < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Text("$seekAmount saniye", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (fast) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 16.dp).background(Color(0x99000000), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${if (speed >= 2f) Fmt.speed(speed) else "2"}x", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        gestureInfo?.let { (bright, v) ->
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp).background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (bright) Icons.Filled.BrightnessMedium else Icons.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).height(4.dp).background(Color.White))
                }
            }
        }

        val busy = (loading || buffering) && error == null
        AnimatedVisibility(controls || scrubbing || error != null || ended, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0x80000000))) {
                // Üst
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 4.dp, vertical = if (fullscreen) 12.dp else 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (fullscreen) vm.exitFullscreen() else onMinimize() }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Küçült", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    if (fullscreen) {
                        Column(Modifier.weight(1f)) {
                            Text(video?.title ?: "", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(video?.channel ?: "", color = Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 1)
                        }
                    } else Spacer(Modifier.weight(1f))
                    if (subTracks.isNotEmpty()) {
                        IconButton(onClick = {
                            touch()
                            Player.setSubtitle(if (subtitle == null) subTracks.firstOrNull { it.lang.startsWith("tr") } ?: subTracks.first() else null)
                        }) {
                            Icon(if (subtitle != null) Icons.Filled.ClosedCaption else Icons.Outlined.ClosedCaptionOff, "Altyazı", tint = Color.White)
                        }
                    }
                    IconButton(onClick = { touch(); onSettings() }) { Icon(Icons.Outlined.Settings, "Ayarlar", tint = Color.White) }
                }

                // Orta
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 72.dp else 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundBtn(Icons.Filled.SkipPrevious, "Önceki", 48) { touch(); Player.prev() }
                    Box(Modifier.size(if (fullscreen) 72.dp else 64.dp), contentAlignment = Alignment.Center) {
                        if (busy && !scrubbing) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
                        else RoundBtn(
                            when { ended -> Icons.Filled.Replay; playing -> Icons.Filled.Pause; else -> Icons.Filled.PlayArrow },
                            if (playing) "Duraklat" else "Oynat", if (fullscreen) 72 else 64
                        ) { touch(); Player.toggle() }
                    }
                    RoundBtn(Icons.Filled.SkipNext, "Sonraki", 48, enabled = Player.hasNext()) { touch(); Player.next() }
                }

                error?.let {
                    val upd by com.erkam.temasa.data.Updater.state.collectAsState()
                    Column(Modifier.align(Alignment.Center).padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(it, color = Color.White, fontSize = 13.sp)
                        if (upd.hasUpdate) Text("Yeni sürüm var: Sen → Güncelle", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Alt: süre, bölüm, tam ekran
                Row(
                    Modifier.fillMaxWidth().align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 4.dp, bottom = if (fullscreen) 36.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeText(video?.live == true, scrubbing, scrubPos, chapters, speed, Modifier.weight(1f))
                    IconButton(onClick = { touch(); if (fullscreen) vm.exitFullscreen() else vm.fullscreen.value = true }) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Tam ekran", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }

        skippedMsg?.let { s ->
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (fullscreen) 72.dp else 44.dp)
                    .background(Color(0xE6212121), RoundedCornerShape(6.dp)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${s.label} atlandı", color = Color.White, fontSize = 13.sp)
                Text("GERİ AL", color = C.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { Player.undoSkip(s); skippedMsg = null }.padding(horizontal = 10.dp, vertical = 10.dp))
            }
        }

        // İlerleme çubuğu: videonun alt kenarında (YouTube gibi)
        if (video?.live != true) {
            LiveSeekBar(
                chapters = chapters, skips = skips,
                expanded = controls || scrubbing,
                showWhenCollapsed = !fullscreen,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(horizontal = if (fullscreen) 16.dp else 0.dp)
                    .offset(y = if (fullscreen) (-20).dp else 0.dp),
                onScrub = { ms, active ->
                    scrubbing = active; scrubPos = ms; touch()
                    if (!active) Player.seekTo(ms) else controls = true
                },
            )
        }
    }
}

@Composable
private fun TimeText(live: Boolean, scrubbing: Boolean, scrubPos: Long, chapters: List<Chapter>, speed: Float, modifier: Modifier) {
    val pos by Player.position.collectAsState()
    val dur by Player.duration.collectAsState()
    val shown = if (scrubbing) scrubPos else pos
    val chapter = chapters.lastOrNull { it.startMs <= shown }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (live) "● CANLI" else "${Fmt.ms(shown)} / ${Fmt.ms(dur)}",
            color = if (live) C.red else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
        if (chapter != null && chapter.title.isNotBlank()) {
            Text("  ·  ${chapter.title}", color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        if (speed != 1f) Text("  ${Fmt.speed(speed)}x", color = Color(0xFFDDDDDD), fontSize = 12.sp)
    }
}

@Composable
private fun LiveSeekBar(
    chapters: List<Chapter>, skips: List<Skip>, expanded: Boolean, showWhenCollapsed: Boolean,
    modifier: Modifier, onScrub: (Long, Boolean) -> Unit,
) {
    val pos by Player.position.collectAsState()
    val dur by Player.duration.collectAsState()
    val buf by Player.buffered.collectAsState()
    SeekBar(pos, buf, dur, chapters, skips, expanded, showWhenCollapsed, modifier, onScrub)
}

@Composable
private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(0x40000000))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = if (enabled) Color.White else Color(0x66FFFFFF), modifier = Modifier.size((size * 0.6f).dp))
    }
}

/**
 * YouTube ilerleme çubuğu: kırmızı izlenen kısım, gri yüklenen kısım, bölüm aralıkları,
 * yeşil sponsor kısımları. Dokununca kalınlaşır, sürüklerken zaman ve önizleme karesi gösterir.
 */
@Composable
fun SeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    chapters: List<Chapter>,
    skips: List<Skip>,
    expanded: Boolean,
    showWhenCollapsed: Boolean,
    modifier: Modifier = Modifier,
    onScrub: (Long, Boolean) -> Unit,
) {
    var drag by remember { mutableStateOf(false) }
    var frac by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val frames by Player.frames.collectAsState()
    val dur = duration.coerceAtLeast(1)
    val playFrac = if (drag) frac else (position.toFloat() / dur).coerceIn(0f, 1f)
    val bufFrac = (buffered.toFloat() / dur).coerceIn(0f, 1f)
    val visible = expanded || drag || showWhenCollapsed

    Box(modifier.height(if (drag) 124.dp else 24.dp), contentAlignment = Alignment.BottomStart) {
        // Sürüklerken: önizleme karesi + zaman + bölüm adı
        if (drag) {
            val ms = (frac * dur).toLong()
            val chapter = chapters.lastOrNull { it.startMs <= ms }?.title
            val boxW = 160.dp
            val boxWpx = with(density) { boxW.toPx() }
            val x = (frac * widthPx - boxWpx / 2).coerceIn(0f, (widthPx - boxWpx).coerceAtLeast(0f))
            Column(
                Modifier.align(Alignment.TopStart).offset { IntOffset(x.roundToInt(), 0) }.width(boxW),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                frames?.let { f -> FramePreview(f, ms, boxW) }
                if (!chapter.isNullOrBlank()) Text(chapter, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Fmt.ms(ms), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
        if (visible) {
            Canvas(
                Modifier.fillMaxWidth().height(24.dp)
                    .then(if (expanded || drag) Modifier.pointerInput(dur) {
                        widthPx = size.width.toFloat()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            drag = true
                            frac = (down.position.x / size.width).coerceIn(0f, 1f)
                            onScrub((frac * dur).toLong(), true)
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                frac = (c.position.x / size.width).coerceIn(0f, 1f)
                                onScrub((frac * dur).toLong(), true)
                                c.consume()
                            }
                            drag = false
                            onScrub((frac * dur).toLong(), false)
                        }
                    } else Modifier)
            ) {
                widthPx = size.width
                val h = when { drag -> 5.dp.toPx(); expanded -> 3.dp.toPx(); else -> 2.dp.toPx() }
                val y = size.height - h - (if (expanded || drag) 0f else 0f)
                val w = size.width
                // Arka plan, yüklenen, izlenen
                drawRect(Color(0x4DFFFFFF), Offset(0f, y), Size(w, h))
                drawRect(Color(0x80FFFFFF), Offset(0f, y), Size(w * bufFrac, h))
                skips.forEach { s ->
                    val a = s.start.toFloat() / dur * w; val b = s.end.toFloat() / dur * w
                    drawRect(C.sponsor, Offset(a, y), Size((b - a).coerceAtLeast(2f), h))
                }
                drawRect(C.red, Offset(0f, y), Size(w * playFrac, h))
                // Bölüm aralıkları
                val gap = 2.dp.toPx()
                chapters.drop(1).forEach { c ->
                    val cx = c.startMs.toFloat() / dur * w
                    drawRect(Color.Black, Offset(cx - gap / 2, y), Size(gap, h))
                }
                // Tutamaç
                if (expanded || drag) {
                    val r = if (drag) 9.dp.toPx() else 6.dp.toPx()
                    drawCircle(C.red, r, Offset((w * playFrac).coerceIn(r, w - r).let { if (playFrac == 0f) r else it }, y + h / 2))
                }
            }
        }
    }
}

/** Sürüklerken gösterilen önizleme karesi (YouTube'un hikâye panosu) */
@Composable
private fun FramePreview(f: org.schabi.newpipe.extractor.stream.Frameset, ms: Long, width: androidx.compose.ui.unit.Dp) {
    val b = remember(f, ms / 1000) { runCatching { f.getFrameBoundsAt(ms) }.getOrNull() } ?: return
    val url = f.urls.getOrNull(b[0]) ?: return
    val scale = width.value / f.frameWidth
    val h = (f.frameHeight * scale).dp
    Box(Modifier.width(width).height(h).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
        AsyncImage(
            url, null, contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize((f.frameWidth * f.framesPerPageX * scale).dp, (f.frameHeight * f.framesPerPageY * scale).dp)
                .offset((-b[1] * scale).dp, (-b[2] * scale).dp)
        )
    }
    Spacer(Modifier.height(4.dp))
}

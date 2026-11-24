package com.erkam.temasa.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.account.Account
import com.erkam.temasa.data.ChannelRef
import com.erkam.temasa.data.Downloads
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.Store
import com.erkam.temasa.data.Video
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.Comment
import com.erkam.temasa.yt.Extras
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.launch

/** Video ekranı (YouTube'daki gibi): oynatıcı, başlık, kanal, düğmeler, yorumlar, öneriler */
@Composable
fun WatchScreen(vm: MainViewModel, onMinimize: () -> Unit) {
    val video by Player.video.collectAsState()
    val info by Player.info.collectAsState()
    val related by Player.related.collectAsState()
    val queue by Player.queue.collectAsState()
    val qi by Player.queueIndex.collectAsState()
    val aspect by Player.videoAspect.collectAsState()
    val chapters by Player.chapters.collectAsState()
    val settings by Store.settings.collectAsState()
    val subs by Store.subs.collectAsState()
    val dls by Downloads.items.collectAsState()
    val later by Store.watchLater.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(false) }
    var showDesc by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var like by remember { mutableStateOf("INDIFFERENT") }
    var subscribed by remember { mutableStateOf<Boolean?>(null) }
    var dislikes by remember { mutableLongStateOf(-1L) }
    var likes by remember { mutableLongStateOf(-1L) }
    var firstComment by remember { mutableStateOf<Comment?>(null) }
    var commentCount by remember { mutableStateOf(-1) }

    val v = video ?: return
    BackHandler { onMinimize() }

    LaunchedEffect(v.id) {
        like = if (Store.liked.value.any { it.id == v.id }) "LIKE" else "INDIFFERENT"
        subscribed = null; dislikes = -1; likes = -1; firstComment = null; commentCount = -1
        launch { Account.state(v.id)?.let { s -> s.likeStatus?.let { like = it }; s.subscribed?.let { subscribed = it } } }
        if (settings.dislikes) launch { Extras.votes(v.id)?.let { dislikes = it.dislikes; if (likes < 0) likes = it.likes } }
        launch { runCatching { Yt.comments(v.id) }.onSuccess { (p, c) -> firstComment = p.items.firstOrNull(); commentCount = c } }
    }
    LaunchedEffect(info) { info?.likeCount?.takeIf { it >= 0 }?.let { likes = it } }

    val isSub = subscribed ?: subs.any { it.id == v.channelId }
    val dl = dls.firstOrNull { it.video.id == v.id }
    val listState = rememberLazyListState()
    LaunchedEffect(v.id) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding()) {
            PlayerBox(
                vm, fullscreen = false,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(1f, 2.4f)),
                onMinimize = onMinimize,
                onSettings = { showSettings = true },
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "title") {
                Column(Modifier.fillMaxWidth().clickable { showDesc = true }.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                    Text(v.title.ifEmpty { " " }, style = T.titleBig, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(v.viewsText, v.ago).joinToString("  ") + "  ...daha fazla",
                        style = T.meta, maxLines = 1
                    )
                }
                if (chapters.isNotEmpty()) ChapterChip { showDesc = true }
            }
            item(key = "channel") {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = v.channelId.isNotEmpty()) {
                        onMinimize(); vm.push(Screen.Channel(v.channelId, v.channel))
                    }.padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(v.channelAvatar, v.channel, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(v.channel, style = T.title.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        info?.uploaderSubscriberCount?.takeIf { it >= 0 }?.let { Text("  ${Fmt.count(it)}", style = T.meta, maxLines = 1) }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (v.channelId.isNotEmpty()) {
                        val ref = ChannelRef(v.channelId, v.channel, v.channelAvatar)
                        if (isSub) PillButton("Abone olundu", Icons.Outlined.NotificationsNone, onClick = {
                            subscribed = false
                            scope.launch { if (!Account.subscribe(ref, false)) vm.toast("Abonelikten çıkılamadı") }
                        }) else Box(
                            Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(C.text)
                                .clickable {
                                    subscribed = true
                                    scope.launch { if (Account.subscribe(ref, true)) vm.toast("Abone olundu") else vm.toast("Abone olunamadı") }
                                }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Abone ol", color = C.bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            item(key = "actions") {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(C.chip), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.clickable {
                                    val n = if (like == "LIKE") "INDIFFERENT" else "LIKE"
                                    if (n == "LIKE" && like != "LIKE" && likes >= 0) likes++ else if (n != "LIKE" && likes > 0) likes--
                                    if (like == "DISLIKE" && dislikes > 0) dislikes--
                                    like = n
                                    scope.launch { if (!Account.rate(v, n)) vm.toast("YouTube'a işlenemedi") }
                                }.padding(start = 14.dp, end = 12.dp).height(36.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (like == "LIKE") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Beğen", tint = C.text, modifier = Modifier.size(20.dp))
                                if (likes >= 0) Text("  ${Fmt.count(likes)}", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            Box(Modifier.width(1.dp).height(22.dp).background(C.chipHi))
                            Row(
                                Modifier.clickable {
                                    val n = if (like == "DISLIKE") "INDIFFERENT" else "DISLIKE"
                                    if (like == "LIKE" && likes > 0) likes--
                                    if (n == "DISLIKE" && dislikes >= 0) dislikes++ else if (n != "DISLIKE" && dislikes > 0) dislikes--
                                    like = n
                                    scope.launch { if (!Account.rate(v, n)) vm.toast("YouTube'a işlenemedi") }
                                }.padding(start = 12.dp, end = 14.dp).height(36.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (like == "DISLIKE") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Beğenme", tint = C.text, modifier = Modifier.size(20.dp))
                                if (settings.dislikes && dislikes >= 0) Text("  ${Fmt.count(dislikes)}", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    item {
                        PillButton("Paylaş", Icons.Outlined.Share, onClick = {
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${v.id}")
                            ctx.startActivity(Intent.createChooser(i, "Paylaş"))
                        })
                    }
                    item {
                        when {
                            dl?.done == true -> PillButton("İndirildi", Icons.Filled.DownloadDone, onClick = { vm.selectTab(Tab.DOWNLOADS) })
                            dl != null && !dl.failed -> Row(
                                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(C.chip).clickable { vm.selectTab(Tab.DOWNLOADS) }.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(progress = { dl.progress }, color = C.text, trackColor = C.chipHi, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Text("  %${(dl.progress * 100).toInt()}", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            else -> PillButton("İndir", Icons.Outlined.Download, onClick = { vm.downloadFor.value = v })
                        }
                    }
                    item {
                        val isLater = later.any { it.id == v.id }
                        PillButton(if (isLater) "Sonra izlenecek" else "Sonra izle", if (isLater) Icons.Filled.WatchLater else Icons.Outlined.WatchLater, onClick = {
                            scope.launch { Account.watchLater(v, !isLater); vm.toast(if (!isLater) "Sonra izle'ye kaydedildi" else "Sonra izle'den kaldırıldı") }
                        })
                    }
                    item { PillButton("Kaydet", Icons.AutoMirrored.Outlined.PlaylistAdd, onClick = { vm.saveTo.value = v }) }
                }
            }
            item(key = "comments") {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(C.card).clickable { showComments = true }.padding(12.dp)
                ) {
                    Row {
                        Text("Yorumlar", style = T.body.copy(fontWeight = FontWeight.SemiBold))
                        if (commentCount > 0) Text("  ${Fmt.count(commentCount.toLong())}", style = T.body.copy(color = C.text2))
                    }
                    Spacer(Modifier.height(8.dp))
                    firstComment?.let { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.avatar, c.author.removePrefix("@"), 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(c.text, style = T.small.copy(color = C.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } ?: Text(if (commentCount == 0) "Henüz yorum yok" else "Yorumları görmek için dokun", style = T.small)
                }
            }
            if (queue.isNotEmpty()) {
                item(key = "queueHead") {
                    Text("Sıradaki · ${qi + 1}/${queue.size}", style = T.section, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
                itemsIndexed(queue.drop(qi + 1).take(8), key = { i, x -> "q$i${x.id}" }) { _, x ->
                    VideoRow(x, onClick = { vm.open(x, queue) }, thumbWidth = 128.dp)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            if (related.isEmpty() && info == null) {
                items(3) { SkeletonCard() }
            }
            items(related, key = { "r" + it.id }) { r ->
                VideoCard(r, onClick = { vm.open(r) }, onChannel = {
                    if (r.channelId.isNotEmpty()) { onMinimize(); vm.push(Screen.Channel(r.channelId, r.channel)) }
                }, onMore = { vm.menu.value = r })
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (showSettings) PlayerSettingsSheet { showSettings = false }
    if (showDesc) DescriptionSheet(likes) { showDesc = false }
    if (showComments) CommentsSheet(v.id) { showComments = false }
}

@Composable
private fun ChapterChip(onClick: () -> Unit) {
    val chapters by Player.chapters.collectAsState()
    val pos by Player.position.collectAsState()
    val ch = remember(chapters, pos / 1000) { chapters.lastOrNull { it.startMs <= pos } }
    if (ch != null && ch.title.isNotBlank()) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp).clip(RoundedCornerShape(8.dp)).background(C.chip)
                .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
        ) { Text("Bölüm: ${ch.title}", style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Yatay tam ekran oynatıcı */
@Composable
fun FullscreenPlayer(vm: MainViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    BackHandler { vm.exitFullscreen() }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val w = (view.context as? android.app.Activity)?.window
        val c = w?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    PlayerBox(vm, fullscreen = true, modifier = Modifier.fillMaxSize(), onMinimize = { vm.exitFullscreen() }, onSettings = { showSettings = true })
    if (showSettings) PlayerSettingsSheet { showSettings = false }
}

/** Alt menünün üstündeki küçük oynatıcı */
@Composable
fun MiniPlayer(vm: MainViewModel) {
    val v by Player.video.collectAsState()
    val playing by Player.playing.collectAsState()
    val pos by Player.position.collectAsState()
    val dur by Player.duration.collectAsState()
    val loading by Player.loading.collectAsState()
    val video = v ?: return
    Box(Modifier.fillMaxWidth().height(60.dp).background(C.surface).clickable { vm.expanded.value = true }) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(107.dp).height(60.dp).background(Color.Black)) {
                VideoSurface(Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(video.title, style = T.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(video.channel, style = T.meta, maxLines = 1)
            }
            androidx.compose.material3.IconButton(onClick = { Player.toggle() }) {
                if (loading) CircularProgressIndicator(color = C.text, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Duraklat" else "Oynat", tint = C.text)
            }
            androidx.compose.material3.IconButton(onClick = { Player.close() }) {
                Icon(Icons.Filled.Close, "Kapat", tint = C.text)
            }
        }
        val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(C.chipHi))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(2.dp).background(C.red))
    }
}

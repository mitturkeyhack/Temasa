package com.erkam.temasa.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import com.erkam.temasa.data.Updater.Phase
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.erkam.temasa.account.LoginActivity
import com.erkam.temasa.data.Downloads
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.Store
import kotlinx.coroutines.launch

@Composable
fun Logo(modifier: Modifier = Modifier, iconOnly: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(7.dp)).background(C.accent), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.12f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.12f, size.height); close()
                }
                drawPath(p, C.bg)
            }
        }
        if (iconOnly) return@Row
        Spacer(Modifier.width(6.dp))
        Text("Temâşâ", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    }
}

@Composable
fun TopBar(vm: MainViewModel, title: String? = null) {
    val photo by Store.profilePhoto.collectAsState()
    val name by Store.profileName.collectAsState()
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (title == null) Logo() else Text(title, style = T.header)
        Spacer(Modifier.weight(1f))
        if (title == null) FiltreDugmesi(vm)
        IconButton(onClick = { vm.push(Screen.Search()) }) { Icon(Icons.Outlined.Search, "Ara", tint = C.text) }
        IconButton(onClick = { vm.selectTab(Tab.YOU) }) {
            if (photo != null || name != null) Avatar(photo, name ?: "S", 28.dp)
            else Icon(Icons.Outlined.AccountCircle, "Sen", tint = C.text, modifier = Modifier.size(28.dp))
        }
    }
}

// ---------------- Ana sayfa ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, list: LazyListState) {
    val items by vm.home.collectAsState()
    val loading by vm.homeLoading.collectAsState()
    val error by vm.homeError.collectAsState()
    val chip by vm.chip.collectAsState()
    val clip by vm.clip.collectAsState()
    val (suzulmus, bekliyor) = rememberSuzulmus(items)
    LoadMore(list) { vm.moreHome() }

    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        PullToRefreshBox(isRefreshing = loading && items.isNotEmpty(), onRefresh = { vm.refreshHome() }, modifier = Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                item(key = "chips") { ChipRow(vm.chips.map { it.first }, chip, lead = { FiltreChip(vm) }) { vm.selectChip(it) } }
                item(key = "filtre") { FiltreSeridi(vm) }
                clip?.let { c ->
                    item(key = "clip") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1F3D3A)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Link, null, tint = Color(0xFF7EE3D8), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Panoda YouTube bağlantısı var", color = Color(0xFFE6FAF7), fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Box(
                                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(C.accent)
                                    .clickable { vm.clip.value = null; vm.handleUrl(c) }.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("İzle", color = C.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                            IconButton(onClick = { vm.clip.value = null }) { Icon(Icons.Filled.Close, "Kapat", tint = C.text2, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                if ((items.isEmpty() && loading) || (suzulmus.isEmpty() && bekliyor)) items(4) { SkeletonCard() }
                if (items.isEmpty() && !loading && error != null) item {
                    Box(Modifier.fillMaxWidth().clickable { vm.refreshHome() }) {
                        Empty(Icons.Outlined.WifiOff, "Bağlantı yok", error, "Tekrar dene" to { vm.refreshHome() })
                    }
                }
                items(suzulmus, key = { "h" + it.id }) { v ->
                    VideoCard(v, onClick = { vm.open(v) }, onChannel = {
                        if (v.channelId.isNotEmpty()) vm.push(Screen.Channel(v.channelId, v.channel))
                    }, onMore = { vm.menu.value = v })
                }
                if (items.isNotEmpty()) item { Loader() }
                if (bekliyor && suzulmus.size < 4 && items.size > 10) item { LaunchedEffect(items.size) { vm.moreHome() } }
            }
        }
    }
}

// ---------------- Abonelikler ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubsScreen(vm: MainViewModel, list: LazyListState) {
    val feed by vm.subsFeed.collectAsState()
    val loading by vm.subsLoading.collectAsState()
    val subs by Store.subs.collectAsState()
    val signed by vm.signedIn.collectAsState()
    val history by Store.history.collectAsState()
    var filter by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.onSignedChanged()
    }
    LoadMore(list) { vm.moreSubs() }
    val shown = remember(feed, filter, history) {
        when (filter) {
            1 -> feed.filter { it.ago?.let { a -> a.contains("saat") || a.contains("dakika") || a.contains("saniye") || a.contains("az önce") } == true }
            2 -> { val seen = history.map { it.id }.toSet(); feed.filter { it.id !in seen } }
            3 -> feed.filter { it.live }
            else -> feed
        }
    }
    val (suzulmus, bekliyor) = rememberSuzulmus(shown)

    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Abonelikler")
        PullToRefreshBox(isRefreshing = loading && feed.isNotEmpty(), onRefresh = { vm.refreshSubs(force = true) }, modifier = Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                if (subs.isNotEmpty()) item(key = "chans") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(subs, key = { it.id }) { c ->
                            Column(Modifier.width(64.dp).clickable { vm.push(Screen.Channel(c.id, c.name)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Avatar(c.avatar, c.name, 56.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(c.name, color = C.text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Divider()
                }
                if (subs.isNotEmpty() || signed) item(key = "chips") {
                    ChipRow(listOf("Tümü", "Bugün", "İzlenmedi", "Canlı"), filter) { filter = it }
                }
                if (subs.isEmpty() && !signed && !loading) item {
                    Empty(
                        Icons.Outlined.Subscriptions, "Abonelik yok",
                        "Bir kanala abone ol ya da oturum aç: YouTube'daki aboneliklerin buraya gelsin.",
                        "Oturum aç" to { login.launch(Intent(ctx, LoginActivity::class.java)) }
                    )
                }
                if ((feed.isEmpty() && loading) || (suzulmus.isEmpty() && bekliyor)) items(4) { SkeletonCard() }
                items(suzulmus, key = { "s" + it.id }) { v ->
                    VideoCard(v, onClick = { vm.open(v) }, onChannel = {
                        if (v.channelId.isNotEmpty()) vm.push(Screen.Channel(v.channelId, v.channel))
                    }, onMore = { vm.menu.value = v })
                }
            }
        }
    }
}

// ---------------- İndirilenler ----------------

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val items by Downloads.items.collectAsState()
    var confirmAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "İndirilenler")
        LazyColumn(Modifier.fillMaxSize()) {
            if (items.isEmpty()) item {
                Empty(Icons.Outlined.Download, "İndirilen video yok", "Bir videonun altındaki İndir düğmesine bas. İnternetsiz de izleyebilirsin.")
            } else item {
                val total = Downloads.totalBytes()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${items.count { it.done }} video · ${Fmt.bytes(total)}", style = T.body.copy(color = C.text2), modifier = Modifier.weight(1f))
                        Text("Tümünü sil", color = C.blue, fontSize = 14.sp, modifier = Modifier.clickable { confirmAll = true }.padding(8.dp))
                    }
                    Text("Sadece Temâşâ içinde açılır, galeride görünmez.", style = T.meta)
                }
            }
            items(items, key = { "d" + it.video.id }) { d ->
                VideoRow(
                    d.video,
                    onClick = { if (d.done) vm.open(d.video) else if (d.failed) Downloads.retry(d.video.id) },
                    onMore = { Downloads.delete(d.video.id) },
                    sub = when {
                        d.done -> "${d.video.channel} · ${d.height}p · ${Fmt.bytes(d.total)}"
                        d.failed -> "İndirilemedi · yeniden denemek için dokun"
                        d.total == 0L -> "Hazırlanıyor..."
                        else -> "%${(d.progress * 100).toInt()} · ${Fmt.bytes(d.speed)}/sn"
                    },
                    extra = if (!d.done && !d.failed) ({
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { d.progress }, color = C.red, trackColor = C.chipHi, modifier = Modifier.fillMaxWidth().height(3.dp))
                    }) else null,
                )
            }
        }
    }
    if (confirmAll) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmAll = false }, containerColor = C.surface,
        title = { Text("Tüm indirilenler silinsin mi?", color = C.text) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { Downloads.deleteAll(); confirmAll = false }) { Text("Sil", color = C.red) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmAll = false }) { Text("İptal", color = C.text) } },
    )
}

// ---------------- Sen ----------------

@Composable
fun YouScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val signed by vm.signedIn.collectAsState()
    val name by Store.profileName.collectAsState()
    val photo by Store.profilePhoto.collectAsState()
    val handle by Store.profileHandle.collectAsState()
    val history by Store.history.collectAsState()
    val lists by Store.lists.collectAsState()
    val later by Store.watchLater.collectAsState()
    val liked by Store.liked.collectAsState()
    val dls by Downloads.items.collectAsState()
    val scope = rememberCoroutineScope()
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { vm.onSignedChanged(); vm.toast("Oturum açıldı") }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.push(Screen.Search()) }) { Icon(Icons.Outlined.Search, "Ara", tint = C.text) }
                IconButton(onClick = { vm.push(Screen.Settings) }) { Icon(Icons.Outlined.Settings, "Ayarlar", tint = C.text) }
            }
            if (signed) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(photo, name ?: "S", 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name ?: "Google hesabı", style = T.header.copy(fontSize = 22.sp))
                        Text(handle ?: "YouTube hesabın bağlı", style = T.meta)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = C.text2, modifier = Modifier.size(88.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Abonelik ve beğenileri kullan", style = T.title.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(6.dp))
                    Text("Kanallara abone ol, videoları beğen; YouTube'daki ana sayfan, aboneliklerin ve Sonra izle listen gelsin.", style = T.body.copy(color = C.text2), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinePill("Oturum aç", { login.launch(Intent(ctx, LoginActivity::class.java)) }, Icons.Outlined.AccountCircle)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (history.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Geçmiş", style = T.section, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(16.dp)).border1().clickable { vm.push(Screen.Library("history", "Geçmiş")) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Tümünü gör", color = C.text, fontSize = 13.sp)
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history.take(20), key = { "y" + it.id }) { v ->
                    Column(Modifier.width(156.dp).clickable { vm.open(v) }) {
                        Thumb(v, Modifier.fillMaxWidth(), corner = 10.dp, small = true)
                        Spacer(Modifier.height(6.dp))
                        Text(v.title, style = T.small.copy(color = C.text, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(v.channel, style = T.meta, maxLines = 1)
                    }
                }
            }
        }
        item {
            Divider()
            MenuRow(Icons.Outlined.History, "Geçmiş") { vm.push(Screen.Library("history", "Geçmiş")) }
            MenuRow(Icons.Outlined.WatchLater, "Sonra izle", if (signed) null else later.size.toString()) { vm.push(Screen.Library("later", "Sonra izle")) }
            MenuRow(Icons.Outlined.ThumbUp, "Beğenilen videolar", if (signed) null else liked.size.toString()) { vm.push(Screen.Library("liked", "Beğenilen videolar")) }
            MenuRow(Icons.AutoMirrored.Outlined.PlaylistPlay, "Oynatma listeleri", lists.size.takeIf { it > 0 }?.toString()) { vm.push(Screen.Library("lists", "Oynatma listeleri")) }
            MenuRow(Icons.Outlined.Download, "İndirilenler", dls.count { it.done }.takeIf { it > 0 }?.let { "$it video" }) { vm.selectTab(Tab.DOWNLOADS) }
            Divider()
            MenuRow(Icons.Outlined.Settings, "Ayarlar") { vm.push(Screen.Settings) }
            if (signed) MenuRow(Icons.Outlined.Logout, "Oturumu kapat") { Account.signOut(); vm.onSignedChanged(); vm.toast("Oturum kapatıldı") }
            UpdateCard()
            Spacer(Modifier.height(28.dp))
        }
    }
}


// ---------------- Güncelleme kartı (Sen sekmesinin en altı) ----------------

@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val st by com.erkam.temasa.data.Updater.state.collectAsState()
    val U = com.erkam.temasa.data.Updater
    val cur = remember { U.current(ctx) }
    androidx.compose.runtime.LaunchedEffect(Unit) { U.check(ctx, silent = true) }
    val rel = st.release
    val highlight = st.phase == Phase.AVAILABLE || st.phase == Phase.READY
    val ago = if (st.checkedAt > 0) {
        val m = (System.currentTimeMillis() - st.checkedAt) / 60000
        if (m < 1) "az önce denetlendi" else if (m < 60) "$m dk önce denetlendi" else "${m / 60} saat önce denetlendi"
    } else "henüz denetlenmedi"
    val (title, sub) = when (st.phase) {
        Phase.CHECKING -> "Denetleniyor..." to "Sürüm $cur"
        Phase.AVAILABLE -> "Yeni sürüm: ${rel?.version}" to "Şu an $cur · ${Fmt.bytes(rel?.size ?: 0)}"
        Phase.DOWNLOADING -> "İndiriliyor..." to "Yeni sürüm ${rel?.version}"
        Phase.READY -> "Kurulmaya hazır" to (st.error ?: "Yükle'ye bas, birkaç saniye sürer")
        Phase.INSTALLING -> "Kuruluyor..." to "Android'in onay ekranında Yükle'ye bas"
        Phase.ERROR -> "Güncelleme" to (st.error ?: "Bir sorun oldu")
        else -> "Temâşâ güncel" to "Sürüm $cur · $ago"
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF181818))
                .border(1.dp, if (highlight) C.accent.copy(alpha = 0.45f) else Color(0xFF2A2A2A), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp)) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.bg).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) { Logo(Modifier, iconOnly = true) }
                    if (highlight) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF181818)).padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.red))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = T.title.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(3.dp))
                    Text(sub, style = T.meta.copy(fontSize = 13.sp, lineHeight = 18.sp))
                }
                Spacer(Modifier.width(8.dp))
                val (label, filled, action) = when (st.phase) {
                    Phase.AVAILABLE -> Triple("Güncelle", true) { U.download(ctx) }
                    Phase.DOWNLOADING -> Triple("İptal", false) { U.cancel() }
                    Phase.READY -> Triple("Yükle", true) { U.install(ctx) }
                    Phase.INSTALLING -> Triple("Yükle", true) { U.install(ctx) }
                    Phase.CHECKING -> Triple("Denetle", false) { }
                    else -> Triple("Denetle", false) { U.check(ctx) }
                }
                Box(
                    Modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
                        .background(if (filled) C.accent else Color.Transparent)
                        .then(if (filled) Modifier else Modifier.border(1.dp, C.chipHi, RoundedCornerShape(19.dp)))
                        .clickable(enabled = st.phase != Phase.CHECKING, onClick = action).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (st.phase == Phase.CHECKING) androidx.compose.material3.CircularProgressIndicator(color = C.text, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Text(label, color = if (filled) C.bg else C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if ((st.phase == Phase.AVAILABLE || st.phase == Phase.READY) && rel != null && rel.notes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF202020)).padding(12.dp)) {
                    Text("YENİLİKLER", color = C.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
                    rel.notes.forEach { n ->
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Text("•", color = C.accent, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(n, style = T.body.copy(fontSize = 13.sp, lineHeight = 18.sp))
                        }
                    }
                }
            }
            if (st.phase == Phase.DOWNLOADING) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A2A2A))) {
                    Box(Modifier.fillMaxWidth(st.progress).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.accent))
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("%${(st.progress * 100).toInt()}", style = T.meta, modifier = Modifier.weight(1f))
                    Text("${Fmt.bytes(st.bytes)} / ${Fmt.bytes(rel?.size ?: 0)}", style = T.meta)
                }
            }
        }
        Text(
            "Videolar açılmazsa önce buradan güncelle. YouTube bir şey değiştirdiğinde düzeltme buraya gelir.",
            color = Color(0xFF8A8A8A), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

package com.erkam.temasa.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.erkam.temasa.BuildConfigLite
import com.erkam.temasa.account.Account
import com.erkam.temasa.account.LoginActivity
import com.erkam.temasa.data.ChannelRef
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.PlaylistRef
import com.erkam.temasa.data.Store
import com.erkam.temasa.data.Video
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.ChannelPage
import com.erkam.temasa.yt.Inner
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

@Composable
fun BackBar(vm: MainViewModel, title: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = C.text) }
        Text(title, style = T.header.copy(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

// ---------------- Arama ----------------

@Composable
fun SearchScreen(vm: MainViewModel, initial: String?) {
    var text by remember { mutableStateOf(initial ?: "") }
    var query by remember { mutableStateOf(initial) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var results by remember { mutableStateOf<List<Any>>(emptyList()) }
    var next by remember { mutableStateOf<Page?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableIntStateOf(0) }
    val searches by Store.searches.collectAsState()
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    fun submit(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        if (t.contains("youtu") && vm.handleUrl(t)) return
        kb?.hide()
        text = t; query = t
        Store.addSearch(t)
        vm.stack.value = vm.stack.value.dropLast(1) + Screen.Search(t)
    }

    LaunchedEffect(Unit) { if (initial == null) { delay(100); runCatching { focus.requestFocus() } } }
    LaunchedEffect(text, query) {
        if (query != null && text == query) return@LaunchedEffect
        delay(180)
        suggestions = if (text.isBlank()) emptyList() else Yt.suggestions(text)
    }
    LaunchedEffect(query, filter) {
        val q = query ?: return@LaunchedEffect
        loading = true; results = emptyList(); next = null
        runCatching { Yt.search(q, Yt.Filter.entries[filter]) }.onSuccess { results = it.items; next = it.next }
        loading = false
        list.scrollToItem(0)
    }
    LoadMore(list) {
        val q = query ?: return@LoadMore
        val n = next ?: return@LoadMore
        if (loading) return@LoadMore
        loading = true
        scope.launch {
            runCatching { Yt.search(q, Yt.Filter.entries[filter], n) }.onSuccess { p -> results = results + p.items; next = p.next }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = C.text) }
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(C.chip).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("YouTube'da ara", color = C.text2, fontSize = 16.sp)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it; if (query != null && it != query) query = null },
                        singleLine = true,
                        textStyle = T.body.copy(fontSize = 16.sp),
                        cursorBrush = SolidColor(C.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (text.isNotEmpty()) IconButton(onClick = { text = ""; query = null; runCatching { focus.requestFocus() } }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Temizle", tint = C.text2, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (query == null) {
            val hist = searches.filter { text.isBlank() || it.contains(text, true) }.take(if (text.isBlank()) 15 else 4)
            LazyColumn(Modifier.fillMaxSize()) {
                items(hist, key = { "h$it" }) { s -> SuggestRow(s, true, { submit(s) }, { text = "$s " }, { Store.removeSearch(s) }) }
                items(suggestions.filter { s -> hist.none { it.equals(s, true) } }, key = { "s$it" }) { s -> SuggestRow(s, false, { submit(s) }, { text = "$s " }, null) }
            }
        } else {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                item { ChipRow(Yt.Filter.entries.map { it.label }, filter) { filter = it } }
                if (loading && results.isEmpty()) items(4) { SkeletonCard() }
                if (!loading && results.isEmpty()) item { Empty(Icons.Outlined.SearchOff, "Sonuç bulunamadı", "Farklı kelimelerle aramayı dene.") }
                items(results, key = { r -> when (r) { is Video -> "v" + r.id; is ChannelRef -> "c" + r.id; is PlaylistRef -> "p" + r.id; else -> r.hashCode().toString() } }) { r ->
                    when (r) {
                        is Video -> VideoCard(r, onClick = { vm.open(r) }, onChannel = {
                            if (r.channelId.isNotEmpty()) vm.push(Screen.Channel(r.channelId, r.channel))
                        }, onMore = { vm.menu.value = r })
                        is ChannelRef -> ChannelRow(r, { vm.push(Screen.Channel(r.id, r.name)) })
                        is PlaylistRef -> PlaylistRow(r) { vm.push(Screen.Playlist(r.id, r.title)) }
                    }
                }
                if (loading && results.isNotEmpty()) item { Loader() }
            }
        }
    }
}

@Composable
private fun SuggestRow(s: String, history: Boolean, onClick: () -> Unit, onFill: () -> Unit, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).height(52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (history) Icons.Outlined.History else Icons.Outlined.Search, null, tint = C.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(20.dp))
        Text(s, style = T.body.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Kaldır", tint = C.text2, modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onFill) { Icon(Icons.Filled.NorthWest, "Doldur", tint = C.text2, modifier = Modifier.size(20.dp)) }
    }
}

// ---------------- Kanal ----------------

@Composable
fun ChannelScreen(vm: MainViewModel, id: String, name: String) {
    var page by remember { mutableStateOf<ChannelPage?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Any>>(emptyList()) }
    var next by remember { mutableStateOf<Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    val subs by Store.subs.collectAsState()
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val tabs = listOf("Videolar" to "videos", "Canlı" to "livestreams", "Listeler" to "playlists", "Hakkında" to "")

    LaunchedEffect(id) { runCatching { Yt.channel(id) }.onSuccess { page = it }.onFailure { failed = true; loading = false } }
    LaunchedEffect(page, tab) {
        val p = page ?: return@LaunchedEffect
        val key = tabs[tab].second
        items = emptyList(); next = null
        val t = p.tabs[key] ?: run { loading = false; return@LaunchedEffect }
        loading = true
        runCatching { Yt.channelTab(t) }.onSuccess { items = it.items; next = it.next }
        loading = false
    }
    LoadMore(list) {
        val p = page ?: return@LoadMore
        val n = next ?: return@LoadMore
        val t = p.tabs[tabs[tab].second] ?: return@LoadMore
        if (loading) return@LoadMore
        loading = true
        scope.launch { runCatching { Yt.channelTab(t, n) }.onSuccess { r -> items = items + r.items; next = r.next }; loading = false }
    }

    val ref = page?.ref ?: ChannelRef(id, name)
    val isSub = subs.any { it.id == ref.id }
    Column(Modifier.fillMaxSize()) {
        BackBar(vm, ref.name.ifEmpty { name })
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            item {
                page?.banner?.let { b ->
                    AsyncImage(b, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(4.2f).clip(RoundedCornerShape(12.dp)))
                }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(ref.avatar, ref.name.ifEmpty { name }, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ref.name.ifEmpty { name }, style = T.header.copy(fontSize = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ref.subs?.let { Text(it, style = T.meta) }
                    }
                }
                if (failed) Text("Kanal yüklenemedi.", style = T.body.copy(color = C.text2), modifier = Modifier.padding(16.dp))
                if (page != null) {
                    Box(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp))
                            .background(if (isSub) C.chip else C.text)
                            .clickable { scope.launch { if (!Account.subscribe(ref, !isSub)) vm.toast("İşlem YouTube'a iletilemedi") else if (!isSub) vm.toast("Abone olundu") } },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSub) { Icon(Icons.Outlined.NotificationsNone, null, tint = C.text, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
                            Text(if (isSub) "Abone olundu" else "Abone ol", color = if (isSub) C.text else C.bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    ChipRow(tabs.map { it.first }, tab) { tab = it }
                }
            }
            if (tab == 3 && page != null) item {
                Text(page!!.description.ifBlank { "Açıklama yok." }, style = T.body, modifier = Modifier.padding(16.dp))
            }
            if (loading && items.isEmpty() && tab != 3) items(3) { SkeletonCard() }
            if (!loading && items.isEmpty() && tab != 3 && page != null) item { Text("Burada bir şey yok.", style = T.body.copy(color = C.text2), modifier = Modifier.padding(24.dp)) }
            items(items, key = { r -> when (r) { is Video -> "v" + r.id; is PlaylistRef -> "p" + r.id; else -> r.hashCode().toString() } }) { r ->
                when (r) {
                    is Video -> {
                        val vv = if (r.channelAvatar == null) r.copy(channelAvatar = ref.avatar, channel = r.channel.ifEmpty { ref.name }, channelId = r.channelId.ifEmpty { ref.id }) else r
                        VideoCard(vv, onClick = { vm.open(vv, items.filterIsInstance<Video>()) }, onMore = { vm.menu.value = vv })
                    }
                    is PlaylistRef -> PlaylistRow(r) { vm.push(Screen.Playlist(r.id, r.title)) }
                }
            }
            if (loading && items.isNotEmpty()) item { Loader() }
        }
    }
}

// ---------------- Oynatma listesi (YouTube) ----------------

@Composable
fun PlaylistScreen(vm: MainViewModel, id: String, title: String) {
    var ref by remember { mutableStateOf<PlaylistRef?>(null) }
    var items by remember { mutableStateOf<List<Video>>(emptyList()) }
    var next by remember { mutableStateOf<Page?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(id) {
        runCatching { Yt.playlist(id) }
            .onSuccess { (r, p) -> ref = r; items = p.items; next = p.next }
            .onFailure {
                // Özel liste (girişliyse YouTube hesabından)
                if (Account.signedIn) runCatching { withContext(Dispatchers.IO) { Inner.browse("VL$id") } }
                    .onSuccess { f -> items = f.videos; token = f.next; ref = PlaylistRef(id, title) }
                    .onFailure { failed = true }
                else failed = true
            }
        loading = false
    }
    LoadMore(list) {
        if (loading) return@LoadMore
        val n = next; val t = token
        if (n == null && t == null) return@LoadMore
        loading = true
        scope.launch {
            if (n != null) runCatching { Yt.playlist(id, n) }.onSuccess { (_, p) -> items = (items + p.items).distinctBy { it.id }; next = p.next }
            else if (t != null) runCatching { withContext(Dispatchers.IO) { Inner.more(t) } }.onSuccess { f -> items = (items + f.videos).distinctBy { it.id }; token = f.next }
            loading = false
        }
    }
    VideoListPage(vm, ref?.title?.ifEmpty { title } ?: title, ref?.channel, ref?.count ?: if (items.isNotEmpty()) "${items.size} video" else null, items, loading, failed, list)
}

/** Liste başlığı + Tümünü oynat / Karıştır + videolar */
@Composable
fun VideoListPage(
    vm: MainViewModel,
    title: String,
    owner: String?,
    count: String?,
    items: List<Video>,
    loading: Boolean,
    failed: Boolean,
    list: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    actions: @Composable () -> Unit = {},
    onRemove: ((Video) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        BackBar(vm, "", actions)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    items.firstOrNull()?.let { Thumb(it, Modifier.fillMaxWidth(), corner = 12.dp) }
                    Spacer(Modifier.height(14.dp))
                    Text(title, style = T.header.copy(fontSize = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(listOfNotNull(owner?.takeIf { it.isNotEmpty() }, count).joinToString(" · "), style = T.meta)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(C.text)
                                .clickable(enabled = items.isNotEmpty()) { vm.open(items.first(), items) },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = C.bg)
                                Text(" Tümünü oynat", color = C.bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(C.chip)
                                .clickable(enabled = items.isNotEmpty()) { val s = items.shuffled(); vm.open(s.first(), s) },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Shuffle, null, tint = C.text, modifier = Modifier.size(20.dp))
                                Text(" Karıştır", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                header?.invoke()
            }
            if (loading && items.isEmpty()) items(5) { SkeletonRow() }
            if (!loading && items.isEmpty()) item {
                Text(if (failed) "Liste yüklenemedi." else "Bu listede video yok.", style = T.body.copy(color = C.text2), modifier = Modifier.padding(24.dp))
            }
            items(items, key = { "l" + it.id }) { v ->
                VideoRow(v, onClick = { vm.open(v, items) }, onMore = if (onRemove != null) ({ onRemove(v) }) else ({ vm.menu.value = v }))
            }
            if (loading && items.isNotEmpty()) item { Loader() }
        }
    }
}

// ---------------- Kitaplık: geçmiş, sonra izle, beğenilenler, listeler ----------------

@Composable
fun LibraryScreen(vm: MainViewModel, id: String, title: String) {
    val signed by vm.signedIn.collectAsState()
    when (id) {
        "history" -> HistoryScreen(vm)
        "lists" -> ListsScreen(vm)
        "later", "liked" -> {
            val local by (if (id == "later") Store.watchLater else Store.liked).collectAsState()
            var remote by remember { mutableStateOf<List<Video>?>(null) }
            var token by remember { mutableStateOf<String?>(null) }
            var loading by remember { mutableStateOf(signed) }
            val scope = rememberCoroutineScope()
            val list = rememberLazyListState()
            LaunchedEffect(signed) {
                if (!signed) return@LaunchedEffect
                runCatching { withContext(Dispatchers.IO) { if (id == "later") Inner.watchLater() else Inner.likedVideos() } }
                    .onSuccess { remote = it.videos; token = it.next }
                loading = false
            }
            LoadMore(list) {
                val t = token ?: return@LoadMore
                if (loading) return@LoadMore
                loading = true
                scope.launch { runCatching { withContext(Dispatchers.IO) { Inner.more(t) } }.onSuccess { f -> remote = (remote.orEmpty() + f.videos).distinctBy { it.id }; token = f.next }; loading = false }
            }
            val items = remember(local, remote) { ((remote ?: emptyList()) + local).distinctBy { it.id } }
            VideoListPage(
                vm, title, Store.profileName.collectAsState().value, "${items.size} video", items, loading, false, list,
                onRemove = if (id == "later") ({ v -> scope.launch { Account.watchLater(v, false); remote = remote?.filter { it.id != v.id } } }) else null,
            )
        }
        else -> {
            val lists by Store.lists.collectAsState()
            val l = lists.firstOrNull { it.id == id }
            var confirm by remember { mutableStateOf(false) }
            VideoListPage(
                vm, l?.name ?: title, "Temâşâ listesi", "${l?.videos?.size ?: 0} video", l?.videos ?: emptyList(), false, false,
                actions = { IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Listeyi sil", tint = C.text) } },
                onRemove = { v -> Store.removeFromList(id, v.id); vm.toast("Listeden kaldırıldı") },
            )
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false }, containerColor = C.surface,
                title = { Text("\"${l?.name}\" silinsin mi?", color = C.text) },
                confirmButton = { TextButton(onClick = { Store.deleteList(id); confirm = false; vm.pop() }) { Text("Sil", color = C.red) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("İptal", color = C.text) } },
            )
        }
    }
}

@Composable
private fun HistoryScreen(vm: MainViewModel) {
    val local by Store.history.collectAsState()
    val signed by vm.signedIn.collectAsState()
    var remote by remember { mutableStateOf<List<Video>>(emptyList()) }
    var token by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    // Girişliyse YouTube'daki izleme geçmişi de gelir
    LaunchedEffect(signed) {
        if (signed) runCatching { withContext(Dispatchers.IO) { Inner.history() } }.onSuccess { remote = it.videos; token = it.next }
    }
    LoadMore(list) {
        val t = token ?: return@LoadMore
        if (busy) return@LoadMore
        busy = true
        scope.launch { runCatching { withContext(Dispatchers.IO) { Inner.more(t) } }.onSuccess { f -> remote = remote + f.videos; token = f.next }; busy = false }
    }
    val history = remember(local, remote) { (local + remote).distinctBy { it.id } }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackBar(vm, "Geçmiş") {
            if (history.isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Geçmişi temizle", tint = C.text) }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            if (history.isEmpty()) item { Empty(Icons.Outlined.History, "Geçmiş boş", "İzlediğin videolar burada görünür.") }
            items(history, key = { "g" + it.id }) { v -> VideoRow(v, onClick = { vm.open(v) }, onMore = { vm.menu.value = v }) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = C.surface,
        title = { Text("İzleme geçmişi temizlensin mi?", color = C.text) },
        text = { Text("Kaldığın yer bilgileri de silinir.", color = C.text2) },
        confirmButton = { TextButton(onClick = { Store.clearHistory(); confirm = false }) { Text("Temizle", color = C.red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("İptal", color = C.text) } },
    )
}

@Composable
private fun ListsScreen(vm: MainViewModel) {
    val lists by Store.lists.collectAsState()
    val signed by vm.signedIn.collectAsState()
    var remote by remember { mutableStateOf<List<PlaylistRef>>(emptyList()) }
    LaunchedEffect(signed) {
        if (signed) runCatching { withContext(Dispatchers.IO) { Inner.library() } }.onSuccess { remote = it.playlists }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(vm, "Oynatma listeleri")
        LazyColumn(Modifier.fillMaxSize()) {
            if (lists.isEmpty() && remote.isEmpty()) item {
                Empty(Icons.AutoMirrored.Outlined.PlaylistPlay, "Liste yok", "Bir videonun altındaki Kaydet düğmesiyle liste oluştur.")
            }
            items(lists, key = { "L" + it.id }) { l ->
                Row(Modifier.fillMaxWidth().clickable { vm.push(Screen.Library(l.id, l.name)) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(C.chip), contentAlignment = Alignment.Center) {
                        l.videos.firstOrNull()?.let { Thumb(it, Modifier.fillMaxWidth(), small = true) }
                            ?: Icon(Icons.Outlined.VideoLibrary, null, tint = C.text2)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(top = 2.dp)) {
                        Text(l.name, style = T.body.copy(fontWeight = FontWeight.Medium))
                        Text("Temâşâ · ${l.videos.size} video", style = T.meta)
                    }
                }
            }
            items(remote, key = { "R" + it.id }) { p -> PlaylistRow(p) { vm.push(Screen.Playlist(p.id, p.title)) } }
        }
    }
}

// ---------------- Ayarlar ----------------

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by Store.settings.collectAsState()
    val signed by vm.signedIn.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    var cache by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { cache = Player.cacheSize() }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.onSignedChanged()
    }
    fun q(h: Int) = if (h == 0) "Otomatik" else "${h}p"

    Column(Modifier.fillMaxSize()) {
        BackBar(vm, "Ayarlar")
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Head("Genel")
                SwitchRow("Otomatik oynatma", "Video bitince sıradakine geç", s.autoplay) { v -> Store.updateSettings { it.copy(autoplay = v) } }
                SwitchRow("Arka planda oynatma", "Uygulamadan çıkınca ya da ekran kapanınca devam et", s.background) { v -> Store.updateSettings { it.copy(background = v) } }
                SwitchRow("Küçük pencere", "Ana ekrana dönünce video küçük pencerede sürsün", s.pip) { v -> Store.updateSettings { it.copy(pip = v) } }
                SwitchRow("Kaldığın yerden devam", null, s.resume) { v -> Store.updateSettings { it.copy(resume = v) } }
                SwitchRow("Panodaki bağlantıyı yakala", "Kopyaladığın YouTube bağlantısını tek dokunuşla aç", s.clipboard) { v -> Store.updateSettings { it.copy(clipboard = v) } }

                Head("Video kalitesi")
                MenuRow(null, "Wi-Fi'de", q(s.qualityWifi)) { dialog = "wifi" }
                MenuRow(null, "Mobil veride", q(s.qualityMobile)) { dialog = "mobile" }
                MenuRow(null, "İndirme kalitesi", "${s.downloadQuality}p") { dialog = "dl" }

                Head("Oynatma")
                MenuRow(null, "Varsayılan hız", "${Fmt.speed(s.speed)}x") { dialog = "speed" }
                SwitchRow("Kanal hızını hatırla", "Her kanal için seçtiğin hız saklanır", s.rememberChannelSpeed) { v -> Store.updateSettings { it.copy(rememberChannelSpeed = v) } }
                SwitchRow("Sponsorları atla", "Sponsor, tanıtım ve \"abone ol\" kısımları otomatik atlanır", s.sponsorBlock) { v -> Store.updateSettings { it.copy(sponsorBlock = v) } }
                SwitchRow("Beğenmeme sayısını göster", null, s.dislikes) { v -> Store.updateSettings { it.copy(dislikes = v) } }
                SwitchRow("Altyazılar açık başlasın", null, s.subtitles) { v -> Store.updateSettings { it.copy(subtitles = v) } }

                Head("YouTube bağlantıları")
                MenuRow(null, "Bağlantıları Temâşâ ile aç", "Ayarla") {
                    val i = if (Build.VERSION.SDK_INT >= 31) Intent(AndroidSettings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    else Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    runCatching { ctx.startActivity(i) }
                }
                Text(
                    "Açılan sayfada \"Desteklenen bağlantıları aç\" seçeneğini aç ve YouTube bağlantılarını ekle. YouTube uygulaması bağlantıları kendine ayırdıysa, YouTube uygulamasının ayarlarından bu seçeneği kapat. Ayrıca her zaman: Paylaş → Temâşâ ile izle.",
                    style = T.meta, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                Head("Hesap")
                if (signed) MenuRow(null, "Oturumu kapat", Store.profileName.collectAsState().value) { Account.signOut(); vm.onSignedChanged() }
                else MenuRow(null, "Google ile oturum aç") { login.launch(Intent(ctx, LoginActivity::class.java)) }

                Head("Depolama")
                MenuRow(null, "İzleme geçmişini temizle") { dialog = "clearHistory" }
                MenuRow(null, "Arama geçmişini temizle") { Store.clearSearches(); vm.toast("Arama geçmişi temizlendi") }
                MenuRow(null, "Ön belleği temizle", Fmt.bytes(cache)) { Player.clearCache(); scope.launch { cache = Player.cacheSize() }; vm.toast("Ön bellek temizlendi") }

                Head("Hakkında")
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Logo()
                    Spacer(Modifier.height(8.dp))
                    Text("Sürüm ${BuildConfigLite.version(ctx)}", style = T.meta)
                    Text("Erkam Yazılım", style = T.body)
                    Text("Geliştirici: Sadullah Akça", style = T.body)
                    Spacer(Modifier.height(6.dp))
                    Text("YouTube Premium ücretli olduğu için geliştirildi: reklamsız, arka planda, istediğin hızda.", style = T.meta)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    when (dialog) {
        "wifi", "mobile" -> Choice(
            if (dialog == "wifi") "Wi-Fi'de kalite" else "Mobil veride kalite",
            listOf(0, 2160, 1440, 1080, 720, 480, 360, 240).map { q(it) to it },
            if (dialog == "wifi") s.qualityWifi else s.qualityMobile,
        ) { v -> val d = dialog; Store.updateSettings { if (d == "wifi") it.copy(qualityWifi = v) else it.copy(qualityMobile = v) }; dialog = null }
        "dl" -> Choice("İndirme kalitesi", listOf(1080, 720, 480, 360).map { "${it}p" to it }, s.downloadQuality) { v -> Store.updateSettings { it.copy(downloadQuality = v) }; dialog = null }
        "speed" -> Choice("Varsayılan hız", listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).map { "${Fmt.speed(it)}x" to (it * 100).toInt() }, (s.speed * 100).toInt()) { v ->
            Store.updateSettings { it.copy(speed = v / 100f) }; dialog = null
        }
        "clearHistory" -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = C.surface,
            title = { Text("İzleme geçmişi temizlensin mi?", color = C.text) },
            confirmButton = { TextButton(onClick = { Store.clearHistory(); dialog = null; vm.toast("Geçmiş temizlendi") }) { Text("Temizle", color = C.red) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("İptal", color = C.text) } },
        )
    }
    if (dialog != null && dialog !in listOf("wifi", "mobile", "dl", "speed", "clearHistory")) dialog = null
}

@Composable
private fun Head(t: String) {
    Spacer(Modifier.height(12.dp))
    Text(t, color = C.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun Choice(title: String, options: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = { onPick(selected) }, containerColor = C.surface,
        title = { Text(title, color = C.text) },
        text = {
            Column {
                options.forEach { (label, v) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(v) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(v == selected, { onPick(v) }, colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.blue))
                        Text(label, style = T.body)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

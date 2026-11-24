package com.erkam.temasa.ui

import android.content.Intent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.VisibilityOff
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.account.Account
import com.erkam.temasa.data.DOption
import com.erkam.temasa.data.Downloads
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.Store
import com.erkam.temasa.data.Video
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.launch

@Composable
fun TemasaApp(vm: MainViewModel) {
    TemasaTheme {
        val video by Player.video.collectAsState()
        val expanded by vm.expanded.collectAsState()
        val fullscreen by vm.fullscreen.collectAsState()
        val inPip by vm.inPip.collectAsState()
        val tab by vm.tab.collectAsState()
        val stack by vm.stack.collectAsState()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { vm.toast.collect { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it) } }
        LaunchedEffect(video) { if (video == null) { vm.expanded.value = false; vm.fullscreen.value = false } }

        if (inPip) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { VideoSurface(Modifier.fillMaxSize(), active = true) }
            return@TemasaTheme
        }
        if (video != null && (fullscreen || (expanded && landscape))) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                FullscreenPlayer(vm)
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter)) { Snackbar(it, containerColor = C.surface, contentColor = C.text) }
            }
            return@TemasaTheme
        }

        val homeList = rememberLazyListState()
        val subsList = rememberLazyListState()

        BackHandler(enabled = stack.isNotEmpty()) { vm.pop() }
        BackHandler(enabled = stack.isEmpty() && tab != Tab.HOME) { vm.selectTab(Tab.HOME) }

        Box(Modifier.fillMaxSize().background(C.bg)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val s = stack.lastOrNull()) {
                        is Screen.Search -> SearchScreen(vm, s.q)
                        is Screen.Channel -> ChannelScreen(vm, s.id, s.name)
                        is Screen.Playlist -> PlaylistScreen(vm, s.id, s.title)
                        is Screen.Library -> LibraryScreen(vm, s.id, s.title)
                        Screen.Settings -> SettingsScreen(vm)
                        null -> when (tab) {
                            Tab.HOME -> HomeScreen(vm, homeList)
                            Tab.SUBS -> SubsScreen(vm, subsList)
                            Tab.DOWNLOADS -> DownloadsScreen(vm)
                            Tab.YOU -> YouScreen(vm)
                        }
                    }
                }
                BottomNav(vm, tab)
            }
            if (video != null && !expanded) {
                val navInset = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                MiniPlayer(vm, bottom = 57.dp + navInset)
            }
            AnimatedVisibility(
                visible = expanded && video != null,
                enter = slideInVertically(tween(260)) { it },
                exit = slideOutVertically(tween(220)) { it },
            ) {
                WatchScreen(vm, onMinimize = { vm.expanded.value = false })
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp)) {
                Snackbar(it, containerColor = C.surface, contentColor = C.text)
            }
        }

        vm.menu.collectAsState().value?.let { VideoMenu(vm, it) }
        vm.saveTo.collectAsState().value?.let { SaveToSheet(vm, it) }
        vm.downloadFor.collectAsState().value?.let { DownloadDialog(vm, it) }
        if (vm.filtreAcik.collectAsState().value) FiltrePaneli(vm)
    }
}

@Composable
private fun BottomNav(vm: MainViewModel, tab: Tab) {
    val photo by Store.profilePhoto.collectAsState()
    val name by Store.profileName.collectAsState()
    Column(Modifier.fillMaxWidth().background(C.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            NavItem("Ana sayfa", if (tab == Tab.HOME) Icons.Filled.Home else Icons.Outlined.Home, tab == Tab.HOME, Modifier.weight(1f)) { vm.selectTab(Tab.HOME) }
            NavItem("Abonelikler", if (tab == Tab.SUBS) Icons.Filled.Subscriptions else Icons.Outlined.Subscriptions, tab == Tab.SUBS, Modifier.weight(1f)) { vm.selectTab(Tab.SUBS) }
            NavItem("İndirilenler", if (tab == Tab.DOWNLOADS) Icons.Filled.DownloadForOffline else Icons.Outlined.Download, tab == Tab.DOWNLOADS, Modifier.weight(1f)) { vm.selectTab(Tab.DOWNLOADS) }
            Column(
                Modifier.weight(1f).height(56.dp).clickable { vm.selectTab(Tab.YOU) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                val upd by com.erkam.temasa.data.Updater.state.collectAsState()
                Box {
                    if (photo != null || name != null) Avatar(photo, name ?: "S", 24.dp)
                    else Icon(Icons.Outlined.AccountCircle, null, tint = C.text, modifier = Modifier.size(24.dp))
                    if (upd.phase == com.erkam.temasa.data.Updater.Phase.AVAILABLE || upd.phase == com.erkam.temasa.data.Updater.Phase.READY)
                        Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.bg).padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.red))
                }
                Spacer(Modifier.height(4.dp))
                Text("Sen", color = C.text, fontSize = 10.sp, fontWeight = if (tab == Tab.YOU) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(56.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(icon, null, tint = C.text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = C.text, fontSize = 10.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Videonun "⋮" menüsü */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoMenu(vm: MainViewModel, v: Video) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dismiss = { vm.menu.value = null }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = C.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(v.title, style = T.body.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            MenuRow(Icons.Outlined.PlayCircle, "Oynat") { dismiss(); vm.open(v) }
            MenuRow(Icons.Outlined.WatchLater, if (Store.isLater(v.id)) "Sonra izle'den kaldır" else "Sonra izle'ye kaydet") {
                val on = !Store.isLater(v.id); dismiss()
                scope.launch { Account.watchLater(v, on); vm.toast(if (on) "Sonra izle'ye kaydedildi" else "Kaldırıldı") }
            }
            MenuRow(Icons.AutoMirrored.Outlined.PlaylistAdd, "Oynatma listesine kaydet") { dismiss(); vm.saveTo.value = v }
            MenuRow(Icons.Outlined.Download, "Videoyu indir") { dismiss(); vm.downloadFor.value = v }
            MenuRow(Icons.Outlined.Share, "Paylaş") {
                dismiss()
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${v.id}"), "Paylaş"))
            }
            if (v.channelId.isNotEmpty()) MenuRow(Icons.Outlined.Person, "Kanala git") { dismiss(); vm.push(Screen.Channel(v.channelId, v.channel)) }
            if (Store.history.value.any { it.id == v.id }) MenuRow(Icons.Outlined.Delete, "İzleme geçmişinden kaldır") { dismiss(); Store.removeHistory(v.id) }
            HorizontalDivider(color = C.divider, modifier = Modifier.padding(vertical = 4.dp))
            MenuRow(Icons.Outlined.VisibilityOff, "Bunun gibileri gösterme") {
                dismiss()
                if (!com.erkam.temasa.filtre.Evren.hazir) { vm.toast("Önce Filtreleme'de EVREN anahtarını gir"); vm.filtreAcik.value = true }
                else { vm.toast("Yapay zekâ bakıyor…"); com.erkam.temasa.filtre.Filtre.bununGibileri(v) { vm.toast(it) } }
            }
            if (v.channelId.isNotEmpty()) MenuRow(Icons.Outlined.Block, "Bu kanalı hiç gösterme") {
                dismiss(); com.erkam.temasa.filtre.Filtre.kanaliEngelle(v.channelId, v.channel.ifBlank { "Kanal" }); vm.toast("${v.channel.ifBlank { "Kanal" }} artık gösterilmeyecek")
            }
        }
    }
}

/** Listeye kaydet */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveToSheet(vm: MainViewModel, v: Video) {
    val lists by Store.lists.collectAsState()
    val later by Store.watchLater.collectAsState()
    val scope = rememberCoroutineScope()
    var newList by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { vm.saveTo.value = null }, containerColor = C.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp).verticalScroll(rememberScrollState())) {
            Text("Kaydet", style = T.section, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val isLater = later.any { it.id == v.id }
            CheckRow("Sonra izle", isLater) { scope.launch { Account.watchLater(v, !isLater) } }
            lists.forEach { l ->
                val has = l.videos.any { it.id == v.id }
                CheckRow(l.name, has) { if (has) Store.removeFromList(l.id, v.id) else Store.addToList(l.id, v) }
            }
            Divider()
            MenuRow(Icons.Outlined.Add, "Yeni oynatma listesi") { newList = true }
        }
    }
    if (newList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newList = false },
            containerColor = C.surface,
            title = { Text("Yeni oynatma listesi", color = C.text) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Ad seçin") }) },
            confirmButton = {
                TextButton(onClick = { Store.createList(name, v); newList = false; vm.saveTo.value = null; vm.toast("\"${name.ifBlank { "Yeni liste" }}\" listesine kaydedildi") }) { Text("Oluştur", color = C.blue) }
            },
            dismissButton = { TextButton(onClick = { newList = false }) { Text("İptal", color = C.text) } },
        )
    }
}

@Composable
private fun CheckRow(title: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).height(52.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(if (on) C.text else Color.Transparent)
                .then(if (on) Modifier else Modifier.border1()),
            contentAlignment = Alignment.Center
        ) { if (on) Icon(Icons.Filled.Check, null, tint = C.bg, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(20.dp))
        Text(title, style = T.body)
    }
}

/** İndirme kalitesi seç (boyutlarıyla) */
@Composable
private fun DownloadDialog(vm: MainViewModel, v: Video) {
    var options by remember { mutableStateOf<List<DOption>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val pref = Store.settings.collectAsState().value.downloadQuality
    var sel by remember { mutableIntStateOf(0) }
    LaunchedEffect(v.id) {
        runCatching { Downloads.options(Yt.info(v.id)) }
            .onSuccess { o -> options = o; sel = (o.filter { it.height <= pref }.maxByOrNull { it.height } ?: o.lastOrNull())?.height ?: 0 }
            .onFailure { failed = true }
    }
    AlertDialog(
        onDismissRequest = { vm.downloadFor.value = null },
        containerColor = C.surface,
        title = { Text("İndirme kalitesi", color = C.text) },
        text = {
            Column {
                when {
                    failed -> Text("Video bilgisi alınamadı.", style = T.body)
                    options == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.text2) }
                    else -> options!!.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { sel = o.height }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(sel == o.height, { sel = o.height }, colors = RadioButtonDefaults.colors(selectedColor = C.blue))
                            Text(o.label, style = T.body, modifier = Modifier.weight(1f))
                            if (o.size > 0) Text(Fmt.bytes(o.size), style = T.meta)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Videolar sadece Temâşâ içinde açılır, galeride görünmez.", style = T.meta)
            }
        },
        confirmButton = {
            TextButton(enabled = options != null && sel > 0, onClick = {
                Downloads.start(v, sel)
                Store.updateSettings { it.copy(downloadQuality = sel) }
                vm.downloadFor.value = null
                vm.toast("İndiriliyor: ${v.title.take(40)}")
            }) { Text("İndir", color = C.blue) }
        },
        dismissButton = { TextButton(onClick = { vm.downloadFor.value = null }) { Text("İptal", color = C.text) } },
    )
}

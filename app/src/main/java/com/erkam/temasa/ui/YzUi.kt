package com.erkam.temasa.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.data.Video
import com.erkam.temasa.play.Player
import com.erkam.temasa.yz.Evren
import com.erkam.temasa.yz.Yz
import kotlinx.coroutines.launch

private val Turkuaz get() = C.accent
private val TurkuazZemin = Color(0x2422C3B3)
private val Panel = Color(0xFF1C1C1C)

/** Yapay zekâ yıldızı */
@Composable
fun Yildiz(size: Dp, color: Color = C.accent) {
    Canvas(Modifier.size(size)) {
        fun star(cx: Float, cy: Float, r: Float): Path = Path().apply {
            moveTo(cx, cy - r)
            quadraticTo(cx + r * 0.12f, cy - r * 0.12f, cx + r, cy)
            quadraticTo(cx + r * 0.12f, cy + r * 0.12f, cx, cy + r)
            quadraticTo(cx - r * 0.12f, cy + r * 0.12f, cx - r, cy)
            quadraticTo(cx - r * 0.12f, cy - r * 0.12f, cx, cy - r)
            close()
        }
        val w = this.size.width
        drawPath(star(w * 0.42f, w * 0.42f, w * 0.38f), color)
        drawPath(star(w * 0.8f, w * 0.8f, w * 0.17f), color)
    }
}

@Composable
private fun YildizRozet(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(TurkuazZemin), contentAlignment = Alignment.Center) { Yildiz(size / 2) }
}

/** Ana sayfa üst çubuğundaki yapay zekâ düğmesi */
@Composable
fun YzDugmesi(vm: MainViewModel) {
    IconButton(onClick = { vm.push(Screen.Yz) }) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(TurkuazZemin).border(1.dp, Turkuaz.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Yildiz(18.dp) }
    }
}

/** Sesle yazma */
@Composable
private fun rememberSesle(onText: (String) -> Unit): () -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    return {
        runCatching {
            l.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR"))
        }
    }
}

/** Alttaki yazma kutusu */
@Composable
private fun YazmaKutusu(ipucu: String, mesgul: Boolean, onSend: (String) -> Unit, mic: Boolean = true) {
    var yazi by remember { mutableStateOf("") }
    val sesle = rememberSesle { yazi = it }
    fun gonder() { val t = yazi.trim(); if (t.isNotEmpty() && !mesgul) { onSend(t); yazi = "" } }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF2A2A2A)).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (yazi.isEmpty()) Text(ipucu, color = C.text2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    yazi, { yazi = it }, textStyle = T.body.copy(fontSize = 15.sp), cursorBrush = SolidColor(C.text), maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { gonder() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (mic) IconButton(onClick = sesle) { Icon(Icons.Outlined.Mic, "Sesle söyle", tint = Color(0xFFCFCFCF)) }
            else Spacer(Modifier.width(10.dp))
        }
        Spacer(Modifier.width(8.dp))
        val aktif = yazi.isNotBlank() && !mesgul
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (aktif) Turkuaz else Color(0xFF2E3A39)).clickable(enabled = aktif) { gonder() },
            contentAlignment = Alignment.Center
        ) {
            if (mesgul) CircularProgressIndicator(color = C.bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            else Icon(Icons.Filled.ArrowUpward, "Gönder", tint = C.bg)
        }
    }
}

/** Anahtar yoksa gösterilen kart */
@Composable
private fun AnahtarKarti(vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF242424)).clickable { vm.anahtarSor.value = true }.padding(14.dp)) {
        Text("Yapay zekâ henüz bağlı değil", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Bir kez anahtarını gir, sonra hep hazır olur.", style = T.meta)
        Spacer(Modifier.height(8.dp))
        Text("Anahtarı gir", color = Turkuaz, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Yapay zekâ anahtarı penceresi (anahtar gömülü değilse) */
@Composable
fun AnahtarPenceresi(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf("") }
    val durum by Evren.durum.collectAsState()
    val hata by Evren.hata.collectAsState()
    AlertDialog(
        onDismissRequest = { vm.anahtarSor.value = false }, containerColor = C.surface,
        title = { Text("Yapay zekâ anahtarı", color = C.text) },
        text = {
            Column {
                Text("EVREN anahtarını yapıştır.", style = T.body.copy(color = C.text2))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(a, { a = it.trim() }, singleLine = true,
                    placeholder = { Text(if (Evren.anahtarSonu().isNotEmpty()) "••••${Evren.anahtarSonu()}" else "evren_llm_...") })
                if (durum == Evren.Durum.HATA && hata != null) Text(hata!!, color = Color(0xFFFF8A65), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = durum != Evren.Durum.BAGLANIYOR, onClick = {
                if (a.isNotBlank()) Evren.anahtarKaydet(a)
                scope.launch { if (Evren.baglan()) { vm.anahtarSor.value = false; vm.toast("Yapay zekâ hazır") } }
            }) { Text(if (durum == Evren.Durum.BAGLANIYOR) "Bağlanıyor…" else "Bağlan", color = C.blue) }
        },
        dismissButton = { TextButton(onClick = { vm.anahtarSor.value = false }) { Text("İptal", color = C.text) } },
    )
}

// ======================= Yapay zekâ sayfası: video bul =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YzScreen(vm: MainViewModel) {
    val sohbet by Yz.sohbet.collectAsState()
    val calisiyor by Yz.calisiyor.collectAsState()
    val durum by Evren.durum.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(sohbet.size, calisiyor) { if (sohbet.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize().background(C.bg).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.ime.union(androidx.compose.foundation.layout.WindowInsets.navigationBars))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = C.text) }
            Box(Modifier.size(34.dp).clip(CircleShape).background(TurkuazZemin).border(1.dp, Turkuaz.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) { Yildiz(18.dp) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Yapay zekâ", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Video bulur, özetler, sorularını cevaplar", style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (sohbet.isNotEmpty()) IconButton(onClick = { Yz.yeniSohbet() }) { Icon(Icons.Outlined.AddComment, "Yeni sohbet", tint = C.text) }
        }
        Divider()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sohbet.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Yildiz(56.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Ne izlemek istersin?", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Aradığını kendi cümlelerinle anlat. YouTube'u tarar, en uygun videoları nedenleriyle önüne koyarım.",
                        style = T.body.copy(color = C.text2, lineHeight = 20.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (!Evren.hazir || durum == Evren.Durum.ANAHTAR_YOK) { Spacer(Modifier.height(18.dp)); AnahtarKarti(vm) }
                }
            } else LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(sohbet.size) { i ->
                    val m = sohbet[i]
                    if (m.ben) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(m.metin, style = T.body, modifier = Modifier.widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                            .background(Color(0xFF2E2E2E)).padding(horizontal = 14.dp, vertical = 10.dp))
                    } else Row(verticalAlignment = Alignment.Top) {
                        YildizRozet(28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            m.not?.let { Text(it, color = Color(0xFF8A8A8A), fontSize = 12.sp, lineHeight = 16.sp) }
                            Text(m.metin, style = T.body.copy(color = if (m.hata) Color(0xFFFF8A65) else Color(0xFFE6E6E6)))
                            m.videolar.forEach { b ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1A1A))
                                        .clickable { vm.open(b.video, m.videolar.map { it.video }) }.padding(8.dp)
                                ) {
                                    Thumb(b.video, Modifier.width(128.dp), corner = 10.dp, small = true)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(b.video.title, color = C.text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(b.video.meta(), color = C.text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (b.neden.isNotBlank()) Text(b.neden, color = Turkuaz, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (m.secenekler.isNotEmpty() && i == sohbet.lastIndex && calisiyor == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                m.secenekler.forEach { o -> Secenek(o, vurgulu = true) { Yz.gonder(o) } }
                            }
                            if (m.videolar.isNotEmpty() && i == sohbet.lastIndex) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Secenek("Daha fazla bul") { Yz.gonder("Daha fazla bul") }
                                Secenek("Hepsini sırayla oynat") { vm.open(m.videolar.first().video, m.videolar.map { it.video }) }
                            }
                            if (m.hata && i == sohbet.lastIndex) {
                                val son = sohbet.lastOrNull { it.ben }?.metin
                                if (son != null) Secenek("Tekrar dene") { Yz.sohbet.value = Yz.sohbet.value.dropLast(2); Yz.gonder(son) }
                            }
                        }
                    }
                }
                calisiyor?.let { c ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            YildizRozet(28.dp)
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(color = Turkuaz, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Text("  $c", style = T.meta.copy(fontSize = 13.sp))
                        }
                    }
                }
            }
        }

        Divider()
        Box {
            YazmaKutusu("Ne arıyorsun? Anlat...", calisiyor != null, onSend = { t ->
                if (!Evren.hazir) vm.anahtarSor.value = true else Yz.gonder(t)
            })
        }
    }
}

@Composable
private fun Secenek(t: String, vurgulu: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(17.dp))
            .background(if (vurgulu) TurkuazZemin else Color.Transparent)
            .border(1.dp, if (vurgulu) Turkuaz.copy(alpha = 0.45f) else Color(0xFF3A3A3A), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) { Text(t, color = if (vurgulu) Color(0xFFE6FAF7) else C.text, fontSize = 13.sp) }
}

// ======================= Videoda "Sor": Özet · Önemli anlar · Sor =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun YzPenceresi(vm: MainViewModel, v: Video) {
    val b = remember(v.id) { Yz.bilgi(v.id) }
    val ozet by b.ozet.collectAsState()
    val hata by b.ozetHata.collectAsState()
    val ozetleniyor by b.ozetleniyor.collectAsState()
    val anlar by b.anlar.collectAsState()
    val anlarYukleniyor by b.anlarYukleniyor.collectAsState()
    val sorular by b.sorular.collectAsState()
    val soruyor by b.soruyor.collectAsState()
    var sekme by remember(v.id) { mutableIntStateOf(if (b.sorular.value.isNotEmpty()) 2 else 0) }
    val clip = LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(v.id) { if (Evren.hazir) Yz.ozetle(v) }
    LaunchedEffect(sorular.size, soruyor) { if (sekme == 2 && sorular.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    val kapat = { vm.yzVideo.value = null }
    fun git(ms: Long) { Player.seekTo(ms); if (!Player.exo.isPlaying) Player.exo.play(); kapat() }

    ModalBottomSheet(
        onDismissRequest = kapat,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel,
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4A4A))) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Yildiz(20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Yapay zekâ", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = kapat) { Icon(Icons.Filled.Close, "Kapat", tint = C.text) }
            }
            Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Özet", "Önemli anlar", "Sor").forEachIndexed { i, t ->
                    Box(
                        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (i == sekme) C.text else Color(0xFF2A2A2A))
                            .clickable { sekme = i }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(t, color = if (i == sekme) C.bg else C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }

            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!Evren.hazir) item { AnahtarKarti(vm) }
                else if (sekme == 0 && ozet?.ozet.isNullOrBlank()) item {
                    if (ozetleniyor) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(color = Turkuaz, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text("  Videoyu okuyorum…", style = T.meta.copy(fontSize = 13.sp))
                    } else if (hata != null) Column {
                        Text(hata!!, color = Color(0xFFFF8A65), fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Secenek("Tekrar dene") { Yz.ozetle(v, yeniden = true) }
                    }
                }
                val o = ozet
                if (Evren.hazir && o != null && o.ozet.isNotBlank() && sekme == 0) {
                    item { Text(o.ozet, style = T.body.copy(color = Color(0xFFE6E6E6), lineHeight = 21.sp)) }
                    if (o.onecikanlar.isNotEmpty()) {
                        item { Text("Öne çıkanlar", color = C.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        items(o.onecikanlar.size) { i ->
                            Row { Text("•  ", style = T.body); Text(o.onecikanlar[i], style = T.body) }
                        }
                    }
                    if (o.bitti) item {
                        Text(
                            (if (o.altyazidan) "Videonun altyazısından çıkarıldı" else "Bu videonun altyazısı yok; başlık ve açıklamadan çıkarıldı") + " · Yapay zekâ hata yapabilir",
                            color = Color(0xFF777777), fontSize = 11.sp
                        )
                    }
                }
                if (Evren.hazir && sekme == 1) {
                    val l = anlar
                    if (l == null) item {
                        if (hata != null && !ozetleniyor) Text(hata!!, color = Color(0xFFFF8A65), fontSize = 14.sp)
                        else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            CircularProgressIndicator(color = Turkuaz, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Text("  Bölümleri çıkarıyorum…", style = T.meta.copy(fontSize = 13.sp))
                        }
                    } else if (l.isEmpty()) item {
                        Text(if (anlarYukleniyor) "Bölümleri çıkarıyorum…" else "Bu videonun altyazısı olmadığı için önemli anları çıkaramadım.", style = T.body.copy(color = C.text2))
                    } else items(l.size) { i ->
                        val a = l[i]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { git(a.ms) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.height(26.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x293EA6FF)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                Text(Yz.zamanYaz(a.ms), color = C.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(a.baslik, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (a.aciklama.isNotBlank()) Text(a.aciklama, style = T.meta)
                            }
                        }
                    }
                }
                if (Evren.hazir && sekme == 2) {
                    if (sorular.isEmpty()) {
                        item { Text("Bu video hakkında merak ettiğini sor. Cevabı videodan bulur, ilgili dakikayı gösteririm.", style = T.body.copy(color = C.text2)) }
                        o?.takeIf { it.bitti }?.sorular?.takeIf { it.isNotEmpty() }?.let { s ->
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { s.forEach { q -> Secenek(q) { Yz.sor(v, q) } } }
                            }
                        }
                        if (o?.bitti != true && ozetleniyor) item { Text("Öneriler hazırlanıyor…", style = T.meta) }
                    }
                    items(sorular.size) { i ->
                        val q = sorular[i]
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(q.soru, style = T.body, modifier = Modifier.widthIn(max = 290.dp)
                                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                                    .background(Color(0xFF2E2E2E)).padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                YildizRozet(28.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    val c = q.cevap
                                    if (c == null) Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(color = Turkuaz, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                        Text("  Videoya bakıyorum…", style = T.meta.copy(fontSize = 13.sp))
                                    } else {
                                        Text(c, style = T.body.copy(color = if (q.hata) Color(0xFFFF8A65) else Color(0xFFE6E6E6), lineHeight = 21.sp))
                                        if (q.bitti) q.ms?.let { ms ->
                                            Row(
                                                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0x293EA6FF)).clickable { git(ms) }.padding(horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Filled.PlayArrow, null, tint = C.blue, modifier = Modifier.size(16.dp))
                                                Text(" ${Yz.zamanYaz(ms)}'dan izle", color = C.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                        if (q.bitti && !q.hata) Row(Modifier.clickable { clip.setText(AnnotatedString(c)); vm.toast("Kopyalandı") }, verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.ContentCopy, null, tint = Color(0xFF9A9A9A), modifier = Modifier.size(14.dp))
                                            Text(" Kopyala", color = Color(0xFF9A9A9A), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Divider()
            Box(Modifier.navigationBarsPadding()) {
                YazmaKutusu(if (sorular.isEmpty()) "Bu video hakkında sor..." else "Başka bir şey sor...", soruyor, mic = true, onSend = { t ->
                    if (!Evren.hazir) vm.anahtarSor.value = true else { sekme = 2; Yz.sor(v, t) }
                })
            }
        }
    }
}

// ======================= Yorumların özeti =======================

@Composable
fun YorumOzetiKarti(videoId: String, baslik: String) {
    val ozetler by Yz.yorumOzetleri.collectAsState()
    val suruyor by Yz.yorumOzetleniyor.collectAsState()
    val hatalar by Yz.yorumHata.collectAsState()
    if (!Evren.hazir) return
    LaunchedEffect(videoId) { Yz.yorumlariOzetle(videoId, baslik) }
    val o = ozetler[videoId]
    val h = hatalar[videoId]
    if (o == null && h != null && h.contains("yorum yok")) return
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp).clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF161E1D)).border(1.dp, Turkuaz.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Yildiz(16.dp)
            Spacer(Modifier.width(8.dp))
            Text("Yorumların özeti", color = Color(0xFFCFEDEA), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (o != null) Text("${o.okunan} yorum okundu", color = Color(0xFF7A9A96), fontSize = 12.sp)
        }
        when {
            o != null -> {
                Text(o.ozet, color = Color(0xFFDDEEEC), fontSize = 13.sp, lineHeight = 19.sp)
                if (o.etiketler.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.etiketler.forEachIndexed { i, e ->
                        Text(e, color = if (i == 0) Color(0xFFBFEFEA) else Color(0xFFCFCFCF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (i == 0) TurkuazZemin else Color(0xFF262626)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            videoId in suruyor -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Turkuaz, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Text("  Yorumları okuyorum…", color = Color(0xFF9FBFBB), fontSize = 13.sp)
            }
            h != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h, color = Color(0xFFFFB199), fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { Yz.yorumlariOzetle(videoId, baslik, yeniden = true) }) { Icon(Icons.Outlined.Refresh, "Tekrar dene", tint = Turkuaz) }
            }
        }
    }
}

/** Video ekranındaki "Sor" düğmesi (YouTube'daki Gemini düğmesinin yerinde) */
@Composable
fun SorDugmesi(onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(TurkuazZemin)
            .border(1.dp, Turkuaz.copy(alpha = 0.55f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Yildiz(16.dp)
        Spacer(Modifier.width(6.dp))
        Text("Sor", color = Color(0xFFE6FAF7), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.data.Video
import com.erkam.temasa.filtre.Evren
import com.erkam.temasa.filtre.Filtre
import com.erkam.temasa.play.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Turkuaz get() = C.accent
private val TurkuazZemin = Color(0x2422C3B3)

/** Yapay zekâ yıldızı (Filtreleme simgesi) */
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

/** Üst çubuktaki yapay zekâ düğmesi */
@Composable
fun FiltreDugmesi(vm: MainViewModel) {
    IconButton(onClick = { vm.filtreAcik.value = true }) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(TurkuazZemin).border(1.dp, Turkuaz.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Yildiz(18.dp) }
    }
}

/** Liste filtreden geçirilir: kararı verilmemişler beklerken gösterilmez */
@Composable
fun rememberSuzulmus(list: List<Video>): Pair<List<Video>, Boolean> {
    val k by Filtre.kararlar.collectAsState()
    val kurallar by Filtre.kurallar.collectAsState()
    val kanallar by Filtre.engelliKanallar.collectAsState()
    LaunchedEffect(list, kurallar) { Filtre.iste(list) }
    val shown = remember(list, k, kurallar, kanallar) { Filtre.suz(list, k, kanallar) }
    val pending = remember(list, k, kurallar, kanallar) { Filtre.bekleyenVar(list) }
    return shown to pending
}

/** Kategori düğmelerinin başındaki "Filtreleme · 3" */
@Composable
fun FiltreChip(vm: MainViewModel) {
    val kurallar by Filtre.kurallar.collectAsState()
    val n = kurallar.count { it.acik }
    if (n == 0) return
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(TurkuazZemin)
            .border(1.dp, Turkuaz.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable { vm.filtreAcik.value = true }.padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Yildiz(14.dp)
        Spacer(Modifier.width(6.dp))
        Text("Filtreleme · $n", color = Color(0xFFE6FAF7), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "Bugün 48 video ve 3 kanal senin için gizlendi" */
@Composable
fun FiltreSeridi(vm: MainViewModel) {
    val kurallar by Filtre.kurallar.collectAsState()
    val sayi by Filtre.bugunGizlenen.collectAsState()
    val kanallar by Filtre.engelliKanallar.collectAsState()
    val hata by Filtre.calismiyor.collectAsState()
    if (kurallar.none { it.acik }) return
    val anahtarYok = !Evren.hazir
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp).clip(RoundedCornerShape(12.dp))
            .background(if (hata != null || anahtarYok) Color(0xFF3A2420) else Color(0xFF161E1D)).clickable { vm.filtreAcik.value = true }
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.VerifiedUser, null, tint = if (hata != null || anahtarYok) Color(0xFFFF8A65) else Turkuaz, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                anahtarYok -> "Filtreleme için EVREN anahtarını gir"
                hata != null -> hata!!
                else -> "Bugün $sayi video" + (if (kanallar.isNotEmpty()) " ve ${kanallar.size} kanal" else "") + " senin için gizlendi"
            },
            color = Color(0xFFCFEDEA), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f)
        )
        Text("Kurallar", color = Turkuaz, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** Kilitliyse şifre sorar, doğruysa işlemi yapar */
@Composable
fun rememberKilitli(): (() -> Unit) -> Unit {
    var bekleyen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pin by remember { mutableStateOf("") }
    var yanlis by remember { mutableStateOf(false) }
    bekleyen?.let { is_ ->
        AlertDialog(
            onDismissRequest = { bekleyen = null; pin = "" }, containerColor = C.surface,
            title = { Text("Filtreleme kilitli", color = C.text) },
            text = {
                Column {
                    Text("Değiştirmek için şifreyi gir.", style = T.body.copy(color = C.text2))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() }.take(8); yanlis = false }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = yanlis, placeholder = { Text("Şifre") })
                    if (yanlis) Text("Şifre yanlış", color = C.red, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { if (Filtre.pinDogru(pin)) { is_(); bekleyen = null; pin = "" } else yanlis = true }) { Text("Tamam", color = C.blue) } },
            dismissButton = { TextButton(onClick = { bekleyen = null; pin = "" }) { Text("İptal", color = C.text) } },
        )
    }
    return { f -> if (Filtre.pinVar()) bekleyen = f else f() }
}

/** Filtreleme paneli: "Neyi çıkarmamı istersin?" */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltrePaneli(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sohbet by Filtre.sohbet.collectAsState()
    val kurallar by Filtre.kurallar.collectAsState()
    val dusunuyor by Filtre.dusunuyor.collectAsState()
    val sayi by Filtre.bugunGizlenen.collectAsState()
    val kanallar by Filtre.engelliKanallar.collectAsState()
    val durum by Evren.durum.collectAsState()
    val evrenHata by Evren.hata.collectAsState()
    val kilitli by Filtre.kilit.collectAsState()
    val kilit = rememberKilitli()
    var yazi by remember { mutableStateOf("") }
    var anahtar by remember { mutableStateOf("") }
    var pinKur by remember { mutableStateOf(false) }
    var kanallariGoster by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val ses = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { yazi = it }
        }
    }
    fun gonder() {
        val t = yazi.trim(); if (t.isEmpty() || dusunuyor) return
        kilit {
            yazi = ""
            scope.launch { Filtre.yaz(t); list.animateScrollToItem(Int.MAX_VALUE.coerceAtMost(list.layoutInfo.totalItemsCount + 5)) }
        }
    }
    LaunchedEffect(sohbet.size) { if (sohbet.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    ModalBottomSheet(
        onDismissRequest = { vm.filtreAcik.value = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1C),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4A4A))) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            // Başlık
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(TurkuazZemin).border(1.dp, Turkuaz.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) { Yildiz(20.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Filtreleme", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Yapay zekâ, kurallarına uymayanları göstermez", style = T.meta)
                }
                IconButton(onClick = { vm.filtreAcik.value = false }) { Icon(Icons.Filled.Close, "Kapat", tint = C.text) }
            }

            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // EVREN anahtarı yoksa
                if (Evren.gomulu && durum == Evren.Durum.HATA) item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF3A2420)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(evrenHata ?: "Yapay zekâya ulaşılamadı", color = Color(0xFFFFCCBC), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { Evren.baglan() } }) { Text("Tekrar dene", color = Turkuaz) }
                    }
                }
                if (!Evren.gomulu && (durum == Evren.Durum.ANAHTAR_YOK || durum == Evren.Durum.HATA)) item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF242424)).padding(14.dp)) {
                        Text(if (durum == Evren.Durum.HATA) (evrenHata ?: "EVREN'e bağlanılamadı") else "EVREN'e bağlan", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Cezerî'de kullandığın EVREN anahtarını buraya yapıştır.", style = T.meta)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2E2E2E)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                                if (anahtar.isEmpty()) Text(if (Evren.anahtarSonu().isNotEmpty()) "••••${Evren.anahtarSonu()}" else "EVREN anahtarı", color = C.text2, fontSize = 14.sp)
                                BasicTextField(anahtar, { anahtar = it.trim() }, singleLine = true, textStyle = T.body, cursorBrush = SolidColor(C.text), modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Turkuaz)
                                    .clickable(enabled = durum != Evren.Durum.BAGLANIYOR) {
                                        if (anahtar.isNotBlank()) Evren.anahtarKaydet(anahtar)
                                        scope.launch { if (Evren.baglan()) { anahtar = ""; vm.toast("EVREN'e bağlandı") } }
                                    }.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (durum == Evren.Durum.BAGLANIYOR) CircularProgressIndicator(color = C.bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                else Text("Bağlan", color = C.bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                // İlk soru
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(TurkuazZemin), contentAlignment = Alignment.Center) { Yildiz(14.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Neyi çıkarmamı istersin?", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Kendi cümlelerinle yaz ya da söyle. Ben anlayıp her yerde uygularım.", style = T.meta.copy(fontSize = 13.sp, lineHeight = 19.sp))
                        }
                    }
                }
                // Konuşma
                items(sohbet.size) { i ->
                    val m = sohbet[i]
                    if (m.ben) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(m.metin, style = T.body, modifier = Modifier.widthIn(max = 290.dp)
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                            .background(Color(0xFF2E2E2E)).padding(horizontal = 14.dp, vertical = 10.dp))
                    } else Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(TurkuazZemin), contentAlignment = Alignment.Center) { Yildiz(14.dp) }
                        Spacer(Modifier.width(10.dp))
                        Text(m.metin, style = T.body.copy(color = Color(0xFFE6E6E6)))
                    }
                }
                if (dusunuyor) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(TurkuazZemin), contentAlignment = Alignment.Center) { Yildiz(14.dp) }
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(color = Turkuaz, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("  Düşünüyor...", style = T.meta)
                    }
                }
                // Kurallar
                if (kurallar.isNotEmpty()) {
                    item { Text("Kurallar", color = C.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, modifier = Modifier.padding(top = 4.dp)) }
                    items(kurallar, key = { it.id }) { k ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF242424))
                                .border(1.dp, if (k.acik) Turkuaz.copy(alpha = 0.35f) else Color(0xFF2E2E2E), RoundedCornerShape(14.dp))
                                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(k.baslik, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (k.aciklama.isNotBlank()) Text(k.aciklama, style = T.meta)
                            }
                            Switch(
                                checked = k.acik, onCheckedChange = { on -> if (on) Filtre.kuralAc(k.id, true) else kilit { Filtre.kuralAc(k.id, false) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Turkuaz, uncheckedTrackColor = Color(0xFF4A4A4A), uncheckedBorderColor = Color(0xFF4A4A4A), uncheckedThumbColor = Color(0xFFCFCFCF))
                            )
                            IconButton(onClick = { kilit { Filtre.kuralSil(k.id) } }) { Icon(Icons.Outlined.Delete, "Kuralı sil", tint = C.text2, modifier = Modifier.size(20.dp)) }
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF161E1D)).clickable { kanallariGoster = !kanallariGoster }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.VerifiedUser, null, tint = Turkuaz, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Bugün $sayi video" + (if (kanallar.isNotEmpty()) " ve ${kanallar.size} kanal" else "") + " gizlendi", color = Color(0xFFCFEDEA), fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (kanallar.isNotEmpty()) Text(if (kanallariGoster) "Gizle" else "Kanallar", color = Turkuaz, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (kanallariGoster) items(kanallar.entries.toList(), key = { "k" + it.key }) { (id, ad) ->
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ad, style = T.body, modifier = Modifier.weight(1f))
                            TextButton(onClick = { kilit { Filtre.kanalEngeliniKaldir(id) } }) { Text("Göster", color = C.blue) }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { if (kilitli) kilit { Filtre.pinKoy(null) } else pinKur = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, tint = C.text, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Şifreyle kilitle", style = T.body)
                                Text("Filtreleme şifresiz kapatılamasın", style = T.meta)
                            }
                            Switch(
                                checked = kilitli, onCheckedChange = { if (it) pinKur = true else kilit { Filtre.pinKoy(null) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Turkuaz, uncheckedTrackColor = Color(0xFF4A4A4A), uncheckedBorderColor = Color(0xFF4A4A4A), uncheckedThumbColor = Color(0xFFCFCFCF))
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }

            // Yazma kutusu
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1C1C1C)).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF2A2A2A)).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (yazi.isEmpty()) Text("Neyi çıkarayım? Yaz...", color = C.text2, fontSize = 15.sp)
                        BasicTextField(yazi, { yazi = it }, textStyle = T.body.copy(fontSize = 15.sp), cursorBrush = SolidColor(C.text), maxLines = 4, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = {
                        runCatching {
                            ses.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Neyi çıkarayım?"))
                        }.onFailure { vm.toast("Sesle yazma bu telefonda yok") }
                    }) { Icon(Icons.Outlined.Mic, "Sesle söyle", tint = Color(0xFFCFCFCF)) }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(if (yazi.isBlank() || dusunuyor) Color(0xFF2E3A39) else Turkuaz)
                        .clickable(enabled = yazi.isNotBlank() && !dusunuyor) {
                            if (!Evren.hazir) vm.toast("Önce EVREN anahtarını gir") else gonder()
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.ArrowUpward, "Gönder", tint = C.bg) }
            }
        }
    }

    if (pinKur) {
        var p by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinKur = false }, containerColor = C.surface,
            title = { Text("Şifre belirle", color = C.text) },
            text = {
                Column {
                    Text("Filtrelemeyi kapatmak ya da kural silmek için bu şifre sorulacak.", style = T.body.copy(color = C.text2))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(p, { p = it.filter { c -> c.isDigit() }.take(8) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), placeholder = { Text("En az 4 rakam") })
                }
            },
            confirmButton = { TextButton(enabled = p.length >= 4, onClick = { Filtre.pinKoy(p); pinKur = false; vm.toast("Filtreleme kilitlendi") }) { Text("Kilitle", color = C.blue) } },
            dismissButton = { TextButton(onClick = { pinKur = false }) { Text("İptal", color = C.text) } },
        )
    }
}

/** Video açılınca sesten takılırsa oynatıcının üstünde */
@Composable
fun FiltreTakilmaKatmani(modifier: Modifier = Modifier) {
    val t by Filtre.takilma.collectAsState()
    val v by Player.video.collectAsState()
    val takilma = t ?: return
    val video = v ?: return
    if (takilma.videoId != video.id) return
    var kalan by remember(takilma) { mutableIntStateOf(3) }
    LaunchedEffect(takilma) {
        Player.exo.pause()
        while (kalan > 0) { delay(1000); kalan-- }
        if (Filtre.takilma.value == takilma) { Filtre.takilma.value = null; Player.next() }
    }
    Column(
        modifier.fillMaxSize().background(Color(0xF2101418)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(TurkuazZemin).border(1.dp, Turkuaz.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.VerifiedUser, null, tint = Turkuaz, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("Filtrelemeye takıldı", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("${takilma.neden} · sıradakine geçiliyor $kalan", color = Color(0xFFBDBDBD), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Color(0xFF4A4A4A), RoundedCornerShape(18.dp))
                    .clickable { Filtre.yineDeIzle(video); Player.exo.play() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { Text("Yine de izle", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Turkuaz)
                    .clickable { Filtre.takilma.value = null; Player.next() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { Text("Hemen geç", color = C.bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

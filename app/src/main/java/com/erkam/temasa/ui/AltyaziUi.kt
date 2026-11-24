package com.erkam.temasa.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.play.Altyazi
import com.erkam.temasa.play.Player
import kotlinx.coroutines.delay

private val Golge = Shadow(Color.Black.copy(alpha = 0.92f), Offset(0f, 2f), 7f)

/** Videonun üstündeki altyazı: üstte küçük orijinal, altta büyük Türkçe (tasarım 17) */
@Composable
fun AltyaziKatmani(fullscreen: Boolean, yukari: Boolean, modifier: Modifier = Modifier) {
    val acik by Altyazi.acik.collectAsState()
    val mod by Altyazi.mod.collectAsState()
    val orj by Altyazi.orijinal.collectAsState()
    val trListe by Altyazi.turkceListe.collectAsState()
    val yz by Altyazi.yzCeviri.collectAsState()
    val boyut by Altyazi.boyut.collectAsState()
    val orjTr by Altyazi.orijinalTurkce.collectAsState()
    val aid by Altyazi.videoId.collectAsState()
    val v by Player.video.collectAsState()
    if (!acik || orj.isEmpty() || v?.id != aid) return

    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { ms = runCatching { Player.exo.currentPosition }.getOrDefault(0L); delay(80) } }

    val i = Altyazi.sira(orj, ms)
    val ust = if (i >= 0) orj[i].metin else null
    val tr = when {
        orjTr -> ust
        i >= 0 && yz[i] != null -> yz[i]
        else -> Altyazi.sira(trListe, ms).takeIf { it >= 0 }?.let { trListe[it].metin }
    }
    var kucuk: String? = null
    var buyuk: String?
    when (mod) {
        Altyazi.Mod.CIFT -> if (orjTr) buyuk = ust else { kucuk = ust; buyuk = tr }
        Altyazi.Mod.TURKCE -> buyuk = tr
        else -> buyuk = ust
    }
    if (buyuk == null && kucuk != null) { buyuk = kucuk; kucuk = null }
    if (buyuk == null) return

    val olcek = listOf(0.8f, 0.9f, 1f, 1.15f, 1.3f)[boyut.coerceIn(0, 4)]
    val kucukSp = (if (fullscreen) 16f else 11.5f) * olcek
    val buyukSp = (if (fullscreen) 23f else 15f) * olcek
    val alt by animateDpAsState(
        (if (fullscreen) 26.dp else 10.dp) + (if (yukari) (if (fullscreen) 58.dp else 30.dp) else 0.dp),
        tween(220), label = "altyazi"
    )

    Box(modifier) {
        // Okunsun diye altta hafif karartma
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (fullscreen) 0.36f else 0.42f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = if (fullscreen) 70.dp else 14.dp, end = if (fullscreen) 70.dp else 14.dp, bottom = alt),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (fullscreen) 6.dp else 3.dp)
        ) {
            if (kucuk != null) Text(
                kucuk, textAlign = TextAlign.Center, maxLines = 2,
                style = TextStyle(color = Color(0xFFDADADA), fontSize = kucukSp.sp, lineHeight = (kucukSp * 1.3f).sp, shadow = Golge)
            )
            Text(
                buyuk, textAlign = TextAlign.Center, maxLines = 3,
                style = TextStyle(color = Color.White, fontSize = buyukSp.sp, lineHeight = (buyukSp * 1.3f).sp, fontWeight = FontWeight.Bold, shadow = Golge)
            )
        }
    }
}

/** CC'ye basınca açılan pencere: dil, yazı boyutu, çeviri durumu (tasarım 18) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AltyaziPenceresi() {
    val acik by Altyazi.acik.collectAsState()
    val mod by Altyazi.mod.collectAsState()
    val boyut by Altyazi.boyut.collectAsState()
    val yok by Altyazi.yok.collectAsState()
    val yukleniyor by Altyazi.yukleniyor.collectAsState()
    val orjTr by Altyazi.orijinalTurkce.collectAsState()
    val dil by Altyazi.orijinalDil.collectAsState()
    val kaynak by Altyazi.turkceKaynak.collectAsState()
    Altyazi.yzCeviri.collectAsState().value
    Altyazi.yzCalisiyor.collectAsState().value
    var tik by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tik++ } }
    val kapat = { Altyazi.panel.value = false }
    val secili = if (acik) mod else Altyazi.Mod.KAPALI
    val dilAd = dil.ifBlank { "Orijinal" }

    ModalBottomSheet(
        onDismissRequest = kapat,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1C),
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4A4A))) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Altyazılar", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Bitti", color = C.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = kapat).padding(8.dp))
            }

            if (yok && !yukleniyor) {
                Text("Bu videoda altyazı yok.", style = T.body.copy(color = C.text2))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dil", color = C.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF242424))) {
                        val yzAlt = when {
                            kaynak == Altyazi.Kaynak.VIDEONUN -> "Türkçe: videonun kendi altyazısı"
                            com.erkam.temasa.yz.Evren.hazir -> "Türkçe: yapay zekâ çevirisi"
                            kaynak == Altyazi.Kaynak.YOUTUBE -> "Türkçe: YouTube çevirisi"
                            else -> "Türkçe çeviri için yapay zekâyı bağla"
                        }
                        if (!orjTr) {
                            DilSatiri("Çift dil · $dilAd + Türkçe", yzAlt, secili == Altyazi.Mod.CIFT, yildiz = true) { Altyazi.modSec(Altyazi.Mod.CIFT) }
                            Ayrac()
                            DilSatiri("Sadece Türkçe", null, secili == Altyazi.Mod.TURKCE) { Altyazi.modSec(Altyazi.Mod.TURKCE) }
                            Ayrac()
                            DilSatiri("Sadece $dilAd", null, secili == Altyazi.Mod.ORIJINAL) { Altyazi.modSec(Altyazi.Mod.ORIJINAL) }
                        } else {
                            DilSatiri("Türkçe", null, secili != Altyazi.Mod.KAPALI) { Altyazi.modSec(Altyazi.Mod.CIFT) }
                        }
                        Ayrac()
                        DilSatiri("Kapalı", null, secili == Altyazi.Mod.KAPALI, soluk = true) { Altyazi.modSec(Altyazi.Mod.KAPALI) }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text("Yazı boyutu", color = C.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(listOf("Çok küçük", "Küçük", "Normal", "Büyük", "Çok büyük")[boyut], color = C.text2, fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BoyutDugmesi("A", 13) { Altyazi.boyutSec(boyut - 1) }
                        Slider(
                            value = boyut.toFloat(), onValueChange = { Altyazi.boyutSec(Math.round(it)) }, valueRange = 0f..4f, steps = 3,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.accent, inactiveTrackColor = Color(0xFF3A3A3A),
                                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        BoyutDugmesi("A", 19) { Altyazi.boyutSec(boyut + 1) }
                    }
                }

                if (!orjTr) {
                    val durum = remember(tik, kaynak, yukleniyor) { Altyazi.durumMetni() }
                    if (durum != null) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF161E1D))
                            .border(1.dp, C.accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(C.accent))
                        Spacer(Modifier.width(10.dp))
                        Text(durum, color = Color(0xFFCFEDEA), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Ayrac() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))

@Composable
private fun DilSatiri(baslik: String, alt: String?, secili: Boolean, yildiz: Boolean = false, soluk: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (yildiz) { Yildiz(18.dp); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(baslik, color = if (soluk && !secili) C.text2 else C.text, fontSize = 14.sp, fontWeight = if (secili) FontWeight.SemiBold else FontWeight.Normal)
            if (alt != null) Text(alt, color = Color(0xFF9FBFBB), fontSize = 12.sp)
        }
        if (secili) Icon(Icons.Filled.Check, null, tint = C.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BoyutDugmesi(t: String, sp: Int, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A2A2A)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(t, color = Color.White, fontSize = sp.sp, fontWeight = FontWeight.Bold)
    }
}

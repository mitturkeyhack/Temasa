package com.erkam.temasa.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.Store
import com.erkam.temasa.play.Player
import com.erkam.temasa.yt.Comment
import com.erkam.temasa.yt.Yt
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(it, null, tint = C.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(18.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body)
            sub?.let { Text(it, style = T.meta) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = C.blue, uncheckedTrackColor = C.chipHi, uncheckedBorderColor = C.chipHi, uncheckedThumbColor = C.text2)
        )
    }
}

@Composable
private fun Section(t: String) = Text(t, style = T.title.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))

/** Oynatıcının ayarlar menüsü: hız, kalite, altyazı, uyku zamanlayıcı, sadece ses, döngü */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerSettingsSheet(onDismiss: () -> Unit) {
    val speed by Player.speed.collectAsState()
    val qualities by Player.qualities.collectAsState()
    val quality by Player.quality.collectAsState()
    val playingH by Player.playingHeight.collectAsState()
    val subs by Player.subTracks.collectAsState()
    val sub by Player.subtitle.collectAsState()
    val audioOnly by Player.audioOnly.collectAsState()
    val loop by Player.loop.collectAsState()
    val sleep by Player.sleepAt.collectAsState()
    val settings by Store.settings.collectAsState()
    val video by Player.video.collectAsState()
    val local by Player.local.collectAsState()
    var page by remember { mutableIntStateOf(0) } // 0 ana, 1 altyazı, 2 uyku

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            when (page) {
                0 -> {
                    // Hız
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Speed, null, tint = C.text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(18.dp))
                        Text("Oynatma hızı", style = T.body, modifier = Modifier.weight(1f))
                        Text("${Fmt.speed(speed)}x", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(Icons.Filled.Remove, "Yavaşlat") { Player.setSpeed(speed - 0.05f) }
                        Slider(
                            value = speed, onValueChange = { Player.setSpeed(Math.round(it * 20) / 20f) },
                            valueRange = 0.25f..4f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.text, inactiveTrackColor = C.chipHi)
                        )
                        RoundIcon(Icons.Filled.Add, "Hızlandır") { Player.setSpeed(speed + 0.05f) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { v ->
                            val on = speed == v
                            Box(
                                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.text else C.chip)
                                    .clickable { Player.setSpeed(v) },
                                contentAlignment = Alignment.Center
                            ) { Text(Fmt.speed(v), color = if (on) C.bg else C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    if (video?.channel?.isNotEmpty() == true) {
                        SwitchRow("Bu kanal için hatırla", "${video?.channel} hep ${Fmt.speed(speed)}x açılsın", settings.rememberChannelSpeed) { on ->
                            Store.updateSettings { it.copy(rememberChannelSpeed = on) }
                            if (on) Player.setSpeed(speed, true)
                        }
                    }
                    Divider()

                    // Kalite
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.HighQuality, null, tint = C.text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(18.dp))
                        Text("Kalite", style = T.body, modifier = Modifier.weight(1f))
                        Text(if (quality == 0) "Otomatik${if (playingH > 0) " (${playingH}p)" else ""}" else "${quality}p", style = T.meta)
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!local) QChip("Otomatik", quality == 0) { Player.setQuality(0) }
                        qualities.forEach { h -> QChip(label(h), quality == h || local) { Player.setQuality(h) } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Divider()

                    MenuRow(Icons.Outlined.ClosedCaption, "Altyazı", sub?.label ?: if (subs.isEmpty()) "Yok" else "Kapalı") { if (subs.isNotEmpty()) page = 1 }
                    MenuRow(Icons.Outlined.Bedtime, "Uyku zamanlayıcı", sleepLabel(sleep)) { page = 2 }
                    SwitchRow("Sadece ses", "Ekran kapalıyken gibi: veri tasarrufu", audioOnly, Icons.Outlined.Headphones) { Player.setAudioOnly(it) }
                    SwitchRow("Döngü", null, loop, Icons.Outlined.Repeat) { Player.setLoop(it) }
                }
                1 -> {
                    Section("Altyazı")
                    OptionRow("Kapalı", sub == null) { Player.setSubtitle(null); page = 0 }
                    subs.forEach { s -> OptionRow(s.label, sub?.url == s.url) { Player.setSubtitle(s); page = 0 } }
                }
                2 -> {
                    Section("Uyku zamanlayıcı")
                    OptionRow("Kapalı", sleep == null) { Player.setSleep(null); page = 0 }
                    listOf(10, 15, 20, 30, 45, 60, 90).forEach { m -> OptionRow("$m dakika", false) { Player.setSleep(m); page = 0 } }
                    OptionRow("Video bitince", sleep == Player.END_OF_VIDEO) { Player.setSleep(-1); page = 0 }
                }
            }
        }
    }
}

private fun label(h: Int) = when {
    h >= 2160 -> "${h}p 4K"
    h >= 1440 -> "${h}p HD"
    h >= 720 -> "${h}p HD"
    else -> "${h}p"
}

private fun sleepLabel(t: Long?): String = when {
    t == null -> "Kapalı"
    t == Player.END_OF_VIDEO -> "Video bitince"
    else -> "${((t - System.currentTimeMillis()) / 60000).coerceAtLeast(1)} dk kaldı"
}

@Composable
private fun QChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) Color(0x2622C3B3) else C.chip)
            .then(if (on) Modifier.border(2.dp, C.accent, RoundedCornerShape(18.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = C.text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(C.chip).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = C.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun MenuRow(icon: ImageVector?, title: String, value: String? = null, color: Color = C.text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(18.dp)) }
        Text(title, style = T.body.copy(color = color), modifier = Modifier.weight(1f))
        value?.let { Text(it, style = T.meta) }
    }
}

@Composable
private fun OptionRow(title: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).height(52.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(40.dp)) { if (on) Icon(Icons.Filled.Check, null, tint = C.text) }
        Text(title, style = T.body)
    }
}

/** Açıklama: başlık, beğeni, görüntüleme, tarih, metin, bölümler */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescriptionSheet(likes: Long, onDismiss: () -> Unit) {
    val info by Player.info.collectAsState()
    val video by Player.video.collectAsState()
    val chapters by Player.chapters.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.surface) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                Text("Açıklama", style = T.section, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Divider()
                Text(video?.title ?: "", style = T.titleBig, modifier = Modifier.padding(16.dp))
                val i = info
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(if (likes >= 0) Fmt.count(likes) else "–", "Beğeni")
                    Stat(if (i != null && i.viewCount >= 0) Fmt.count(i.viewCount) else "–", "Görüntüleme")
                    val d = i?.uploadDate?.offsetDateTime()
                    Stat(d?.let { "${it.dayOfMonth} ${months[it.monthValue - 1]}" } ?: "–", d?.year?.toString() ?: "")
                }
                Spacer(Modifier.height(16.dp))
                val text = remember(i) { i?.description?.content?.let { Yt.htmlToText(it) } ?: "" }
                if (text.isNotBlank()) Text(
                    text, style = T.body,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.chip).padding(12.dp)
                )
                if (chapters.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Bölümler", style = T.section, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            items(chapters) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { Player.seekTo(c.startMs); onDismiss() }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Fmt.ms(c.startMs), color = C.blue, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp))
                    Text(c.title, style = T.body)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private val months = listOf("Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara")

@Composable
private fun Stat(v: String, l: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, style = T.titleBig)
        Text(l, style = T.meta)
    }
}


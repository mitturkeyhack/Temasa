package com.erkam.temasa.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.erkam.temasa.play.Player
import kotlin.math.roundToInt

private val SATIR = 72.dp

/** "Sıradaki" penceresi (tasarım 22): sürükleyerek sırala, kaydırarak çıkar, karıştır, tekrarla */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiraPenceresi(vm: MainViewModel) {
    val q by Player.queue.collectAsState()
    val qi by Player.queueIndex.collectAsState()
    val tekrar by Player.siraTekrar.collectAsState()
    val pos by Player.position.collectAsState()
    val kapat = { vm.siraAcik.value = false }
    LaunchedEffect(q.isEmpty()) { if (q.isEmpty()) kapat() }
    val satirPx = with(LocalDensity.current) { SATIR.toPx() }
    var suruklenen by remember { mutableStateOf<Int?>(null) }
    var kayma by remember { mutableFloatStateOf(0f) }
    val hedef = suruklenen?.let { s -> (s + (kayma / satirPx).roundToInt()).coerceIn(0, (q.size - 1).coerceAtLeast(0)) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (qi > 1) list.scrollToItem(qi - 1) }

    val kalanDk = remember(q, qi, pos / 30_000) {
        val sonra = q.drop(qi + 1).sumOf { it.duration }
        val su = ((q.getOrNull(qi)?.duration ?: 0) * 1000 - pos).coerceAtLeast(0) / 1000
        ((sonra + su) / 60).toInt()
    }

    ModalBottomSheet(
        onDismissRequest = kapat,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1C),
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4A4A))) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sıradaki", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("${qi + 1} / ${q.size}" + if (kalanDk > 0) " · $kalanDk dk kaldı" else "", style = T.meta)
                }
                IconButton(onClick = { Player.siraKaristir(); vm.toast("Karıştırıldı") }) { Icon(Icons.Filled.Shuffle, "Karıştır", tint = C.text) }
                IconButton(onClick = { Player.siraTekrar.value = !tekrar }) { Icon(Icons.Filled.Repeat, "Tekrarla", tint = if (tekrar) C.accent else C.text) }
                IconButton(onClick = {
                    val k = Player.siraKopya(); kapat(); Player.siraTemizle()
                    vm.toastGeriAl("Sıra temizlendi") { Player.siraGeriYukle(k) }
                }) { Icon(Icons.Filled.Close, "Sırayı temizle", tint = C.text) }
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = suruklenen == null) {
                items(q.size, key = { q[it].id }) { i ->
                    val v = q[i]
                    // Sürüklenen satırın geçtiği yerdeki satırlar ona yer açsın
                    val yer = when {
                        suruklenen == null || i == suruklenen -> 0f
                        hedef != null && suruklenen!! < i && i <= hedef -> -satirPx
                        hedef != null && suruklenen!! > i && i >= hedef -> satirPx
                        else -> 0f
                    }
                    val kay by animateFloatAsState(yer, label = "sira")
                    val bu = i == suruklenen
                    val sil = rememberSwipeToDismissBoxState(confirmValueChange = { d ->
                        if (d != SwipeToDismissBoxValue.Settled && i != qi) {
                            val k = Player.siraKopya(); Player.siradanCikar(i)
                            vm.toastGeriAl("Sıradan çıkarıldı") { Player.siraGeriYukle(k) }
                            true
                        } else false
                    })
                    SwipeToDismissBox(
                        state = sil, enableDismissFromStartToEnd = i != qi && suruklenen == null, enableDismissFromEndToStart = i != qi && suruklenen == null,
                        backgroundContent = { Box(Modifier.fillMaxWidth().height(SATIR).background(Color(0xFF3A2420))) },
                        modifier = Modifier.zIndex(if (bu) 1f else 0f).graphicsLayer { translationY = if (bu) kayma else kay }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(SATIR)
                                .then(if (bu) Modifier.shadow(8.dp) else Modifier)
                                .background(if (i == qi) Color(0xFF2A2A2A) else if (bu) Color(0xFF262626) else Color(0xFF1C1C1C))
                                .clickable(enabled = suruklenen == null) { if (i != qi) vm.open(v, q) }
                                .padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                if (i == qi) Icon(Icons.Filled.PlayArrow, null, tint = C.accent, modifier = Modifier.size(16.dp))
                                else Text("${i + 1}", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Thumb(v, Modifier.width(100.dp), corner = 8.dp, small = true)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.title, color = C.text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(v.channel, color = C.text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(
                                Modifier.size(44.dp, SATIR).pointerInput(q.size, i) {
                                    detectDragGestures(
                                        onDragStart = { suruklenen = i; kayma = 0f },
                                        onDrag = { c, d -> c.consume(); kayma += d.y },
                                        onDragEnd = { val s = suruklenen; val h = hedef; suruklenen = null; kayma = 0f; if (s != null && h != null) Player.siraTasi(s, h) },
                                        onDragCancel = { suruklenen = null; kayma = 0f },
                                    )
                                },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.DragIndicator, "Sürükle", tint = Color(0xFF8A8A8A)) }
                        }
                    }
                }
            }
        }
    }
}

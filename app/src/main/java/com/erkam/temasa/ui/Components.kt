package com.erkam.temasa.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.erkam.temasa.data.ChannelRef
import com.erkam.temasa.data.Downloads
import com.erkam.temasa.data.Fmt
import com.erkam.temasa.data.PlaylistRef
import com.erkam.temasa.data.Store
import com.erkam.temasa.data.Video
import kotlinx.coroutines.flow.distinctUntilChanged

/** Video kapağı: süre etiketi, canlı etiketi ve izlenme çizgisi ile */
@Composable
fun Thumb(
    v: Video,
    modifier: Modifier = Modifier,
    corner: Dp = 0.dp,
    small: Boolean = false,
) {
    val positions by Store.positions.collectAsState()
    val progress = remember(positions, v.id) { Store.progress(v.id) }
    var fallback by remember(v.id) { mutableStateOf(false) }
    val cover = remember(v.id) { if (Downloads.state(v.id)?.done == true) Downloads.coverFile(v.id) else null }
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(corner)).background(C.chip)) {
        AsyncImage(
            model = cover ?: if (fallback || small) "https://i.ytimg.com/vi/${v.id}/hqdefault.jpg" else v.thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { if (!fallback) fallback = true },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            v.live -> Badge("CANLI", C.red, Modifier.align(Alignment.BottomEnd))
            v.duration > 0 -> Badge(Fmt.time(v.duration), Color(0xCC000000), Modifier.align(Alignment.BottomEnd))
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(C.red))
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, modifier: Modifier) {
    Text(
        text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = modifier.padding(8.dp).background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(colorFor(name)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
        if (!url.isNullOrEmpty()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

fun colorFor(s: String): Color {
    val palette = listOf(0xFF6D4AFF, 0xFFE0A458, 0xFF3FA796, 0xFF4C7BD9, 0xFFB5739D, 0xFFD86F4A, 0xFF5E9E4A, 0xFF8F6BD8)
    return Color(palette[(s.hashCode() and 0x7fffffff) % palette.size])
}

/** YouTube'daki büyük video kartı */
@Composable
fun VideoCard(v: Video, onClick: () -> Unit, onChannel: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Thumb(v, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 20.dp)) {
            if (v.channel.isNotEmpty()) {
                Avatar(v.channelAvatar, v.channel, 36.dp, Modifier.clickable(enabled = onChannel != null) { onChannel?.invoke() })
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(v.title, style = T.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(v.meta(), style = T.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onMore != null) MoreButton(onMore)
        }
    }
}

/** Yan yana küçük satır (listeler, geçmiş, indirilenler) */
@Composable
fun VideoRow(
    v: Video,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    sub: String? = null,
    leading: (@Composable () -> Unit)? = null,
    thumbWidth: Dp = 160.dp,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, end = 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        leading?.invoke()
        Thumb(v, Modifier.width(thumbWidth), corner = 8.dp, small = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(v.title, style = T.body.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(sub ?: v.meta(), style = T.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            extra?.invoke()
        }
        if (onMore != null) MoreButton(onMore) else Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun MoreButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Filled.MoreVert, "Diğer seçenekler", tint = C.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ChannelRow(c: ChannelRef, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(c.avatar, c.name, 72.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = T.title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.subs?.let { Text(it, style = T.meta, maxLines = 1) }
        }
        trailing?.invoke()
    }
}

@Composable
fun PlaylistRow(p: PlaylistRef, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(C.chip)) {
            AsyncImage(p.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(14.dp))
                p.count?.let { Text(" $it", color = Color.White, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(p.title, style = T.body.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(listOf(p.channel, "Oynatma listesi").filter { it.isNotEmpty() }.joinToString(" · "), style = T.meta, maxLines = 1)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) C.text else C.chip)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) C.bg else C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ChipRow(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items) { i, t -> Chip(t, i == selected) { onSelect(i) } }
    }
}

/** Yüklenirken gri kutular (YouTube gibi) */
@Composable
fun SkeletonCard() {
    val t = rememberInfiniteTransition(label = "s")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxWidth().alpha(a)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(C.chip))
        Row(Modifier.padding(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(C.chip))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(C.chip))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(C.chip))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SkeletonRow() {
    val t = rememberInfiniteTransition(label = "s")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).alpha(a)) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(C.chip))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(C.chip))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(C.chip))
        }
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String? = null, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = C.text2, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = T.title.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        text?.let { Spacer(Modifier.height(8.dp)); Text(it, style = T.body.copy(color = C.text2), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        action?.let { (label, f) ->
            Spacer(Modifier.height(20.dp))
            OutlinePill(label, f)
        }
    }
}

@Composable
fun OutlinePill(label: String, onClick: () -> Unit, icon: ImageVector? = null, color: Color = C.blue) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(Color.Transparent)
            .then(Modifier.border1())
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

fun Modifier.border1(): Modifier = this.border(1.dp, C.chipHi, RoundedCornerShape(20.dp))

/** Gri yuvarlak düğme (Paylaş, İndir...) */
@Composable
fun PillButton(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.text, bg: Color = C.chip) {
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun Loader(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = C.text2, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

/** Listenin sonuna yaklaşınca devamını yükle */
@Composable
fun LoadMore(state: LazyListState, threshold: Int = 6, onLoad: () -> Unit) {
    val near by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { near }.distinctUntilChanged().collect { if (it) onLoad() }
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))

@Composable
fun BoxScope.CenterText(t: String) = Text(t, style = T.body.copy(color = C.text2), modifier = Modifier.align(Alignment.Center))

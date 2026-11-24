package com.erkam.temasa.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.account.LoginActivity
import com.erkam.temasa.data.Store
import com.erkam.temasa.yt.CommentPage
import com.erkam.temasa.yt.Comments
import com.erkam.temasa.yt.YtComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Yorumlar: YouTube'daki gibi sıralama, yazma, yanıtlar, beğenme */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(vm: MainViewModel, videoId: String, initial: CommentPage?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val signed by vm.signedIn.collectAsState()
    val myName by Store.profileName.collectAsState()
    val myPhoto by Store.profilePhoto.collectAsState()

    var head by remember { mutableStateOf(initial) }
    var items by remember { mutableStateOf(initial?.items ?: emptyList()) }
    var next by remember { mutableStateOf(initial?.next) }
    var sort by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(initial == null) }
    var failed by remember { mutableStateOf(false) }

    // Yanıtlar sayfası
    var parent by remember { mutableStateOf<YtComment?>(null) }
    var replies by remember { mutableStateOf<List<YtComment>>(emptyList()) }
    var repliesNext by remember { mutableStateOf<String?>(null) }
    var repliesLoading by remember { mutableStateOf(false) }

    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.toast("Oturum açıldı")
    }

    suspend fun load(token: String?) {
        loading = true; failed = false
        runCatching { withContext(Dispatchers.IO) { if (token == null) Comments.first(videoId) else Comments.page(token) } }
            .onSuccess { p ->
                val h = head
                head = if (token == null || h == null) p
                else p.copy(sorts = p.sorts.ifEmpty { h.sorts }, createParams = p.createParams ?: h.createParams, count = p.count ?: h.count)
                items = p.items; next = p.next
            }
            .onFailure { failed = true }
        loading = false
    }

    LaunchedEffect(videoId) { if (initial == null) load(null) }
    // Girişten sonra yorum yazma yetkisi için yeniden yükle
    LaunchedEffect(signed) { if (signed && head != null && head?.createParams == null) load(null) }

    LoadMore(list) {
        if (parent != null) {
            val t = repliesNext ?: return@LoadMore
            if (repliesLoading) return@LoadMore
            repliesLoading = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Comments.page(t) } }.onSuccess { p -> replies = replies + p.items; repliesNext = p.next }
                repliesLoading = false
            }
        } else {
            val t = next ?: return@LoadMore
            if (loading) return@LoadMore
            loading = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Comments.page(t) } }.onSuccess { p -> items = items + p.items; next = p.next }
                loading = false
            }
        }
    }

    fun openReplies(c: YtComment) {
        parent = c; replies = emptyList(); repliesNext = null; text = ""
        val t = c.repliesToken ?: return
        repliesLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Comments.page(t) } }.onSuccess { p -> replies = p.items; repliesNext = p.next }
            repliesLoading = false
            list.scrollToItem(0)
        }
    }

    fun needLogin(): Boolean {
        if (signed) return false
        vm.toast("Bunun için oturum aç")
        login.launch(Intent(ctx, LoginActivity::class.java))
        return true
    }

    fun rate(c: YtComment, like: Boolean) {
        if (needLogin()) return
        val newState = if (like) (if (c.likeState == "LIKE") "INDIFFERENT" else "LIKE") else (if (c.likeState == "DISLIKE") "INDIFFERENT" else "DISLIKE")
        val action = when {
            like && c.likeState == "LIKE" -> c.unlikeAction
            like -> c.likeAction
            c.likeState == "DISLIKE" -> c.undislikeAction
            else -> c.dislikeAction
        }
        if (action == null) { vm.toast("Bu yorum için yapılamıyor"); return }
        val upd = c.copy(likeState = newState)
        items = items.map { if (it.id == c.id) upd else it }
        replies = replies.map { if (it.id == c.id) upd else it }
        if (parent?.id == c.id) parent = upd
        scope.launch { if (runCatching { withContext(Dispatchers.IO) { Comments.action(action) } }.isFailure) vm.toast("YouTube'a iletilemedi") }
    }

    fun send() {
        val t = text.trim()
        if (t.isEmpty() || sending || needLogin()) return
        val p = parent
        val params = if (p != null) p.replyParams else head?.createParams
        if (params == null) { vm.toast(if (p != null) "Bu yoruma yanıt verilemiyor" else "Bu videoda yorum yapılamıyor"); return }
        sending = true
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { if (p != null) Comments.reply(params, t) else Comments.create(params, t) } }.isSuccess
            sending = false
            if (ok) {
                val mine = YtComment(
                    id = "yeni" + System.currentTimeMillis(), author = myName ?: "Sen", authorId = "", avatar = myPhoto, text = t,
                    ago = "şimdi", likes = "", replyCount = 0, hearted = false, pinned = false, creator = false, mine = true,
                    likeState = "INDIFFERENT", likeAction = null, unlikeAction = null, dislikeAction = null, undislikeAction = null,
                    replyParams = null, repliesToken = null, isReply = p != null,
                )
                if (p != null) replies = replies + mine else { items = listOf(mine) + items; list.scrollToItem(0) }
                text = ""
                vm.toast(if (p != null) "Yanıtın gönderildi" else "Yorumun gönderildi")
            } else vm.toast("Gönderilemedi. Tekrar dene.")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.surface,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(C.chipHi)) },
    ) {
        BackHandler(enabled = parent != null) { parent = null; text = "" }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.93f).imePadding()) {
            // Başlık
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (parent != null) IconButton(onClick = { parent = null; text = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = C.text) }
                else Spacer(Modifier.width(12.dp))
                Text(if (parent != null) "Yanıtlar" else "Yorumlar", style = T.section.copy(fontSize = 20.sp))
                if (parent == null) head?.count?.let { Text("  " + it.replace(" Yorum", "").replace(" yorum", ""), style = T.body.copy(color = C.text2, fontSize = 16.sp)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Kapat", tint = C.text) }
            }
            if (parent == null && (head?.sorts?.size ?: 0) > 1) {
                ChipRow(head!!.sorts.map { it.first.replace("En popüler", "Popüler") }, sort) { i ->
                    if (i != sort) { sort = i; val tok = head!!.sorts[i].second; scope.launch { items = emptyList(); load(tok); list.scrollToItem(0) } }
                }
            }
            Divider()

            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
                val p = parent
                if (p != null) {
                    item(key = "parent") {
                        CommentRow(p, big = true, onLike = { rate(p, true) }, onDislike = { rate(p, false) }, onReply = { if (!needLogin()) runCatching { focus.requestFocus() } }, onReplies = null)
                    }
                    items(replies, key = { "r" + it.id }) { r ->
                        Box(Modifier.padding(start = 40.dp)) {
                            CommentRow(r, big = false, onLike = { rate(r, true) }, onDislike = { rate(r, false) },
                                onReply = { if (!needLogin()) { text = "${r.author} "; runCatching { focus.requestFocus() } } }, onReplies = null)
                        }
                    }
                    if (repliesLoading) item { Loader() }
                } else {
                    if (loading && items.isEmpty()) item { Loader() }
                    if (!loading && items.isEmpty()) item {
                        Text(
                            if (failed) "Yorumlar yüklenemedi." else "Yorumlar kapalı ya da henüz yorum yok.",
                            style = T.body.copy(color = C.text2), modifier = Modifier.padding(24.dp)
                        )
                    }
                    items(items, key = { "c" + it.id }) { c ->
                        CommentRow(c, big = true, onLike = { rate(c, true) }, onDislike = { rate(c, false) },
                            onReply = { if (!needLogin()) openReplies(c) },
                            onReplies = if (c.replyCount > 0 && c.repliesToken != null) ({ openReplies(c) }) else null)
                    }
                    if (loading && items.isNotEmpty()) item { Loader() }
                }
            }

            // Yazma kutusu
            Divider()
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!signed) {
                    Row(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.chip)
                            .clickable { login.launch(Intent(ctx, LoginActivity::class.java)) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { Text("Yorum yapmak için oturum aç", color = C.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                } else {
                    Avatar(myPhoto, myName ?: "S", 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(C.chip).padding(horizontal = 16.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (text.isEmpty()) Text(if (parent != null) "Yanıtlayın..." else "Yorum ekleyin...", color = C.text2, fontSize = 15.sp)
                        BasicTextField(
                            value = text, onValueChange = { text = it },
                            textStyle = T.body.copy(fontSize = 15.sp), cursorBrush = SolidColor(C.text), maxLines = 5,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (text.isNotBlank() || sending) {
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = { send() }, enabled = !sending) {
                            if (sending) CircularProgressIndicator(color = C.text, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            else Icon(Icons.AutoMirrored.Filled.Send, "Gönder", tint = C.blue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    c: YtComment,
    big: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
    onReplies: (() -> Unit)?,
) {
    var expanded by remember(c.id) { mutableStateOf(false) }
    var overflow by remember(c.id) { mutableStateOf(false) }
    val avatar: Dp = if (big) 36.dp else 26.dp
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Avatar(c.avatar, c.author.removePrefix("@").ifEmpty { "?" }, avatar)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (c.pinned) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PushPin, null, tint = C.text2, modifier = Modifier.size(12.dp))
                Text(" Sabitlendi", style = T.meta)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val name = c.author.ifEmpty { "Kullanıcı" }
                if (c.creator) Text(
                    name, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.chipHi).padding(horizontal = 6.dp, vertical = 1.dp)
                ) else Text(name, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" • ${c.ago}", style = T.meta, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                c.text, style = T.body.copy(fontSize = 15.sp, lineHeight = 21.sp),
                maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow },
                modifier = Modifier.clickable(enabled = overflow || expanded) { expanded = !expanded }
            )
            if (overflow && !expanded) Text("Devamını okuyun", color = C.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { expanded = true }.padding(vertical = 2.dp))
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onLike).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (c.likeState == "LIKE") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Beğen", tint = C.text, modifier = Modifier.size(18.dp))
                    if (c.likes.isNotBlank() && c.likes != "0") Text("  ${c.likes}", style = T.meta)
                }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onDislike).padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Icon(if (c.likeState == "DISLIKE") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Beğenme", tint = C.text, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onReply).padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, "Yanıtla", tint = C.text, modifier = Modifier.size(18.dp))
                }
                if (c.hearted) {
                    Spacer(Modifier.width(20.dp))
                    Icon(Icons.Filled.Favorite, "Kanal sahibi beğendi", tint = C.red, modifier = Modifier.size(16.dp))
                }
            }
            if (onReplies != null) {
                Text(
                    "${c.replyCount} yanıt", color = C.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onReplies).padding(vertical = 6.dp, horizontal = 2.dp)
                )
            }
        }
    }
}

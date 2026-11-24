package com.erkam.temasa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erkam.temasa.data.Bildirimler
import com.erkam.temasa.data.ChannelRef

/** Zil simgesi: kanalın bildirim seçimine göre */
@Composable
fun zilIkonu(kanal: String): ImageVector {
    val z by Bildirimler.ziller.collectAsState()
    return when (z[kanal] ?: Bildirimler.Zil.KISI) {
        Bildirimler.Zil.TUM -> Icons.Filled.NotificationsActive
        Bildirimler.Zil.KISI -> Icons.Outlined.NotificationsNone
        Bildirimler.Zil.YOK -> Icons.Outlined.NotificationsOff
    }
}

/** "Abone olundu"ya basınca (tasarım 23): bildirim seçimi ve abonelikten çık */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZilPenceresi(vm: MainViewModel, k: ChannelRef, abonelikDenCik: () -> Unit) {
    val ctx = LocalContext.current
    val z by Bildirimler.ziller.collectAsState()
    val acik by Bildirimler.acik.collectAsState()
    val secili = z[k.id] ?: Bildirimler.Zil.KISI
    val kapat = { vm.zilKanal.value = null }
    ModalBottomSheet(onDismissRequest = kapat, containerColor = Color(0xFF1C1C1C)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            Text("Bildirimler · ${k.name}", color = C.text2, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            if (!acik) Text("Abonelik bildirimleri Ayarlar'da kapalı.", color = Color(0xFFFFB199), fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).clickable { Bildirimler.acikSec(ctx, true) })
            Bildirimler.Zil.entries.forEach { s ->
                val ikon = when (s) { Bildirimler.Zil.TUM -> Icons.Filled.NotificationsActive; Bildirimler.Zil.KISI -> Icons.Outlined.NotificationsNone; else -> Icons.Outlined.NotificationsOff }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (s == secili) Color(0xFF262626) else Color.Transparent)
                        .clickable { Bildirimler.zilSec(k.id, s); if (s != Bildirimler.Zil.YOK && !acik) Bildirimler.acikSec(ctx, true); kapat() }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(ikon, null, tint = C.text, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.ad, color = C.text, fontSize = 15.sp)
                        Text(s.aciklama, color = Color(0xFF9A9A9A), fontSize = 12.sp)
                    }
                    if (s == secili) Icon(Icons.Filled.Check, null, tint = C.accent, modifier = Modifier.size(20.dp))
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color(0xFF2E2E2E)))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { kapat(); abonelikDenCik() }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.PersonRemove, null, tint = C.text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Text("Abonelikten çık", color = C.text, fontSize = 15.sp)
            }
        }
    }
}

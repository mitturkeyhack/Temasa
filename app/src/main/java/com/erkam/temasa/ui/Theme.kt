package com.erkam.temasa.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** YouTube koyu tema renkleri */
object C {
    val bg = Color(0xFF0F0F0F)
    val surface = Color(0xFF212121)
    val card = Color(0xFF1F1F1F)
    val chip = Color(0xFF272727)
    val chipHi = Color(0xFF3F3F3F)
    val divider = Color(0xFF2E2E2E)
    val text = Color(0xFFF1F1F1)
    val text2 = Color(0xFFAAAAAA)
    val text3 = Color(0xFF717171)
    val accent = Color(0xFF22C3B3)
    val red = Color(0xFFFF0033)
    val blue = Color(0xFF3EA6FF)
    val green = Color(0xFF00D400)
    val sponsor = Color(0xFF00D400)
}

object T {
    val title = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = C.text)
    val titleBig = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = C.text)
    val meta = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = C.text2)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = C.text)
    val small = TextStyle(fontSize = 12.sp, color = C.text2)
    val header = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.text)
    val section = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.text)
}

@Composable
fun TemasaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.text,
            onPrimary = C.bg,
            secondary = C.accent,
            background = C.bg,
            surface = C.surface,
            surfaceContainer = C.surface,
            surfaceContainerHigh = Color(0xFF282828),
            surfaceContainerHighest = Color(0xFF303030),
            surfaceContainerLow = C.card,
            onSurface = C.text,
            onSurfaceVariant = C.text2,
            outline = C.chipHi,
            outlineVariant = C.divider,
        ),
        content = content
    )
}

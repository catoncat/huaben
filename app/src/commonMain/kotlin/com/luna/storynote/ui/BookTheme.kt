package com.luna.storynote.ui

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val ShelfBackground = Color(0xFFF6F0E4)
internal val BookPageBackground = Color(0xFFFCF9F2)
internal val BookCardSurface = Color(0xFFFFFDF9)
internal val BookHairline = Color(0xFFE6DCCB)
internal val BookBorder = Color(0xFFDFD3BE)

internal val BookInk = Color(0xFF231E1A)
internal val BookSubInk = Color(0xFF5A4E43)
internal val PinyinInk = Color(0xFF706154)
internal val MutedInk = Color(0xFF8C7E70)

internal val Terracotta = Color(0xFFD95338)
internal val TerracottaSoft = Color(0xFFFCEDE8)
internal val ForestGreen = Color(0xFF287D57)
internal val ForestGreenSoft = Color(0xFFEBF6F0)
internal val AmberInk = Color(0xFFB86B14)
internal val AmberSoft = Color(0xFFFEF4E2)

internal val BookSpinePalette = listOf(
    Color(0xFFD95B43),
    Color(0xFF45876D),
    Color(0xFF4F77A3),
    Color(0xFFC48733),
    Color(0xFF82609E),
    Color(0xFF3B838A),
)

internal fun spineColorFor(index: Int): Color =
    BookSpinePalette[(index.coerceAtLeast(0)) % BookSpinePalette.size]

internal fun formatDurationClock(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(Locale.US, s / 60, s % 60)
}

internal fun formatBookDate(ms: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ms))

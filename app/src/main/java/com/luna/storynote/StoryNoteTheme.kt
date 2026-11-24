package com.luna.storynote

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val PaperBg = Color(0xFFFDF8EE)
internal val CardBg = Color(0xFFFFFFFF)
internal val LinedPaperBg = Color(0xFFFFFCF5)
internal val BorderWarm = Color(0xFFECE2CE)
internal val InkStrong = Color(0xFF2A241E)
internal val InkBody = Color(0xFF3E362E)
internal val InkMuted = Color(0xFF847769)
internal val CoralMain = Color(0xFFF25738)
internal val CoralSoft = Color(0xFFFFF0EC)
internal val GreenMain = Color(0xFF22A06B)
internal val GreenSoft = Color(0xFFEDF9F2)
internal val AmberMain = Color(0xFFD97706)
internal val AmberSoft = Color(0xFFFEF3C7)

internal fun formatClock(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(Locale.US, total / 60, total % 60)
}

internal fun formatDateShort(ms: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))

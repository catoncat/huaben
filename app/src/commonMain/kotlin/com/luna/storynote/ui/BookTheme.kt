package com.luna.storynote.ui

import androidx.compose.ui.graphics.Color

// Studio & Paper Surfaces (Paper by FiftyThree / Apple Books inspired)
internal val DeskSurface = Color(0xFFEAE3D4)
internal val DeskTopBarGlass = Color(0xF2EAE3D4)
internal val BookPaper = Color(0xFFFAF7F0)
internal val BookPaperWarm = Color(0xFFF6F1E6)
internal val BookPageEdge1 = Color(0xFFEDE6D8)
internal val BookPageEdge2 = Color(0xFFE0D7C5)
internal val BookRuleLine = Color(0xFFE3DAC9)
internal val BookOutline = Color(0xFFD8CEBC)

// Typography Ink
internal val InkPrimary = Color(0xFF1F1B17)
internal val InkSecondary = Color(0xFF574C41)
internal val InkPinyin = Color(0xFF756759)
internal val InkSubtle = Color(0xFF968879)
internal val InkDraftWet = Color(0xFF5C4E42)

// Accents & Highlights
internal val Vermilion = Color(0xFFD44A2E)
internal val VermilionSoft = Color(0xFFFAEDE8)
internal val HighlightSun = Color(0xFFF9EED5)
internal val HighlightWord = Color(0xFFF5E3C3)
internal val JadeInk = Color(0xFF2B6E57)
internal val JadeSoft = Color(0xFFE7F3EE)
internal val TianZiRed = Color(0xFFD98B7E)
internal val StrokeGhost = Color(0xFFE7DFD0)

// Curated Museum Book-Cloth Covers & Gold Foil
internal val BookClothColors = listOf(
    Color(0xFFB84630), // Vermilion Cloth
    Color(0xFF2D6353), // Pine Viridian Cloth
    Color(0xFF345375), // Prussian Indigo Cloth
    Color(0xFFA8742B), // Ochre Mustard Cloth
    Color(0xFF61476B), // Aubergine Cloth
    Color(0xFF455C41), // Moss Cloth
)

internal val FoilGold = Color(0xFFEAD4AA)
internal val TitlePlatePaper = Color(0xFFFAF6EC)

internal fun bookClothFor(index: Int): Color =
    BookClothColors[(index.coerceAtLeast(0)) % BookClothColors.size]

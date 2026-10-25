package com.yinxia.music.ui.theme

import androidx.compose.ui.graphics.Color

// 深色主题
val Violet80 = Color(0xFFC9BEFF)
val VioletGrey80 = Color(0xFFCBC4DA)
val Amber80 = Color(0xFFFFD8A8)

// 浅色主题
val Violet40 = Color(0xFF5B45C9)
val VioletGrey40 = Color(0xFF605A72)
val Amber40 = Color(0xFF8A5A00)

/** 没有封面时的渐变底色，按歌曲 id 取，保证同一首歌颜色始终一致。 */
val ArtworkGradients: List<Pair<Color, Color>> = listOf(
    Color(0xFF6A4BE0) to Color(0xFF2B1B57),
    Color(0xFF2E7D9A) to Color(0xFF102E3A),
    Color(0xFFC2566B) to Color(0xFF3C1622),
    Color(0xFF3E8E62) to Color(0xFF122C1F),
    Color(0xFFE0A13B) to Color(0xFF3D2A08),
    Color(0xFF7A5CC7) to Color(0xFF23203F),
)

fun gradientFor(songId: Long): Pair<Color, Color> {
    val index = ((songId % ArtworkGradients.size) + ArtworkGradients.size) % ArtworkGradients.size
    return ArtworkGradients[index.toInt()]
}

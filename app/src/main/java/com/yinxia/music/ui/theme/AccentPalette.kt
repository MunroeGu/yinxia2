package com.yinxia.music.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.yinxia.music.R

/** 一个可选的主题色。 */
data class AccentOption(
    @StringRes val nameRes: Int,
    /** 种子色：真正用之前会经 [accentForTheme] 按明暗主题调整 */
    val seed: Color,
)

/**
 * 默认主题色的可选项（封面没有图片时用它）。
 *
 * 只给固定预设、不做取色轮：预设能保证每个颜色在浅色/深色下都好看，
 * 随便取色很容易取到看不清的颜色。
 */
val AccentPalette: List<AccentOption> = listOf(
    AccentOption(R.string.accent_violet, Color(0xFF6C4BE0)),
    AccentOption(R.string.accent_indigo, Color(0xFF3F51B5)),
    AccentOption(R.string.accent_blue, Color(0xFF1F6FEB)),
    AccentOption(R.string.accent_cyan, Color(0xFF0B7C99)),
    AccentOption(R.string.accent_teal, Color(0xFF0F8B7E)),
    AccentOption(R.string.accent_green, Color(0xFF2E8B45)),
    AccentOption(R.string.accent_amber, Color(0xFFC98A00)),
    AccentOption(R.string.accent_orange, Color(0xFFD2691E)),
    AccentOption(R.string.accent_red, Color(0xFFD32F2F)),
    AccentOption(R.string.accent_pink, Color(0xFFD81B60)),
)

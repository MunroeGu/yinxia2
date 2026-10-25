package com.yinxia.music.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * 把"种子色"调整成当前明暗主题下能当强调色用的颜色。
 *
 * 封面里挑出来的颜色和用户自选的颜色都走这里：
 *  - 饱和度抬到至少 0.45：否则灰扑扑的没有颜色感；
 *  - 亮度按主题夹进区间：深色主题要够亮（否则看不见），浅色主题要够深（否则发白看不清）。
 *
 * 所以调色板里存的是种子色，而不是最终颜色 —— 同一个种子在两种主题下都能用。
 */
fun accentForTheme(seed: Color, darkTheme: Boolean): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(seed.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = if (darkTheme) {
        hsv[2].coerceIn(0.72f, 0.92f)
    } else {
        hsv[2].coerceIn(0.40f, 0.55f)
    }
    return Color(AndroidColor.HSVToColor(hsv))
}

/** 压在这个颜色上的文字/图标该用白还是黑。 */
fun onAccentColor(color: Color): Color =
    if (color.luminance() < 0.5f) Color.White else Color.Black

/**
 * 用强调色覆盖 Material 主题的主色。
 *
 * 刻意只改 primary / onPrimary / primaryContainer / onPrimaryContainer 四个：
 * 界面里所有"强调"的地方（播放按钮、进度条、选中行、图标）会自动跟着变，
 * 而背景、正文颜色仍走原来的配色 —— 整个界面不会变成一锅花里胡哨的颜色。
 *
 * [accent] 为 null 时（没在播放、封面提取失败、用户没自定义默认色）保持原样。
 */
@Composable
fun AccentTheme(
    accent: Color?,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme

    // ⚠️ 这里**必须只有一条调用结构**，不能写成"accent == null 就直接 content() 返回"。
    //
    // 之前就是那么写的：两条分支里 content() 的调用点不同，于是当强调色在"有 / 无"之间切换时，
    // Compose 会认为那是一棵全新的树，把整个子树重建一遍 ——
    // LazyColumn 的滚动状态随之丢失，表现为"换歌时闪一下并跳回列表顶部"。
    // 触发条件很具体：切到一首没有封面的歌（取色返回 null，又没设过默认色），强调色变 null。
    val container = if (accent == null) base.primaryContainer else lerp(base.surface, accent, 0.28f)
    val scheme = if (accent == null) {
        base
    } else {
        base.copy(
            primary = accent,
            onPrimary = onAccentColor(accent),
            primaryContainer = container,
            onPrimaryContainer = onAccentColor(container),
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

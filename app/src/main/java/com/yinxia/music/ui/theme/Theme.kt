package com.yinxia.music.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Violet80,
    secondary = VioletGrey80,
    tertiary = Amber80,
)

private val LightColors = lightColorScheme(
    primary = Violet40,
    secondary = VioletGrey40,
    tertiary = Amber40,
)

@Composable
fun YinxiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * 想跟手机壁纸取色（Android 12+ 的 Monet）改成 true 即可。
     * 默认 false：保留 App 自己的紫罗兰主色，风格更统一。
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = YinxiaTypography,
        content = content,
    )
}

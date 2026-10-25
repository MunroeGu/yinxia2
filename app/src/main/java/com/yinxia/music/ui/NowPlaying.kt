package com.yinxia.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.yinxia.music.R
import com.yinxia.music.data.ArtworkLoader
import com.yinxia.music.data.Song
import com.yinxia.music.util.formatDuration
import kotlinx.coroutines.isActive

/**
 * 全屏播放页（由 ModalBottomSheet 承载）。
 *
 * 进度条拖动期间用本地 dragPosition 显示，松手才 seek：
 * 否则每帧都把拖动位置写回播放器，会和播放进度互相打架，出现"拖不动"的抖动。
 */
@Composable
fun NowPlayingContent(
    song: Song,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    val total = durationMs.coerceAtLeast(1L)
    val shown = dragPosition?.toLong() ?: positionMs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = stringResource(R.string.action_close),
                )
            }
            Text(
                text = stringResource(R.string.now_playing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(48.dp))
        }

        Spacer(Modifier.height(12.dp))

        // 播放页封面做成黑胶唱片：播放时整张唱片匀速旋转，暂停时停在当时的角度
        VinylRecord(
            song = song,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxWidth(0.86f),
        )

        Spacer(Modifier.height(28.dp))

        Text(
            text = song.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.artist ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        Slider(
            value = shown.coerceIn(0L, total).toFloat() / total.toFloat(),
            onValueChange = { fraction -> dragPosition = fraction * total },
            onValueChangeFinished = {
                dragPosition?.let { onSeek(it.toLong()) }
                dragPosition = null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(shown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    painter = painterResource(R.drawable.ic_shuffle),
                    contentDescription = stringResource(R.string.action_shuffle),
                    tint = if (shuffleEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.action_previous),
                    modifier = Modifier.size(34.dp),
                )
            }

            FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp)) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    ),
                    contentDescription = stringResource(
                        if (isPlaying) R.string.action_pause else R.string.action_play,
                    ),
                    modifier = Modifier.size(34.dp),
                )
            }

            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.action_next),
                    modifier = Modifier.size(34.dp),
                )
            }

            IconButton(onClick = onCycleRepeat) {
                Icon(
                    painter = painterResource(
                        if (repeatMode == Player.REPEAT_MODE_ONE) {
                            R.drawable.ic_repeat_one
                        } else {
                            R.drawable.ic_repeat
                        },
                    ),
                    contentDescription = stringResource(R.string.action_repeat),
                    tint = if (repeatMode == Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 黑胶唱片封面：底盘 + 圆形封面 + 中心孔一起随播放旋转，右上角一根唱臂随播放搭上/抬起。
 *
 * 旋转的实现细节：
 *  - 角度由 [Animatable] 持有，动画写在 `LaunchedEffect(isPlaying)` 里循环 animateTo。
 *    暂停（isPlaying 变 false）时协程被取消，**角度冻结在当前位置**；
 *    继续播放从同一角度接着转 —— 和真实唱机「停针再抬起接着放」的手感一致。
 *  - 每转完一整圈把角度 snap 回 0~360：不收的话角度会无限增大，
 *    转上几个小时后 Float 的精度就不够画出平滑旋转了。
 *  - 底盘的同心圆纹理是圆对称的，转起来本身看不出来；「在转」的视觉证据主要是
 *    封面图案在动。纹理负责的是静止时的质感（有沟槽，而不是一块纯色圆板）。
 *  - 一圈 [VINYL_ROTATION_MS]（20 秒）是主流音乐 App 唱片动画的常见速度，转太快会晕。
 */
@Composable
private fun VinylRecord(
    song: Song,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(durationMillis = VINYL_ROTATION_MS, easing = LinearEasing),
            )
            rotation.snapTo(rotation.value % 360f)
        }
    }

    // 唱臂：播放时针头搭在唱片沟槽区（顺时针压向中心方向），暂停时抬起离开盘面
    val armAngle by animateFloatAsState(
        targetValue = if (isPlaying) VINYL_ARM_ENGAGED else VINYL_ARM_LIFTED,
        animationSpec = tween(durationMillis = 360),
        label = "vinylArm",
    )

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val discColor = Color(0xFF17171B)
        val grooveColor = Color.White

        // 旋转层 1：黑胶底盘 + 沟槽纹理
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(color = discColor, radius = radius)
            // 外缘一圈高光，勾出轮廓
            drawCircle(
                color = grooveColor.copy(alpha = 0.10f),
                radius = radius - 1.dp.toPx(),
                style = Stroke(width = 1.dp.toPx()),
            )
            // 封面外圈到盘缘之间的沟槽：很淡的几圈同心圆
            listOf(0.760f, 0.800f, 0.845f, 0.895f).forEach { fraction ->
                drawCircle(
                    color = grooveColor.copy(alpha = 0.045f),
                    radius = radius * fraction,
                    style = Stroke(width = 1f),
                )
            }
        }

        // 圆形封面，直径约唱片的 68% —— 和主流音乐 App 的唱片比例一致
        Artwork(
            song = song,
            modifier = Modifier.fillMaxSize(0.68f),
            shape = CircleShape,
            targetPx = ArtworkLoader.LARGE_PX,
        )

        // 旋转层 2：中心孔。放在封面之上、随封面同转，位置永远和封面圆心重合
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value },
        ) {
            val hole = size.minDimension / 2f * 0.05f
            drawCircle(color = Color(0xFF101013), radius = hole)
            drawCircle(
                color = Color.White.copy(alpha = 0.28f),
                radius = hole,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // 唱臂：不随唱片转。pivot 固定在右上角，竖直向下为 0°，
        // 正角把针头往盘心方向压（搭上），负角往外摆（抬起）。
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width * 0.90f, size.height * 0.08f)
            rotate(degrees = armAngle, pivot = pivot) {
                val needle = Offset(pivot.x, pivot.y + size.height * 0.32f)
                val armColor = Color(0xFF8A8595)
                drawLine(
                    color = armColor,
                    start = pivot,
                    end = needle,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(color = armColor, radius = 4.dp.toPx(), center = pivot)
                drawCircle(color = Color(0xFFD7D2E0), radius = 2.dp.toPx(), center = needle)
            }
        }
    }
}

/** 一整圈用时：太快会有点晕，20 秒和主流音乐 App 的唱片动画速度一致 */
private const val VINYL_ROTATION_MS = 20_000

/** 唱臂角度：0° = 竖直下垂（针头自然落在盘缘沟槽），正角搭向盘心，负角抬离盘面 */
private const val VINYL_ARM_ENGAGED = 12f
private const val VINYL_ARM_LIFTED = -25f

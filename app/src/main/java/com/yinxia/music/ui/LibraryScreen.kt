package com.yinxia.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
// scrollBy 不是 LazyListState 的成员方法，而是 foundation.gestures 里的扩展函数，必须显式 import
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yinxia.music.R
import com.yinxia.music.data.ArtworkLoader
import com.yinxia.music.data.Song
import com.yinxia.music.util.formatDuration
import com.yinxia.music.util.songDetailLines
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 音乐库列表。三种形态：
 *  - 普通：点一下播放，长按进入多选
 *  - 多选：每行前面出现勾选框
 *  - 手动排序（[sorting] = true）：长按整行上下拖动排序。此模式行内不放任何图标，
 *    歌曲信息不会被遮挡；排完点顶部「完成」退出，列表恢复成普通形态。
 *
 * 拖动实现上有四个坑，这里都处理了：
 *  1. 用长按触发（[detectDragGesturesAfterLongPress]）。普通拖动会和列表滚动抢手势。
 *  2. 判定"跨过相邻行"必须用**下标**而不是 key：换位后被拖项的下标就等于目标下标，
 *     天然排除了"同一帧里反复换位"；如果用 key 做去重，往回拖时会被误判成重复而卡死。
 *  3. 拖到边缘要自动滚动，否则几百首的列表里没法长距离调整。
 *  4. 被拖行的视觉位置必须由「手指位置」和「它当前的布局位置」**推导**，不能自己累加。
 *     旧实现每帧无条件 `dragOffset += 滚动量`：列表滚到头、滚不动时，这段偏移没有任何东西
 *     抵消它，每帧 10dp 一路累积，行就脱离手指飞出屏幕。改成推导后，滚不动时布局位置不变、
 *     推导结果也就不变；而且每次都夹在列表可见范围内，行不可能被画到列表外面。
 */
@Composable
fun LibraryScreen(
    songs: List<Song>,
    currentSongId: Long,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    sorting: Boolean,
    showDetails: Boolean,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onToggleSelection: (Song) -> Unit,
    onMoveSong: (draggedId: Long, targetId: Long) -> Unit,
    onDragFinished: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 被拖动的行：key 用来在布局里定位它，index 用来判断它是不是已经排到首位/末位
    var draggingKey by remember { mutableStateOf<Long?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    // 被拖行相对它**当前布局位置**的偏移。它只由 syncDragOffset() 推导，自己从不累加
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 手指在列表坐标系里的绝对位置：只随手指真实移动变化，列表怎么滚它都不变
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    // 落点在这一行内部的偏移，于是「行的视觉顶端 = dragPointerY - dragGrabY」
    var dragGrabY by remember { mutableFloatStateOf(0f) }
    // 拖到边缘时的滚动速度，0 表示不滚
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    // 上一次换位后这一行应该落在的下标；布局把它反映出来之前不再判定跨行，
    // 否则同一批指针事件会把刚换过去的歌又换回来
    var pendingIndex by remember { mutableIntStateOf(-1) }

    val currentOnMove = rememberUpdatedState(onMoveSong)
    val currentOnDragFinished = rememberUpdatedState(onDragFinished)
    // 拖动中 songs 可能被换成新列表，而手势 lambda 是长驻的，条数必须读最新值
    val currentSongCount = rememberUpdatedState(songs.size)

    val density = LocalDensity.current
    val edgeTriggerPx = with(density) { 72.dp.toPx() }
    val autoScrollStepPx = with(density) { 10.dp.toPx() }

    fun resetDrag() {
        draggingKey = null
        draggingIndex = -1
        dragOffset = 0f
        dragPointerY = 0f
        dragGrabY = 0f
        autoScrollSpeed = 0f
        pendingIndex = -1
    }

    /**
     * 把被拖的行钉回手指下面，并夹进列表可见范围。返回它当前的布局信息，取不到（还没测量）时为 null。
     *
     * 这里是**推导**而不是累加，也是这次修复的核心：
     *   视觉顶端 = 手指位置 - 抓取点（再夹到列表可见范围内）
     *   偏移     = 视觉顶端 - 这一行当前的布局位置
     * 列表自动滚动时，这一行自己的布局位置就会变，下一次推导自然把差值补回来；
     * 列表滚不动（到顶/到底）时布局位置不再变化，推导结果也就固定下来，不会再跑。
     * 旧实现每帧无条件 `dragOffset += 滚动量`，滚不动时没有任何东西抵消它，
     * 于是一帧 10dp 地累积，行就离开手指飞出屏幕。
     */
    fun syncDragOffset(): LazyListItemInfo? {
        val key = draggingKey ?: return null
        val layout = listState.layoutInfo
        val info = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        draggingIndex = info.index

        // 以"首/末可见行"与"视口边界"取交集：行可以一直被推到第一行(最后一行)的槽位上，
        // 但绝不会被画到列表可见内容之外
        val first = layout.visibleItemsInfo.first()
        val last = layout.visibleItemsInfo.last()
        val minTop = maxOf(first.offset.toFloat(), layout.viewportStartOffset.toFloat())
        val maxTop = minOf(
            (last.offset + last.size - info.size).toFloat(),
            (layout.viewportEndOffset - info.size).toFloat(),
        )
        val visualTop = (dragPointerY - dragGrabY)
            .coerceIn(minTop, maxTop.coerceAtLeast(minTop))
        dragOffset = visualTop - info.offset
        return info
    }

    /**
     * 判定被拖动的行是否跨过了相邻行，跨过就换位。
     *
     * 换位之后视觉位置不需要手工补偿：下一次 [syncDragOffset] 会按这行新的槽位重新推导，
     * 位置保持连续（旧实现要手动用 target.offset 修正，正是补偿写错的来源）。
     */
    fun evaluateCrossing() {
        val draggedKey = draggingKey ?: return
        val info = syncDragOffset() ?: return

        // 布局还没把上一次换位反映出来（这行仍停在旧下标）时先不判定：
        // 这时行心还在同一个目标上，再换一次正好把刚换过去的歌换回来
        if (pendingIndex >= 0) {
            if (info.index != pendingIndex) return
            pendingIndex = -1
        }

        val layout = listState.layoutInfo
        val visualTop = info.offset + dragOffset
        val visualCenter = visualTop + info.size / 2f

        val target = layout.visibleItemsInfo.firstOrNull { candidate ->
            candidate.key != draggedKey &&
                visualCenter.toInt() in candidate.offset until (candidate.offset + candidate.size)
        } ?: return

        val targetKey = target.key as? Long ?: return
        currentOnMove.value(draggedKey, targetKey)
        pendingIndex = target.index
    }

    /**
     * 根据被拖行的视觉位置决定这一帧要不要自动滚动。
     *
     * 已经排到第一位/最后一位，或者列表在这个方向上已经滚不动了，速度直接归零 ——
     * 这时再滚只是空转（旧实现就是在这里空转，并且把行一路推离手指）。
     */
    fun updateAutoScroll(info: LazyListItemInfo) {
        val layout = listState.layoutInfo
        val visualTop = info.offset + dragOffset
        val visualBottom = visualTop + info.size
        val canScrollUp = draggingIndex > 0 && listState.canScrollBackward
        val canScrollDown = draggingIndex < currentSongCount.value - 1 &&
            listState.canScrollForward
        autoScrollSpeed = when {
            canScrollUp && visualTop < layout.viewportStartOffset + edgeTriggerPx ->
                -autoScrollStepPx

            canScrollDown && visualBottom > layout.viewportEndOffset - edgeTriggerPx ->
                autoScrollStepPx

            else -> 0f
        }
    }

    // 拖到边缘时自动滚动：没有这个，长列表里只能在小范围内调整顺序
    LaunchedEffect(draggingKey) {
        if (draggingKey == null) return@LaunchedEffect
        while (isActive) {
            delay(16)

            // 不需要自动滚动时什么都不做：不读布局、不滚列表，更不会空转
            if (autoScrollSpeed == 0f) continue

            val info = syncDragOffset()
            if (info == null) {
                autoScrollSpeed = 0f
                continue
            }
            updateAutoScroll(info)

            val delta = autoScrollSpeed
            if (delta == 0f) continue
            // 列表在这个方向已经滚不动了（到顶/到底）就别再动它：
            // 偏移是推导出来的，不会再出现"滚动没发生、偏移却还在累加"的情况
            if (delta < 0f && !listState.canScrollBackward) {
                autoScrollSpeed = 0f
                continue
            }
            if (delta > 0f && !listState.canScrollForward) {
                autoScrollSpeed = 0f
                continue
            }
            listState.scrollBy(delta)
            // 列表滚了，行自身的布局位置跟着变，重新推导一次偏移，行就还贴在手指下面
            syncDragOffset()
            evaluateCrossing()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(items = songs, key = { _, song -> song.id }) { _, song ->
            val isDragging = sorting && draggingKey == song.id

            SongRow(
                song = song,
                isCurrent = song.id == currentSongId,
                isPlaying = isPlaying && song.id == currentSongId,
                selectionMode = selectionMode,
                selected = song.id in selectedIds,
                sorting = sorting,
                showDetails = showDetails,
                isDragging = isDragging,
                dragOffsetY = if (isDragging) dragOffset else 0f,
                onClick = { if (selectionMode) onToggleSelection(song) else onSongClick(song) },
                onLongClick = { onSongLongClick(song) },
                dragModifier = if (sorting) {
                    Modifier.pointerInput(song.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                // 起点下标和布局位置都从当前布局里取，避免用到过期下标
                                val info = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == song.id }
                                if (info != null) {
                                    draggingKey = song.id
                                    draggingIndex = info.index
                                    // 手指在列表坐标系里的位置 = 这一行当前的布局位置 + 落点偏移
                                    dragPointerY = info.offset + offset.y
                                    dragGrabY = offset.y
                                    dragOffset = 0f
                                    autoScrollSpeed = 0f
                                    pendingIndex = -1
                                }
                            },
                            onDragEnd = {
                                resetDrag()
                                currentOnDragFinished.value()
                            },
                            onDragCancel = {
                                resetDrag()
                                currentOnDragFinished.value()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                // 只累加手指自己的位移（即手指在列表坐标系里的真实位置）；
                                // 列表滚动带来的差值由 syncDragOffset() 通过布局位置抵消
                                dragPointerY += amount.y
                                val info = syncDragOffset()
                                if (info != null) {
                                    updateAutoScroll(info)
                                    evaluateCrossing()
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    sorting: Boolean,
    showDetails: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragModifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val rowColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffsetY }
            // 被拖起来的行给个底色和投影，明确"它现在跟着手指"
            .then(
                if (isDragging) {
                    Modifier.shadow(elevation = 8.dp, clip = false)
                } else {
                    Modifier
                },
            )
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else rowColor,
            )
            // 排序模式下禁用点击：长按要用来拖动，避免误触播放
            .combinedClickable(
                enabled = !sorting,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(dragModifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }

        // 48dp 的行只需要 ~144px，解码小图能省掉约 5/6 的像素量
        Artwork(
            song = song,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(10.dp),
            targetPx = ArtworkLoader.SMALL_PX,
        )
        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.subtitle(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showDetails) {
                songDetailLines(song).forEach { line ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 排序模式下这里什么都不放：不放箭头，也不放拖拽把手，歌曲信息占满可用宽度
        if (!sorting) {
            Spacer(Modifier.width(12.dp))
            if (isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = stringResource(R.string.now_playing),
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = formatDuration(song.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "歌手 · 专辑"，缺字段时自动省略。 */
@Composable
private fun Song.subtitle(): String {
    val unknown = stringResource(R.string.unknown_artist)
    return buildString {
        append(artist ?: unknown)
        album?.takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
    }
}

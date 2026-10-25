package com.yinxia.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yinxia.music.R
import com.yinxia.music.data.Playlist

/**
 * 列表上方的歌单筛选条。
 *
 * 刻意**不用 Material 的 FilterChip**：Chip 自带一个非空的 onClick，内部会装一个
 * clickable，和外层包一层的 combinedClickable 抢手势，长按经常收不到事件。
 * 而"长按歌单 → 改名 / 删除"是这个界面唯一的入口，必须可靠，
 * 所以这里用 Row + 背景 + 描边自己画一个，手势完全交给 combinedClickable。
 */
@Composable
fun PlaylistChips(
    playlists: List<Playlist>,
    activeId: Long?,
    onSelect: (Long?) -> Unit,
    onLongPress: (Playlist) -> Unit,
    onCreateNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            ChipSurface(
                label = stringResource(R.string.playlist_all),
                selected = activeId == null,
                onClick = { onSelect(null) },
            )
        }

        items(items = playlists, key = { it.id }) { playlist ->
            ChipSurface(
                label = playlist.name + " · " +
                    stringResource(R.string.songs_count, playlist.songIds.size),
                selected = activeId == playlist.id,
                onClick = { onSelect(playlist.id) },
                onLongClick = { onLongPress(playlist) },
            )
        }

        item {
            ChipSurface(
                label = stringResource(R.string.playlist_new),
                selected = false,
                onClick = onCreateNew,
            )
        }
    }
}

/**
 * 自己画的 chip。[onLongClick] 为 null 时只响应点击（「全部」和「新建」用这种）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChipSurface(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

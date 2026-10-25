package com.yinxia.music.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yinxia.music.R
import com.yinxia.music.data.SortMode

/**
 * 排序方式选择面板。
 *
 * 名称和时间各有升/降两个方向，做成 5 个互斥选项而不是"选字段 + 切换方向"两步，
 * 少一次点击，也不会出现"选了降序却忘了当前是哪个字段"的困惑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    sortMode: SortMode,
    sortAscending: Boolean,
    onSelect: (SortMode, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.sort_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            Text(
                text = stringResource(R.string.sort_menu_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))

            SortOption(
                labelRes = R.string.sort_title_asc,
                selected = sortMode == SortMode.TITLE && sortAscending,
                onClick = { onSelect(SortMode.TITLE, true) },
            )
            SortOption(
                labelRes = R.string.sort_title_desc,
                selected = sortMode == SortMode.TITLE && !sortAscending,
                onClick = { onSelect(SortMode.TITLE, false) },
            )
            SortOption(
                labelRes = R.string.sort_date_desc,
                selected = sortMode == SortMode.DATE_ADDED && !sortAscending,
                onClick = { onSelect(SortMode.DATE_ADDED, false) },
            )
            SortOption(
                labelRes = R.string.sort_date_asc,
                selected = sortMode == SortMode.DATE_ADDED && sortAscending,
                onClick = { onSelect(SortMode.DATE_ADDED, true) },
            )
            SortOption(
                labelRes = R.string.sort_manual,
                selected = sortMode == SortMode.MANUAL,
                onClick = { onSelect(SortMode.MANUAL, true) },
            )
        }
    }
}

@Composable
private fun SortOption(
    @StringRes labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 14.dp),
        )
    }
}

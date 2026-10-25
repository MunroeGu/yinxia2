package com.yinxia.music.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yinxia.music.R
import com.yinxia.music.data.ArtworkLoader
import com.yinxia.music.data.Playlist
import com.yinxia.music.data.SortMode
import com.yinxia.music.ui.theme.AccentTheme
import com.yinxia.music.ui.theme.accentForTheme
import kotlinx.coroutines.launch

/**
 * 应用主界面：权限门 -> 音乐库 -> 迷你播放条 -> 全屏播放页，
 * 外加排序面板、扫描范围面板、多选删除。
 */
@Composable
fun YinxiaApp(
    viewModel: PlayerViewModel,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDeleteRequest: (List<Uri>) -> Unit,
) {
    val library by viewModel.library.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    // PlaybackUiState 是一个整体，播放中每 500ms 就会换一个新实例（positionMs 在变）。
    // 如果在这里直接读 playback.currentSongId，整个 YinxiaApp 作用域会跟着进度条
    // 一起重组 2 次/秒，再一路带到下面的列表。derivedStateOf 只在"取出来的值真的
    // 变了"时才通知读取者，于是进度 tick 被挡在门外，切歌时照常生效。
    val currentSongId by remember { derivedStateOf { playback.currentSongId } }
    val playingSong = library.allSongs.firstOrNull { it.id == currentSongId }

    // 主题色优先跟随当前播放歌曲的封面；没有封面就退回用户自选的默认色
    var coverSeed by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(playingSong?.id) {
        coverSeed = playingSong?.let { ArtworkLoader.coverColor(context, it) }
    }
    val seed = coverSeed ?: library.defaultAccentArgb?.let { Color(it) }
    val accent = seed?.let { accentForTheme(it, darkTheme) }

    // 换歌时颜色渐变过去，避免硬切
    val animatedAccent by animateColorAsState(
        targetValue = accent ?: MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 600),
        label = "accent",
    )

    AccentTheme(accent = accent?.let { animatedAccent }) {
        YinxiaAppContent(
            viewModel = viewModel,
            permissionGranted = permissionGranted,
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
            onDeleteRequest = onDeleteRequest,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YinxiaAppContent(
    viewModel: PlayerViewModel,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDeleteRequest: (List<Uri>) -> Unit,
) {
    val library by viewModel.library.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()

    // 同上：把"值其实没变"的字段从每 500ms 一次的进度 tick 里摘出来。
    // 以前 currentSongId 在函数体顶部、isPlaying 在 Scaffold 的 content lambda 里
    // 直接读 playback，两者都会因为 positionMs 变化而失效；content lambda 一旦失效，
    // 里面的 LibraryScreen（参数里有 List<Song>，不可跳过）就会整个重组，
    // 正在滚动的可见行跟着重建 —— 播放时的滚动掉帧就是这么来的。
    val currentSongId by remember { derivedStateOf { playback.currentSongId } }
    val isPlaying by remember { derivedStateOf { playback.isPlaying } }

    var searchVisible by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var actionsTarget by remember { mutableStateOf<Playlist?>(null) }

    // 多选/拖动是"模式"，按返回键应该先退出模式而不是退出 App
    BackHandler(enabled = library.selectionMode || library.manualEditing) {
        when {
            library.selectionMode -> viewModel.clearSelection()
            else -> viewModel.finishManualSort()
        }
    }

    // 启动就连播放服务；拿到权限后（或用户刚授权回来）扫描一次
    LaunchedEffect(Unit) { viewModel.connectPlayer() }
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.loadLibrary()
    }

    // 删除要交给 Activity 发起：Android 11+ 必须弹系统确认框，UI 层拿不到那个能力
    LaunchedEffect(pendingDelete) {
        if (pendingDelete.isNotEmpty()) {
            onDeleteRequest(pendingDelete.map { it.uri })
            viewModel.consumeDeleteRequest()
        }
    }

    // 正在播放的歌可能被扫描范围过滤掉了，所以从全量列表里找
    val currentSong = library.allSongs.firstOrNull { it.id == currentSongId }
    // 只有"进入拖动状态"时列表才是可排序的；平时即便排序方式是手动，也不显示任何多余控件
    val manualSorting = library.manualEditing

    Scaffold(
        topBar = {
            Column {
                if (library.selectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(
                                    R.string.selected_count,
                                    library.selectedSongIds.size,
                                ),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.action_cancel),
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = viewModel::selectAllVisible) {
                                Text(stringResource(R.string.action_select_all))
                            }
                            TextButton(
                                onClick = {
                                    val activeId = library.activePlaylistId
                                    if (activeId != null) {
                                        viewModel.removeSongsFromPlaylist(
                                            activeId,
                                            library.selectedSongIds.toList(),
                                        )
                                        viewModel.clearSelection()
                                    } else {
                                        showAddToPlaylist = true
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (library.activePlaylistId != null) {
                                            R.string.playlist_remove_selected
                                        } else {
                                            R.string.playlist_title
                                        },
                                    ),
                                )
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                } else if (library.manualEditing) {
                    // 排序模式：只留一个「完成」，行内不加任何图标，避免遮挡歌曲信息
                    TopAppBar(
                        title = { Text(stringResource(R.string.manual_sort_title)) },
                        actions = {
                            TextButton(onClick = viewModel::finishManualSort) {
                                Text(stringResource(R.string.action_done))
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.library_title),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (permissionGranted && library.songs.isNotEmpty()) {
                                    Text(
                                        text = stringResource(
                                            R.string.songs_count,
                                            library.songs.size,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        actions = {
                            if (permissionGranted && library.allSongs.isNotEmpty()) {
                                IconButton(onClick = { showSortSheet = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_sort),
                                        contentDescription = stringResource(R.string.action_sort),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(onClick = { showFolderSheet = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder),
                                        contentDescription = stringResource(R.string.action_scan_range),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(onClick = { showThemeSheet = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_palette),
                                        contentDescription = stringResource(R.string.action_theme),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        searchVisible = !searchVisible
                                        if (!searchVisible) viewModel.setQuery("")
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (searchVisible) R.drawable.ic_close else R.drawable.ic_search,
                                        ),
                                        contentDescription = stringResource(R.string.search_hint),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.setShowSongDetails(!library.showSongDetails)
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_info),
                                        contentDescription = stringResource(
                                            R.string.action_song_details,
                                        ),
                                        tint = if (library.showSongDetails) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        },
                    )

                    if (permissionGranted && library.manualEditing) {
                        Text(
                            text = stringResource(R.string.manual_sort_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    if (permissionGranted && searchVisible) {
                        OutlinedTextField(
                            value = library.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (currentSong != null) {
                MiniPlayer(
                    song = currentSong,
                    isPlaying = isPlaying,
                    progress = playback.progress,
                    onExpand = { showNowPlaying = true },
                    onTogglePlay = viewModel::togglePlayPause,
                    onNext = viewModel::next,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                !permissionGranted -> PermissionPage(
                    onGrant = onRequestPermission,
                    onOpenSettings = onOpenAppSettings,
                )

                library.loading -> LoadingPage()

                library.allSongs.isEmpty() -> EmptyPage(onRefresh = viewModel::loadLibrary)

                library.activePlaylistId != null && library.songs.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 歌单为空时也要留下歌单条，否则用户没法切回「全部」
                    PlaylistChips(
                        playlists = library.playlists,
                        activeId = library.activePlaylistId,
                        onSelect = viewModel::setActivePlaylist,
                        onLongPress = { actionsTarget = it },
                        onCreateNew = { showCreatePlaylist = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EmptyPage(
                        onRefresh = viewModel::loadLibrary,
                        title = stringResource(R.string.playlist_empty),
                        hint = stringResource(R.string.playlist_empty_hint),
                    )
                }

                library.songs.isEmpty() && library.folderFilterEnabled -> EmptyPage(
                    onRefresh = viewModel::loadLibrary,
                    title = stringResource(R.string.state_no_folder),
                    hint = stringResource(R.string.state_no_folder_hint),
                )

                library.songs.isEmpty() -> EmptyPage(
                    onRefresh = viewModel::loadLibrary,
                    title = stringResource(R.string.state_no_result),
                    hint = null,
                )

                else -> Column(Modifier.fillMaxSize()) {
                    PlaylistChips(
                        playlists = library.playlists,
                        activeId = library.activePlaylistId,
                        onSelect = viewModel::setActivePlaylist,
                        onLongPress = { actionsTarget = it },
                        onCreateNew = { showCreatePlaylist = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LibraryScreen(
                        songs = library.songs,
                        currentSongId = currentSongId,
                        isPlaying = isPlaying,
                        selectionMode = library.selectionMode,
                        selectedIds = library.selectedSongIds,
                        sorting = manualSorting,
                        showDetails = library.showSongDetails,
                        onSongClick = viewModel::onSongSelected,
                        onSongLongClick = { song ->
                            if (library.selectionMode) {
                                viewModel.toggleSelection(song.id)
                            } else {
                                viewModel.startSelection(song.id)
                            }
                        },
                        onToggleSelection = { song -> viewModel.toggleSelection(song.id) },
                        onMoveSong = viewModel::moveSongTo,
                        onDragFinished = viewModel::commitManualOrder,
                        contentPadding = PaddingValues(bottom = 8.dp),
                    )
                }
            }
        }
    }

    if (showSortSheet) {
        SortSheet(
            sortMode = library.sortMode,
            sortAscending = library.sortAscending,
            onSelect = { mode, ascending ->
                if (mode == SortMode.MANUAL) {
                    // 手动排序要先进入拖动状态，而不是仅切换排序字段
                    viewModel.startManualSort()
                } else {
                    viewModel.setSortMode(mode, ascending)
                }
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showFolderSheet) {
        FolderSheet(
            folders = library.folders,
            filterEnabled = library.folderFilterEnabled,
            selectedFolders = library.selectedFolders,
            onToggle = viewModel::setFolderSelected,
            onScanAll = viewModel::scanAllFolders,
            onDismiss = { showFolderSheet = false },
        )
    }

    if (showThemeSheet) {
        ThemeSheet(
            selectedArgb = library.defaultAccentArgb,
            darkTheme = isSystemInDarkTheme(),
            onSelect = { argb ->
                viewModel.setDefaultAccent(argb)
                showThemeSheet = false
            },
            onDismiss = { showThemeSheet = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(
                    stringResource(R.string.delete_message, library.selectedSongIds.size),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.requestDeleteSelection()
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showAddToPlaylist) {
        AddToPlaylistSheet(
            playlists = library.playlists,
            selectedCount = library.selectedSongIds.size,
            onCreate = { name ->
                viewModel.createPlaylist(name, library.selectedSongIds.toList())
                viewModel.clearSelection()
                showAddToPlaylist = false
            },
            onPick = { id ->
                viewModel.addSongsToPlaylist(id, library.selectedSongIds.toList())
                viewModel.clearSelection()
                showAddToPlaylist = false
            },
            onDismiss = { showAddToPlaylist = false },
        )
    }

    if (showCreatePlaylist) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            initialName = "",
            confirmLabel = stringResource(R.string.action_confirm),
            onConfirm = { name ->
                viewModel.createPlaylist(name, emptyList())
                showCreatePlaylist = false
            },
            onDismiss = { showCreatePlaylist = false },
        )
    }

    actionsTarget?.let { target ->
        PlaylistActionsDialog(
            playlistName = target.name,
            onRename = {
                renameTarget = target
                actionsTarget = null
            },
            onDelete = {
                viewModel.deletePlaylist(target.id)
                actionsTarget = null
            },
            onDismiss = { actionsTarget = null },
        )
    }

    renameTarget?.let { target ->
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            initialName = target.name,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { name ->
                viewModel.renamePlaylist(target.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (showNowPlaying && currentSong != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        // 先播收起动画，动画结束再真正移出组合，避免"啪"地一下消失
        val closeSheet: () -> Unit = {
            scope.launch { sheetState.hide() }.invokeOnCompletion { showNowPlaying = false }
        }

        ModalBottomSheet(
            onDismissRequest = { showNowPlaying = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            NowPlayingContent(
                song = currentSong,
                isPlaying = playback.isPlaying,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                shuffleEnabled = playback.shuffleEnabled,
                repeatMode = playback.repeatMode,
                onCollapse = closeSheet,
                onTogglePlay = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onSeek = viewModel::seekTo,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeat,
            )
        }
    }
}

@Composable
private fun PermissionPage(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.permission_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onGrant) {
            Text(stringResource(R.string.permission_grant))
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.permission_settings))
        }
    }
}

@Composable
private fun LoadingPage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyPage(
    onRefresh: () -> Unit,
    title: String = stringResource(R.string.state_empty),
    hint: String? = stringResource(R.string.state_empty_hint),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

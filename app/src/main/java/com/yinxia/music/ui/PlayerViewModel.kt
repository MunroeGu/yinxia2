package com.yinxia.music.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.yinxia.music.R
import com.yinxia.music.data.FolderEntry
import com.yinxia.music.data.LibraryCache
import com.yinxia.music.data.LibraryPreferences
import com.yinxia.music.data.MediaStoreFingerprint
import com.yinxia.music.data.MusicRepository
import com.yinxia.music.data.Playlist
import com.yinxia.music.data.Song
import com.yinxia.music.data.SortMode
import com.yinxia.music.player.PlaybackConnection
import com.yinxia.music.player.toMediaItem
import com.yinxia.music.widget.PlayerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NO_SONG_ID = -1L

data class LibraryUiState(
    /** 过滤 + 排序后的结果，界面直接显示它，同时它就是播放队列 */
    val songs: List<Song> = emptyList(),
    /** 扫描到的全部歌曲，「全选」和文件夹汇总要用 */
    val allSongs: List<Song> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val sortMode: SortMode = SortMode.TITLE,
    val sortAscending: Boolean = true,
    /**
     * 是否处在「手动排序」的拖动状态。
     * 退出后手动顺序依然生效，只是列表恢复成普通形态，不再显示任何拖拽相关的东西。
     */
    val manualEditing: Boolean = false,
    /** 用户自定义的默认主题色（ARGB）；null = 用主题自带颜色 */
    val defaultAccentArgb: Int? = null,
    /** 是否在列表里显示码率/采样率这类详细信息 */
    val showSongDetails: Boolean = false,
    /** 自建歌单 */
    val playlists: List<Playlist> = emptyList(),
    /** 当前只看哪个歌单；null = 看全部 */
    val activePlaylistId: Long? = null,
    /** false = 扫描全部文件夹；此时界面上的勾选一律显示为全选 */
    val folderFilterEnabled: Boolean = false,
    val selectedFolders: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val selectedSongIds: Set<Long> = emptySet(),
)

data class PlaybackUiState(
    val currentSongId: Long = NO_SONG_ID,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasQueue: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * 界面状态持有者。
 *
 * 播放本身活在 PlaybackService 里，这里只保存"界面要显示什么"，
 * 所以旋转屏幕、退到后台再回来都不会中断播放。
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val preferences = LibraryPreferences(application)
    private val connection = PlaybackConnection(application)

    // 自带作用域，不依赖 lifecycle-viewmodel-ktx，onCleared 里统一取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 每个作用域（「全部」/ 每个歌单）各自一份手动顺序，常驻内存免得每次重排都读一遍
     * SharedPreferences。作用域字符串约定见 [manualOrderScope]。
     */
    private val manualOrders = HashMap<String, MutableList<Long>>()

    /**
     * 当前视图手动排序的作用域：看歌单时是 `playlist:<id>`，看全部时是 `library`。
     * 手动顺序按作用域分开存，拖动某个歌单不会影响其他歌单和「全部」。
     */
    private val manualOrderScope: String
        get() = _library.value.activePlaylistId?.let { "playlist:$it" } ?: "library"

    /** 冷启动的播放恢复只做一次，成功或确认"无队列可恢复"后置位 */
    private var playbackRestored = false

    /**
     * 上次把播放进度写进 SharedPreferences 的时刻（elapsedRealtime），用于节流。
     * 进度每 500ms 刷新一次，不能每次都写盘。
     */
    private var lastPositionPersistAt = 0L

    /** 上次落盘的位置；位置没变就不用再写一遍 */
    private var lastPersistedPositionMs = -1L

    /** 上次落盘时对应的歌曲 id；切歌时要强制写一次 */
    private var lastPositionSongId = NO_SONG_ID

    /** 等待系统删除确认的歌曲，删除结果回来后要用 */
    private var pendingDeleteSongs: List<Song> = emptyList()

    /** 冷启动缓存只读一次：init 和 loadLibrary 都可能触发，避免重复解析同一份 JSON */
    private var cacheRestored = false

    /** 冷启动那次"用缓存填列表"的任务，loadLibrary 要先等它结束才知道列表是不是已经画出来了 */
    private var cachePublishJob: Job? = null

    /**
     * 冷启动的缓存里那份媒体库指纹。只对"进程起来后的第一次扫描"有意义，读过就清掉。
     * 和当前指纹一致时，缓存就是权威数据，可以直接跳过整段扫描（FIX 5）。
     */
    private var cachedFingerprint: MediaStoreFingerprint? = null

    /**
     * 冷启动时是否真的用缓存把列表画到了屏幕上。只对第一次扫描有意义，读过就清掉 ——
     * 删除歌曲后重新扫描时列表本来就在屏幕上，不该再为"给滚动让路"多等 1.5 秒。
     */
    private var publishedFromCacheAtStart = false

    private val _library = MutableStateFlow(
        LibraryUiState(
            sortMode = preferences.sortMode,
            sortAscending = preferences.sortAscending,
            folderFilterEnabled = preferences.folderFilterEnabled,
            selectedFolders = preferences.selectedFolders,
            defaultAccentArgb = preferences.defaultAccentArgb,
            showSongDetails = preferences.showSongDetails,
            playlists = preferences.playlists,
        ),
    )
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    /** 需要交给系统弹确认框的删除请求 */
    private val _pendingDelete = MutableStateFlow<List<Song>>(emptyList())
    val pendingDelete: StateFlow<List<Song>> = _pendingDelete.asStateFlow()

    init {
        // 进程刚起来时先把上次扫到的歌单摆上去，MediaStore 扫描随后在后台刷新。
        // 第一次安装（还没有缓存文件）时这里什么也不做，加载/空列表语义和以前完全一致。
        cachePublishJob = scope.launch { publishedFromCacheAtStart = publishCachedLibrary() }
    }

    private var progressJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlayback()
            if (isPlaying) startProgressUpdates() else stopProgressUpdates()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlayback()
            persistQueueIndex()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = syncPlayback()

        override fun onRepeatModeChanged(repeatMode: Int) = syncPlayback()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncPlayback()
    }

    // ---------------------------------------------------------------- 播放

    fun connectPlayer() {
        connection.connect { controller ->
            controller.addListener(playerListener)
            syncPlayback()
            if (controller.isPlaying) startProgressUpdates()
            // 恢复上次播放：控制器就绪这一刻缓存可能已经画好了，也可能还没有，
            // 两条路都会试着恢复，playbackRestored 保证真正的恢复只发生一次。
            scope.launch { restorePlaybackIfIdle() }
        }
    }

    /** 点列表里的一首歌：当前显示的整个列表作为播放队列 */
    fun playSong(song: Song) {
        val controller = connection.controller ?: return
        val queue = _library.value.songs
        val startIndex = queue.indexOfFirst { it.id == song.id }
        if (startIndex < 0) return

        controller.setMediaItems(queue.map { it.toMediaItem() }, startIndex, 0L)
        // 把队列存下来：系统杀掉进程后，桌面插件要靠它把队列恢复回来
        preferences.queueSongIds = queue.map { it.id }
        preferences.queueIndex = startIndex
        controller.prepare()
        controller.play()
    }

    /**
     * 列表里点一首歌：
     *  - 点的就是当前这首 → 暂停 / 继续（不会从头开始，位置保留）
     *  - 点的是别的歌 → 从这首重新开始播
     */
    fun onSongSelected(song: Song) {
        if (song.id == _playback.value.currentSongId) {
            togglePlayPause()
        } else {
            playSong(song)
        }
    }

    fun togglePlayPause() {
        val controller = connection.controller ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun next() {
        connection.controller?.seekToNextMediaItem()
    }

    fun previous() {
        connection.controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        connection.controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val controller = connection.controller ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    /** 关 -> 列表循环 -> 单曲循环 -> 关 */
    fun cycleRepeat() {
        val controller = connection.controller ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * 播放进度靠轮询而不是靠插值：切歌、seek、暂停这些事件都会改变位置，
     * 每 500ms 同步一次的代价很小，但状态永远和播放器一致。
     */
    private fun startProgressUpdates() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                syncPlayback()
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
        syncPlayback()
    }

    private fun syncPlayback() {
        val controller = connection.controller ?: return

        val currentSongId = controller.currentMediaItem?.mediaId?.toLongOrNull() ?: NO_SONG_ID
        val song = _library.value.allSongs.firstOrNull { it.id == currentSongId }
        val playerDuration = controller.duration
        // 播放器还没解析出时长时，用扫描时拿到的时长兜底，进度条不会突然跳成 0
        val fallbackDuration = song?.durationMs ?: 0L
        val positionMs = controller.currentPosition.coerceAtLeast(0L)
        val isPlaying = controller.isPlaying

        _playback.update {
            it.copy(
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = if (playerDuration > 0L) playerDuration else fallbackDuration,
                shuffleEnabled = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode,
                hasQueue = controller.mediaItemCount > 0,
            )
        }

        // 冷启动要靠它恢复进度；内部按 5 秒节流，不是每 tick 都写盘
        persistPositionIfDue(
            songId = currentSongId,
            positionMs = positionMs,
            isPlaying = isPlaying,
            playbackState = controller.playbackState,
        )

        // 桌面插件：内部会按"歌曲 + 播放状态"去重，
        // 所以每秒两次的进度刷新不会造成重复推送
        PlayerWidget.push(getApplication(), song, isPlaying)
    }

    /**
     * 把"听到第几毫秒"写进偏好设置，供冷启动恢复。
     *
     * 这个函数被 [syncPlayback] 调用，而 [syncPlayback] 在播放中每 500ms 跑一次，
     * 每次都写盘太浪费（即使 SharedPreferences.apply 是异步的，也仍要序列化整份文件），
     * 所以：
     *  - **播放中**最多每 [POSITION_PERSIST_INTERVAL_MS]（5 秒）写一次；
     *  - **切歌 / 暂停 / 停止 / 播完**不等节流窗口，立刻把当前进度钉住 ——
     *    这几个时刻之后位置基本不再变，正是最需要准确的时候；
     *  - 位置没变就不写：暂停后的事件风暴（[Player.Listener] 会连着回调几次）
     *    不会反复落同一份数据；
     *  - 队列为空（还没开始播 / 刚被清空）时直接不写：连接刚建立时
     *    `controller.currentPosition` 是 0，写下去会把上次的进度覆盖掉。
     */
    private fun persistPositionIfDue(
        songId: Long,
        positionMs: Long,
        isPlaying: Boolean,
        playbackState: Int,
    ) {
        if (songId == NO_SONG_ID) return

        val now = SystemClock.elapsedRealtime()
        val itemChanged = songId != lastPositionSongId
        // 暂停 / 停止 / 播完：进度不会再自己走，这一下必须写准
        val settled = !isPlaying ||
            playbackState == Player.STATE_ENDED ||
            playbackState == Player.STATE_IDLE
        val due = itemChanged || settled ||
            now - lastPositionPersistAt >= POSITION_PERSIST_INTERVAL_MS
        if (!due) return
        if (!itemChanged && positionMs == lastPersistedPositionMs) return

        preferences.queuePositionMs = positionMs
        lastPersistedPositionMs = positionMs
        lastPositionSongId = songId
        lastPositionPersistAt = now
    }

    /**
     * 冷启动把上次的播放队列、第几首、听到第几毫秒放回播放器，底部的迷你播放条
     * 才会重新出现。
     *
     * 只跑一次，且只在两个条件都成立时跑：(a) 音乐库已经有内容（缓存或扫描），
     * (b) 播放器的队列还是空的。任何一个条件没满足就**不置位地返回**，等下一次
     * 调用再来 —— 恢复能力不能因为"调用得早"就丢掉。
     *
     * 刻意不调用 `play()`：恢复的是"上次听到哪"，不是"自动出声"。
     */
    private suspend fun restorePlaybackIfIdle() {
        if (playbackRestored) return

        // 库还没出来（缓存/扫描都还没到）时先不认输，等 allSongs 有了再恢复
        if (_library.value.allSongs.isEmpty()) return

        val controller = connection.controller ?: return

        // 已经有队列：热启动（服务还活着），或者用户抢在恢复前点了歌 —— 都不能覆盖
        if (controller.mediaItemCount > 0) {
            playbackRestored = true
            return
        }

        val ids = preferences.queueSongIds
        if (ids.isEmpty()) {
            playbackRestored = true
            return
        }

        val byId = _library.value.allSongs.associateBy { it.id }
        // 按存下来的顺序重建；已经不在库里的歌直接跳过
        val songs = ids.mapNotNull { byId[it] }
        if (songs.isEmpty()) {
            playbackRestored = true
            return
        }

        val index = preferences.queueIndex.coerceIn(0, songs.lastIndex)
        val position = preferences.queuePositionMs.coerceAtLeast(0L)
        controller.setMediaItems(songs.map { it.toMediaItem() }, index, position)
        controller.prepare()
        // 刚恢复的位置就是磁盘上那一份：记进节流记账里，恢复后紧接着的同步
        // 不会把同一个位置再写一遍（也就不会因为播放器还没解析出位置而写成 0）。
        lastPositionSongId = songs[index].id
        lastPersistedPositionMs = position
        lastPositionPersistAt = SystemClock.elapsedRealtime()
        playbackRestored = true
    }

    // ------------------------------------------------------------ 音乐库

    fun loadLibrary() {
        scope.launch {
            // 冷启动时 init 已经在读缓存了。先等它结束：只有确认列表已经（或马上就会）
            // 由缓存画到屏幕上，才能判断这次扫描该不该跳过/让路。不等它的话，扫描会先于
            // 缓存发布开始，"给滚动让路"根本不会生效。
            cachePublishJob?.join()

            // 缓存优先：先把上次的结果画出来，再让扫描在后台覆盖。
            // 注意顺序 —— 先发布缓存再决定 loading，否则会把已经画好的列表盖回加载页。
            val fromCache = publishedFromCacheAtStart || publishCachedLibrary()
            publishedFromCacheAtStart = false
            val fingerprintFromCache = cachedFingerprint
            cachedFingerprint = null

            // 已经有内容（缓存或上一次的扫描结果）时不再显示加载页，列表不会闪成空白。
            // 第一次安装没有缓存也没有旧结果，这里仍然是 true，语义和以前一致。
            _library.update { it.copy(loading = it.allSongs.isEmpty()) }

            // ---------------------------------------------------------------- FIX 5
            // 缓存里记着落盘那一刻的媒体库指纹。指纹一致（且缓存确实有歌）时，
            // 我们的列表就是权威数据，整段扫描可以直接不做 —— 这正是"刚进 App
            // 立刻拖列表卡"的根因：扫描不再发生，也就没有资源去和滚动抢。
            //
            // 只在指纹"已知"时才敢跳过：API < 30 没有 generation，readFingerprint()
            // 返回 UNKNOWN（isKnown = false），老设备的行为和以前完全一样（每次都扫）。
            val currentFingerprint = repository.readFingerprint()
            if (fingerprintFromCache != null &&
                fingerprintFromCache.isKnown &&
                fingerprintFromCache == currentFingerprint &&
                _library.value.allSongs.isNotEmpty()
            ) {
                _library.update { it.copy(loading = false) }
                // 列表已经就位（缓存），但控制器可能是刚刚才连上的；补一次恢复尝试。
                // 已经恢复过 / 已经有队列时它自己会立刻返回。
                restorePlaybackIfIdle()
                return@launch
            }

            // 缓存已经把列表画出来了，刷新不急；等一小会儿再扫，
            // 免得用户刚进来第一次滚动正好和扫描抢资源（用户反馈就是这个时机最卡）。
            // 第一次安装没有缓存时必须立刻扫，否则用户什么都看不到。
            if (fromCache) delay(FIRST_SCAN_DELAY_MS)

            // 扫描前先记一份指纹：媒体库如果在这中间被改动，"之前"和"之后"就对不上，
            // 我们宁可不写缓存（见下面），也好过把"新的指纹 + 旧的歌单"存下去。
            val fingerprintBeforeScan = repository.readFingerprint()
            val songs = repository.loadSongs()
            syncManualOrder(songs)
            dropMissingSongsFromPlaylists(songs)

            // 缓存优先让列表在扫描结束之前就画好了，用户很可能正拖着它滚动。
            // 这时如果扫描结果和屏幕上那份完全一样（按 id 顺序逐一比较），就绝不能
            // 再碰 allSongs / folders / songs：那会换掉整份数据、让 LazyColumn 在
            // 滚动途中重建每一个可见行 —— 用户看到的就是"列表已经出来了，一拖还是卡"。
            // 内容没变时只把 loading 收掉，其余一概不动（顺带也省掉了主线程上的
            // buildFolders 与 recompute 排序）。别把这层判断"简化"回无条件更新。
            //
            // 顺带比较 title：只比 id 的话，在外面改了标签（id 不变、标题变了）就永远
            // 不会刷新那一行。title 是 String，比较不分配内存，代价可以忽略。
            // 其余字段（专辑、时长…）即使变了也不影响这一行是否需要重画，
            // 而且缓存每次扫描都会重写，下次启动读到的就是新数据。
            val current = _library.value.allSongs
            val unchanged = songs.size == current.size &&
                songs.indices.all {
                    songs[it].id == current[it].id && songs[it].title == current[it].title
                }

            if (unchanged) {
                _library.update { it.copy(loading = false) }
            } else {
                _library.update {
                    it.copy(
                        allSongs = songs,
                        folders = MusicRepository.buildFolders(songs),
                        loading = false,
                    )
                }
                recompute()
            }

            // allSongs 已经（或一直）有内容：补一次播放恢复尝试。
            // 缓存那条路可能跑在控制器就绪之前，这里是它的兜底。
            restorePlaybackIfIdle()

            // 扫描成功才落盘。loadSongs() 在没权限 / MediaStore 异常时也返回空列表，
            // 那种"空"不是真的空库，写进去会把好不容易攒下的缓存清掉。
            //
            // 指纹一起存，但只在"扫描前后指纹没变"时才存。否则可能是扫描期间媒体库
            // 又被改了 —— 那样写下去就成了"新指纹 + 旧歌单"，下次启动会误判成没变而
            // 跳过扫描，刚加进来的歌就永远看不到了。宁可不更新缓存（下次启动照常扫描）。
            if (songs.isNotEmpty()) {
                val fingerprintAfterScan = repository.readFingerprint()
                if (fingerprintAfterScan == fingerprintBeforeScan) {
                    LibraryCache.save(
                        getApplication<Application>(),
                        songs,
                        fingerprintAfterScan,
                    )
                }
            }
        }
    }

    /**
     * 冷启动时把上次扫描的结果立刻发布出来，界面不用干等 MediaStore。
     *
     * - 用 [cacheRestored] 保证只读一次：init 和 [loadLibrary] 都会调，不会重复解析；
     * - 已经有歌曲时直接跳过，避免拿旧数据盖掉刚扫出来的新结果。
     *
     * @return 是否真的用缓存填上了列表
     */
    private suspend fun publishCachedLibrary(): Boolean {
        if (cacheRestored) return false
        cacheRestored = true
        if (_library.value.allSongs.isNotEmpty()) return false

        val snapshot = LibraryCache.load(getApplication<Application>()) ?: return false
        val cached = snapshot.songs
        // 解析是挂起的，期间可能已经有扫描结果回来了，发布前再确认一次
        if (_library.value.allSongs.isNotEmpty()) return false

        syncManualOrder(cached)
        // 刻意不在这里 dropMissingSongsFromPlaylists：缓存可能比真实库旧，
        // 拿旧数据去删歌单条目是会落盘的破坏性操作，等下面真正扫完再清理。
        _library.update {
            it.copy(
                allSongs = cached,
                // 文件夹列表本来也只是从歌曲汇总出来的，不需要重新扫描
                folders = MusicRepository.buildFolders(cached),
                loading = false,
            )
        }
        recompute()
        // 指纹交给 loadLibrary：它据此决定这次要不要真的扫（FIX 5）
        cachedFingerprint = snapshot.fingerprint
        // 缓存已经把 allSongs 填上了：控制器若已就绪，这里就把上次的播放恢复出来。
        // 控制器还没连上时它会不置位地返回，connectPlayer 就绪后再补一次。
        restorePlaybackIfIdle()
        return true
    }

    /** 取某个作用域的手动顺序（第一次用到才从偏好里读），返回常驻内存的可变表 */
    private fun manualOrderFor(scope: String): MutableList<Long> =
        manualOrders.getOrPut(scope) { preferences.manualOrderFor(scope).toMutableList() }

    /** 当前视图（「全部」或当前歌单）的手动顺序 */
    private fun currentManualOrder(): MutableList<Long> = manualOrderFor(manualOrderScope)

    /**
     * 让**当前作用域**的手动顺序覆盖到刚扫到的歌曲：新歌按名称追加在末尾，
     * 已删除 / 已移出歌单的 id 清掉。这样用户没手动排过的时候，手动模式也是一个
     * 合理的初始顺序。
     *
     * [all] 是整份媒体库；真正参与同步的只有当前视图看得见的那些歌：
     * 「全部」作用域用整个媒体库，歌单作用域只维护歌单里那些歌 —— 否则每个歌单
     * 都会存下全库的 id，既浪费空间，顺序也会被无关的歌带着走。
     */
    private fun syncManualOrder(all: List<Song>) {
        val state = _library.value
        val scope = manualOrderScope
        val playlistIds = state.activePlaylistId
            ?.let { active -> state.playlists.firstOrNull { it.id == active }?.songIds }
            ?.toHashSet()
        val scoped = if (playlistIds == null) all else all.filter { it.id in playlistIds }
        // 空歌单 / 还没扫描出结果时什么都不做，别把已有顺序清空
        if (scoped.isEmpty()) return

        val order = manualOrderFor(scope)
        val validIds = scoped.mapTo(HashSet()) { it.id }
        val removed = order.removeAll { it !in validIds }

        val known = order.toHashSet()
        val added = scoped.asSequence()
            .filter { it.id !in known }
            // 同理不用 sortedBy { it.title.lowercase() }：每次比较一个字符串
            .sortedWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            .map { it.id }
            .toList()
        if (added.isNotEmpty()) order.addAll(added)

        if (removed || added.isNotEmpty()) preferences.setManualOrderFor(scope, order)
    }

    /**
     * 把"当前队列 + 播到第几首"写进偏好设置。
     *
     * 每次切歌写一次，代价是可以接受的（一首歌才一次），换来的好处是
     * 进程被系统杀掉之后还能把队列恢复出来 —— 否则桌面插件的按钮会对一个空队列下命令。
     */
    private fun persistQueueIndex() {
        val controller = connection.controller ?: return
        if (controller.mediaItemCount == 0) return

        val ids = (0 until controller.mediaItemCount).mapNotNull { index ->
            controller.getMediaItemAt(index).mediaId.toLongOrNull()
        }
        if (ids.isEmpty()) return

        preferences.queueSongIds = ids
        preferences.queueIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
    }

    /**
     * 歌曲被删掉后，把歌单里失效的 id 清掉，避免歌单里挂着永远播不了的条目。
     */
    private fun dropMissingSongsFromPlaylists(all: List<Song>) {
        val validIds = all.mapTo(HashSet()) { it.id }
        var changed = false
        val cleaned = _library.value.playlists.map { playlist ->
            val kept = playlist.songIds.filter { it in validIds }
            if (kept.size != playlist.songIds.size) {
                changed = true
                playlist.copy(songIds = kept)
            } else {
                playlist
            }
        }
        if (!changed) return
        preferences.playlists = cleaned
        _library.update { it.copy(playlists = cleaned) }
    }

    /** 按 扫描范围 -> 搜索词 -> 排序 重新算出展示列表 */
    private fun recompute() {
        val state = _library.value
        val query = state.query.trim()

        // 只看某个歌单时，按歌单里的 id 过滤；歌单里已经不存在的歌（被删了/不在扫描范围）自动忽略
        val playlistSongIds = state.activePlaylistId
            ?.let { active -> state.playlists.firstOrNull { it.id == active }?.songIds }
            ?.toHashSet()

        val filtered = state.allSongs.filter { song ->
            val folderOk = !state.folderFilterEnabled || song.folderKey in state.selectedFolders
            val playlistOk = playlistSongIds == null || song.id in playlistSongIds
            val queryOk = query.isEmpty() ||
                song.title.contains(query, ignoreCase = true) ||
                song.artist?.contains(query, ignoreCase = true) == true ||
                song.album?.contains(query, ignoreCase = true) == true
            folderOk && playlistOk && queryOk
        }

        // 三种排序都用 sortedWith + compareTo，不用 sortedBy/compareBy：
        // 后者会把选择器的返回值装箱，标题那一项还会为**每一次比较**新建一个
        // lowercase() 字符串。排序是 N log N，几千首歌就是几万次分配，全部堆在
        // 主线程上（scope 是 Dispatchers.Main.immediate）扫描刚结束的那一瞬间，
        // 正好砸在用户开始拖列表的时候。
        // String.compareTo(other, ignoreCase = true) 与 Long.compareTo 都不分配。
        val ordered = when (state.sortMode) {
            SortMode.TITLE -> filtered.sortedWith { a, b ->
                a.title.compareTo(b.title, ignoreCase = true)
            }

            SortMode.DATE_ADDED -> filtered.sortedWith { a, b ->
                a.dateAddedMs.compareTo(b.dateAddedMs)
            }

            SortMode.MANUAL -> {
                // 手动顺序是每个视图各自一份的：全部 / 每个歌单互不影响
                val orderIndex = currentManualOrder()
                    .withIndex()
                    .associate { (index, id) -> id to index }
                filtered.sortedWith { a, b ->
                    val byOrder = (orderIndex[a.id] ?: Int.MAX_VALUE)
                        .compareTo(orderIndex[b.id] ?: Int.MAX_VALUE)
                    if (byOrder != 0) {
                        byOrder
                    } else {
                        a.title.compareTo(b.title, ignoreCase = true)
                    }
                }
            }
        }

        // 手动顺序自带方向，不再额外反转
        val displayed = if (state.sortMode == SortMode.MANUAL || state.sortAscending) {
            ordered
        } else {
            ordered.reversed()
        }

        _library.update { it.copy(songs = displayed) }
    }

    fun setQuery(query: String) {
        _library.update { it.copy(query = query) }
        recompute()
    }

    /** 设置默认主题色（只在歌曲没有封面时生效） */
    fun setDefaultAccent(argb: Int?) {
        preferences.defaultAccentArgb = argb
        _library.update { it.copy(defaultAccentArgb = argb) }
    }

    /** 显示/隐藏歌曲详细信息（码率、采样率等） */
    fun setShowSongDetails(show: Boolean) {
        preferences.showSongDetails = show
        _library.update { it.copy(showSongDetails = show) }
    }

    /** 只看某个歌单；传 null 表示看全部 */
    fun setActivePlaylist(playlistId: Long?) {
        _library.update { it.copy(activePlaylistId = playlistId) }
        // 切到另一个作用域：它的顺序可能从来没排过（新歌单 / 第一次进），
        // 这里先补齐并落盘，手动模式下才有可用的顺序；已经排过的歌单顺序原样保留。
        syncManualOrder(_library.value.allSongs)
        recompute()
    }

    /**
     * 用当前选中的歌曲新建歌单（[songIds] 为空时只建一个空歌单）。
     * 返回新歌单的 id。名称会去掉首尾空格，空名回退成"新建歌单"。
     */
    fun createPlaylist(name: String, songIds: List<Long>): Long {
        val cleanName = name.trim().ifEmpty { "新建歌单" }
        val playlists = _library.value.playlists.toMutableList()
        // 用当前最大 id + 1，避免和已存在的撞号
        val newId = (playlists.maxOfOrNull { it.id } ?: 0L) + 1L
        playlists += Playlist(id = newId, name = cleanName, songIds = songIds.distinct())
        applyPlaylists(playlists)
        return newId
    }

    /** 把歌曲加入歌单（已经在里面的不重复加） */
    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        val playlists = _library.value.playlists.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songIds = (playlist.songIds + songIds).distinct())
            } else {
                playlist
            }
        }
        applyPlaylists(playlists)
    }

    /** 把歌曲从歌单里移除 */
    fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>) {
        val removing = songIds.toHashSet()
        val playlists = _library.value.playlists.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songIds = playlist.songIds.filterNot { it in removing })
            } else {
                playlist
            }
        }
        applyPlaylists(playlists)
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        val cleanName = name.trim().ifEmpty { return }
        val playlists = _library.value.playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(name = cleanName) else playlist
        }
        applyPlaylists(playlists)
    }

    fun deletePlaylist(playlistId: Long) {
        val playlists = _library.value.playlists.filterNot { it.id == playlistId }
        // 这个歌单自己的手动顺序一起丢掉：作用域是 "playlist:<id>"，删掉之后再也没有
        // 任何视图会读它，留着只是 JSON 里的孤儿条目（也不会串到别的视图去）
        forgetManualOrderForPlaylist(playlistId)
        // 正在看这个歌单时，删掉后自动退回"全部"
        if (_library.value.activePlaylistId == playlistId) {
            _library.update { it.copy(activePlaylistId = null) }
        }
        // 统一走 applyPlaylists：落盘 + 更新状态 + 按新作用域补齐顺序 + 重算列表
        applyPlaylists(playlists)
    }

    /** 删掉某个歌单的手动顺序（内存缓存 + 磁盘），不碰其他作用域 */
    private fun forgetManualOrderForPlaylist(playlistId: Long) {
        val scope = "playlist:$playlistId"
        manualOrders.remove(scope)
        preferences.setManualOrderFor(scope, emptyList())
    }

    /** 歌单改动统一走这里：落盘 + 更新状态 + 补齐当前作用域的手动顺序 + 重算列表 */
    private fun applyPlaylists(playlists: List<Playlist>) {
        preferences.playlists = playlists
        _library.update { it.copy(playlists = playlists) }
        // 歌单内容变了：当前视图（很可能就是这个歌单）的手动顺序要跟着补新歌 / 删旧歌
        syncManualOrder(_library.value.allSongs)
        recompute()
    }

    fun setSortMode(mode: SortMode, ascending: Boolean) {
        preferences.sortMode = mode
        preferences.sortAscending = ascending
        _library.update {
            it.copy(sortMode = mode, sortAscending = ascending, manualEditing = false)
        }
        recompute()
    }

    /** 进入手动排序：列表变成可长按拖动；同时退出多选，避免两种模式打架 */
    fun startManualSort() {
        preferences.sortMode = SortMode.MANUAL
        _library.update {
            it.copy(
                sortMode = SortMode.MANUAL,
                manualEditing = true,
                selectionMode = false,
                selectedSongIds = emptySet(),
            )
        }
        recompute()
    }

    /** 结束手动排序：顺序保留，退出拖动状态 */
    fun finishManualSort() {
        commitManualOrder()
        _library.update { it.copy(manualEditing = false) }
    }

    /**
     * 拖动排序：把 [draggedId] 插到 [targetId] 当前所在的位置。
     *
     * 拖动时每跨过一行就会调用一次，所以这里刻意不做整表重算、也不写磁盘，
     * 只改内存里的展示列表和手动顺序，松手时再由 [commitManualOrder] 落盘。
     */
    fun moveSongTo(draggedId: Long, targetId: Long) {
        val displayed = _library.value.songs.toMutableList()
        val from = displayed.indexOfFirst { it.id == draggedId }
        val to = displayed.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return

        displayed.add(to, displayed.removeAt(from))

        // 用"当前显示顺序 + 没显示出来的歌曲原顺序"重建**当前作用域**的手动顺序，
        // 这样即使开着搜索或只扫描了部分文件夹，也不会把没显示的歌曲搞乱；
        // 也只动这个作用域 —— 其他歌单和「全部」的顺序一点不变。
        val order = currentManualOrder()
        val displayedIds = displayed.mapTo(HashSet()) { it.id }
        val next = displayed.map { it.id } + order.filter { it !in displayedIds }
        order.clear()
        order.addAll(next)

        _library.update { it.copy(sortMode = SortMode.MANUAL, songs = displayed) }
    }

    /** 把当前作用域的手动顺序写进 SharedPreferences */
    fun commitManualOrder() {
        preferences.setManualOrderFor(manualOrderScope, currentManualOrder())
        preferences.sortMode = SortMode.MANUAL
    }

    // -------------------------------------------------------- 扫描范围

    fun setFolderSelected(folderKey: String, selected: Boolean) {
        val allKeys = _library.value.folders.mapTo(HashSet()) { it.key }
        if (allKeys.isEmpty()) return

        val current = if (_library.value.folderFilterEnabled) {
            _library.value.selectedFolders.toMutableSet()
        } else {
            allKeys.toMutableSet()
        }
        if (selected) current += folderKey else current -= folderKey

        // 全部勾选等价于不限制，直接关掉过滤，避免存一份没意义的全集
        val scanningAll = current.size == allKeys.size && current.containsAll(allKeys)

        preferences.folderFilterEnabled = !scanningAll
        preferences.selectedFolders = current
        _library.update {
            it.copy(folderFilterEnabled = !scanningAll, selectedFolders = current)
        }
        recompute()
    }

    fun scanAllFolders() {
        preferences.folderFilterEnabled = false
        preferences.selectedFolders = emptySet()
        _library.update { it.copy(folderFilterEnabled = false, selectedFolders = emptySet()) }
        recompute()
    }

    // ------------------------------------------------------------ 多选

    fun startSelection(songId: Long) {
        _library.update { it.copy(selectionMode = true, selectedSongIds = setOf(songId)) }
    }

    fun toggleSelection(songId: Long) {
        val current = _library.value.selectedSongIds
        val next = if (songId in current) current - songId else current + songId
        _library.update { it.copy(selectionMode = next.isNotEmpty(), selectedSongIds = next) }
    }

    fun selectAllVisible() {
        _library.update { it.copy(selectedSongIds = it.songs.mapTo(HashSet()) { song -> song.id }) }
    }

    fun clearSelection() {
        _library.update { it.copy(selectionMode = false, selectedSongIds = emptySet()) }
    }

    // ------------------------------------------------------------ 删除

    fun requestDeleteSelection() {
        val selected = _library.value.allSongs.filter { it.id in _library.value.selectedSongIds }
        if (selected.isEmpty()) return
        pendingDeleteSongs = selected
        _pendingDelete.value = selected
    }

    /** 界面已经拿去发起删除请求了，清掉状态避免重复触发；结果由 onDeleteResult 收尾 */
    fun consumeDeleteRequest() {
        _pendingDelete.value = emptyList()
    }

    /** 系统确认框的结果（或低版本直接删除的结果） */
    fun onDeleteResult(success: Boolean) {
        val deleted = pendingDeleteSongs
        pendingDeleteSongs = emptyList()
        _pendingDelete.value = emptyList()
        if (!success || deleted.isEmpty()) return

        removeDeletedFromQueue(deleted.mapTo(HashSet()) { it.id })
        clearSelection()
        // 删除改变了媒体库。先把缓存里的指纹抹掉（用 UNKNOWN 覆盖写一次），再去重新扫描。
        // 顺序不能反，而且必须等写盘完成：
        //  - 若这次扫描失败（没权限 / MediaStore 异常），或者期间进程被杀，
        //    磁盘上留下的是"旧歌单 + 未知指纹"，下次启动照样会重新扫描；
        //  - 反过来先扫描再抹指纹，一旦扫描失败，磁盘上仍是"旧歌单 + 能对上的旧指纹"，
        //    下次启动就会跳过扫描，被删掉的歌又出现在列表里（点了也没反应）。
        scope.launch {
            LibraryCache.save(
                getApplication<Application>(),
                _library.value.allSongs,
                MediaStoreFingerprint.UNKNOWN,
            )
            loadLibrary()
        }
    }

    /**
     * Android 10 及以下没有 createDeleteRequest，只能直接删：
     * 9 及以下需要 WRITE_EXTERNAL_STORAGE；10 可能抛 RecoverableSecurityException，这里按失败处理。
     */
    fun deleteDirectly(uris: List<Uri>) {
        scope.launch {
            val anyDeleted = withContext(Dispatchers.IO) {
                var any = false
                uris.forEach { uri ->
                    try {
                        if (getApplication<Application>().contentResolver.delete(uri, null, null) > 0) {
                            any = true
                        }
                    } catch (_: Throwable) {
                        // 权限不足或文件被占用；下面统一报失败
                    }
                }
                any
            }
            if (!anyDeleted) {
                Toast.makeText(
                    getApplication<Application>(),
                    R.string.delete_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onDeleteResult(anyDeleted)
        }
    }

    private fun removeDeletedFromQueue(deletedIds: Set<Long>) {
        val controller = connection.controller ?: return
        // 倒着删，避免索引位移
        for (index in controller.mediaItemCount - 1 downTo 0) {
            val id = controller.getMediaItemAt(index).mediaId.toLongOrNull()
            if (id != null && id in deletedIds) controller.removeMediaItem(index)
        }
    }

    override fun onCleared() {
        connection.controller?.removeListener(playerListener)
        scope.cancel()
        connection.release()
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L

        /**
         * 播放进度落盘的最小间隔。
         *
         * [syncPlayback] 在播放中每 500ms 跑一次，每次都写 SharedPreferences 太浪费；
         * 5 秒一次足以让冷启动恢复到"大致同一处"，而切歌/暂停/停止时另有立即写入，
         * 那些才是真正需要精确的时刻。
         */
        const val POSITION_PERSIST_INTERVAL_MS = 5_000L

        /**
         * 缓存已经把列表画出来时，首次扫描推迟这么久才开始。
         *
         * 1.5s 是"够用户把第一次滚动做完、又不至于让新歌迟到太久"的折中：
         * 冷启动后用户第一波拖动通常就在这半秒到一秒多之间，把扫描错开这一段，
         * 滚动就不会和 MediaStore 抢 CPU/IO。第一次安装（没有缓存）时不延迟。
         */
        const val FIRST_SCAN_DELAY_MS = 1500L
    }
}

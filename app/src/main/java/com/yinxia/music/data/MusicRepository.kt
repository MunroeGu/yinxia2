package com.yinxia.music.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前媒体库的"指纹"：每个外部卷的 MediaStore version + generation。
 *
 * 用途见 [MusicRepository.readFingerprint]：和缓存里记下的那一份完全一致时，
 * 缓存就是权威数据，可以整段跳过扫描。
 */
data class MediaStoreFingerprint(val volumes: Map<String, VolumeStamp>) {

    data class VolumeStamp(val version: String, val generation: Long)

    /** 平台不支持（API < 30）或读取失败：永不匹配，保证退回"每次都扫"的老行为 */
    val isKnown: Boolean get() = volumes.isNotEmpty()

    companion object {
        val UNKNOWN = MediaStoreFingerprint(emptyMap())
    }
}

/**
 * 通过 MediaStore 扫描设备上的本地音频，并汇总出文件夹列表。
 *
 * 不走文件系统遍历：一是 Android 10 以后分区存储不允许随便读目录，
 * 二是 MediaStore 有索引，几万首歌也很快。
 */
class MusicRepository(private val context: Context) {

    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        // 扫描和用户滚动在抢 CPU/IO。把这条线程降到后台优先级，
        // 系统在调度时会优先保证 UI 线程的帧率（用户反馈"刚进 App 立刻拖列表卡"）。
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

        val songs = ArrayList<Song>(256)

        // Android 10 起用相对路径（如 "Music/Albums/"）标识文件夹，
        // 更老的系统只有绝对路径 "_data" 可用。
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val folderColumnName = resolveFolderColumnName(useRelativePath)

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            folderColumnName,
        )
        // BITRATE 是 Android 11（API 30）才有的列，SAMPLERATE 是 Android 16（API 36）才有的；
        // 低版本上查询这些列会直接抛异常，所以按版本加进去
        val bitrateColumnName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.Audio.Media.BITRATE
        } else {
            null
        }
        // 注意：SAMPLERATE 是 API 36（Android 16）才加进 MediaStore 的，不是 API 31。
        // 版本写错会让 Android 12~15 的投影带上一个不存在的列，query 直接抛异常，
        // 而异常在下面被吞成"空列表"，表现出来就是"歌曲全不见了"。
        val sampleRateColumnName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            MediaStore.Audio.Media.SAMPLERATE
        } else {
            null
        }
        bitrateColumnName?.let { projection += it }
        sampleRateColumnName?.let { projection += it }

        // IS_MUSIC 能滤掉铃声、通知音、录音；时长下限再滤掉系统音效
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
            " AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val folderColumn = cursor.getColumnIndexOrThrow(folderColumnName)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bitrateColumn = bitrateColumnName?.let { cursor.getColumnIndex(it) } ?: -1
                val sampleRateColumn = sampleRateColumnName?.let { cursor.getColumnIndex(it) } ?: -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)
                    val folderKey = folderKeyOf(
                        rawFolder = cursor.getString(folderColumn),
                        useRelativePath = useRelativePath,
                    )

                    songs += Song(
                        id = id,
                        title = title?.takeIf { it.isNotBlank() } ?: UNKNOWN_TITLE,
                        artist = cursor.getString(artistColumn)?.cleanMediaStoreValue(),
                        album = cursor.getString(albumColumn)?.cleanMediaStoreValue(),
                        durationMs = cursor.getLong(durationColumn),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                        albumId = cursor.getLong(albumIdColumn),
                        folderKey = folderKey,
                        folderName = folderNameOf(folderKey),
                        // DATE_ADDED 是秒，统一成毫秒
                        dateAddedMs = cursor.getLong(dateAddedColumn) * 1000L,
                        bitrate = if (bitrateColumn >= 0) cursor.getInt(bitrateColumn) else null,
                        sampleRate = if (sampleRateColumn >= 0) cursor.getInt(sampleRateColumn) else null,
                        sizeBytes = cursor.getLong(sizeColumn),
                        mimeType = cursor.getString(mimeColumn),
                    )
                }
            }
        } catch (_: SecurityException) {
            // 没有读音频权限，交给界面去提示，这里当空库处理
            return@withContext emptyList()
        } catch (_: IllegalArgumentException) {
            // 个别 ROM 的 MediaStore 实现异常，同样按空库处理而不是崩溃
            return@withContext emptyList()
        }

        songs
    }

    /**
     * 读一次当前媒体库的指纹（FIX 5）。
     *
     * 为什么是"每个外部卷一份 version + generation"，而不是只取一个 generation：
     *
     *  - 我们的查询用的是 [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI]，它是
     *    `VOLUME_EXTERNAL` 这个**合并卷**，把当前挂载的所有外部存储（内置主存储
     *    + 可能的 SD 卡）合在一起。而 `getGeneration` / `getVersion` 的 volumeName
     *    必须是 `getExternalVolumeNames()` 返回的**具体卷名**，不能传合并卷名。
     *    只盯 `VOLUME_EXTERNAL_PRIMARY` 的话，SD 卡上新增/删除的歌不会让主卷的
     *    generation 变化，我们就会误判成"没变"而跳过扫描、永远看不到那些歌。
     *    所以这里对每个具体卷都取一份。
     *  - AOSP 在 `getGeneration` 的文档里明确写了：generation 在"整体 version 变化"
     *    时可能被**重置**，比较前必须先确认 version 没变。所以 version 也一起存。
     *
     * 任何一步失败（平台不支持、MediaProvider 没起来、volume 无效）都返回
     * [MediaStoreFingerprint.UNKNOWN]，调用方据此走普通扫描 —— 宁可多扫，不可漏歌。
     */
    suspend fun readFingerprint(): MediaStoreFingerprint = withContext(Dispatchers.IO) {
        // minSdk 26：API 30 以下没有 generation，直接给哨兵，老设备行为不变
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            MediaStoreFingerprint.UNKNOWN
        } else {
            runCatching {
                val volumeNames = MediaStore.getExternalVolumeNames(context)
                if (volumeNames.isEmpty()) return@runCatching MediaStoreFingerprint.UNKNOWN

                val stamps = HashMap<String, MediaStoreFingerprint.VolumeStamp>(volumeNames.size)
                volumeNames.forEach { volumeName ->
                    stamps[volumeName] = MediaStoreFingerprint.VolumeStamp(
                        version = MediaStore.getVersion(context, volumeName),
                        generation = MediaStore.getGeneration(context, volumeName),
                    )
                }
                MediaStoreFingerprint(stamps)
            }.getOrDefault(MediaStoreFingerprint.UNKNOWN)
        }
    }

    /** MediaStore 对缺失的歌手/专辑会塞字面量 "<unknown>"，直接显示很难看，统一转成 null。 */
    private fun String.cleanMediaStoreValue(): String? =
        takeIf { it.isNotBlank() && !it.equals(UNKNOWN_MEDIA_STORE_VALUE, ignoreCase = true) }

    @Suppress("DEPRECATION")
    private fun resolveFolderColumnName(useRelativePath: Boolean): String =
        if (useRelativePath) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA

    /**
     * 把 MediaStore 返回的原始路径归一成文件夹标识：
     * 相对路径去掉首尾斜杠（"Music/Albums/" -> "Music/Albums"），
     * 绝对路径取父目录（"/storage/emulated/0/Music/a.mp3" -> "/storage/emulated/0/Music"）。
     */
    private fun folderKeyOf(rawFolder: String?, useRelativePath: Boolean): String {
        if (rawFolder.isNullOrBlank()) return ""
        val trimmed = rawFolder.trim()
        return if (useRelativePath) {
            trimmed.trim('/')
        } else {
            trimmed.substringBeforeLast('/', "")
        }
    }

    private fun folderNameOf(folderKey: String): String {
        if (folderKey.isEmpty()) return ROOT_FOLDER_NAME
        val last = folderKey.trimEnd('/').substringAfterLast('/')
        return last.ifEmpty { folderKey }
    }

    companion object {
        private const val MIN_DURATION_MS = 15_000L
        private const val UNKNOWN_MEDIA_STORE_VALUE = "<unknown>"
        private const val UNKNOWN_TITLE = "未知曲目"
        const val ROOT_FOLDER_NAME = "内部存储根目录"

        /** 按文件夹汇总，用于「扫描范围」里的勾选列表。 */
        fun buildFolders(songs: List<Song>): List<FolderEntry> =
            songs.groupBy { it.folderKey }
                .map { (key, group) ->
                    FolderEntry(key = key, displayName = group.first().folderName, songCount = group.size)
                }
                // 和 PlayerViewModel.recompute() 一样避开 sortedBy/compareBy 的
                // 每次比较一次 lowercase() 分配：这个方法是在主线程上、扫描刚结束
                // （用户开始滚列表）那一刻被调用的。
                .sortedWith { a, b ->
                    val byName = a.displayName.compareTo(b.displayName, ignoreCase = true)
                    if (byName != 0) byName else a.key.compareTo(b.key)
                }
    }
}

package com.yinxia.music.data

import android.net.Uri

/**
 * 一首本地歌曲。
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val uri: Uri,
    /** 只用于 Android 9 及以下的封面回退（老的 albumart 表按专辑 id 索引） */
    val albumId: Long,
    /** 所在文件夹的稳定标识，空字符串表示内部存储根目录 */
    val folderKey: String,
    /** 文件夹短名，给界面显示用 */
    val folderName: String,
    /** MediaStore 记录的加入时间（毫秒） */
    val dateAddedMs: Long,
    /** 码率（bps）。系统只在 Android 11+ 提供，取不到时为 null */
    val bitrate: Int?,
    /** 采样率（Hz）。系统只在 Android 16+（API 36）提供，取不到时为 null */
    val sampleRate: Int?,
    /** 文件大小（字节） */
    val sizeBytes: Long,
    /** MIME 类型，例如 audio/flac */
    val mimeType: String?,
)

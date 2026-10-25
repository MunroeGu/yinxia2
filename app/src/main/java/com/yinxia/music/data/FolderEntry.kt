package com.yinxia.music.data

/**
 * 一个有音频的文件夹。
 *
 * [key] 是稳定标识：Android 10+ 用 MediaStore 的相对路径（如 "Music/Albums"），
 * Android 9 及以下退化成绝对路径（如 "/storage/emulated/0/Music"）。
 * 空字符串表示内部存储根目录。
 */
data class FolderEntry(
    val key: String,
    val displayName: String,
    val songCount: Int,
)

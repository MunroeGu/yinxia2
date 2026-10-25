package com.yinxia.music.util

import com.yinxia.music.data.Song
import java.util.Locale

/**
 * 把歌曲的技术信息拆成最多两行：
 *  第一行 格式 · 码率 · 采样率
 *  第二行 文件大小
 *
 * 之所以拆两行：全部塞在一行里在手机上会被截断（用户反馈"信息太长显示不全"）。
 * 拿不到的字段直接跳过（码率要 Android 11+，采样率要 Android 16+）；
 * 一个都拿不到时返回空列表，界面就不显示这些行。
 */
fun songDetailLines(song: Song): List<String> {
    val technical = mutableListOf<String>()

    formatFromMime(song.mimeType)?.let { technical += it }

    song.bitrate?.takeIf { it > 0 }?.let { bps ->
        technical += "${bps / 1000} kbps"
    }

    song.sampleRate?.takeIf { it > 0 }?.let { hz ->
        technical += String.format(Locale.US, "%.1f kHz", hz / 1000f)
    }

    val lines = mutableListOf<String>()
    if (technical.isNotEmpty()) lines += technical.joinToString(" · ")
    if (song.sizeBytes > 0) {
        lines += String.format(Locale.US, "%.1f MB", song.sizeBytes / 1024f / 1024f)
    }
    return lines
}

/** "audio/flac" -> "FLAC"；拿不到就返回 null */
private fun formatFromMime(mime: String?): String? {
    val subtype = mime?.substringAfter('/', "")?.takeIf { it.isNotBlank() } ?: return null
    return when (subtype.lowercase(Locale.US)) {
        "mpeg", "mp3" -> "MP3"
        "mp4", "m4a", "aac" -> "AAC"
        "x-flac", "flac" -> "FLAC"
        "x-wav", "wav", "wave" -> "WAV"
        "ogg" -> "OGG"
        else -> subtype.uppercase(Locale.US)
    }
}

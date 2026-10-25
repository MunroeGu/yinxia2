package com.yinxia.music.widget

import android.content.Context
import androidx.media3.session.MediaController
import com.yinxia.music.data.LibraryPreferences
import com.yinxia.music.data.MusicRepository
import com.yinxia.music.player.toMediaItem

/**
 * 进程被系统杀掉后，播放队列只在内存里，于是插件按播放等于对空队列下命令 ——
 * 表现为"按钮完全没反应"。这里用上次存下来的歌曲 id 顺序把队列重建出来。
 *
 * 返回 true 表示确实重建了（调用方随后执行原本的命令）。
 */
object QueueRestore {

    suspend fun restoreIfEmpty(context: Context, controller: MediaController): Boolean {
        if (controller.mediaItemCount > 0) return false

        val preferences = LibraryPreferences(context)
        val wantedIds = preferences.queueSongIds
        if (wantedIds.isEmpty()) return false

        val songsById = MusicRepository(context).loadSongs().associateBy { it.id }
        // 按存下来的顺序重建，歌曲已被删掉的直接跳过
        val songs = wantedIds.mapNotNull { songsById[it] }
        if (songs.isEmpty()) return false

        val index = preferences.queueIndex.coerceIn(0, songs.lastIndex)
        // 从上次听到的位置接着放。夹进当前歌曲的时长里；时长未知（还没解析出来）时
        // 只保证非负，多出来的部分播放器自己会夹掉。
        val current = songs[index]
        val position = if (current.durationMs > 0L) {
            preferences.queuePositionMs.coerceIn(0L, current.durationMs)
        } else {
            preferences.queuePositionMs.coerceAtLeast(0L)
        }
        controller.setMediaItems(songs.map { it.toMediaItem() }, index, position)
        controller.prepare()
        return true
    }
}

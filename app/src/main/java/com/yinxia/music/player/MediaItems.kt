package com.yinxia.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.yinxia.music.data.Song

/**
 * Song -> MediaItem 的转换。
 * 抽出来放这里，是因为除了 ViewModel，桌面插件在恢复队列时也要用同一套转换。
 */
fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()

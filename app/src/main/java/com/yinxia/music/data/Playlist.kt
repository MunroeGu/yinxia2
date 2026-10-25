package com.yinxia.music.data

/**
 * 用户自建的歌单。
 *
 * 只存歌曲 id 列表，不存歌曲副本：歌曲被删掉或不在扫描范围里时，
 * 歌单里的 id 自然失效（界面上会忽略它），不会留下脏数据。
 */
data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long>,
)

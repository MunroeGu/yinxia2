package com.yinxia.music.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 音乐库的本地缓存。
 *
 * 冷启动时 MediaStore 扫描要等一会儿，界面会一直空着 —— 用户感受到的就是"几秒卡顿"。
 * 这里把上次扫到的结果存成 JSON，下次启动先把旧数据画出来（几乎瞬间），
 * 再在后台扫描并用新结果覆盖，所以看到的永远是"有内容 + 稍后自动更新"。
 *
 * 除了歌曲，还顺带把落盘那一刻的媒体库指纹（[MediaStoreFingerprint]）存下来：
 * 指纹和当前一致时，这份缓存就是权威数据，调用方（PlayerViewModel）可以**整段跳过扫描**。
 *
 * 用 org.json 而不是引入序列化库：字段少、结构简单，不值得多一个依赖。
 */
object LibraryCache {

    private const val FILE_NAME = "library_cache.json"
    private const val KEY_STAMPS = "stamps"
    private const val KEY_SONGS = "songs"
    private const val KEY_VERSION = "version"
    private const val KEY_GENERATION = "generation"

    /**
     * 缓存里的一份完整快照：歌曲 + 落盘那一刻的媒体库指纹。
     * 指纹和当前媒体库一致时，这份快照就是权威数据。
     */
    data class Snapshot(val songs: List<Song>, val fingerprint: MediaStoreFingerprint)

    /**
     * 写盘。[fingerprint] 传 [MediaStoreFingerprint.UNKNOWN]（空指纹）表示
     * "这份缓存不再权威"：下次启动一定会重新扫描。删除歌曲后就是这么做的。
     */
    suspend fun save(
        context: Context,
        songs: List<Song>,
        fingerprint: MediaStoreFingerprint,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray()
            songs.forEach { song -> array.put(toJson(song)) }

            val stamps = JSONObject()
            fingerprint.volumes.forEach { (volumeName, stamp) ->
                stamps.put(
                    volumeName,
                    JSONObject().apply {
                        put(KEY_VERSION, stamp.version)
                        put(KEY_GENERATION, stamp.generation)
                    },
                )
            }

            val root = JSONObject().apply {
                put(KEY_STAMPS, stamps)
                put(KEY_SONGS, array)
            }
            file(context).writeText(root.toString())
        }
        Unit
    }

    /**
     * 读缓存。
     *
     * 两种格式都认：
     *  - 新格式 `{"stamps": {...}, "songs": [...]}`；
     *  - 旧格式（顶层就是一个歌曲数组）。
     *
     * 旧格式没有指纹，按"未知指纹"处理：老缓存既不能让 App 崩，也不能被当成权威数据
     * 而跳过扫描 —— 下一次扫描成功时文件就会被改写成新格式。
     */
    suspend fun load(context: Context): Snapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val target = file(context)
            if (!target.exists()) return@runCatching null

            val text = target.readText()
            if (text.isBlank()) return@runCatching null

            val root = runCatching { JSONObject(text) }.getOrNull()
            val songsArray: JSONArray
            val fingerprint: MediaStoreFingerprint
            if (root != null) {
                songsArray = root.optJSONArray(KEY_SONGS) ?: return@runCatching null
                fingerprint = parseFingerprint(root)
            } else {
                songsArray = runCatching { JSONArray(text) }.getOrNull() ?: return@runCatching null
                fingerprint = MediaStoreFingerprint.UNKNOWN
            }
            if (songsArray.length() == 0) return@runCatching null

            val songs = (0 until songsArray.length()).mapNotNull { index ->
                songsArray.optJSONObject(index)?.let(::fromJson)
            }
            if (songs.isEmpty()) return@runCatching null

            Snapshot(songs = songs, fingerprint = fingerprint)
        }.getOrNull()
    }

    /** 缺失/写坏的条目一律当"未知"，绝不返回半张假指纹。 */
    private fun parseFingerprint(root: JSONObject): MediaStoreFingerprint {
        val stamps = root.optJSONObject(KEY_STAMPS) ?: return MediaStoreFingerprint.UNKNOWN
        val volumes = HashMap<String, MediaStoreFingerprint.VolumeStamp>(stamps.length())
        stamps.keys().forEach { volumeName ->
            val stamp = stamps.optJSONObject(volumeName) ?: return@forEach
            val version = stamp.optString(KEY_VERSION, "")
            val generation = stamp.optLong(KEY_GENERATION, -1L)
            if (version.isNotEmpty() && generation >= 0L) {
                volumes[volumeName] = MediaStoreFingerprint.VolumeStamp(version, generation)
            }
        }
        return if (volumes.isEmpty()) MediaStoreFingerprint.UNKNOWN else MediaStoreFingerprint(volumes)
    }

    private fun toJson(song: Song): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("durationMs", song.durationMs)
        put("uri", song.uri.toString())
        put("albumId", song.albumId)
        put("folderKey", song.folderKey)
        put("folderName", song.folderName)
        put("dateAddedMs", song.dateAddedMs)
        put("bitrate", song.bitrate ?: -1)
        put("sampleRate", song.sampleRate ?: -1)
        put("sizeBytes", song.sizeBytes)
        put("mimeType", song.mimeType)
    }

    private fun fromJson(item: JSONObject): Song? {
        val id = item.optLong("id", -1L)
        val uri = item.optString("uri", "")
        if (id <= 0L || uri.isEmpty()) return null

        return Song(
            id = id,
            title = item.optString("title"),
            artist = item.optString("artist").ifBlank { null },
            album = item.optString("album").ifBlank { null },
            durationMs = item.optLong("durationMs"),
            // Song.uri 是 android.net.Uri，缓存里存的是字符串，取出来必须还原
            uri = Uri.parse(uri),
            albumId = item.optLong("albumId", -1L),
            folderKey = item.optString("folderKey", ""),
            folderName = item.optString("folderName", ""),
            dateAddedMs = item.optLong("dateAddedMs"),
            bitrate = item.optInt("bitrate", -1).takeIf { it > 0 },
            sampleRate = item.optInt("sampleRate", -1).takeIf { it > 0 },
            sizeBytes = item.optLong("sizeBytes", 0L),
            mimeType = item.optString("mimeType").ifBlank { null },
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}

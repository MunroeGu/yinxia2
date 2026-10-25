package com.yinxia.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 界面偏好的持久化：排序方式、扫描范围、手动顺序。
 *
 * 数据量极小（一个字符串加一个 id 列表），用 SharedPreferences 就够了，
 * 不值得为此引入 DataStore。
 */
class LibraryPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 手动排序表的解析结果；null = 还没读过。读写都先过它，避免反复解析 JSON */
    private var manualOrdersCache: MutableMap<String, List<Long>>? = null

    var sortMode: SortMode
        get() = runCatching { SortMode.valueOf(prefs.getString(KEY_SORT_MODE, null).orEmpty()) }
            .getOrDefault(SortMode.TITLE)
        set(value) {
            prefs.edit().putString(KEY_SORT_MODE, value.name).apply()
        }

    /** 仅对名称/时间排序有意义；手动排序忽略它 */
    var sortAscending: Boolean
        get() = prefs.getBoolean(KEY_SORT_ASC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SORT_ASC, value).apply()
        }

    /**
     * 是否启用了「只扫描部分文件夹」。
     * 关掉时扫描全部，界面上的勾选状态统一显示为全选。
     */
    var folderFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOLDER_FILTER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FOLDER_FILTER, value).apply()
        }

    var selectedFolders: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_FOLDERS, value).apply()
        }

    /**
     * 手动排序：**每个视图各自一份**的歌曲 id 顺序。
     *
     * 以前只有一个全局列表，于是在某个歌单里拖动会把「全部」以及其他歌单的顺序
     * 一起改掉（用户反馈的 bug）。现在按作用域存成 JSON：
     * `{"library":[...], "playlist:5":[...]}` —— 「全部」和各歌单互不影响。
     *
     * 作用域约定：`"library"` = 全部，`"playlist:<id>"` = 某个歌单。
     */
    fun manualOrderFor(scope: String): List<Long> = manualOrders()[scope].orEmpty()

    fun setManualOrderFor(scope: String, ids: List<Long>) {
        val orders = manualOrders()
        // 内容没变就不写盘：拖动排序时 commit 会被反复调用，没必要每次都序列化一遍
        if (orders[scope].orEmpty() == ids) return
        // 空列表等价于"这个作用域没有自定义顺序"（歌单被删时也用它清理），不存空数组
        if (ids.isEmpty()) orders.remove(scope) else orders[scope] = ids.toList()
        persistManualOrders(orders)
    }

    /**
     * 解析后的整份排序表；第一次访问时才读，之后读写都走内存。
     *
     * 迁移：新 key 不存在、旧 key（全局单列表）存在时，把旧列表当作 `"library"`
     * 作用域的顺序。这里只是读时兼容，**不立刻写新 key** —— 用户不拖动就什么都不写；
     * 第一次真正写入时，整份表（含这份老数据）才落到新 key 上。
     */
    private fun manualOrders(): MutableMap<String, List<Long>> {
        manualOrdersCache?.let { return it }

        val loaded = LinkedHashMap<String, List<Long>>()
        val raw = prefs.getString(KEY_MANUAL_ORDERS, null)
        if (raw != null) {
            runCatching {
                val json = JSONObject(raw)
                json.keys().forEach { scope ->
                    val ids = json.optJSONArray(scope) ?: return@forEach
                    loaded[scope] = (0 until ids.length()).map { ids.getLong(it) }
                }
            }
        } else {
            val legacy = legacyManualOrder()
            if (legacy.isNotEmpty()) loaded[LIBRARY_SCOPE] = legacy
        }

        manualOrdersCache = loaded
        return loaded
    }

    /** 老版本那份全局手动顺序（逗号分隔）。只用于迁移读取，新代码不再写它 */
    private fun legacyManualOrder(): List<Long> = prefs.getString(KEY_MANUAL_ORDER, null)
        ?.split(',')
        ?.mapNotNull { it.toLongOrNull() }
        .orEmpty()

    private fun persistManualOrders(orders: Map<String, List<Long>>) {
        val json = JSONObject()
        orders.forEach { (scope, ids) ->
            json.put(scope, JSONArray().apply { ids.forEach { put(it) } })
        }
        prefs.edit().putString(KEY_MANUAL_ORDERS, json.toString()).apply()
    }

    /**
     * 用户自定义的默认主题色（ARGB）。null 表示"没指定"，界面用主题自带的紫罗兰。
     * 用 contains 判断有没有设过：没设过和设成 0 是两件事。
     */
    var defaultAccentArgb: Int?
        get() = if (prefs.contains(KEY_DEFAULT_ACCENT)) prefs.getInt(KEY_DEFAULT_ACCENT, 0) else null
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_DEFAULT_ACCENT) else putInt(KEY_DEFAULT_ACCENT, value)
            }.apply()
        }

    /** 是否在列表里显示码率/采样率这类详细信息 */
    var showSongDetails: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DETAILS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_DETAILS, value).apply()
        }

    /** 自建歌单。用 JSON 存：数量少、结构简单，不值得为此引入数据库 */
    var playlists: List<Playlist>
        get() {
            val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val ids = item.optJSONArray("songIds") ?: JSONArray()
                    Playlist(
                        id = item.getLong("id"),
                        name = item.getString("name"),
                        songIds = (0 until ids.length()).map { ids.getLong(it) },
                    )
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val array = JSONArray()
            value.forEach { playlist ->
                val ids = JSONArray()
                playlist.songIds.forEach { ids.put(it) }
                array.put(
                    JSONObject().apply {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("songIds", ids)
                    },
                )
            }
            prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
        }

    /** 上次播放的队列（歌曲 id 顺序）。进程被杀后靠它把队列恢复回来 */
    var queueSongIds: List<Long>
        get() = prefs.getString(KEY_QUEUE, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        set(value) {
            prefs.edit().putString(KEY_QUEUE, value.joinToString(",")).apply()
        }

    /** 队列里正在播第几首 */
    var queueIndex: Int
        get() = prefs.getInt(KEY_QUEUE_INDEX, 0)
        set(value) {
            prefs.edit().putInt(KEY_QUEUE_INDEX, value).apply()
        }

    /** 上次播放到这首歌的第几毫秒。冷启动要靠它恢复进度 */
    var queuePositionMs: Long
        get() = prefs.getLong(KEY_QUEUE_POSITION, 0L)
        set(value) {
            prefs.edit().putLong(KEY_QUEUE_POSITION, value).apply()
        }

    /** 桌面插件上次渲染用的状态。插件渲染不再依赖播放服务，靠这几个值就能画出上次的样子 */
    var widgetTitle: String?
        get() = prefs.getString(KEY_WIDGET_TITLE, null)
        set(value) {
            prefs.edit().putString(KEY_WIDGET_TITLE, value).apply()
        }

    var widgetArtist: String?
        get() = prefs.getString(KEY_WIDGET_ARTIST, null)
        set(value) {
            prefs.edit().putString(KEY_WIDGET_ARTIST, value).apply()
        }

    var widgetIsPlaying: Boolean
        get() = prefs.getBoolean(KEY_WIDGET_PLAYING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WIDGET_PLAYING, value).apply()
        }

    var widgetSongId: Long
        get() = prefs.getLong(KEY_WIDGET_SONG_ID, -1L)
        set(value) {
            prefs.edit().putLong(KEY_WIDGET_SONG_ID, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "yinxia_library"
        const val KEY_SORT_MODE = "sort_mode"
        const val KEY_SORT_ASC = "sort_ascending"
        const val KEY_FOLDER_FILTER = "folder_filter_enabled"
        const val KEY_FOLDERS = "selected_folders"
        /** 旧版本的全局手动顺序。只保留来读（迁移成 "library" 作用域），不再写 */
        const val KEY_MANUAL_ORDER = "manual_order"
        /** 新版本：每个作用域一份手动顺序，JSON 存 */
        const val KEY_MANUAL_ORDERS = "manual_orders"
        const val LIBRARY_SCOPE = "library"
        const val KEY_DEFAULT_ACCENT = "default_accent_argb"
        const val KEY_SHOW_DETAILS = "show_song_details"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_QUEUE = "queue_song_ids"
        const val KEY_QUEUE_INDEX = "queue_index"
        const val KEY_QUEUE_POSITION = "queue_position_ms"
        const val KEY_WIDGET_TITLE = "widget_title"
        const val KEY_WIDGET_ARTIST = "widget_artist"
        const val KEY_WIDGET_PLAYING = "widget_is_playing"
        const val KEY_WIDGET_SONG_ID = "widget_song_id"
    }
}

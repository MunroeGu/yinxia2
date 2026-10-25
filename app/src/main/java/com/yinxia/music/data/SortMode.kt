package com.yinxia.music.data

/**
 * 音乐库的排序方式。
 */
enum class SortMode {
    /** 按名称 */
    TITLE,

    /** 按添加进媒体库的时间 */
    DATE_ADDED,

    /** 用户手动排的顺序 */
    MANUAL,
}

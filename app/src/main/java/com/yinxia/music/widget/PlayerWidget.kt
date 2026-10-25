package com.yinxia.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.yinxia.music.MainActivity
import com.yinxia.music.R
import com.yinxia.music.data.ArtworkLoader
import com.yinxia.music.data.Song

/**
 * 桌面插件的渲染。
 *
 * 为什么由 App 主动 push，而不是插件自己去查播放状态：
 * RemoteViews 只能"构建好推给系统"，而封面位图只有 App 进程的内存缓存里才有
 * （插件进程重新解码一次既慢、又要重新处理权限）。所以正常更新路径是：
 * 播放状态变化 → App 构建 RemoteViews → 推给桌面。
 *
 * 插件按钮的点击由 [PlayerWidgetProvider] 处理，那里一律整块重画（不用局部更新：
 * 局部更新在部分启动器上会丢掉按钮的点击绑定）。
 *
 * 除了封面和文字，这里还渲染两样东西：
 *  - **背板**：一张圆角矩形位图，填充色取自当前歌曲封面的主色（没有封面时用默认底色）。
 *    RemoteViews 没有任何"改 shape 颜色"的通道，动态上色只能靠位图；
 *  - **封面圆角**：连占位图一起，所有封面先裁成正方形再切圆角，和 App 内观感一致。
 */
object PlayerWidget {

    private const val PREFS = "yinxia_widget"
    private const val KEY_LAST_STATE = "last_state"
    private const val COVER_SIZE = 192

    /** 封面圆角（相对 192px 标准图）：约 22%，54dp 的视图上 ≈ 12dp，和 App 内列表封面观感接近 */
    private const val COVER_RADIUS = 42f

    /** 背板位图的单边像素上限：纯色底板用不着启动器的完整分辨率 */
    private const val BACKDROP_MAX_PX = 640

    /** 背板圆角，与 res/drawable/widget_background.xml 里的 corners 半径保持一致 */
    private const val BACKDROP_RADIUS_DP = 22f

    /**
     * 背板透明度系数：最终 alpha = 颜色资源里的 alpha × 这个系数。
     * 资源里浅色 0xD9（85%）/ 深色 0xCC（80%），乘上 0.88 后约 75% / 70%，
     * 比初版透一档。想再调透明度只动这一个数。
     */
    private const val BACKDROP_ALPHA_FACTOR = 0.88f

    /** 渲染版本：改动渲染逻辑/配色时 +1，强制所有插件重推一次新样子（见 pushState 的签名） */
    private const val RENDER_VERSION = 2

    /** 上一次处理过的封面，避免每次播放/暂停都重新缩一次图 */
    private var coverSongId = -1L
    private var coverImage: Bitmap? = null

    /** 占位封面（带圆角）只生成一次，不随歌曲变 */
    private var placeholderImage: Bitmap? = null

    /** 上一次生成的背板位图，key 一致（歌 + 主题 + 尺寸都相同）就直接复用 */
    private var backdropKey: String? = null
    private var backdropImage: Bitmap? = null

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))

    /**
     * 播放状态变化时调用。内部会跳过"没有实质变化"的调用，
     * 所以每秒两次的进度刷新不会造成重复推送。
     */
    fun push(context: Context, song: Song?, isPlaying: Boolean) {
        pushState(
            context = context,
            title = song?.title,
            artist = song?.artist,
            isPlaying = isPlaying,
            songId = song?.id ?: -1L,
        )
    }

    /**
     * 用拆散的字段推一次状态。
     *
     * 播放服务里只有 MediaItem、没有 Song 对象，所以服务处理完桌面按钮之后
     * 由这个入口把真实状态推回插件（[push] 也是转调它）。
     */
    fun pushState(
        context: Context,
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        songId: Long,
    ) {
        // 封面是几百毫秒后才解码好的，所以"这一首有没有封面"也要进签名：
        // 否则切歌那一瞬间的推送会把占位图发到桌面，而且之后再也不会重推真封面。
        // coverFor 在拿不到封面时不写缓存，所以后面再调用一次就能取到。
        val cover = coverFor(songId.takeIf { it > 0 })

        // 先把状态存下来。必须放在签名去重之前：即使这一次因为"没实质变化"不重推界面，
        // 插件下次重画（onUpdate / 拉伸尺寸）也要能拿到最新的歌名和播放状态。
        val preferences = com.yinxia.music.data.LibraryPreferences(context)
        preferences.widgetTitle = title
        preferences.widgetArtist = artist
        preferences.widgetIsPlaying = isPlaying
        preferences.widgetSongId = songId

        // 深浅模式也进签名：系统切换深浅色后，背板要跟着换一套底色/描边，
        // 不能因为"歌没换"就一直停在旧主题的样子
        val night = isNightMode(context)
        // 渲染版本进签名：渲染逻辑/配色改动后强制所有插件重推一次新样子，
        // 否则升级 App 后"歌没换"的插件会一直停在旧配色上
        val signature = "v$RENDER_VERSION|$songId|$title|$artist|$isPlaying|${cover != null}|$night"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_STATE, null) == signature) return
        prefs.edit().putString(KEY_LAST_STATE, signature).apply()
        updateAll(context, title, artist, isPlaying, cover, songId)
    }

    /**
     * 仅用上次存下来的状态重画一次，**完全不连播放服务**。
     *
     * 插件尺寸变化时走这条路：只要连接服务失败，原来那条路径就什么都不会重画，
     * 表现就是"拉成 2x2 布局也不变"。
     */
    fun renderFromStoredState(context: Context) {
        val preferences = com.yinxia.music.data.LibraryPreferences(context)
        val songId = preferences.widgetSongId.takeIf { it > 0 }
        updateAll(
            context = context,
            title = preferences.widgetTitle,
            artist = preferences.widgetArtist,
            isPlaying = preferences.widgetIsPlaying,
            cover = coverFor(songId),
            songId = songId ?: -1L,
        )
    }

    /**
     * 点一下播放/暂停时**立刻**把图标翻过来（不连服务、不等回调）。
     *
     * 两个作用：
     *  1. 用户马上看到反馈，而不是"按了没反应"；
     *  2. 这是唯一能远程判断"点击到底有没有送到我们进程"的方法 ——
     *     如果图标翻了，说明广播到了，问题在命令那一段；如果图标纹丝不动，说明点击根本没送到。
     * 真正状态稍后由服务回调（或 App 下一次推送）纠正。
     */
    fun toggleIconOptimistically(context: Context) {
        val preferences = com.yinxia.music.data.LibraryPreferences(context)
        val nowPlaying = !preferences.widgetIsPlaying
        preferences.widgetIsPlaying = nowPlaying
        updateAll(
            context = context,
            title = preferences.widgetTitle,
            artist = preferences.widgetArtist,
            isPlaying = nowPlaying,
            cover = coverFor(preferences.widgetSongId.takeIf { it > 0 }),
            songId = preferences.widgetSongId,
        )
    }

    /**
     * 给所有插件实例整块重画。
     *
     * [songId] 用来取背板主色：> 0 且封面已在内存缓存里时，背板跟着封面变色；
     * 否则退回颜色资源里的默认底色（进程刚被杀、封面缓存还没热的阶段）。
     */
    fun updateAll(
        context: Context,
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        cover: Bitmap?,
        songId: Long = -1L,
    ) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)

        val seedArgb = songId.takeIf { it > 0 }
            ?.let { ArtworkLoader.cachedDominantColor(it) }
            ?.toArgb()
        // 背板填充色 + 文字/图标颜色都从封面主色一起推导（没有主色时用默认底色）
        val palette = widgetPalette(context, seedArgb)

        ids.forEach { id ->
            val options = manager.getAppWidgetOptions(id)
            val layoutRes = layoutFor(options)
            // 背板位图按这个实例的尺寸生成（fitXY 拉伸铺满），所以逐实例来
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                .coerceAtLeast(1)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                .coerceAtLeast(1)
            manager.updateAppWidget(
                id,
                buildViews(
                    context = context,
                    title = title,
                    artist = artist,
                    isPlaying = isPlaying,
                    cover = cover,
                    layoutRes = layoutRes,
                    backdrop = backdropFor(context, widthDp, heightDp, palette),
                    palette = palette,
                ),
            )
        }
    }

    fun buildViews(
        context: Context,
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        cover: Bitmap?,
        layoutRes: Int,
        backdrop: Bitmap,
        palette: WidgetPalette,
    ): RemoteViews = RemoteViews(context.packageName, layoutRes).apply {
        setImageViewBitmap(R.id.widget_backdrop, backdrop)

        setTextViewText(R.id.widget_title, title ?: context.getString(R.string.app_name))
        setTextViewText(R.id.widget_artist, artist ?: context.getString(R.string.widget_idle))
        // 文字颜色跟背板走：widgetPalette 从背板色推导，和底板协调又拉开对比
        setTextColor(R.id.widget_title, palette.textPrimary)
        setTextColor(R.id.widget_artist, palette.textSecondary)

        if (cover != null) {
            setImageViewBitmap(R.id.widget_cover, cover)
        } else {
            // 占位也走圆角位图：图层资源本身是直角的，直接 set 会和真封面的圆角观感打架
            val placeholder = placeholderFor(context)
            if (placeholder != null) {
                setImageViewBitmap(R.id.widget_cover, placeholder)
            } else {
                setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
            }
        }

        setImageViewResource(
            R.id.widget_toggle,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        setContentDescription(
            R.id.widget_toggle,
            context.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
        )
        // 图标颜色也跟背板走（布局 XML 里的 android:tint 只是推送前的兜底色）
        tintIcon(R.id.widget_prev, palette.icon)
        tintIcon(R.id.widget_toggle, palette.icon)
        tintIcon(R.id.widget_next, palette.icon)

        bindClick(
            R.id.widget_toggle,
            commandIntent(context, PlayerWidgetProvider.ACTION_TOGGLE, REQUEST_TOGGLE),
        )
        bindClick(
            R.id.widget_prev,
            commandIntent(context, PlayerWidgetProvider.ACTION_PREV, REQUEST_PREV),
        )
        bindClick(
            R.id.widget_next,
            commandIntent(context, PlayerWidgetProvider.ACTION_NEXT, REQUEST_NEXT),
        )
        // 注意：不要把"打开 App"挂在根布局上 —— 小米启动器会把它当成整块插件的点击，
        // 子按钮的点击就收不到了（用户反馈的"按钮无响应"很可能就是这个）。
        // 改成只有封面能点开 App。
        bindClick(R.id.widget_cover, openAppIntent(context))
    }

    /**
     * 生成背板位图：圆角矩形 + 半透明填充 + 发丝描边，填充色来自 [WidgetPalette.fillArgb]
     * （由 [widgetPalette] 从封面主色推导）。
     *
     * - 圆角半径和描边宽度按"位图边长 / 实际 dp"等比换算，位图被 fitXY 拉到多大，
     *   视觉上的 22dp 圆角 / 1dp 描边都不走样；
     * - 同一个（背板色，尺寸）组合只生成一次，后续推送直接复用。
     */
    private fun backdropFor(
        context: Context,
        widthDp: Int,
        heightDp: Int,
        palette: WidgetPalette,
    ): Bitmap {
        val key = "${palette.fillArgb}|$widthDp*$heightDp"
        if (key == backdropKey) {
            backdropImage?.let { return it }
        }

        val density = context.resources.displayMetrics.density
        // 按 dp 还原像素，同时限制单边：底板是纯色，用不着启动器的完整分辨率
        var width = (widthDp * density).toInt().coerceAtLeast(1)
        var height = (heightDp * density).toInt().coerceAtLeast(1)
        val longest = maxOf(width, height)
        if (longest > BACKDROP_MAX_PX) {
            width = width * BACKDROP_MAX_PX / longest
            height = height * BACKDROP_MAX_PX / longest
        }
        // 位图被缩小后，圆角/描边也要同比例缩，拉伸回去时视觉尺寸才不变
        val scale = height.toFloat() / (heightDp * density).coerceAtLeast(1f)
        val radiusPx = BACKDROP_RADIUS_DP * density * scale
        val strokePx = (density * scale).coerceAtLeast(1f)

        val fill = palette.fillArgb
        val outline = context.getColor(R.color.widget_outline)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(), radiusPx, radiusPx,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill },
        )
        // 描边往内缩半个线宽：贴边画会有一半被圆角裁掉，看起来比设计值细
        val half = strokePx / 2f
        canvas.drawRoundRect(
            half, half, width - half, height - half, radiusPx, radiusPx,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = outline
                style = Paint.Style.STROKE
                strokeWidth = strokePx
            },
        )

        backdropKey = key
        backdropImage = bitmap
        return bitmap
    }

    /** 一次推送的整套配色：背板填充色 + 跟它推导出来的文字/图标颜色。 */
    private data class WidgetPalette(
        val fillArgb: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val icon: Int,
    )

    /**
     * 从封面主色（或默认底色）推导整套插件配色。
     *
     * 背板填充色：
     *  - alpha 取自颜色资源（浅色 0xD9 / 深色 0xCC）再乘 [BACKDROP_ALPHA_FACTOR]，
     *    资源仍是唯一的"透明度底数"，深浅模式各有一份；
     *  - 主色亮度夹进 0.20~0.60：近黑/近白的封面不至于把插件变成一块黑板或白板。
     *
     * 文字/图标颜色：
     *  - 深浅由背板的**感知亮度**决定（>= 0.5 用深色字，否则浅色字）——HSV 的 V 不等于
     *    人眼感受到的明暗，判断深浅必须用加权亮度；
     *  - 都取背板的色相做极淡的染色（饱和度压到原来的 25%~35%），明度拉开对比 ——
     *    和背板协调、又能从背板里区分出来，且不会艳。
     */
    private fun widgetPalette(context: Context, seedArgb: Int?): WidgetPalette {
        val base = context.getColor(R.color.widget_background)
        val fill = if (seedArgb != null) {
            val alpha = (android.graphics.Color.alpha(base) * BACKDROP_ALPHA_FACTOR)
                .toInt()
                .coerceIn(0, 255)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(seedArgb, hsv)
            hsv[2] = hsv[2].coerceIn(0.20f, 0.60f)
            android.graphics.Color.HSVToColor(alpha, hsv)
        } else {
            base
        }

        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(fill, hsv)
        val hue = hsv[0]
        val seedSat = hsv[1]

        val textPrimary: Int
        val textSecondary: Int
        val icon: Int
        if (luminanceOf(fill) >= 0.5f) {
            // 浅背板 → 深色字：明度从深到浅 = 标题 0.13 / 图标 0.24 / 副标题 0.38
            textPrimary = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, seedSat * 0.35f, 0.13f))
            icon = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, seedSat * 0.35f, 0.24f))
            textSecondary = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, seedSat * 0.30f, 0.38f))
        } else {
            // 深背板 → 浅色字：明度从亮到暗 = 标题 0.95 / 图标 0.82 / 副标题 0.74
            textPrimary = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, seedSat * 0.30f, 0.95f))
            icon = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, seedSat * 0.30f, 0.82f))
            textSecondary = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, seedSat * 0.25f, 0.74f))
        }
        return WidgetPalette(fill, textPrimary, textSecondary, icon)
    }

    /** 加权感知亮度（0~1）。判断"背板算浅还是算深"用它，不用 HSV 的 V。 */
    private fun luminanceOf(color: Int): Float {
        val r = android.graphics.Color.red(color) / 255f
        val g = android.graphics.Color.green(color) / 255f
        val b = android.graphics.Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** 只按歌曲 id 取封面（receiver 里只有 mediaId，没有 Song 对象） */
    fun coverFor(songId: Long?): Bitmap? {
        if (songId == null) return null
        if (songId == coverSongId) return coverImage

        val source = ArtworkLoader.cached(songId)?.asAndroidBitmap() ?: return null
        val rounded = roundedSquare(source, COVER_RADIUS)
        coverSongId = songId
        coverImage = rounded
        return rounded
    }

    /**
     * 把占位图层资源画成带圆角的标准位图，只做一次。
     * 画不出来（资源缺失等极端情况）返回 null，调用方退回直角资源，不至于没有占位。
     */
    private fun placeholderFor(context: Context): Bitmap? {
        placeholderImage?.let { return it }
        val drawable = runCatching { context.getDrawable(R.drawable.widget_cover_placeholder) }
            .getOrNull() ?: return null
        return try {
            val square = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, COVER_SIZE, COVER_SIZE)
            Canvas(square).also { drawable.draw(it) }
            val rounded = roundedSquare(square, COVER_RADIUS)
            placeholderImage = rounded
            rounded
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 封面统一处理：先裁成正方形（centerCrop 语义），再缩到标准边长，最后切圆角。
     *
     * 以前直接 `createScaledBitmap(source, 192, 192)`：系统 loadThumbnail 返回的图
     * **不保证是正方形**（保持原封面比例，比如 160×120），硬拉伸不仅把封面压变形，
     * 四个圆角也被拉成椭圆角 —— 这就是"封面看着没有圆角、很锐利"的来源。
     * 先裁后缩之后，圆角始终是正圆弧。
     */
    private fun roundedSquare(source: Bitmap, radiusPx: Float): Bitmap? = try {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val square = Bitmap.createBitmap(source, left, top, side, side)
        val scaled = if (side == COVER_SIZE) {
            square
        } else {
            Bitmap.createScaledBitmap(square, COVER_SIZE, COVER_SIZE, true)
        }
        Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawRoundRect(
                0f, 0f, COVER_SIZE.toFloat(), COVER_SIZE.toFloat(), radiusPx, radiusPx,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                },
            )
        }
    } catch (_: Throwable) {
        // 封面缺失、解码失败、权限边缘情况都当没有封面
        null
    }

    /**
     * 桌面插件跟着系统深浅模式走：启动器上没有 App 自己的主题，系统是什么模式，
     * 插件就该是什么配色。这里读当前 configuration 的夜间标志，
     * 配合 `values-night` 资源一起决定背板的默认底色和描边。
     */
    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 绑定点击。Android 12+ 用新的 setOnClickResponse（框架现在推荐的方式，
     * 重建视图后更不容易丢），低版本退回 setOnClickPendingIntent。
     *
     * 注意 RemoteResponse 是 RemoteViews 的**嵌套类**（android/widget/RemoteViews$RemoteResponse），
     * 没有 android.widget.RemoteResponse 这个顶层类，写错了会直接编译不过。
     */
    private fun RemoteViews.bindClick(viewId: Int, pendingIntent: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            setOnClickResponse(
                viewId,
                android.widget.RemoteViews.RemoteResponse.fromPendingIntent(pendingIntent),
            )
        } else {
            setOnClickPendingIntent(viewId, pendingIntent)
        }
    }

    /**
     * 给控件图标上色。Android 12+ 走 setImageTintList（框架推荐、重建视图后不易丢），
     * 低版本退回 setColorFilter —— 对纯色矢量图标两者效果一致。
     * 布局 XML 里保留的 android:tint 只是"推送前"的兜底色，运行时一定会被这里覆盖。
     */
    private fun RemoteViews.tintIcon(viewId: Int, color: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            setColorStateList(
                viewId,
                "setImageTintList",
                android.content.res.ColorStateList.valueOf(color),
            )
        } else {
            setInt(viewId, "setColorFilter", color)
        }
    }

    /**
     * 按插件高度挑布局：
     *  - 高度足够（>= 100dp）→ 竖排：封面 + 歌名 + 歌手 + 按钮能叠着放下；
     *  - 否则 → 横排（3x1 / 4x1 这种扁的）。
     *
     * 用高度而不是宽高比：2x2 在不同启动器上上报的宽高差别很大，
     * 而"高度不够就放不下竖排内容"这件事是确定的。
     */
    fun layoutFor(options: android.os.Bundle): Int {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (minHeight <= 0) return R.layout.widget_player
        return if (minHeight >= 100 || minWidth < 180) {
            R.layout.widget_player_square
        } else {
            R.layout.widget_player
        }
    }

    private fun commandIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        // ⚠️ 必须是**隐式**广播：只带 action + 包名，不指定组件。
        //
        // 以前这里写的是 Intent(context, PlayerWidgetProvider::class.java)，那是显式广播 ——
        // 显式广播只会送给这一个接收器，PlaybackService 里动态注册的接收器收不到；
        // 而提供者又因为"服务活着"直接返回，于是**谁都没有执行命令**，
        // 表现就是"状态能同步、按钮完全没反应"。
        val intent = Intent(action).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_TOGGLE = 11
    private const val REQUEST_PREV = 12
    private const val REQUEST_NEXT = 13
}

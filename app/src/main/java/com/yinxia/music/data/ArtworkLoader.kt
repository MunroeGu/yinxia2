package com.yinxia.music.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 封面加载与配色提取：不引第三方图片库，也不引 Palette。
 *
 * Android 10+ 直接对歌曲的 content URI 调 loadThumbnail，系统会返回内嵌封面；
 * 更老的系统退回老的 albumart 表。任何失败都返回 null，界面用渐变占位兜底。
 */
object ArtworkLoader {

    /**
     * 列表/迷你条这类小目标的目标边长（px）。
     *
     * 48~56dp 的行在 3x 屏上也只有 ~144~168px，160 足够清晰；像素量却只有原来的
     * 384 的约 1/5.8（160² vs 384²），解码和缓存都省下近 6 倍。
     */
    const val SMALL_PX = 160

    /**
     * 大图目标边长（px，播放页用）。播放页大图约 0.72×屏宽，1080p 上 ~780px，
     * 沿用系统缩略图那条路径（Android 10+）已经在用的 384px：对小内存设备也不会
     * 为了大图把原图整张读进来。
     */
    const val LARGE_PX = 384

    /**
     * 封面缓存的内存预算（字节），按位图实际占用计费。
     *
     * 以前是"最多 80 张"：小图和大图混在一起时，张数上限根本约束不住内存
     * （80 张 384px ARGB_8888 就是 ~47MB）。改成按字节计费后，缓存最多只吃
     * [MAX_CACHE_BYTES]：160px 缩略图 ~100KB/张（可放 ~160 张），
     * 384px 大图 ~576KB/张（可放 ~28 张），任何混合比例都不会超预算。
     */
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024

    /** 同时解码上限，见 [decodeSemaphore] */
    private const val MAX_CONCURRENT_DECODES = 6

    /** 种子色缓存的条目上限（Color 很小，不需要按字节计） */
    private const val SEED_CACHE_SIZE = 80

    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.asAndroidBitmap().byteCount
    }

    /** 每首歌从封面里挑出的"种子色"（还没有按明暗主题调整），key 同 [cacheKey] */
    private val seedCache = LruCache<String, Color>(SEED_CACHE_SIZE)

    /**
     * 同时最多允许 [MAX_CONCURRENT_DECODES] 个封面解码。
     *
     * 这是一道"防止病态突发"的护栏，不是给常见路径调的性能参数：
     * Android 10+ 走的是 contentResolver.loadThumbnail(uri, Size(px,px))，
     * 系统按请求尺寸缩好再返回，单次解码本来就很便宜 —— 3 个许可太少，
     * 滚动时封面会肉眼可见地一张张慢慢"挤"出来，所以放宽到 6。
     *
     * 真正怕把内存和 GC 打爆的是 API 26~28 的老 albumart 路径（要自己读原图），
     * 但那条路已经由 [decodeLegacyAlbumArt] 的两遍 inSampleSize 解码保护住了，
     * 不需要再靠压低并发来兜底。所以这里是放宽上限，不是取消上限：
     * 冷启动可见的十几行同时发起请求时，仍然不会被一次性放开。
     */
    private val decodeSemaphore = Semaphore(permits = MAX_CONCURRENT_DECODES)

    /**
     * 同步取缓存，用于滚动时先显示已有封面，避免闪一下占位图。
     *
     * 小图和大图是两份缓存，所以只给歌曲 id 的调用方（比如桌面插件）拿不到"它要的尺寸"。
     * 这里的约定是：**优先返回小图**，没有再退回大图 —— 列表缩略图是解码量最大、
     * 命中率最高的一份，插件那条路径（192px）拿到 160px 已经够用。
     * 需要精确尺寸就显式传 [targetPx]。
     */
    fun cached(songId: Long, targetPx: Int? = null): ImageBitmap? =
        if (targetPx != null) {
            memoryCache.get(cacheKey(songId, targetPx))
        } else {
            memoryCache.get(cacheKey(songId, SMALL_PX))
                ?: memoryCache.get(cacheKey(songId, LARGE_PX))
        }

    suspend fun load(context: Context, song: Song, targetPx: Int = LARGE_PX): ImageBitmap? {
        if (song.albumId <= 0L && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val key = cacheKey(song.id, targetPx)
        memoryCache.get(key)?.let { return it }
        val bitmap = decodeLimited(context, song, targetPx) ?: return null
        val image = bitmap.asImageBitmap()
        memoryCache.put(key, image)
        return image
    }

    /**
     * 取这首歌封面的主色（种子色，未按主题调整）。
     *
     * 已经有封面缓存时直接复用那张图，不重复解码。
     * 返回 null 表示这首歌没有可用封面 —— 调用方应该退回用户设定的默认色。
     *
     * 默认取 [SMALL_PX]：提取主色时会先把图缩到 24×24 再统计（见 [extractDominantColor]），
     * 拿 160px 还是 384px 的原图去缩，得到的颜色是一样的；而小图正好是列表已经在解的那一份，
     * 所以这里几乎总能命中缓存、不会为了一个颜色额外解码一张 384px 大图。
     */
    suspend fun coverColor(context: Context, song: Song, targetPx: Int = SMALL_PX): Color? {
        val key = cacheKey(song.id, targetPx)
        seedCache.get(key)?.let { return it }

        val bitmap = memoryCache.get(key)?.asAndroidBitmap()
            ?: decodeLimited(context, song, targetPx)
            ?: return null

        val seed = withContext(Dispatchers.Default) { extractDominantColor(bitmap) } ?: return null
        seedCache.put(key, seed)
        return seed
    }

    /**
     * 同步版取主色：只查内存缓存（先种子色、再位图现算），**不做任何 IO / 解码**。
     *
     * 给桌面插件的背板取色用——RemoteViews 的推送发生在主线程，不能挂起；
     * 而插件推送时这首歌的封面十有八九已经在内存缓存里（列表/播放页刚显示过）。
     * 缓存里什么都没有时返回 null，调用方退回默认背板，等下一次封面就绪后的推送再补色。
     *
     * 现算那一步是 24×24 缩放 + 几百个像素的直方图（见 [extractDominantColor]），
     * 即使 seedCache 未命中也只是微秒级的主线程开销。
     */
    fun cachedDominantColor(songId: Long): Color? {
        val smallKey = cacheKey(songId, SMALL_PX)
        seedCache.get(smallKey)?.let { return it }

        val bitmap = memoryCache.get(smallKey)?.asAndroidBitmap()
            ?: memoryCache.get(cacheKey(songId, LARGE_PX))?.asAndroidBitmap()
            ?: return null

        val seed = extractDominantColor(bitmap) ?: return null
        seedCache.put(smallKey, seed)
        return seed
    }

    fun clear() {
        memoryCache.evictAll()
        seedCache.evictAll()
    }

    /**
     * 真正干活的解码入口。一定要跑在 [Dispatchers.IO] 上（由 [decodeLimited] 保证）：
     * loadThumbnail 是跨进程 IPC + 解码，BitmapFactory 是纯 CPU/内存重活。
     *
     * [targetPx] 是请求的目标边长：列表要 160、播放页要 384。
     * Android 10+ 直接把这个尺寸交给系统，让系统缩好再跨进程传回来
     * （小图不只是省我们这边的内存，也省了 IPC 的数据量）。
     */
    private fun decode(context: Context, song: Song, targetPx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 系统这条路径本身就会按请求的尺寸缩放
            context.contentResolver.loadThumbnail(song.uri, thumbnailSizeFor(targetPx), null)
        } else {
            decodeLegacyAlbumArt(context, song.albumId, targetPx)
        }
    } catch (_: Throwable) {
        // 封面缺失、解码失败、权限边缘情况都当没有封面
        null
    }

    /** 统一走信号量 + IO 线程，避免列表首帧把设备一次性打满。 */
    private suspend fun decodeLimited(context: Context, song: Song, targetPx: Int): Bitmap? =
        decodeSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                // 解码和滚动在抢 CPU/IO：同样把这条线程降到后台优先级，
                // 系统调度时优先保证 UI 线程的帧率。
                //
                // 注意：Dispatchers.IO 的线程是复用的，优先级会留在这条线程上
                // （见最终报告里的说明）。这里判断为可接受：IO 线程池在这个 App 里
                // 只承担扫描/封面/缓存读写这类后台工作，本来就该排在 UI 之后。
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND,
                )
                decode(context, song, targetPx)
            }
        }

    /**
     * Android 9 及以下的退路：老的 albumart 表没有缩略图接口，只能自己读。
     *
     * 先只解图片头拿原始尺寸（inJustDecodeBounds，几乎不花内存），
     * 再按 [inSampleSize] 缩到接近 [targetPx] —— 否则一张 3000×3000 的封面
     * 按 ARGB_8888 解出来就是 36MB，而它在列表里只占 48dp。
     */
    @Suppress("DEPRECATION")
    private fun decodeLegacyAlbumArt(context: Context, albumId: Long, targetPx: Int): Bitmap? {
        val uri = legacyAlbumArtUri(albumId)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    /**
     * inSampleSize 只能是 2 的幂。取满足"解码后两个方向都还不小于 [target]"的最大值，
     * 也就是结果会落在 target~2×target 之间：绝不比需要的更大太多，也不会糊到不能用。
     */
    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    /**
     * 从封面里挑一个能当主题色用的颜色。
     *
     * 不用"取平均色"：平均之后通常是一片发灰的泥浆色，放到界面上很难看。这里做了三件事：
     *  1. 缩到 24x24 再统计，几百张封面也不会卡；
     *  2. 量化成 4 位/通道的直方图，按 [出现次数 × (0.25 + 平均饱和度)] 打分 ——
     *     让"面积小但鲜艳"的颜色也能和"面积大但发灰"的颜色竞争；
     *  3. 丢掉接近黑/白/灰的像素，它们当主题色没有意义。
     */
    private fun extractDominantColor(bitmap: Bitmap): Color? {
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        } catch (_: Throwable) {
            return null
        }
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        // 每个桶累加：[像素数, r, g, b, 饱和度之和]
        val buckets = HashMap<Int, FloatArray>(64)
        val hsv = FloatArray(3)

        pixels.forEach { argb ->
            if (((argb ushr 24) and 0xFF) < 128) return@forEach

            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF

            AndroidColor.RGBToHSV(r, g, b, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            if (saturation < 0.15f || value < 0.15f || value > 0.95f) return@forEach

            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val bucket = buckets.getOrPut(key) { FloatArray(5) }
            bucket[0] += 1f
            bucket[1] += r
            bucket[2] += g
            bucket[3] += b
            bucket[4] += saturation
        }

        if (buckets.isEmpty()) return null

        var winner: FloatArray? = null
        var bestScore = 0f
        buckets.values.forEach { bucket ->
            val count = bucket[0]
            val averageSaturation = bucket[4] / count
            val score = count * (0.25f + averageSaturation)
            if (score > bestScore) {
                bestScore = score
                winner = bucket
            }
        }

        val best = winner ?: return null
        val count = best[0]
        return Color(
            red = (best[1] / count) / 255f,
            green = (best[2] / count) / 255f,
            blue = (best[3] / count) / 255f,
            alpha = 1f,
        )
    }

    /** 老的 albumart 表按专辑 id 索引，注意不是歌曲 id。 */
    private fun legacyAlbumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId,
    )

    private const val SAMPLE_SIZE = 24

    /** 大图（播放页）请求用的尺寸对象；两个标准尺寸都复用，避免每次解码都新建 */
    private val THUMBNAIL_SIZE = Size(LARGE_PX, LARGE_PX)
    private val SMALL_THUMBNAIL_SIZE = Size(SMALL_PX, SMALL_PX)

    /** 只有非标准尺寸才现建一个 Size（实践中只有小图/大图两个值） */
    private fun thumbnailSizeFor(targetPx: Int): Size = when (targetPx) {
        SMALL_PX -> SMALL_THUMBNAIL_SIZE
        LARGE_PX -> THUMBNAIL_SIZE
        else -> Size(targetPx, targetPx)
    }

    /**
     * 缓存键：同一首歌的小图和大图必须能共存，所以尺寸要进 key。
     * 两套内存缓存（位图、种子色）用同一套 key。
     */
    private fun cacheKey(songId: Long, targetPx: Int): String = "$songId@$targetPx"
}

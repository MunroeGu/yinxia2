package com.yinxia.music.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yinxia.music.MainActivity
import com.yinxia.music.widget.PlayerWidget
import com.yinxia.music.widget.PlayerWidgetProvider

/**
 * 后台播放的核心。
 *
 * MediaSessionService 会自动做两件事：
 *  1. 播放时把服务提升为前台服务，避免被系统回收；
 *  2. 把 MediaSession 注册给系统，于是锁屏、通知栏、蓝牙耳机按键、车机都能控制播放。
 *
 * 此外这里还**直接处理桌面插件的按钮点击**（见 [widgetCommandReceiver]）。
 *
 * 插件以前是让广播接收器临时连一个 MediaController 去发命令，命令执行完 600ms 再断开。
 * App 退到后台后它自己那个控制器往往已经没了，于是**插件的临时控制器成了最后一个** ——
 * 它一断开，会话就变成"零控制器"，系统随即把播放服务和 App 进程一起收掉。
 * 用户看到的就是：按暂停真的暂停了，但音乐紧接着没了、之后再点插件毫无反应、
 * 重新打开 App 是冷启动（因为进程已经死了）。
 *
 * 现在命令直接在本进程里作用于自己的播放器：不 bind、不建控制器、不触发前台服务启动，
 * 也就不可能再把它弄停。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * 桌面插件按钮的接收器。
     *
     * 注册在服务里，所以只有服务活着时才存在；服务不在时由 [PlayerWidgetProvider]
     * 走兜底路径（连控制器 + 恢复队列）。
     */
    private val widgetCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = mediaSession?.player ?: return

            when (intent.action) {
                PlayerWidgetProvider.ACTION_TOGGLE ->
                    if (player.isPlaying) player.pause() else player.play()

                PlayerWidgetProvider.ACTION_NEXT -> player.seekToNextMediaItem()
                PlayerWidgetProvider.ACTION_PREV -> player.seekToPreviousMediaItem()

                else -> return
            }

            // 立刻把插件刷成真实状态（插件那边的乐观翻转会被这里纠正）
            val item = player.currentMediaItem
            PlayerWidget.pushState(
                context = context,
                title = item?.mediaMetadata?.title?.toString(),
                artist = item?.mediaMetadata?.artist?.toString(),
                isPlaying = player.isPlaying,
                songId = item?.mediaId?.toLongOrNull() ?: -1L,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机 / 断开蓝牙时自动暂停，否则会外放出来
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 点通知栏封面时回到 App
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        registerWidgetCommands()
        isRunning = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 用户从最近任务里划掉 App 时。
     *
     * ⚠️ 这里以前写的是 `!player.playWhenReady || mediaItemCount == 0` 就 stopSelf()，
     * 也就是"只要没在播就收工"。后果是：暂停着划掉 App，服务和进程一起没了，
     * 之后桌面插件的按钮就再也找不到执行者（用户反馈的"点开 App 是冷启动"就是这个）。
     *
     * 现在只要队列还在就留着服务（通知栏也留着，和主流播放器一样），
     * 队列真的清空了才收工。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        // 服务销毁时一定反注册，但即使失败也不能让 onDestroy 抛异常
        runCatching { unregisterReceiver(widgetCommandReceiver) }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun registerWidgetCommands() {
        val filter = IntentFilter().apply {
            addAction(PlayerWidgetProvider.ACTION_TOGGLE)
            addAction(PlayerWidgetProvider.ACTION_NEXT)
            addAction(PlayerWidgetProvider.ACTION_PREV)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 广播来自桌面上的 PendingIntent（发送身份是我们自己），对外可见即可
            registerReceiver(widgetCommandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(widgetCommandReceiver, filter)
        }
    }

    companion object {
        /**
         * 服务是否活着。
         *
         * 桌面插件的接收器和服务在同一个进程里，读这个标志就能判断
         * "命令该由服务执行，还是该自己连控制器"。
         * 只有本服务会写它（onCreate 置 true、onDestroy 置 false）。
         */
        @Volatile
        var isRunning: Boolean = false
    }
}

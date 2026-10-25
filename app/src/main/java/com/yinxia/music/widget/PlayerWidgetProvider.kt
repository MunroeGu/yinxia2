package com.yinxia.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.yinxia.music.player.PlaybackConnection
import com.yinxia.music.player.PlaybackService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 桌面插件。
 *
 * 界面的正常刷新由 App 主动 push（见 [PlayerWidget]），这里负责两件事：
 *  1. 插件刚被添加到桌面、或系统要求刷新时，按播放服务的真实状态画一次；
 *  2. 处理插件上的按钮点击。
 *
 * 按钮要真正控制播放，必须通过 MediaController 连到 PlaybackService。
 * 连上或失败都必须结束 goAsync 的任务，否则系统会认为广播处理超时。
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 先用存下来的状态画出来：不依赖播放服务，所以拉尺寸、重建插件时一定会有反应
        PlayerWidget.renderFromStoredState(context)
        refreshFromService(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 尺寸变了要立刻按新尺寸重画；同样先走"不连服务"的那条路
        PlayerWidget.renderFromStoredState(context)
        refreshFromService(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (val action = intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> handleCommand(context, action)
        }
    }

    private fun refreshFromService(context: Context) {
        // ⚠️ 服务活着时**不要**为了"读一下状态"去连控制器：那个临时控制器断开时
        // 有可能成为压垮会话的最后一根稻草（见 handleCommand 的说明）。
        // 存下来的状态 + App / 服务的主动推送已经足够准确。
        if (PlaybackService.isRunning) return

        withConnection(context) { controller ->
            PlayerWidget.updateAll(
                context = context,
                title = controller.currentMediaItem?.mediaMetadata?.title?.toString(),
                artist = controller.currentMediaItem?.mediaMetadata?.artist?.toString(),
                isPlaying = controller.isPlaying,
                cover = PlayerWidget.coverFor(controller.currentMediaItem?.mediaId?.toLongOrNull()),
                songId = controller.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L,
            )
        }
    }

    private fun handleCommand(context: Context, action: String) {
        // 服务活着 → 命令由 PlaybackService 进程内的接收器执行：同一进程、不 bind、
        // 不建控制器，所以不会像以前那样把服务和 App 进程一起弄停。
        //
        // ⚠️ 同一个广播两个接收器都会收到，顺序没有保证，所以这里必须先返回，
        // 不能顺手做乐观翻转 —— 否则可能把服务推上来的真实状态又改错。
        if (PlaybackService.isRunning) return

        // 服务不在（App 从未启动过 / 队列已清空）才走兜底路径：先给一个立刻的视觉反馈
        if (action == ACTION_TOGGLE) PlayerWidget.toggleIconOptimistically(context)

        withConnection(context) { controller ->
            // 队列空了（进程刚被杀过）就先按上次的顺序重建，再执行命令
            QueueRestore.restoreIfEmpty(context, controller)

            when (action) {
                ACTION_TOGGLE -> if (controller.isPlaying) controller.pause() else {
                    if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                    controller.play()
                }

                ACTION_NEXT -> controller.seekToNextMediaItem()

                ACTION_PREV -> controller.seekToPreviousMediaItem()
            }

            // 一律整块重画，不用局部更新：局部更新在部分启动器上会丢掉按钮的点击绑定
            //（用户反馈"暂停后再按播放没反应"就是这个），整块重画会重新注册所有 PendingIntent
            PlayerWidget.updateAll(
                context = context,
                title = controller.currentMediaItem?.mediaMetadata?.title?.toString(),
                artist = controller.currentMediaItem?.mediaMetadata?.artist?.toString(),
                isPlaying = controller.isPlaying,
                cover = PlayerWidget.coverFor(controller.currentMediaItem?.mediaId?.toLongOrNull()),
                songId = controller.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L,
            )
        }
    }

    /** 连上播放服务执行一段操作，无论成功失败都在超时前结束广播任务。 */
    private fun withConnection(
        context: Context,
        block: suspend (androidx.media3.session.MediaController) -> Unit,
    ) {
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                scope.cancel()
                pending.finish()
            }
        }

        val connection = PlaybackConnection(context)
        connection.connect { controller ->
            scope.launch {
                try {
                    block(controller)
                } finally {
                    // 延迟断开：命令是转给播放服务后才生效的，立刻断开有丢掉它的风险
                    Handler(Looper.getMainLooper()).postDelayed({
                        connection.release()
                        finishOnce()
                    }, RELEASE_DELAY_MS)
                }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, TIMEOUT_MS)
    }

    companion object {
        const val ACTION_TOGGLE = "com.yinxia.music.widget.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.yinxia.music.widget.ACTION_NEXT"
        const val ACTION_PREV = "com.yinxia.music.widget.ACTION_PREV"
        // 恢复播放队列时要在广播窗口里扫一遍媒体库（大库可能几百毫秒到几秒），
        // 5 秒太紧：超时会取消协程、把命令吞掉，表现成"进程被杀后第一次点按钮没反应"。
        // goAsync 给的上限约 10 秒，这里取 9 秒。
        private const val TIMEOUT_MS = 9000L
        private const val RELEASE_DELAY_MS = 600L
    }
}

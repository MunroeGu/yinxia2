package com.yinxia.music.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * 界面进程与 PlaybackService 之间的桥梁。
 *
 * MediaController 必须异步获取（连接是跨进程的），所以这里用 ListenableFuture 包一层，
 * 连上以后回调给 ViewModel。
 */
class PlaybackConnection(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    var controller: MediaController? = null
        private set

    fun connect(onReady: (MediaController) -> Unit) {
        controller?.let {
            onReady(it)
            return
        }
        if (controllerFuture != null) return

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                val connected = try {
                    future.get()
                } catch (_: Throwable) {
                    null
                }
                if (connected != null) {
                    controller = connected
                    onReady(connected)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }
}

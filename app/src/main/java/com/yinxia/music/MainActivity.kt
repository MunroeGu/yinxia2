package com.yinxia.music

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.yinxia.music.ui.PlayerViewModel
import com.yinxia.music.ui.YinxiaApp
import com.yinxia.music.ui.theme.YinxiaTheme

/**
 * 唯一的 Activity。
 *
 * 权限状态和"删除文件"这两件事放在 Activity 而不是 Compose 里：
 * 前者要用 onResume 处理"跑去系统设置开权限再回来"，
 * 后者要用 IntentSender 弹系统确认框，都属于只能在 Activity 层做的事。
 */
class MainActivity : ComponentActivity() {

    private val permissionGranted = mutableStateOf(false)

    private val viewModel: PlayerViewModel by lazy {
        // 显式给 AndroidViewModelFactory：不依赖各版本 lifecycle 对默认工厂的推断
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[PlayerViewModel::class.java]
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted.value = hasAudioPermission()
    }

    /** 系统删除确认框的结果 */
    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // Kotlin 里 Java 静态常量不保证能被子类直接引用，所以写全限定名
        viewModel.onDeleteResult(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容延伸到状态栏/导航栏底下，由 Compose 的 Scaffold 负责避开
        WindowCompat.setDecorFitsSystemWindows(window, false)
        permissionGranted.value = hasAudioPermission()

        setContent {
            YinxiaTheme {
                YinxiaApp(
                    viewModel = viewModel,
                    permissionGranted = permissionGranted.value,
                    onRequestPermission = { permissionLauncher.launch(requiredPermissions()) },
                    onOpenAppSettings = ::openAppSettings,
                    onDeleteRequest = ::requestDelete,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGranted.value = hasAudioPermission()
    }

    /**
     * Android 11 起，删除别的应用创建的媒体文件必须走系统确认框；
     * 用户在系统弹窗里点确认后才真正删除，App 自己删不掉。
     */
    private fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            // 10 及以下没有这套机制，退回直接删
            viewModel.deleteDirectly(uris)
        }
    }

    private fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        audioPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    /** Android 13 起读音频用的是 READ_MEDIA_AUDIO，老的存储权限不再有效。 */
    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun requiredPermissions(): Array<String> = buildList {
        add(audioPermission())
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                add(Manifest.permission.POST_NOTIFICATIONS)

            // Android 9 及以下删除文件需要写权限
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}

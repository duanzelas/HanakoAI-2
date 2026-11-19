package `fun`.kirari.hanako.platform.capture.projection

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import `fun`.kirari.hanako.core.debug.AppDebugLogStore

/**
 * 只为「系统内录」要一次屏幕录制授权。
 *
 * 内录（`AudioPlaybackCapture`）必须挂在一个 MediaProjection 上，而用 Shizuku 截图的人
 * 手里根本没有投影会话——这就是以前语音模式只能录到麦克风声音的原因。这里补上授权入口：
 * 用户允许后建立「只录音、不建虚拟屏」的会话，直接把结果交给等待中的录音流程。
 *
 * 不复用 [ProjectionPermissionActivity]：那个入口会顺手按启动模式重启悬浮球，
 * 只是补个录音授权不该动现有截图后端。
 */
class AudioProjectionPermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val projectionManager = remember {
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            }
            var launched by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
                deliverResult(result.resultCode, result.data)
                finish()
            }

            LaunchedEffect(Unit) {
                if (launched) return@LaunchedEffect
                launched = true
                // Android 14+ 要求 getMediaProjection 之前已经有 mediaProjection 类型的前台服务
                ContextCompat.startForegroundService(
                    this@AudioProjectionPermissionActivity,
                    Intent(
                        this@AudioProjectionPermissionActivity,
                        MediaProjectionForegroundService::class.java
                    )
                )
                launcher.launch(screenCaptureIntent(projectionManager))
            }
        }
    }

    private fun deliverResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            AppDebugLogStore.i(TAG, "user denied screen capture consent for audio recording")
            AudioProjectionRequest.deliver(false)
            return
        }
        val error = runCatching {
            ProjectionSessionManager.startAudioSession(this, resultCode, data)
        }.exceptionOrNull()
        if (error != null) {
            AppDebugLogStore.e(TAG, "cannot start audio projection session", error)
            AudioProjectionRequest.deliver(false)
            return
        }
        AppDebugLogStore.i(TAG, "audio projection session granted")
        AudioProjectionRequest.deliver(true)
    }

    private companion object {
        const val TAG = "HanakoAudioProjection"

        /**
         * Android 14 起系统会让用户选「整个屏幕 / 单个应用」。内录要的是**整个屏幕**的声音，
         * 选成单个应用就只能录到那个应用，所以直接请求默认屏幕，省掉这个容易选错的选项。
         */
        fun screenCaptureIntent(manager: MediaProjectionManager): Intent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        }
    }
}

/**
 * 「等一次内录授权」的进程内回调桥。
 *
 * 悬浮球是服务，授权弹窗只能是 Activity，回调也就只能靠这个单例接回来。
 * 同一时刻只允许一个挂起请求——重复点按不该叠出第二个弹窗。
 */
internal object AudioProjectionRequest {
    private const val TAG = "HanakoAudioProjection"

    private var pending: ((Boolean) -> Unit)? = null

    @Synchronized
    fun isPending(): Boolean = pending != null

    @Synchronized
    fun await(context: Context, onResult: (Boolean) -> Unit) {
        if (pending != null) {
            AppDebugLogStore.i(TAG, "consent request already pending, ignore duplicated tap")
            return
        }
        pending = onResult
        runCatching {
            context.startActivity(
                Intent(context, AudioProjectionPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { error ->
            AppDebugLogStore.e(TAG, "cannot launch audio projection permission activity", error)
            deliver(false)
        }
    }

    @Synchronized
    fun deliver(granted: Boolean) {
        val callback = pending ?: return
        pending = null
        runCatching { callback(granted) }
            .onFailure { AppDebugLogStore.e(TAG, "consent callback crashed", it) }
    }

    @Synchronized
    fun cancel() {
        if (pending == null) return
        AppDebugLogStore.i(TAG, "consent request cancelled by user")
        pending = null
    }
}

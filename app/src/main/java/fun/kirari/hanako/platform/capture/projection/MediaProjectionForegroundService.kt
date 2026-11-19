package `fun`.kirari.hanako.platform.capture.projection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import `fun`.kirari.hanako.R
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

class MediaProjectionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ProjectionSessionManager.stop()
                stopSelf()
            }

            ACTION_START_SESSION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != Int.MIN_VALUE && resultData != null) {
                    ProjectionSessionManager.startSession(this, resultCode, resultData)
                }
            }

            ACTION_START_AUDIO_SESSION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != Int.MIN_VALUE && resultData != null) {
                    runCatching {
                        ProjectionSessionManager.startAudioSession(this, resultCode, resultData)
                    }.onFailure { error ->
                        AppDebugLogStore.e("HanakoProjection", "start audio session failed", error)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ProjectionSessionManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, "fun.kirari.hanako.app.MainActivity"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hanako Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "fun.kirari.hanako.capture.STOP"
        const val ACTION_START_SESSION = "fun.kirari.hanako.capture.START_SESSION"

        /** 只为系统内录（AudioPlaybackCapture）建立一个 MediaProjection，不创建虚拟屏。 */
        const val ACTION_START_AUDIO_SESSION = "fun.kirari.hanako.capture.START_AUDIO_SESSION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 2001
    }
}

object ProjectionSessionManager {
    private const val TAG = "HanakoProjection"

    /** 屏幕镜像（VirtualDisplay）是否就绪。截图后端用它判断能不能直接取帧。 */
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive

    /** 是否持有一个只用于录音的投影会话。 */
    private val _audioSessionActive = MutableStateFlow(false)
    val audioSessionActive: StateFlow<Boolean> = _audioSessionActive

    @Volatile
    private var mediaProjection: MediaProjection? = null
    @Volatile
    private var imageReader: ImageReader? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var callback: MediaProjection.Callback? = null

    /**
     * 当前会话是否「只录音」。
     *
     * 内录结束后只允许回收这种会话：屏幕镜像会话是用户显式开的，录音顺手关掉会把截图也一起干掉。
     */
    @Volatile
    private var audioOnly: Boolean = false

    @Volatile
    private var width: Int = 0
    @Volatile
    private var height: Int = 0

    fun startSession(context: Context, resultCode: Int, data: Intent) {
        stop()

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
            ?: error("无法创建屏幕捕捉会话")
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        width = metrics.widthPixels
        height = metrics.heightPixels
        val density = metrics.densityDpi
        val localReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val localCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }
        projection.registerCallback(localCallback, Handler(Looper.getMainLooper()))
        val localDisplay = projection.createVirtualDisplay(
            "hanako-live-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            localReader.surface,
            null,
            null
        )

        mediaProjection = projection
        imageReader = localReader
        virtualDisplay = localDisplay
        callback = localCallback
        audioOnly = false
        _audioSessionActive.value = false
        _sessionActive.value = true
    }

    /**
     * 只建一个「录音用」的投影会话。
     *
     * `AudioPlaybackCapture` 必须挂在一个有效的 MediaProjection 上，但它并不需要虚拟屏——
     * 只要授权令牌换成投影对象即可。这样用 Shizuku 截图的人也能内录，而且不会为了录音
     * 白白多开一路屏幕镜像。
     *
     * 已经有会话（屏幕镜像或上一次内录）时直接复用：再 `getMediaProjection` 一次会把
     * 正在用的会话顶掉，截图会突然失效。
     */
    fun startAudioSession(context: Context, resultCode: Int, data: Intent) {
        if (mediaProjection != null) {
            AppDebugLogStore.i(TAG, "reuse existing projection for audio capture, audioOnly=$audioOnly")
            return
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
            ?: error("无法创建音频录制会话")
        val localCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }
        projection.registerCallback(localCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection
        callback = localCallback
        audioOnly = true
        width = 0
        height = 0
        _sessionActive.value = false
        _audioSessionActive.value = true
        AppDebugLogStore.i(TAG, "audio-only projection session started")
    }

    fun hasActiveSession(): Boolean {
        return mediaProjection != null && imageReader != null && virtualDisplay != null
    }

    /**
     * 暴露当前会话的 MediaProjection，供 AudioPlaybackCapture 复用。
     */
    fun currentProjection(): MediaProjection? = mediaProjection

    /**
     * 回收「只录音」的会话。
     *
     * @return true 表示确实回收了一个音频专用会话，调用方可以顺手把前台服务停掉。
     */
    fun releaseAudioOnlySession(): Boolean {
        if (mediaProjection == null || !audioOnly) return false
        AppDebugLogStore.i(TAG, "releasing audio-only projection session")
        stop()
        return true
    }

    fun captureLatestBitmap(): Bitmap {
        val reader = imageReader ?: error("屏幕捕捉会话未初始化")
        val image = waitForImage(reader) ?: error("未获取到屏幕内容")
        image.use {
            val plane = it.planes.first()
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        mediaProjection?.let { projection ->
            callback?.let { runCatching { projection.unregisterCallback(it) } }
            runCatching { projection.stop() }
        }
        callback = null
        mediaProjection = null
        audioOnly = false
        width = 0
        height = 0
        _sessionActive.value = false
        _audioSessionActive.value = false
    }

    private fun waitForImage(reader: ImageReader): Image? {
        repeat(15) {
            reader.acquireLatestImage()?.let { return it }
            Thread.sleep(40)
        }
        return null
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}

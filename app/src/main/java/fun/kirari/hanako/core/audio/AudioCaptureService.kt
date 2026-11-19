package `fun`.kirari.hanako.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import `fun`.kirari.hanako.R

/**
 * 录音期间的前台服务。
 *
 * 存在的唯一目的：Android 14+ 要求后台访问麦克风必须有一个 `microphone` 类型的前台服务，
 * 否则 `AudioRecord` 会拿到静音或直接抛异常。录音本身在 [AudioRecorderHub]，这里只持证。
 *
 * 不常驻——只在「开始录音 → 停止录音」之间存活，避免多一条常驻通知。
 */
class AudioCaptureService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.audio_capture_notification_title))
            .setContentText(getString(R.string.audio_capture_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.audio_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.audio_capture_channel_description)
            }
        )
    }

    companion object {
        /** 服务是否真的起来了。录音前要等它，否则 Android 14+ 上麦克风会被判为后台访问。 */
        @Volatile
        var running: Boolean = false
            private set

        private const val CHANNEL_ID = "audio_capture"
        private const val NOTIFICATION_ID = 1003
        const val ACTION_STOP = "fun.kirari.hanako.audio.STOP"

        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, AudioCaptureService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AudioCaptureService::class.java)) }
        }
    }
}

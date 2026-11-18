package `fun`.kirari.hanako.platform.capture.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val LOG_TAG = "HanakoShizuku"

/**
 * secure display 捕获的长边上限。1600px 对 OCR 与多模态都足够，
 * 同时让单帧 JPEG 稳定落在 Binder 事务预算内。
 */
private const val SECURE_CAPTURE_MAX_LONG_EDGE = 1600

/** 采样判定整帧是否全黑：secure policy 被忽略时 WMS 会返回黑图，不能当成成功。 */
private fun looksAllBlack(bytes: ByteArray): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return true
    var sample = 1
    while (bounds.outWidth / sample > 96) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return true
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.isNotEmpty() && pixels.all { (it and 0x00FFFFFF) == 0 }
    } finally {
        bitmap.recycle()
    }
}

internal object ShizukuShellScreencap : BinaryScreencap {
    override suspend fun captureImage(): ByteArray {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(LOG_TAG, "capture request started")

        // 1) 优先走 secure display 捕获：可以拿到 FLAG_SECURE 页面的真实内容
        var service = ShizukuUserServiceClient.acquire()
        val secure = runCatching { service.captureSecureDisplay(SECURE_CAPTURE_MAX_LONG_EDGE) }
            .onFailure { error ->
                if (error is RemoteException) {
                    ShizukuUserServiceClient.invalidate()
                    Log.w(LOG_TAG, "user service died during secure capture, will rebind")
                } else {
                    Log.w(LOG_TAG, "secure display capture unavailable: ${error.javaClass.simpleName}")
                }
            }
            .getOrNull()

        if (secure != null && secure.isNotEmpty()) {
            if (!looksAllBlack(secure)) {
                Log.i(
                    LOG_TAG,
                    "secure display capture ok bytes=${secure.size} " +
                        "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                )
                return secure
            }
            Log.w(LOG_TAG, "secure display capture returned an all-black frame, falling back to screencap")
        }

        // 2) 回退到原有 screencap（普通页面、老系统、权限被拒时都走这里）
        service = ShizukuUserServiceClient.acquire()
        return runCatching {
            service.exec(arrayOf("sh", "-c", "screencap -p"))
                .also { png ->
                    Log.i(
                        LOG_TAG,
                        "screencap request completed bytes=${png.size} " +
                            "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
        }.getOrElse { error ->
            if (error is RemoteException) {
                ShizukuUserServiceClient.invalidate()
            }
            Log.e(LOG_TAG, "screencap request failed", error)
            throw error
        }
    }
}

internal object ShizukuUserServiceClient {
    @Volatile
    private var remoteService: IShizukuShellService? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("fun.kirari.hanako", ShizukuShellService::class.java.name)
    )
        .processNameSuffix("capture")
        .tag("hanako-shizuku-shell")
        // 接口新增 captureSecureDisplay，版本号必须自增，否则 Shizuku 会复用旧的服务进程
        .version(3)
        .daemon(false)
        .debuggable(false)

    suspend fun acquire(): IShizukuShellService {
        remoteService
            ?.takeIf { it.asBinder().isBinderAlive }
            ?.let { return it }
        remoteService = null
        check(Shizuku.pingBinder()) { "Shizuku 未运行，请先启动 Shizuku 服务。" }
        Log.i(LOG_TAG, "binding user service")

        return try {
            withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val callbackConnection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            Log.i(LOG_TAG, "user service connected name=$name alive=${service.isBinderAlive}")
                            val remote = IShizukuShellService.Stub.asInterface(service)
                            remoteService = remote
                            activeConnection = this
                            if (continuation.isActive) {
                                continuation.resume(remote)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            Log.w(LOG_TAG, "user service disconnected name=$name")
                            remoteService = null
                            if (activeConnection === this) activeConnection = null
                        }
                    }
                    runCatching {
                        Shizuku.bindUserService(userServiceArgs, callbackConnection)
                        Log.i(LOG_TAG, "bindUserService dispatched")
                    }.onFailure { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    continuation.invokeOnCancellation {
                        runCatching {
                            Shizuku.unbindUserService(userServiceArgs, callbackConnection, false)
                        }
                    }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("连接 Shizuku 截图服务超时，请重启 Shizuku 后重试。", error)
        }
    }

    fun invalidate() {
        remoteService = null
        activeConnection?.let { connection ->
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
        }
        activeConnection = null
    }

    private const val BIND_TIMEOUT_MS = 10_000L
}

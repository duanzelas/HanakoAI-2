package `fun`.kirari.hanako.platform.capture.shizuku

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private const val SECURE_TAG = "HanakoSecureCap"

/**
 * 以 shell 身份（Shizuku UserService，uid 2000）直接向 WindowManagerService 申请整屏捕获，
 * 并显式要求把 FLAG_SECURE 的 secure 层一起合成。
 *
 * 为什么必须走这条路：
 * 普通 App 的 MediaProjection / scrcpy / 录屏 都只能拿到「非安全 Display」的画面，
 * 而 secure 层只有在目标 Display 带 VIRTUAL_DISPLAY_FLAG_SECURE 时才会被合成，
 * 创建这种 Display 需要 CAPTURE_SECURE_VIDEO_OUTPUT（signature|privileged，第三方应用拿不到）。
 * shell uid 持有该权限，所以由 UserService 直接调 WMS.captureDisplay 才能拿到真实画面；
 * `screencap` 不带 secure policy，遇到 FLAG_SECURE 页面只会输出黑图。
 *
 * 版本差异（这是本文件按名字探测的原因）：
 * - Android 14 ~ 16.0：android.window.ScreenCapture + CaptureArgs.mCaptureSecureLayers(boolean)
 * - Android 16.1+    ：android.window.ScreenCaptureInternal + mSecureContentPolicy(int, 1 = 允许捕获)
 * 两种形态都尝试，避免系统再次改名时整条链路编译期就废掉。
 *
 * 注意：该能力会绕过其他应用的截屏保护，只能在用户自主授权、且不违反当地法律与目标应用
 * 服务条款的前提下使用；Google Play 政策明确禁止此类功能，因此只适用于侧载分发。
 */
internal object SecureDisplayCapture {

    /** Binder 单次事务上限约 1MB，留出余量后按 800KB 控制单帧体积。 */
    private const val MAX_PAYLOAD_BYTES = 800 * 1024
    private const val JPEG_QUALITY = 92
    private const val MIN_JPEG_QUALITY = 60

    /**
     * 捕获整屏并编码为 JPEG。
     *
     * @param maxLongEdge 长边上限（px），<=0 表示不限制，只在超出体积预算时才等比缩小。
     * @return JPEG 字节；任何一步失败都返回 null，由调用方回退到 screencap。
     */
    fun captureJpeg(maxLongEdge: Int = 0): ByteArray? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        return runCatching { captureInternal(maxLongEdge) }
            .onFailure { error ->
                Log.e(SECURE_TAG, "secure capture failed: ${error.javaClass.name}: ${error.message}", error)
            }
            .getOrNull()
            ?.also { bytes ->
                Log.i(
                    SECURE_TAG,
                    "secure capture ok bytes=${bytes.size} " +
                        "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                )
            }
    }

    private fun captureInternal(maxLongEdge: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.w(SECURE_TAG, "secure capture needs API 34+, sdk=${Build.VERSION.SDK_INT}")
            return null
        }
        val api = CaptureApi.load() ?: run {
            Log.w(SECURE_TAG, "no compatible ScreenCapture API on sdk=${Build.VERSION.SDK_INT}")
            return null
        }
        val args = api.buildArgs() ?: return null
        val listener = api.newSyncListener() ?: run {
            Log.w(SECURE_TAG, "createSyncCaptureListener unavailable on ${api.baseClassName}")
            return null
        }
        val wms = windowManagerService() ?: run {
            Log.w(SECURE_TAG, "WindowManagerGlobal.getWindowManagerService() unavailable")
            return null
        }
        val invocation = api.findCaptureMethod(wms, listener) ?: run {
            Log.w(SECURE_TAG, "captureDisplay not found on ${wms.javaClass.name}")
            return null
        }
        invocation.method.isAccessible = true
        if (invocation.arity == 3) {
            invocation.method.invoke(wms, 0, args, listener)
        } else {
            invocation.method.invoke(wms, args, listener)
        }
        val buffer = api.readBuffer(listener) ?: run {
            Log.w(SECURE_TAG, "capture returned no ScreenshotHardwareBuffer")
            return null
        }
        val bitmap = api.asBitmap(buffer) ?: run {
            Log.w(SECURE_TAG, "ScreenshotHardwareBuffer.asBitmap() returned null")
            return null
        }
        return encode(bitmap, maxLongEdge)
    }

    private fun windowManagerService(): Any? = runCatching {
        Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null)
    }.getOrNull()

    private fun encode(source: Bitmap, maxLongEdge: Int): ByteArray? {
        var working: Bitmap = source
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            working = source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
        var quality = JPEG_QUALITY
        var limit = if (maxLongEdge > 0) maxLongEdge else maxOf(working.width, working.height)
        repeat(4) {
            val scaled = scaleToLongEdge(working, limit)
            val bytes = compressToJpeg(scaled, quality)
            if (scaled !== working) scaled.recycle()
            if (bytes != null && bytes.size <= MAX_PAYLOAD_BYTES) return bytes
            Log.w(SECURE_TAG, "payload too large (${bytes?.size ?: 0} B), retry q=$quality limit=$limit")
            quality = (quality - 8).coerceAtLeast(MIN_JPEG_QUALITY)
            limit = (limit * 4 / 5).coerceAtLeast(320)
        }
        return null
    }

    private fun scaleToLongEdge(bitmap: Bitmap, limit: Int): Bitmap {
        if (limit <= 0) return bitmap
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= limit) return bitmap
        val ratio = limit.toFloat() / longEdge
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val ok = runCatching {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }.getOrDefault(false)
        return if (ok) output.toByteArray() else null
    }

    private data class CaptureInvocation(val method: Method, val arity: Int)

    /**
     * 按类名探测当前系统可用的截屏 API，兼容 android.window.ScreenCapture 与
     * android.window.ScreenCaptureInternal 两种命名。
     */
    private class CaptureApi(
        val baseClassName: String,
        private val baseClass: Class<*>,
        private val argsClass: Class<*>,
        private val builderClass: Class<*>
    ) {
        fun buildArgs(): Any? {
            val builder = runCatching {
                builderClass.getDeclaredConstructor().newInstance()
            }.getOrNull() ?: run {
                Log.w(SECURE_TAG, "cannot instantiate ${builderClass.name}")
                return null
            }
            // Android 14 ~ 16.0：builder 上就有布尔开关
            var applied = runCatching {
                builderClass
                    .getMethod("setCaptureSecureLayers", java.lang.Boolean.TYPE)
                    .invoke(builder, true)
                true
            }.getOrDefault(false)

            val args = runCatching {
                builderClass.getMethod("build").invoke(builder)
            }.getOrNull() ?: run {
                Log.w(SECURE_TAG, "${builderClass.name}.build() failed")
                return null
            }

            // Android 16.1+：整型策略，1 = 允许捕获 secure 内容
            applied = setIntField(args, "mSecureContentPolicy", 1) || applied
            applied = setIntField(args, "mProtectedContentPolicy", 1) || applied
            // Android 14 ~ 16.0 兜底：直接改布尔字段
            applied = setBooleanField(args, "mCaptureSecureLayers", true) || applied

            if (!applied) {
                Log.w(SECURE_TAG, "no secure-layer switch found on ${argsClass.name}")
                return null
            }
            return args
        }

        fun newSyncListener(): Any? {
            val candidates = mutableListOf(baseClass)
            runCatching { Class.forName("$baseClassName\$SynchronousScreenCaptureListener") }
                .getOrNull()
                ?.let { candidates.add(it) }
            for (cls in candidates) {
                val created = runCatching {
                    val factory = cls.getMethod("createSyncCaptureListener")
                    if (!Modifier.isStatic(factory.modifiers)) null else factory.invoke(null)
                }.getOrNull()
                if (created != null) return created
            }
            return runCatching {
                Class.forName("$baseClassName\$SynchronousScreenCaptureListener")
                    .getDeclaredConstructor()
                    .apply { isAccessible = true }
                    .newInstance()
            }.getOrNull()
        }

        fun findCaptureMethod(wms: Any, listener: Any): CaptureInvocation? {
            val listenerType = listener.javaClass
            val methods = wms.javaClass.methods.asSequence() +
                wms.javaClass.declaredMethods.asSequence()
            for (method in methods) {
                if (method.name != "captureDisplay") continue
                val params = method.parameterTypes
                if (params.size == 3 &&
                    params[0] == Integer.TYPE &&
                    params[1].isAssignableFrom(argsClass) &&
                    params[2].isAssignableFrom(listenerType)
                ) {
                    return CaptureInvocation(method, 3)
                }
                if (params.size == 2 &&
                    params[0].isAssignableFrom(argsClass) &&
                    params[1].isAssignableFrom(listenerType)
                ) {
                    return CaptureInvocation(method, 2)
                }
            }
            return null
        }

        fun readBuffer(listener: Any): Any? = runCatching {
            listener.javaClass.getMethod("getBuffer").invoke(listener)
        }.getOrNull()

        fun asBitmap(buffer: Any): Bitmap? = runCatching {
            buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? Bitmap
        }.getOrNull()

        private fun setIntField(target: Any, name: String, value: Int): Boolean = runCatching {
            val field = argsClass.getDeclaredField(name)
            field.isAccessible = true
            if (field.type != Integer.TYPE) {
                false
            } else {
                field.setInt(target, value)
                true
            }
        }.getOrDefault(false)

        private fun setBooleanField(target: Any, name: String, value: Boolean): Boolean = runCatching {
            val field = argsClass.getDeclaredField(name)
            field.isAccessible = true
            if (field.type != java.lang.Boolean.TYPE) {
                false
            } else {
                field.setBoolean(target, value)
                true
            }
        }.getOrDefault(false)

        companion object {
            private val CANDIDATES = listOf(
                "android.window.ScreenCaptureInternal",
                "android.window.ScreenCapture"
            )

            fun load(): CaptureApi? {
                for (name in CANDIDATES) {
                    val api = runCatching {
                        CaptureApi(
                            baseClassName = name,
                            baseClass = Class.forName(name),
                            argsClass = Class.forName("$name\$CaptureArgs"),
                            builderClass = Class.forName("$name\$CaptureArgs\$Builder")
                        )
                    }.onFailure { error ->
                        Log.d(SECURE_TAG, "probe $name failed: ${error.javaClass.simpleName}")
                    }.getOrNull()
                    if (api != null) {
                        Log.i(SECURE_TAG, "using capture api $name")
                        return api
                    }
                }
                return null
            }
        }
    }
}

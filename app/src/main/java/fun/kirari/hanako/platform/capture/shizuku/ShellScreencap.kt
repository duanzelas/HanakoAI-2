package `fun`.kirari.hanako.platform.capture.shizuku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface BinaryScreencap {
    /** 返回整屏截图字节，可能是 PNG（screencap）或 JPEG（secure display 捕获）。 */
    suspend fun captureImage(): ByteArray
}

internal object ShellScreencap {
    suspend fun captureBitmap(source: BinaryScreencap): Bitmap {
        val bytes = withContext(Dispatchers.IO) { source.captureImage() }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("无法解码截图数据（PNG/JPEG）")
    }
}

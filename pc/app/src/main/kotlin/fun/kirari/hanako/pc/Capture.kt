package `fun`.kirari.hanako.pc

import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 截图与剪贴板取图，全部走 AWT，不依赖平台 SDK。 */
object Capture {

    /** 整个虚拟桌面（多屏时是并集），用于铺满框选遮罩。 */
    fun screenBounds(): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        return env.screenDevices.fold(Rectangle()) { acc, device ->
            val b = device.defaultConfiguration.bounds
            if (acc.isEmpty) b else acc.union(b)
        }
    }

    fun capture(region: Region): BufferedImage? = runCatching {
        Robot().createScreenCapture(region.toRectangle())
    }.getOrNull()

    /** 从剪贴板取位图；没有图片时返回 null。 */
    fun clipboardImage(): BufferedImage? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
        image.toBufferedImage()
    }.getOrNull()

    /** 是不是从剪贴板复制的本地文件（图片文件也支持）。 */
    fun clipboardImageFile(): BufferedImage? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return null
        @Suppress("UNCHECKED_CAST")
        val files = clipboard.getData(DataFlavor.javaFileListFlavor) as? List<java.io.File> ?: return null
        val file = files.firstOrNull { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "webp") }
            ?: return null
        if (!file.exists()) return null
        ImageIO.read(file)
    }.getOrNull()

    private fun Image.toBufferedImage(): BufferedImage {
        if (this is BufferedImage) return this
        val result = BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB)
        result.createGraphics().apply {
            drawImage(this@toBufferedImage, 0, 0, null)
            dispose()
        }
        return result
    }

    /** 长边缩到 maxLongEdge 以内再压 JPEG，控制请求体积。 */
    fun toBase64Jpeg(image: BufferedImage, maxLongEdge: Int = 1600): String {
        val scaled = scale(image, maxLongEdge)
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "jpg", out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun scale(image: BufferedImage, maxLongEdge: Int): BufferedImage {
        val longEdge = maxOf(image.width, image.height)
        if (longEdge <= maxLongEdge) return image
        val ratio = maxLongEdge.toDouble() / longEdge
        val w = (image.width * ratio).toInt().coerceAtLeast(1)
        val h = (image.height * ratio).toInt().coerceAtLeast(1)
        val result = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        result.createGraphics().apply {
            setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            drawImage(image, 0, 0, w, h, null)
            dispose()
        }
        return result
    }
}

package `fun`.kirari.hanako.pc

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * 全屏框选遮罩。用 AWT 而不是 Compose 透明窗口，原因是坐标必须与
 * `Robot.createScreenCapture` 的像素坐标严格一致，走 AWT 能避免 DPI 缩放换算误差。
 */
class RegionSelector(private val onResult: (Region?) -> Unit) {

    private var window: JWindow? = null
    private var dispatcher: KeyEventDispatcher? = null
    private var finished = false

    fun show() {
        val bounds = Capture.screenBounds()
        val frame = JWindow()
        window = frame
        frame.setBounds(bounds)
        frame.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        // 逐像素透明；部分环境不支持时退化为半透明遮罩（仍可用，只是看不到底下的实时画面）
        val transparent = runCatching {
            frame.background = Color(0, 0, 0, 0)
            true
        }.getOrDefault(false)

        val panel = SelectorPanel(bounds, transparent) { region ->
            if (!finished) {
                finished = true
                close()
                onResult(region)
            }
        }
        frame.contentPane.add(panel)

        val keyDispatcher = KeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) {
                if (!finished) {
                    finished = true
                    close()
                    onResult(null)
                }
                true
            } else {
                false
            }
        }
        dispatcher = keyDispatcher
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)

        frame.isVisible = true
        frame.toFront()
        SwingUtilities.invokeLater { panel.requestFocusInWindow() }
    }

    private fun close() {
        dispatcher?.let { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) }
        dispatcher = null
        window?.let {
            it.isVisible = false
            it.dispose()
        }
        window = null
    }

    private class SelectorPanel(
        private val screenBounds: Rectangle,
        private val transparent: Boolean,
        private val onDone: (Region?) -> Unit
    ) : JPanel() {

        private var start: Point? = null
        private var current: Point? = null

        init {
            isOpaque = false
            isFocusable = true
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    start = e.point
                    current = e.point
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    val from = start
                    val to = e.point
                    start = null
                    current = null
                    repaint()
                    if (from == null) {
                        onDone(null)
                        return
                    }
                    val rect = normalized(from, to)
                    if (rect.width < 8 || rect.height < 8) {
                        onDone(null)
                        return
                    }
                    // 面板坐标 -> 屏幕绝对坐标
                    onDone(
                        Region(
                            screenBounds.x + rect.x,
                            screenBounds.y + rect.y,
                            rect.width,
                            rect.height
                        )
                    )
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    current = e.point
                    repaint()
                }
            })
        }

        private fun normalized(a: Point, b: Point): Rectangle {
            val x = minOf(a.x, b.x)
            val y = minOf(a.y, b.y)
            return Rectangle(x, y, kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (transparent) Color(0, 0, 0, 96) else Color(0, 0, 0, 170)
                g2.fillRect(0, 0, width, height)

                val from = start
                val to = current
                if (from != null && to != null) {
                    val rect = normalized(from, to)
                    if (transparent) {
                        // 挖空选区，让用户看清框到的是什么
                        g2.composite = AlphaComposite.Clear
                        g2.fillRect(rect.x, rect.y, rect.width, rect.height)
                        g2.composite = AlphaComposite.SrcOver
                    }
                    g2.color = Color(0x2E, 0xA0, 0x43)
                    g2.stroke = BasicStroke(2f)
                    g2.drawRect(rect.x, rect.y, rect.width, rect.height)

                    val label = "${rect.width} × ${rect.height}"
                    g2.font = Font("Segoe UI", Font.PLAIN, 13)
                    val metrics = g2.fontMetrics
                    val pad = 6
                    val labelWidth = metrics.stringWidth(label) + pad * 2
                    val labelHeight = metrics.height + pad
                    val labelY = (rect.y - labelHeight - 4).coerceAtLeast(4)
                    g2.color = Color(0x2E, 0xA0, 0x43)
                    g2.fillRoundRect(rect.x, labelY, labelWidth, labelHeight, 8, 8)
                    g2.color = Color.WHITE
                    g2.drawString(label, rect.x + pad, labelY + metrics.ascent + pad / 2)
                } else {
                    g2.color = Color.WHITE
                    g2.font = Font("Segoe UI", Font.PLAIN, 16)
                    val hint = "拖动框选题目区域 · Esc 取消 · 选完位置会固定下来"
                    val metrics = g2.fontMetrics
                    val x = (width - metrics.stringWidth(hint)) / 2
                    val y = (height - metrics.height) / 2
                    g2.color = Color(0, 0, 0, 140)
                    g2.fillRoundRect(x - 16, y - 12, metrics.stringWidth(hint) + 32, metrics.height + 20, 12, 12)
                    g2.color = Color.WHITE
                    g2.drawString(hint, x, y + metrics.ascent)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}

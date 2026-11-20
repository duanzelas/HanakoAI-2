package `fun`.kirari.hanako.pc

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * 托盘常驻。托盘图标的位图是动态画出来的：
 * 有答案时直接画字母（A / AB / ABD），没有答案时画一个空心 H。
 */
class TrayController(
    private val onCapture: () -> Unit,
    private val onSelect: () -> Unit,
    private val onClipboard: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onExit: () -> Unit,
    private val onRecord: () -> Unit = {}
) {

    private var trayIcon: TrayIcon? = null
    private var available = false
    @Volatile
    private var recording = false
    private var lastLetters = ""
    private var lastTooltip = "HanakoAI-EX PC · 就绪"

    fun install(): Boolean {
        if (!SystemTray.isSupported()) return false
        val tray = SystemTray.getSystemTray()
        val icon = TrayIcon(render(""), "HanakoAI-EX PC · 就绪")
        icon.isImageAutoSize = true
        icon.addActionListener { onOpenSettings() }

        val menu = PopupMenu()
        menu.add(item("按固定位置截图", onCapture))
        menu.add(item("录音作答（开始/结束）", onRecord))
        menu.add(item("重新框选区域", onSelect))
        menu.addSeparator()
        menu.add(item("从剪贴板读图", onClipboard))
        menu.addSeparator()
        menu.add(item("打开设置", onOpenSettings))
        menu.add(item("退出", onExit))
        icon.popupMenu = menu

        val ok = runCatching { tray.add(icon); true }.getOrDefault(false)
        if (ok) {
            trayIcon = icon
            available = true
        }
        return ok
    }

    fun uninstall() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
        available = false
    }

    /** 更新托盘图标与提示；letters 为空时回到默认图标。 */
    fun update(letters: String, tooltip: String) {
        lastLetters = letters
        lastTooltip = tooltip
        val icon = trayIcon ?: return
        SwingUtilities.invokeLater {
            runCatching {
                icon.image = render(letters)
                icon.toolTip = tooltip.take(120)
            }
        }
    }

    /**
     * 录音状态：图标整体变红。
     *
     * 录音时用户往往已经切到别的窗口看题，托盘是唯一能确认「正在录」的地方，
     * 所以红色优先于答案字母，比在提示文字里写一句更直观。
     */
    fun setRecording(value: Boolean) {
        if (recording == value) return
        recording = value
        val icon = trayIcon ?: return
        SwingUtilities.invokeLater {
            runCatching {
                icon.image = render(lastLetters)
                icon.toolTip = if (value) "HanakoAI-EX PC · 录音中" else lastTooltip.take(120)
            }
        }
    }

    fun showMessage(title: String, body: String) {
        val icon = trayIcon ?: return
        SwingUtilities.invokeLater {
            runCatching { icon.displayMessage(title, body.take(200), TrayIcon.MessageType.INFO) }
        }
    }

    val isAvailable: Boolean get() = available

    private fun item(label: String, action: () -> Unit): MenuItem =
        MenuItem(label).apply { addActionListener { action() } }

    /** 64x64 画布，交给系统按 DPI 缩放。 */
    private fun render(letters: String): BufferedImage {
        val size = 64
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (recording) {
                // 红底 + 白色实心圆点，缩到 16px 也能一眼看出在录
                g.color = Color(0xE5, 0x39, 0x35)
                g.fillRoundRect(4, 4, size - 8, size - 8, 22, 22)
                g.color = Color.WHITE
                g.fillOval(23, 23, 18, 18)
                return image
            }

            if (letters.isBlank()) {
                g.color = Color(0x2B, 0x31, 0x38)
                g.fillRoundRect(6, 6, size - 12, size - 12, 20, 20)
                g.color = Color(0xE6, 0xE8, 0xEB)
                g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(24, 20, 24, 44)
                g.drawLine(24, 32, 40, 32)
                g.drawLine(40, 20, 40, 44)
            } else {
                g.color = Color(0x1B, 0x7F, 0x4B)
                g.fillRoundRect(4, 4, size - 8, size - 8, 22, 22)
                g.color = Color.WHITE
                val fontSize = when (letters.length) {
                    1 -> 40
                    2 -> 30
                    3 -> 22
                    else -> 18
                }
                g.font = Font("Segoe UI", Font.BOLD, fontSize)
                val metrics = g.fontMetrics
                val textWidth = metrics.stringWidth(letters)
                val x = (size - textWidth) / 2
                val y = (size - metrics.height) / 2 + metrics.ascent
                g.drawString(letters, x, y)
            }
        } finally {
            g.dispose()
        }
        return image
    }
}

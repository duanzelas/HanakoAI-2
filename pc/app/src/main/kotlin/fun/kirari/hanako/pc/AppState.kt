package `fun`.kirari.hanako.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage

/** 所有可变状态与动作集中在这里，UI 只做渲染，托盘/热键也调同一套方法。 */
class AppState(private val scope: CoroutineScope) {

    var settings by mutableStateOf(PcSettings.load())
        private set

    var letters by mutableStateOf("")
        private set
    var answer by mutableStateOf("")
        private set
    var ocrText by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("就绪")
        private set
    var busy by mutableStateOf(false)
        private set

    /** 悬浮小球是否显示（显示答案字母） */
    var bubbleVisible by mutableStateOf(false)
    /** 主设置窗口是否显示，关闭时缩到托盘 */
    var showMain by mutableStateOf(true)
    /** 正在框选 */
    var selecting by mutableStateOf(false)

    var tray: TrayController? = null
    var hotkeyAvailable by mutableStateOf(false)

    private val solver = Solver()
    private var selector: RegionSelector? = null

    fun updateSettings(transform: (PcSettings) -> PcSettings) {
        settings = transform(settings)
        // 输入框每敲一个字都会走到这里，落盘做个 300ms 去抖
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            settings.save()
        }
    }

    private var saveJob: kotlinx.coroutines.Job? = null

    /** 按固定区域截图；还没有区域时先引导框选。 */
    fun captureNow() {
        val region = settings.region
        if (region == null) {
            status = "还没有固定区域，先框选一次"
            startSelect()
            return
        }
        if (busy) return
        runCapture(region)
    }

    fun startSelect() {
        if (selecting || busy) return
        selecting = true
        showMain = false
        val s = RegionSelector { region ->
            selecting = false
            selector = null
            if (region != null) {
                updateSettings { it.copy(region = region) }
                status = "已固定区域 ${region.width}×${region.height} @ (${region.x}, ${region.y})"
                // 等遮罩彻底消失再截，否则会把绿色选择框一起截进去
                scope.launch {
                    delay(220)
                    runCapture(region)
                }
            } else {
                status = "已取消框选"
                showMain = true
            }
        }
        selector = s
        s.show()
    }

    fun captureClipboard() {
        if (busy) return
        status = "读取剪贴板…"
        runCapture(region = null) {
            Capture.clipboardImage() ?: Capture.clipboardImageFile()
        }
    }

    fun copyAnswer() {
        if (answer.isBlank()) return
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(answer), null)
            status = "答案已复制到剪贴板"
        }.onFailure { status = "复制失败：${it.message}" }
    }

    private fun runCapture(region: Region?, provider: (() -> BufferedImage?)? = null) {
        if (busy) return
        scope.launch {
            busy = true
            bubbleVisible = false
            try {
                status = "截图中…"
                val image = provider?.invoke() ?: region?.let { Capture.capture(it) }
                if (image == null) {
                    status = "没拿到图像：截图失败或剪贴板里没有图片"
                    return@launch
                }
                val result = solver.solve(settings, image) { step -> status = step }
                answer = result.answer
                letters = result.letters
                ocrText = result.ocrText
                status = when {
                    result.letters.isNotEmpty() -> "答案 ${result.letters}"
                    result.answer.isBlank() -> "模型没有返回内容"
                    else -> "已回答（未识别出选项字母）"
                }
                bubbleVisible = true
                tray?.update(
                    result.letters.ifBlank { "✓" },
                    buildString {
                        append(if (result.letters.isEmpty()) "已回答" else "答案 ${result.letters}")
                        append(" · ")
                        append(result.answer.replace('\n', ' ').take(70))
                    }
                )
            } catch (t: Throwable) {
                status = "失败：${t.message ?: t::class.simpleName}"
                tray?.update("!", "失败：${t.message ?: "未知错误"}")
            } finally {
                busy = false
            }
        }
    }
}

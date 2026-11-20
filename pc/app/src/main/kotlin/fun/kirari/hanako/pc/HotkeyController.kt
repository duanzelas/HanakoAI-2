package `fun`.kirari.hanako.pc

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 全局热键：
 *   Ctrl+Alt+S  按固定区域截图并作答
 *   Ctrl+Alt+R  重新框选区域
 *   Ctrl+Alt+V  开始/结束录音作答（语音+画面模式）
 * 回调在 JNativeHook 的原生线程上触发，内部只做状态更新与协程投递，不直接碰 UI。
 */
class HotkeyController(
    private val onCapture: () -> Unit,
    private val onSelect: () -> Unit,
    private val onRecord: () -> Unit = {}
) : NativeKeyListener {

    private var installed = false

    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            // jnativehook 默认用 INFO 级别刷屏，关掉
            Logger.getLogger(GlobalScreen::class.java.packageName).apply {
                level = Level.WARNING
                useParentHandlers = false
            }
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
            installed = true
            true
        }.getOrElse { false }
    }

    fun uninstall() {
        if (!installed) return
        runCatching {
            GlobalScreen.removeNativeKeyListener(this)
            GlobalScreen.unregisterNativeHook()
        }
        installed = false
    }

    val isAvailable: Boolean get() = installed

    override fun nativeKeyPressed(event: NativeKeyEvent) {
        val modifiers = event.modifiers
        val ctrl = modifiers and NativeKeyEvent.CTRL_MASK != 0
        val alt = modifiers and NativeKeyEvent.ALT_MASK != 0
        if (!ctrl || !alt) return
        when (event.keyCode) {
            NativeKeyEvent.VC_S -> onCapture()
            NativeKeyEvent.VC_R -> onSelect()
            NativeKeyEvent.VC_V -> onRecord()
        }
    }

    override fun nativeKeyReleased(event: NativeKeyEvent) = Unit

    override fun nativeKeyTyped(event: NativeKeyEvent) = Unit
}

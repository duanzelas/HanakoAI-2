package `fun`.kirari.hanako.pc

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 全局快捷键。
 *
 * 键位不写死在这里，每次按键都去读 [settingsProvider] 的当前值，所以在设置里改完
 * 立刻生效，不用重启。
 *
 * 同一个监听同时负责「捕获新快捷键」：设置界面点「更改」后调用 [captureNext]，
 * 下一次按下的键（含当时的修饰键）直接就是这个组合的最终答案。用同一条监听而不是
 * Compose 的 onKeyEvent，一是避免两套键码互相映射出错，二是捕获时全局动作自动停摆
 * （见 [nativeKeyPressed] 的分支），不会出现「设快捷键的过程中顺手截了一张图」。
 *
 * 回调在 jnativehook 的原生线程上触发，实现里只做状态更新与协程投递，不碰 UI。
 */
class HotkeyController(
    private val settingsProvider: () -> PcSettings,
    private val onCapture: () -> Unit,
    private val onSelect: () -> Unit,
    private val onRecord: () -> Unit
) : NativeKeyListener {

    @Volatile
    private var installed = false

    @Volatile
    private var captureCallback: ((HotkeySpec?) -> Unit)? = null

    /** 按住不放时系统会连续发 pressed，这里记录已按下的键，松手才清除。 */
    private val pressed = ConcurrentHashMap.newKeySet<Int>()

    val isAvailable: Boolean get() = installed

    val isCapturing: Boolean get() = captureCallback != null

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

    /**
     * 进入捕获模式：下一个按下的键（含修饰键）回调给 [callback]；按 Esc 回调 null。
     *
     * 捕获期间所有全局动作都不触发，所以可以放心在设置窗口里设成任何组合。
     */
    fun captureNext(callback: (HotkeySpec?) -> Unit) {
        pressed.clear()
        captureCallback = callback
    }

    fun cancelCapture() {
        captureCallback = null
    }

    override fun nativeKeyPressed(event: NativeKeyEvent) {
        if (!installed) return
        // 修饰键自身按下不算一次完整组合，等主键
        if (isModifier(event.keyCode)) return
        // 系统自动重复：同一次按住只认第一次
        if (!pressed.add(event.keyCode)) return

        val capture = captureCallback
        if (capture != null) {
            captureCallback = null
            if (event.keyCode == NativeKeyEvent.VC_ESCAPE) {
                capture(null)
            } else {
                capture(HotkeySpec.of(event))
            }
            return
        }

        val settings = settingsProvider()
        when {
            settings.hotkeyCapture.matches(event) -> onCapture()
            settings.hotkeyRecord.matches(event) -> onRecord()
            settings.hotkeySelect.matches(event) -> onSelect()
        }
    }

    override fun nativeKeyReleased(event: NativeKeyEvent) {
        pressed.remove(event.keyCode)
    }

    override fun nativeKeyTyped(event: NativeKeyEvent) = Unit

    private fun isModifier(keyCode: Int): Boolean = keyCode == NativeKeyEvent.VC_CONTROL ||
        keyCode == NativeKeyEvent.VC_ALT ||
        keyCode == NativeKeyEvent.VC_SHIFT ||
        keyCode == NativeKeyEvent.VC_META
}

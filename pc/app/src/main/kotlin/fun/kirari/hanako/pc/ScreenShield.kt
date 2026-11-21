package `fun`.kirari.hanako.pc

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * 防录屏 / 防截屏（Windows）。
 *
 * 做法是 Win32 的 `SetWindowDisplayAffinity`：把窗口标记成「不参与捕获」，
 * DWM 在合成捕获画面时会跳过它。实测对 PrintScreen、Xbox Game Bar、OBS 的
 * 「显示器捕获 / 窗口捕获」、以及绝大多数走 DWM 的截屏工具有效——它们看到的是
 * 一块空白（或被移除后的背景），而不是窗口内容。
 *
 * 两个等级：
 * - `WDA_EXCLUDEFROMCAPTURE`（0x11，Win10 2004 / build 19041 起）：窗口从捕获里彻底消失；
 * - `WDA_MONITOR`（0x01，Win7 起）：老系统上退而求其次，窗口区域在捕获里变成黑色。
 *
 * 边界也要说清楚（设置界面里有同样的说明）：
 * - 只在 Windows 上有效；
 * - 手机拍屏、采集卡、HDMI 环出这类「物理截取」无法防御；
 * - 开启后你自己截图也看不到本程序窗口，这是同一个机制的两面。
 *
 * 窗口句柄用标题找：Compose Desktop 没有暴露 HWND，而本程序的三个窗口标题都是由
 * 常量拼出来的、进程内唯一，`FindWindow` 加上「必须属于本进程」的校验足够稳，
 * 也避免了反射 JDK 内部字段（那条路要 `--add-opens`，打包后更脆）。
 */
object ScreenShield {

    private const val WDA_NONE = 0x00000000
    private const val WDA_MONITOR = 0x00000001
    private const val WDA_EXCLUDEFROMCAPTURE = 0x00000011

    enum class Result {
        /** 已按「从捕获中排除」生效。 */
        EXCLUDED,

        /** 老系统上退化成「捕获里显示为黑块」。 */
        BLACKED_OUT,

        /** 已关闭（恢复正常显示）。 */
        DISABLED,

        /** 非 Windows，或拿不到 user32。 */
        UNSUPPORTED,

        /** 没找到窗口（标题对不上 / 窗口还没建好）。 */
        WINDOW_NOT_FOUND,

        /** 调用了但系统拒绝。 */
        FAILED;

        val isProtected: Boolean get() = this == EXCLUDED || this == BLACKED_OUT

        val label: String
            get() = when (this) {
                EXCLUDED -> "已生效（截屏/录屏中看不到本窗口）"
                BLACKED_OUT -> "已生效（旧系统：截屏中窗口显示为黑块）"
                DISABLED -> "已关闭"
                UNSUPPORTED -> "当前系统不支持（仅 Windows 10 2004 及以上）"
                WINDOW_NOT_FOUND -> "窗口未找到，重新打开窗口后重试"
                FAILED -> "设置失败：系统拒绝了该窗口"
            }
    }

    /** `SetWindowDisplayAffinity` 不在 JNA 5.6 的 User32 映射里，自己补一条。 */
    private interface User32Affinity : StdCallLibrary {
        fun SetWindowDisplayAffinity(hwnd: Pointer, affinity: Int): Boolean

        fun GetWindowDisplayAffinity(hwnd: Pointer, affinity: IntByReference): Boolean

        companion object {
            val INSTANCE: User32Affinity? = runCatching {
                Native.load("user32", User32Affinity::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }.getOrNull()
        }
    }

    private val user32: User32? = runCatching {
        Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }.getOrNull()

    val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * 按窗口标题开关保护。[title] 传 `window.title`，也就是 Win32 上的窗口名。
     */
    fun apply(title: String, enabled: Boolean): Result {
        if (!isWindows) return Result.UNSUPPORTED
        val lib = User32Affinity.INSTANCE ?: return Result.UNSUPPORTED
        val hwnd = findOwnWindow(title) ?: return Result.WINDOW_NOT_FOUND
        val ptr = hwnd.pointer ?: return Result.WINDOW_NOT_FOUND

        if (!enabled) {
            val ok = runCatching { lib.SetWindowDisplayAffinity(ptr, WDA_NONE) }
                .getOrDefault(false)
            return if (ok) Result.DISABLED else Result.FAILED
        }

        val excluded = runCatching { lib.SetWindowDisplayAffinity(ptr, WDA_EXCLUDEFROMCAPTURE) }
            .getOrDefault(false)
        if (excluded) return Result.EXCLUDED

        // Win10 2004 之前的系统不认识 0x11，退回「捕获中变黑」这一档
        val monitored = runCatching { lib.SetWindowDisplayAffinity(ptr, WDA_MONITOR) }
            .getOrDefault(false)
        return if (monitored) Result.BLACKED_OUT else Result.FAILED
    }

    /** 当前窗口实际处于哪一档（用于界面回显，不做任何修改）。 */
    fun query(title: String): Result {
        if (!isWindows) return Result.UNSUPPORTED
        val hwnd = findOwnWindow(title) ?: return Result.WINDOW_NOT_FOUND
        val ptr = hwnd.pointer ?: return Result.WINDOW_NOT_FOUND
        val flag = IntByReference(0)
        val ok = runCatching { User32Affinity.INSTANCE?.GetWindowDisplayAffinity(ptr, flag) }
            .getOrNull() ?: false
        if (!ok) return Result.FAILED
        return when (flag.value) {
            WDA_EXCLUDEFROMCAPTURE -> Result.EXCLUDED
            WDA_MONITOR -> Result.BLACKED_OUT
            else -> Result.DISABLED
        }
    }

    private fun findOwnWindow(title: String): HWND? {
        val u32 = user32 ?: return null
        val hwnd = runCatching { u32.FindWindow(null, title) }.getOrNull() ?: return null
        // 同标题的窗口可能属于另一个实例，务必确认是本进程的
        val pid = IntByReference(0)
        runCatching { u32.GetWindowThreadProcessId(hwnd, pid) }
        val self = runCatching { Kernel32.INSTANCE.GetCurrentProcessId() }.getOrDefault(-1)
        return hwnd.takeIf { pid.value == self }
    }
}

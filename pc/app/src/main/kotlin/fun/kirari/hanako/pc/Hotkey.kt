package `fun`.kirari.hanako.pc

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent

/**
 * 可以绑到全局快捷键上的动作。
 *
 * 只保留「按一下就有意义」的三件事：截一张图、录一段音、换一个区域。
 * 其余动作（重新生成、切模式）需要上下文，不适合做全局热键。
 */
enum class HotkeyAction(val label: String, val hint: String) {
    CAPTURE(
        label = "多模态识屏",
        hint = "按一下截取固定区域，直接交给多模态模型作答"
    ),
    RECORD(
        label = "录音识屏",
        hint = "按一下开始录音，再按一下结束并作答（音源见下方「语音与录音」）"
    ),
    SELECT(
        label = "重新框选区域",
        hint = "拖一个矩形，作为之后每次截图的固定区域"
    )
}

/**
 * 一个快捷键：主键 + 修饰键。
 *
 * 为什么存 jnativehook 的 keyCode 而不是 Compose 的 `Key`：全局热键本来就跑在
 * jnativehook 的原生线程上，设置界面捕获按键也走同一条监听（见 [HotkeyController]），
 * 中间不做任何映射就不会出现「界面显示 A、实际触发 B」的错位。
 *
 * 修饰键要求**精确匹配**：设了 Ctrl+Alt+S 时，按 Ctrl+Alt+Shift+S 不会触发，
 * 否则会和带 Shift 的其他软件快捷键互相打架。
 */
data class HotkeySpec(
    val keyCode: Int = 0,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false
) {
    val isSet: Boolean get() = keyCode != 0

    /** 界面展示用，例如 `Ctrl+Alt+S`。 */
    val label: String
        get() {
            if (!isSet) return "未设置"
            val parts = ArrayList<String>(5)
            if (ctrl) parts += "Ctrl"
            if (alt) parts += "Alt"
            if (shift) parts += "Shift"
            if (meta) parts += "Win"
            parts += keyLabel
            return parts.joinToString("+")
        }

    /** 主键名称；取不到就退化成 `按键 31`，不让界面崩。 */
    val keyLabel: String
        get() = runCatching { NativeKeyEvent.getKeyText(keyCode) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "按键 $keyCode"

    fun matches(event: NativeKeyEvent): Boolean {
        if (!isSet || event.keyCode != keyCode) return false
        val m = event.modifiers
        val hasCtrl = m and NativeKeyEvent.CTRL_MASK != 0
        val hasAlt = m and NativeKeyEvent.ALT_MASK != 0
        val hasShift = m and NativeKeyEvent.SHIFT_MASK != 0
        val hasMeta = m and NativeKeyEvent.META_MASK != 0
        return hasCtrl == ctrl && hasAlt == alt && hasShift == shift && hasMeta == meta
    }

    /** 存进 properties：`keyCode#flags`，flags 是 1=Ctrl 2=Alt 4=Shift 8=Win。 */
    fun encode(): String {
        val flags = (if (ctrl) 1 else 0) or
            (if (alt) 2 else 0) or
            (if (shift) 4 else 0) or
            (if (meta) 8 else 0)
        return "$keyCode#$flags"
    }

    companion object {
        fun of(event: NativeKeyEvent): HotkeySpec {
            val m = event.modifiers
            return HotkeySpec(
                keyCode = event.keyCode,
                ctrl = m and NativeKeyEvent.CTRL_MASK != 0,
                alt = m and NativeKeyEvent.ALT_MASK != 0,
                shift = m and NativeKeyEvent.SHIFT_MASK != 0,
                meta = m and NativeKeyEvent.META_MASK != 0
            )
        }

        fun decode(raw: String?): HotkeySpec? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('#')
            if (parts.size != 2) return null
            val key = parts[0].toIntOrNull() ?: return null
            if (key == 0) return null
            val flags = parts[1].toIntOrNull() ?: 0
            return HotkeySpec(
                keyCode = key,
                ctrl = flags and 1 != 0,
                alt = flags and 2 != 0,
                shift = flags and 4 != 0,
                meta = flags and 8 != 0
            )
        }

        /** 默认：Ctrl+Alt+S 截图、Ctrl+Alt+V 录音、Ctrl+Alt+R 框选。 */
        val DEFAULT_CAPTURE = HotkeySpec(NativeKeyEvent.VC_S, ctrl = true, alt = true)
        val DEFAULT_RECORD = HotkeySpec(NativeKeyEvent.VC_V, ctrl = true, alt = true)
        val DEFAULT_SELECT = HotkeySpec(NativeKeyEvent.VC_R, ctrl = true, alt = true)
    }
}

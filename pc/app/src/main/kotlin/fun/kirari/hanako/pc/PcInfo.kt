package `fun`.kirari.hanako.pc

import java.awt.Desktop
import java.net.URI

/**
 * PC 端的版本与仓库信息，集中在一处。
 *
 * PC 端从这里开始独立编号：桌面版的功能集和 Android 不再一一对应（例如语音录音走麦克风、
 * 没有悬浮球那一套），硬把版本号绑在一起只会互相拖累。
 *
 * 但下面这两处必须一起改，否则窗口标题写着新版、安装包里还是旧版，用户报问题时对不上：
 * 这里的 [VERSION] 与 `pc/app/build.gradle.kts` 的 `packageVersion`。
 */
internal object PcInfo {
    const val VERSION = "0.0.30"
    const val APP_NAME = "HanakoAI-EX PC"

    /** 与 Android 端「设置 → 更多 → GitHub 仓库」指向同一个仓库。 */
    const val REPO_URL = "https://github.com/duanzelas/HanakoAI-EX"
    const val REPO_LABEL = "github.com/duanzelas/HanakoAI-EX"

    /** 用系统默认浏览器打开仓库；容器里没有浏览器时静默失败，不打断使用。 */
    fun openRepo(): Boolean {
        return runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(REPO_URL))
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}

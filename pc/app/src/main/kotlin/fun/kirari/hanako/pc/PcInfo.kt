package `fun`.kirari.hanako.pc

import java.awt.Desktop
import java.net.URI

/**
 * PC 端的版本与仓库信息，集中在一处。
 *
 * 版本号要和 pe 分支的 `app/build.gradle.kts` 对齐：每次同步 Android 侧功能时，
 * 改这里的 [VERSION] 和 `pc/app/build.gradle.kts` 的 `packageVersion`，两处必须一致，
 * 否则窗口标题写着 0.0.28、安装包里是 0.0.20，用户报问题时对不上。
 */
internal object PcInfo {
    const val VERSION = "0.0.28"
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

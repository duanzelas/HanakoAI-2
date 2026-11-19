package `fun`.kirari.hanako.feature.overlay.state

import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import `fun`.kirari.hanako.feature.overlay.state.BubbleState
import `fun`.kirari.hanako.core.data.AppSettings
import `fun`.kirari.hanako.core.model.ProcessingResult
import `fun`.kirari.hanako.core.model.ProcessingRoute
import `fun`.kirari.hanako.platform.capture.CaptureLaunchMode

internal val SheetDockOffset = 88.dp
internal val PanelHandleYOffset = 38.dp
internal const val SheetAnimationDurationMs = 260

internal enum class OverlaySheetMode {
    CROP,
    RESULT
}

internal enum class AutoRunState {
    IDLE,
    RUNNING,
    COMPLETED
}

/**
 * 悬浮球槽位。
 *
 * 开「多悬浮窗」后屏幕上有两颗球：1 号球固定纯视觉，2 号球固定语音+画面。
 * 只有一颗球时所有动作都用 [PRIMARY]，行为和以前一致。
 */
internal enum class BubbleSlot {
    PRIMARY,
    SECONDARY
}

internal data class OverlayUiState(
    val settings: AppSettings = AppSettings(),
    val screenshot: Bitmap? = null,
    val selectedBitmap: Bitmap? = null,
    val liveOcrText: String = "",
    val liveAnswerText: String = "",
    val result: ProcessingResult? = null,
    val error: String? = null,
    val working: Boolean = false,
    val sheetVisible: Boolean = false,
    val sheetMode: OverlaySheetMode = OverlaySheetMode.CROP,
    val launchMode: CaptureLaunchMode = CaptureLaunchMode.NORMAL,
    val autoRunState: AutoRunState = AutoRunState.IDLE,
    val autoCopiedLabel: String? = null,
    val pendingVibrationLetters: String? = null,
    val bubbleState: BubbleState = BubbleState.Idle,
    /** 当前这次动作是哪颗球发起的；状态只画在这颗球上。 */
    val activeSlot: BubbleSlot = BubbleSlot.PRIMARY,
    /**
     * 单次动作的路线覆盖。
     *
     * 两颗球各自绑定固定模式（1 号球纯视觉、2 号球语音+画面），所以在多悬浮窗模式下
     * 不能再读全局 `processingRoute`，否则「视觉球」会按语音链路作答。
     * 为空时回落到全局设置。
     */
    val routeOverride: ProcessingRoute? = null
) {
    /** 面板与结果卡片展示用，和真正跑的处理链路保持一致。 */
    internal val effectiveRoute: ProcessingRoute
        get() = routeOverride ?: settings.processingRoute

    /** 真正传给作答链路的设置（路线可能被悬浮球覆盖）。 */
    internal fun effectiveSettings(): AppSettings {
        val override = routeOverride ?: return settings
        return if (settings.processingRoute == override) settings else settings.copy(processingRoute = override)
    }
}

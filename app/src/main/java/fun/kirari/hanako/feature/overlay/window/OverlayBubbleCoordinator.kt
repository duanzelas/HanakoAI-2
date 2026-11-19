package `fun`.kirari.hanako.feature.overlay.window

import android.content.Context
import android.view.WindowManager
import `fun`.kirari.hanako.core.data.AppSettings
import `fun`.kirari.hanako.core.data.DEFAULT_BUBBLE_DIAMETER_DP
import `fun`.kirari.hanako.core.model.ProcessingRoute
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import `fun`.kirari.hanako.feature.overlay.state.BubbleSlot
import `fun`.kirari.hanako.feature.overlay.state.BubbleState
import `fun`.kirari.hanako.feature.overlay.state.OverlayUiState
import kotlin.math.roundToInt

/**
 * 一颗球的手势落地。
 *
 * 两颗球最终都落到同一个 ViewModel 上，只是入口不同（1 号球纯视觉，2 号球语音+画面）。
 */
internal class BubbleGestureSink(
    val onSingleTap: () -> Unit,
    val onDoubleTap: () -> Unit,
    val onLongPress: () -> Unit,
    val onLongPressHaptic: () -> Unit
)

/**
 * 双悬浮球编排。
 *
 * 只有一颗球时行为和以前完全一致。开启「多悬浮窗」后多出一颗固定角色的球：
 * - 1 号球（primary）：纯视觉，点了就截图问多模态模型；
 * - 2 号球（secondary）：语音+画面，点了就开录，再点结束并作答。
 *
 * 两颗球共用同一个 ViewModel 与状态机，[OverlayUiState.activeSlot] 决定「这次的状态长在哪颗球上」，
 * 另一颗保持 Idle。这样不用复制一整套处理链路，也不会出现两颗球同时转圈的假象。
 */
internal class OverlayBubbleCoordinator(
    private val context: Context,
    private val windowManager: WindowManager,
    private val primarySink: BubbleGestureSink,
    private val secondarySink: BubbleGestureSink,
    private val settingsProvider: () -> AppSettings,
    private val isStaticModeEnabled: () -> Boolean
) {
    private val tag = "HanakoBubbleCoordinator"
    private var primary: BubbleWindowController? = null
    private var secondary: BubbleWindowController? = null

    fun attach() {
        if (primary != null) return
        primary = create(BubbleSlot.PRIMARY)?.also { it.show() }
    }

    /** 按当前设置与状态同步两颗球：需要时创建/销毁 2 号球，并把状态分派给正确的球。 */
    fun update(state: OverlayUiState) {
        val automation = state.settings.automation
        val multiBubble = automation.multiBubbleEnabled

        if (multiBubble && secondary == null) {
            AppDebugLogStore.i(tag, "multi bubble enabled, creating secondary bubble")
            secondary = create(BubbleSlot.SECONDARY)?.also { it.show() }
        } else if (!multiBubble && secondary != null) {
            AppDebugLogStore.i(tag, "multi bubble disabled, removing secondary bubble")
            secondary?.destroy()
            secondary = null
        }

        val owner = if (multiBubble) state.activeSlot else BubbleSlot.PRIMARY
        primary?.update(
            if (owner == BubbleSlot.PRIMARY) state.bubbleState else BubbleState.Idle,
            state.launchMode
        )
        secondary?.update(
            if (owner == BubbleSlot.SECONDARY) state.bubbleState else BubbleState.Idle,
            state.launchMode
        )
    }

    fun centerOf(slot: BubbleSlot): Pair<Int, Int>? {
        return when (slot) {
            BubbleSlot.PRIMARY -> primary?.getBubbleCenter()
            BubbleSlot.SECONDARY -> secondary?.getBubbleCenter() ?: primary?.getBubbleCenter()
        }
    }

    fun destroy() {
        primary?.destroy()
        primary = null
        secondary?.destroy()
        secondary = null
    }

    private fun create(slot: BubbleSlot): BubbleWindowController? {
        val sink = when (slot) {
            BubbleSlot.PRIMARY -> primarySink
            BubbleSlot.SECONDARY -> secondarySink
        }
        val density = context.resources.displayMetrics.density
        val topPx = BASE_Y_DP * density
        // 2 号球默认排在 1 号球下面，避免刚开启时完全重叠、看着像只有一颗
        val y = if (slot == BubbleSlot.SECONDARY) {
            (topPx + (DEFAULT_BUBBLE_DIAMETER_DP + 56f) * density).roundToInt()
        } else {
            topPx.roundToInt()
        }
        return BubbleWindowController(
            context = context,
            windowManager = windowManager,
            onSingleTap = sink.onSingleTap,
            onDoubleTap = sink.onDoubleTap,
            onLongPress = sink.onLongPress,
            onLongPressHaptic = sink.onLongPressHaptic,
            isStaticModeEnabled = isStaticModeEnabled,
            bubbleAppearanceSettings = {
                val automation = settingsProvider().automation
                when (slot) {
                    BubbleSlot.PRIMARY -> automation.bubbleAppearance
                    BubbleSlot.SECONDARY -> automation.secondaryBubbleAppearance
                }
            },
            initialX = (BASE_X_DP * density).roundToInt(),
            initialY = y,
            slotName = if (slot == BubbleSlot.PRIMARY) "primary" else "secondary",
            voiceRole = slot == BubbleSlot.SECONDARY,
            voiceRouteEnabled = {
                settingsProvider().processingRoute == ProcessingRoute.AUDIO_AND_SCREEN
            }
        )
    }

    private companion object {
        const val BASE_X_DP = 32f
        const val BASE_Y_DP = 360f
    }
}

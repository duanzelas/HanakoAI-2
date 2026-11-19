package `fun`.kirari.hanako.feature.overlay.presentation

import `fun`.kirari.hanako.feature.overlay.state.AutoRunState
import `fun`.kirari.hanako.platform.capture.CaptureLaunchMode
import `fun`.kirari.hanako.feature.overlay.state.OverlaySheetMode
import `fun`.kirari.hanako.feature.overlay.state.OverlayUiState

import `fun`.kirari.hanako.feature.overlay.state.BubbleEvent
import `fun`.kirari.hanako.feature.overlay.state.BubbleMenuItem
import `fun`.kirari.hanako.feature.overlay.state.BubbleSlot
import `fun`.kirari.hanako.feature.overlay.state.BubbleState
import `fun`.kirari.hanako.feature.overlay.state.BubbleStateMachine
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class OverlayInteractionController(
    private val uiState: MutableStateFlow<OverlayUiState>,
    private val bubbleStateMachine: BubbleStateMachine,
    private val openCropSheet: () -> Unit,
    private val capturePage: () -> Unit,
    private val sendCaptures: () -> Unit,
    private val exitMultiPageCaptureMode: () -> Unit,
    private val cancelActiveProcessing: () -> Unit,
    private val enterMultiPageCaptureMode: () -> Unit,
    private val isBubbleMenuEnabled: () -> Boolean,
    private val toggleProcessingRoute: () -> Unit,
    private val toggleWebSearch: () -> Unit,
    private val openSettings: () -> Unit,
    /** 语音模式：开始录音（true = 麦克风，false = 系统内录；slot = 哪颗球发起的） */
    private val startVoiceRecording: (Boolean, BubbleSlot) -> Unit,
    private val stopVoiceRecording: () -> Unit,
    private val cancelVoiceRecording: () -> Unit,
    /** 当前工作模式是否为「语音+画面」 */
    private val isVoiceRoute: () -> Boolean
) {
    private val tag = "HanakoOverlayInteraction"

    fun consumeAutoCompletedState() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.d(tag, "consumeAutoCompletedState state=${uiState.value.autoRunState} bubble=${currentState::class.simpleName}")

        if (uiState.value.launchMode == CaptureLaunchMode.AUTO && uiState.value.autoRunState == AutoRunState.COMPLETED) {
            if (currentState is BubbleState.Copied ||
                (uiState.value.settings.automation.staticModeEnabled && currentState is BubbleState.ShowingLetters)
            ) {
                uiState.update { it.copy(autoRunState = AutoRunState.IDLE, pendingVibrationLetters = null) }
                bubbleStateMachine.forceState(BubbleState.Idle)
            } else {
                uiState.update { it.copy(autoRunState = AutoRunState.IDLE, pendingVibrationLetters = null) }
            }
        }
    }

    fun consumePendingVibrationLetters() {
        uiState.update { it.copy(pendingVibrationLetters = null) }
    }

    fun onBubbleTappedAfterLettersShown() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "onBubbleTappedAfterLettersShown launchMode=${uiState.value.launchMode} bubble=${currentState::class.simpleName}")

        if (uiState.value.launchMode == CaptureLaunchMode.AUTO && currentState is BubbleState.ShowingLetters) {
            AppDebugLogStore.i(tag, "onBubbleTappedAfterLettersShown clearing letters and entering pending reset")
            bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
        }
    }

    fun handleSingleTap(slot: BubbleSlot = BubbleSlot.PRIMARY) {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "handleSingleTap slot=$slot state=${currentState::class.simpleName}")

        if (slot == BubbleSlot.SECONDARY) {
            handleSecondarySingleTap(currentState)
            return
        }

        when (currentState) {
            is BubbleState.MenuExpanded -> {
                bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
            }
            is BubbleState.Recording -> {
                // 两段式：第一次点击开始录，第二次点击结束并作答
                stopVoiceRecording()
            }
            is BubbleState.MultiPageCapture -> {
                capturePage()
            }
            is BubbleState.MultiPageCapturing -> {
                AppDebugLogStore.i(tag, "handleSingleTap ignored, capturing in progress")
            }
            is BubbleState.MultiPageCaptureSuccess -> {
                AppDebugLogStore.i(tag, "handleSingleTap ignored, showing capture success")
            }
            is BubbleState.Copied -> {
                bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
            }
            is BubbleState.Error -> {
                bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
            }
            else -> {
                if (isVoiceRoute()) {
                    startVoiceRecording(false, slot)
                } else {
                    openCropSheet()
                }
            }
        }
    }

    /**
     * 2 号球只有一件事：录音。
     *
     * 它有固定角色（语音+画面），所以不做「按当前工作模式分流」那一套；
     * 正在处理时忽略点击，避免把上一题的结果顶掉。
     */
    private fun handleSecondarySingleTap(currentState: BubbleState) {
        when (currentState) {
            is BubbleState.Recording -> stopVoiceRecording()
            is BubbleState.MenuExpanded -> bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
            is BubbleState.Processing -> AppDebugLogStore.i(tag, "secondary tap ignored, still processing")
            is BubbleState.MultiPageCapture,
            is BubbleState.MultiPageCapturing,
            is BubbleState.MultiPageCaptureSuccess ->
                AppDebugLogStore.i(tag, "secondary tap ignored, multi page capture running")
            else -> startVoiceRecording(false, BubbleSlot.SECONDARY)
        }
    }

    fun handleLongPress(slot: BubbleSlot = BubbleSlot.PRIMARY, anchorX: Int = 0, anchorY: Int = 0) {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "handleLongPress slot=$slot state=${currentState::class.simpleName} anchor=($anchorX,$anchorY)")

        if (slot == BubbleSlot.SECONDARY) {
            // 2 号球长按 = 放弃这次录音；没在录音就什么都不做，免得误触进多页截图
            if (currentState is BubbleState.Recording) cancelVoiceRecording()
            return
        }

        when (currentState) {
            is BubbleState.Recording -> {
                // 长按 = 放弃这次录音
                cancelVoiceRecording()
            }
            is BubbleState.MultiPageCapture -> {
                if (currentState.capturedBitmaps.isNotEmpty()) {
                    sendCaptures()
                }
            }
            is BubbleState.MultiPageCapturing -> {
                AppDebugLogStore.i(tag, "handleLongPress ignored, capturing in progress")
            }
            is BubbleState.MultiPageCaptureSuccess -> {
                if (currentState.capturedBitmaps.isNotEmpty()) {
                    sendCaptures()
                }
            }
            is BubbleState.MenuExpanded -> {
                bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
            }
            is BubbleState.Idle, is BubbleState.ShowingLetters,
            is BubbleState.Copied, is BubbleState.Error -> {
                enterMultiPageCaptureMode()
            }
            else -> {
                if (isBubbleMenuEnabled()) {
                    bubbleStateMachine.dispatch(BubbleEvent.LongPress(anchorX, anchorY))
                }
            }
        }
    }

    fun handleDoubleTap(slot: BubbleSlot = BubbleSlot.PRIMARY, anchorX: Int = 0, anchorY: Int = 0) {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "handleDoubleTap slot=$slot state=${currentState::class.simpleName}")

        if (slot == BubbleSlot.SECONDARY) {
            when (currentState) {
                is BubbleState.Recording -> cancelVoiceRecording()
                is BubbleState.MenuExpanded -> bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
                is BubbleState.Processing,
                is BubbleState.MultiPageCapture,
                is BubbleState.MultiPageCapturing,
                is BubbleState.MultiPageCaptureSuccess -> Unit
                else -> if (isBubbleMenuEnabled()) {
                    bubbleStateMachine.dispatch(BubbleEvent.LongPress(anchorX, anchorY))
                }
            }
            return
        }

        when (currentState) {
            is BubbleState.Recording -> {
                cancelVoiceRecording()
            }
            is BubbleState.MultiPageCapture,
            is BubbleState.MultiPageCapturing,
            is BubbleState.MultiPageCaptureSuccess -> {
                exitMultiPageCaptureMode()
            }
            is BubbleState.Processing -> {
                cancelActiveProcessing()
            }
            is BubbleState.Idle,
            is BubbleState.ShowingLetters,
            is BubbleState.Copied,
            is BubbleState.Error -> {
                if (isBubbleMenuEnabled()) {
                    bubbleStateMachine.dispatch(BubbleEvent.LongPress(anchorX, anchorY))
                }
            }
            is BubbleState.MenuExpanded -> {
                bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
            }
            else -> {}
        }
    }

    fun onMenuDismissed() {
        val currentState = bubbleStateMachine.currentState
        if (currentState is BubbleState.MenuExpanded) {
            bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
        }
    }

    fun handleMenuSelect(item: BubbleMenuItem) {
        AppDebugLogStore.i(tag, "handleMenuSelect item=$item")
        when (item) {
            BubbleMenuItem.ToggleRoute -> toggleProcessingRoute()
            BubbleMenuItem.ToggleSearch -> toggleWebSearch()
            BubbleMenuItem.Settings -> openSettings()
            BubbleMenuItem.VoiceRecognition -> {
                // 麦克风是独立入口，但菜单还在退场：这里不直接开录，
                // 由 ViewModel 在 onMenuDismissed() 之后再启动，否则 CloseMenu 会把状态重置回 Idle。
            }
        }
        // 不在此处 dispatch CloseMenu。
        // 状态恢复统一由退场动画结束后的 onMenuDismissed() 处理。
    }
}

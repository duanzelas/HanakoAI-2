package `fun`.kirari.hanako.feature.overlay.presentation

import `fun`.kirari.hanako.feature.overlay.state.AutoRunState
import `fun`.kirari.hanako.platform.capture.CaptureLaunchMode
import `fun`.kirari.hanako.feature.overlay.state.OverlaySheetMode
import `fun`.kirari.hanako.feature.overlay.state.OverlayUiState

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.kirari.hanako.core.model.ProcessingRoute
import `fun`.kirari.hanako.feature.overlay.state.BubbleMenuItem
import `fun`.kirari.hanako.feature.overlay.state.BubbleSlot
import `fun`.kirari.hanako.feature.overlay.state.BubbleState
import `fun`.kirari.hanako.feature.overlay.state.BubbleStateMachine
import `fun`.kirari.hanako.core.data.ModelPurpose
import `fun`.kirari.hanako.core.data.ModelSelection
import `fun`.kirari.hanako.core.data.SettingsRepository
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import `fun`.kirari.hanako.core.network.ProviderModelsApi
import `fun`.kirari.hanako.solve.application.SolveOperations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class OverlayViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val solveOperations: SolveOperations,
    val providerModelsApi: ProviderModelsApi,
    private val mainActivityIntent: (Context) -> Intent
) : ViewModel() {
    private val tag = "HanakoOverlayVM"
    
    // 新的状态机
    val bubbleStateMachine = BubbleStateMachine()

    /** 菜单里点了「麦克风」：等菜单退场动画结束后再真正开录 */
    private var pendingMicrophoneStart = false
    
    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()
    private val multiPageCaptureController = MultiPageCaptureController(
        appContext = appContext,
        scope = viewModelScope,
        uiState = _uiState,
        bubbleStateMachine = bubbleStateMachine
    )
    private val autoProcessingController = AutoProcessingController(
        appContext = appContext,
        scope = viewModelScope,
        uiState = _uiState,
        solveOperations = solveOperations,
        bubbleStateMachine = bubbleStateMachine
    )
    private val answerController = OverlayAnswerController(
        scope = viewModelScope,
        uiState = _uiState,
        solveOperations = solveOperations,
        bubbleStateMachine = bubbleStateMachine
    )
    private val settingsController = OverlaySettingsController(
        scope = viewModelScope,
        repository = repository,
        uiState = _uiState
    )
    private val voiceCaptureController = VoiceCaptureController(
        appContext = appContext,
        scope = viewModelScope,
        uiState = _uiState,
        bubbleStateMachine = bubbleStateMachine,
        onSolve = { bitmaps, transcript, durationMillis, notice ->
            // 自动模式下录音结束只把答案放在球上（字幕/复制），不弹面板
            if (_uiState.value.launchMode == CaptureLaunchMode.AUTO) {
                autoProcessingController.processVoiceAutomation(
                    bitmaps = bitmaps,
                    audioTranscript = transcript,
                    audioDurationMillis = durationMillis,
                    notice = notice
                )
            } else {
                answerController.process(bitmaps, transcript, durationMillis, notice)
            }
        }
    )
    private val interactionController = OverlayInteractionController(
        uiState = _uiState,
        bubbleStateMachine = bubbleStateMachine,
        openCropSheet = ::openCropSheet,
        capturePage = ::capturePage,
        sendCaptures = ::sendCaptures,
        exitMultiPageCaptureMode = ::exitMultiPageCaptureMode,
        cancelActiveProcessing = autoProcessingController::cancelActiveProcessing,
        enterMultiPageCaptureMode = ::enterMultiPageCaptureMode,
        isBubbleMenuEnabled = { _uiState.value.settings.automation.bubbleMenuEnabled },
        toggleProcessingRoute = ::toggleProcessingRoute,
        toggleWebSearch = ::toggleWebSearch,
        openSettings = {
            appContext.startActivity(
                mainActivityIntent(appContext).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        },
        startVoiceRecording = ::startVoiceRecording,
        stopVoiceRecording = ::stopVoiceRecording,
        cancelVoiceRecording = ::cancelVoiceRecording,
        isVoiceRoute = {
            // 多悬浮窗模式下 1 号球固定是「纯视觉」，语音交给 2 号球；
            // 单球模式下语音路线在普通模式与自动模式都走录音
            !_uiState.value.settings.automation.multiBubbleEnabled &&
                _uiState.value.effectiveRoute == ProcessingRoute.AUDIO_AND_SCREEN
        }
    )

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        
        // 监听状态机变化，同步到 UI 状态
        viewModelScope.launch {
            bubbleStateMachine.state.collect { bubbleState ->
                _uiState.update { it.copy(bubbleState = bubbleState) }
            }
        }
    }

    fun setLaunchMode(mode: CaptureLaunchMode) {
        AppDebugLogStore.i(tag, "setLaunchMode mode=$mode")
        _uiState.update { state ->
            state.copy(
                launchMode = mode,
                autoRunState = if (mode == CaptureLaunchMode.NORMAL) AutoRunState.IDLE else state.autoRunState,
                autoCopiedLabel = if (mode == CaptureLaunchMode.NORMAL) null else state.autoCopiedLabel,
                pendingVibrationLetters = if (mode == CaptureLaunchMode.NORMAL) null else state.pendingVibrationLetters,
                error = null
            )
        }
        // 普通模式重置状态机
        if (mode == CaptureLaunchMode.NORMAL) {
            bubbleStateMachine.forceState(BubbleState.Idle)
        }
    }

    fun openCropSheet() {
        AppDebugLogStore.i(tag, "openCropSheet launchMode=${_uiState.value.launchMode}")
        // 1 号球固定「纯视觉」：多悬浮窗模式下把路线钉到直连多模态，不受全局设置影响
        _uiState.update {
            it.copy(activeSlot = BubbleSlot.PRIMARY, routeOverride = visualRouteOverride())
        }
        if (_uiState.value.launchMode == CaptureLaunchMode.AUTO) {
            AppDebugLogStore.i(tag, "openCropSheet delegated to processFullScreen for auto mode")
            processFullScreen()
            return
        }
        viewModelScope.launch {
            runCatching {
                `fun`.kirari.hanako.platform.capture.ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "openCropSheet capture success width=${bitmap.width} height=${bitmap.height}")
                _uiState.update {
                    it.copy(
                        screenshot = bitmap,
                        selectedBitmap = null,
                        liveOcrText = "",
                        liveAnswerText = "",
                        result = null,
                        error = null,
                        working = false,
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP,
                        autoRunState = AutoRunState.IDLE,
                        autoCopiedLabel = null,
                        pendingVibrationLetters = null
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "openCropSheet failed", error)
                _uiState.update {
                    it.copy(
                        error = error.message ?: "截屏失败",
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP
                    )
                }
            }
        }
    }

    fun processFullScreen() {
        autoProcessingController.processFullScreen()
    }

    fun process(bitmap: Bitmap) {
        answerController.process(bitmap)
    }

    fun process(bitmaps: List<Bitmap>) {
        answerController.process(bitmaps)
    }

    fun regenerateCurrentResult() {
        answerController.regenerateCurrentResult()
    }

    fun closeSheet() {
        _uiState.update { it.copy(sheetVisible = false, error = null) }
    }

    /**
     * 语音模式：开始录音。microphone=true 走麦克风，否则走系统内录。
     *
     * 录音一旦成立，作答路线就钉到「语音+画面」：以前这里不钉，全局模式是纯视觉时
     * 转写结果会被整段丢掉，用户只会看到「录了但没按语音回答」。
     */
    fun startVoiceRecording(
        microphone: Boolean = false,
        slot: BubbleSlot = BubbleSlot.PRIMARY
    ) {
        _uiState.update {
            it.copy(activeSlot = slot, routeOverride = ProcessingRoute.AUDIO_AND_SCREEN)
        }
        voiceCaptureController.start(microphone)
    }

    /** 语音模式：结束录音并作答。 */
    fun stopVoiceRecording() {
        voiceCaptureController.stop()
    }

    /** 语音模式：放弃这次录音。 */
    fun cancelVoiceRecording() {
        voiceCaptureController.cancel()
    }

    val isVoiceRecording: Boolean
        get() = voiceCaptureController.isRecording

    fun consumeAutoCompletedState() {
        interactionController.consumeAutoCompletedState()
    }

    fun consumePendingVibrationLetters() {
        interactionController.consumePendingVibrationLetters()
    }

    fun presentAutomationEffect(result: `fun`.kirari.hanako.core.model.ProcessingResult) {
        autoProcessingController.presentAutomationEffect(result)
    }

    fun onBubbleTappedAfterLettersShown() {
        interactionController.onBubbleTappedAfterLettersShown()
    }

    // 多页截图相关方法
    
    /**
     * 进入多页截图模式
     */
    fun enterMultiPageCaptureMode() {
        _uiState.update {
            it.copy(activeSlot = BubbleSlot.PRIMARY, routeOverride = visualRouteOverride())
        }
        multiPageCaptureController.enter()
    }

    /**
     * 截图一次
     */
    fun capturePage() {
        multiPageCaptureController.capturePage()
    }

    /**
     * 发送截图给 AI
     */
    fun sendCaptures() {
        AppDebugLogStore.i(tag, "sendCaptures called state=${bubbleStateMachine.currentState::class.simpleName}")
        if (!multiPageCaptureController.canSendCaptures()) {
            AppDebugLogStore.i(tag, "sendCaptures called but cannot send")
            return
        }
        
        val bitmaps = multiPageCaptureController.capturedBitmaps()
        AppDebugLogStore.i(tag, "sendCaptures count=${bitmaps.size}")
        
        if (bitmaps.isEmpty()) {
            AppDebugLogStore.i(tag, "sendCaptures no bitmaps available")
            return
        }
        
        multiPageCaptureController.markSendCaptures()
        AppDebugLogStore.i(tag, "sendCaptures dispatched SendCaptures, new state=${bubbleStateMachine.currentState::class.simpleName}")
        autoProcessingController.processBitmaps(bitmaps)
    }

    /**
     * 退出多页截图模式
     */
    fun exitMultiPageCaptureMode() {
        multiPageCaptureController.exit()
    }

    /**
     * 处理单击事件（根据当前状态决定行为）
     */
    fun handleSingleTap(slot: BubbleSlot = BubbleSlot.PRIMARY) {
        markInteractingSlot(slot)
        interactionController.handleSingleTap(slot)
    }

    /**
     * 处理长按事件
     * - 多图模式下：发送已截图片
     * - Idle/ShowingLetters/Copied/Error：进入多图截图模式
     * - Processing 等：展开扇形菜单
     */
    fun handleLongPress(anchorX: Int = 0, anchorY: Int = 0, slot: BubbleSlot = BubbleSlot.PRIMARY) {
        markInteractingSlot(slot)
        interactionController.handleLongPress(slot, anchorX, anchorY)
    }

    /**
     * 处理双击事件
     * - 多图模式：退出多图
     * - Processing：取消处理
     * - Idle/ShowingLetters/Copied/Error：展开扇形菜单
     */
    fun handleDoubleTap(anchorX: Int = 0, anchorY: Int = 0, slot: BubbleSlot = BubbleSlot.PRIMARY) {
        markInteractingSlot(slot)
        interactionController.handleDoubleTap(slot, anchorX, anchorY)
    }

    /**
     * 记录「这次是点哪颗球」。
     *
     * 正在录音/处理时不改 owner——否则随手点一下另一颗球，倒计时胶囊会整个跳过去。
     * 空闲时才切换，这样从 2 号球双击弹出的菜单也会长在 2 号球旁边。
     */
    private fun markInteractingSlot(slot: BubbleSlot) {
        val current = bubbleStateMachine.currentState
        val busy = current is BubbleState.Recording ||
            current is BubbleState.Processing ||
            current is BubbleState.MultiPageCapture ||
            current is BubbleState.MultiPageCapturing
        if (busy) return
        if (_uiState.value.activeSlot == slot) return
        _uiState.update { it.copy(activeSlot = slot) }
    }

    /** 多悬浮窗模式下 1 号球走纯视觉；单球模式沿用全局设置。 */
    private fun visualRouteOverride(): ProcessingRoute? {
        return if (_uiState.value.settings.automation.multiBubbleEnabled) {
            ProcessingRoute.MULTIMODAL_DIRECT
        } else {
            null
        }
    }

    /**
     * 菜单关闭后的回调
     */
    fun onMenuDismissed() {
        interactionController.onMenuDismissed()
        if (pendingMicrophoneStart) {
            pendingMicrophoneStart = false
            startVoiceRecording(true, _uiState.value.activeSlot)
        }
    }

    /**
     * 处理菜单项点击
     */
    fun handleMenuSelect(item: BubbleMenuItem) {
        if (item == BubbleMenuItem.VoiceRecognition) {
            // 菜单退场会触发 CloseMenu 把状态恢复成 Idle，所以录音要等退场结束再启动
            pendingMicrophoneStart = true
        }
        interactionController.handleMenuSelect(item)
    }

    fun toggleWebSearch() {
        settingsController.toggleWebSearch()
    }

    fun selectAssistant(assistantId: String) = settingsController.selectAssistant(assistantId)

    fun selectPreviousAssistant() {
        settingsController.selectPreviousAssistant()
    }

    fun selectNextAssistant() {
        settingsController.selectNextAssistant()
    }

    fun updateModelSelection(purpose: ModelPurpose, selection: ModelSelection) =
        settingsController.updateModelSelection(purpose, selection)

    fun updateModelSelectionWithFavorite(purpose: ModelPurpose, selection: ModelSelection, favoriteModel: Boolean = false) =
        settingsController.updateModelSelectionWithFavorite(purpose, selection, favoriteModel)

    fun toggleFavoriteModel(providerId: String, modelId: String) =
        settingsController.toggleFavoriteModel(providerId, modelId)

    /**
     * 手动切换工作模式。
     *
     * 用户既然手点了「视觉/OCR/语音」，就把悬浮球的固定角色让位：
     * 清掉 [OverlayUiState.routeOverride]，否则面板上的切换看起来毫无反应。
     */
    fun toggleProcessingRoute() {
        _uiState.update { it.copy(routeOverride = null) }
        settingsController.toggleProcessingRoute()
    }

    override fun onCleared() {
        // 服务被系统回收时别把麦克风留在打开状态
        voiceCaptureController.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            appContext: Context,
            dependencies: `fun`.kirari.hanako.feature.overlay.OverlayDependencies
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return OverlayViewModel(
                        appContext = appContext,
                        repository = dependencies.settingsRepository,
                        solveOperations = dependencies.solveOperations,
                        providerModelsApi = dependencies.providerModelsApi,
                        mainActivityIntent = dependencies.mainActivityIntent
                    ) as T
                }
            }
        }
    }
}

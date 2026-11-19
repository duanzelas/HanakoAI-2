package `fun`.kirari.hanako.feature.overlay.presentation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import `fun`.kirari.hanako.core.audio.AudioCaptureService
import `fun`.kirari.hanako.core.audio.AudioPermissionActivity
import `fun`.kirari.hanako.core.audio.AudioRecorderHub
import `fun`.kirari.hanako.core.audio.WavCodec
import `fun`.kirari.hanako.core.data.AppSettings
import `fun`.kirari.hanako.core.data.resolveTranscriptionApiKey
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import `fun`.kirari.hanako.platform.capture.CaptureLaunchMode
import `fun`.kirari.hanako.feature.overlay.state.BubbleEvent
import `fun`.kirari.hanako.feature.overlay.state.BubbleState
import `fun`.kirari.hanako.feature.overlay.state.BubbleStateMachine
import `fun`.kirari.hanako.feature.overlay.state.OverlaySheetMode
import `fun`.kirari.hanako.feature.overlay.state.OverlayUiState
import `fun`.kirari.hanako.platform.capture.ScreenCaptureManager
import `fun`.kirari.hanako.platform.capture.projection.AudioProjectionRequest
import `fun`.kirari.hanako.platform.capture.projection.MediaProjectionForegroundService
import `fun`.kirari.hanako.platform.capture.projection.ProjectionSessionManager
import `fun`.kirari.llm.core.TranscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 语音模式的两段式录制。
 *
 * 第一次触发开始录音，第二次触发结束：停止录音 → 转写 → 截一张当前画面 → 交给作答链路。
 *
 * 音源严格由入口决定，不做任何自动切换：
 * - 主按钮（语音+画面模式单击）永远是**系统内录**（AudioPlaybackCapture）；
 * - 麦克风只走悬浮球菜单里的「麦克风」，用户不点就绝不开麦。
 *
 * 以前内录失败会自动退回麦克风，结果是在 Shizuku 截图后端下「看起来在录音，其实录的全是环境音」，
 * 用户既不知道发生了什么，也无从排查。现在内录拿不到投影会话就直接去要授权，
 * 授权被拒绝就明确报错，把换音源的决定权交回用户。
 */
internal class VoiceCaptureController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<OverlayUiState>,
    private val bubbleStateMachine: BubbleStateMachine,
    private val onSolve: (
        bitmaps: List<Bitmap>,
        transcript: String?,
        durationMillis: Long,
        notice: String?
    ) -> Unit
) {
    private val tag = "HanakoVoiceCapture"
    private val client = TranscriptionClient()
    private var tickerJob: Job? = null
    private var pipelineJob: Job? = null
    private var startInFlight = false
    private var serviceStarted = false

    fun start(microphone: Boolean) {
        if (AudioRecorderHub.isRecording) return
        if (AudioProjectionRequest.isPending()) {
            AppDebugLogStore.i(tag, "waiting for projection consent, ignore duplicated start")
            return
        }
        val settings = uiState.value.settings
        if (!settings.transcription.enabled) {
            fail("语音转写已关闭，请到「设置 → 更多 → 语音转写」开启")
            return
        }
        if (!AudioPermissionActivity.hasPermission(appContext)) {
            AppDebugLogStore.i(tag, "record permission missing, launching permission activity")
            runCatching { appContext.startActivity(AudioPermissionActivity.intent(appContext)) }
                .onFailure { AppDebugLogStore.e(tag, "cannot launch permission activity", it) }
            fail("需要录音权限，请在系统弹窗中允许后再点一次")
            return
        }

        if (microphone) {
            AppDebugLogStore.i(tag, "start microphone recording (explicit entry)")
            startPipeline(AudioRecorderHub.Source.MIC)
            return
        }

        // 内录：没有投影会话就去要一次授权，绝不偷偷改用麦克风
        if (ProjectionSessionManager.currentProjection() == null) {
            requestProjectionConsent()
            return
        }
        startPipeline(AudioRecorderHub.Source.PLAYBACK)
    }

    /**
     * 向系统要一次「录屏与录音」授权。
     *
     * 用 Shizuku 截图时手里没有投影会话，内录在物理上就不可能，而投影授权只能由用户点。
     * 允许后直接续上录音，不用再点一次悬浮球；拒绝则明确说明原因并提示改用麦克风。
     */
    private fun requestProjectionConsent() {
        AppDebugLogStore.i(tag, "no projection session for playback capture, requesting consent")
        // 只写日志与状态文案，不动悬浮球状态：系统授权弹窗本身已经说明发生了什么
        uiState.update { it.copy(error = "等待「录屏与录音」授权，允许后会自动开始录音") }
        AudioProjectionRequest.await(appContext) { granted ->
            if (!granted) {
                fail("未获得屏幕录制授权，无法内录。可改用悬浮球菜单里的「麦克风」录音")
                return@await
            }
            val projection = ProjectionSessionManager.currentProjection()
            if (projection == null) {
                fail("屏幕录制授权已给，但投影会话没建起来，请重试或改用麦克风")
                return@await
            }
            startPipeline(AudioRecorderHub.Source.PLAYBACK)
        }
    }

    /**
     * 真正启动录音。
     *
     * 麦克风要先等 `AudioCaptureService` 起来：Android 14+ 只有在 「microphone 类型的前台服务」
     * 运行时才允许后台取麦。`startForegroundService` 是异步的，如果立刻 startRecording，
     * 服务还没建好，AppOps 会把这次访问当后台调用直接拒绝（或返回静音）。
     *
     * 内录同理，要先确认投影会话真的在手，否则 `AudioPlaybackCapture` 会初始化失败。
     */
    private fun startPipeline(source: AudioRecorderHub.Source) {
        if (startInFlight) return
        startInFlight = true
        scope.launch {
            try {
                if (source == AudioRecorderHub.Source.MIC) {
                    AudioCaptureService.start(appContext)
                    var waited = 0
                    while (!AudioCaptureService.running && waited < 800) {
                        delay(40)
                        waited += 40
                    }
                    if (!AudioCaptureService.running) {
                        AppDebugLogStore.e(tag, "audio foreground service did not start in time")
                    }
                    serviceStarted = true
                } else {
                    var waited = 0
                    while (ProjectionSessionManager.currentProjection() == null && waited < 1200) {
                        delay(40)
                        waited += 40
                    }
                }
                val started = runCatching { AudioRecorderHub.start(appContext, source) }
                started
                    .onSuccess {
                        AppDebugLogStore.i(tag, "recording started source=$it")
                        uiState.update { state -> state.copy(error = null) }
                        bubbleStateMachine.dispatch(BubbleEvent.StartRecording(source == AudioRecorderHub.Source.MIC))
                        startTicker()
                    }
                    .onFailure { error ->
                        AppDebugLogStore.e(tag, "recording start failed", error)
                        stopAudioServiceIfNeeded()
                        releaseAudioProjection()
                        fail(error.message ?: "录音启动失败")
                    }
            } finally {
                startInFlight = false
            }
        }
    }

    private fun stopAudioServiceIfNeeded() {
        if (!serviceStarted) return
        serviceStarted = false
        AudioCaptureService.stop(appContext)
    }

    /**
     * 只回收「为这次内录临时建的」投影会话。
     *
     * 屏幕镜像会话（用户显式开的截图后端）不能碰，否则录完音截图就废了。
     */
    private fun releaseAudioProjection() {
        val released = runCatching { ProjectionSessionManager.releaseAudioOnlySession() }.getOrDefault(false)
        if (!released) return
        runCatching {
            appContext.stopService(
                Intent(appContext, MediaProjectionForegroundService::class.java).apply {
                    action = MediaProjectionForegroundService.ACTION_STOP
                }
            )
        }.onFailure { AppDebugLogStore.e(tag, "cannot stop projection service", it) }
    }

    fun stop() {
        if (!AudioRecorderHub.isRecording) return
        finishRecording()
    }

    /**
     * 收尾。时长到上限时引擎会先自己把 running 置 false，
     * 这时 stop() 的前置判断会挡住调用，所以计时器走这个内部入口。
     *
     * 语音是「增强」而不是「前置条件」：内录拿不到声音、转写接口报错，
     * 都退化成纯画面作答并把原因写进结果，而不是让悬浮球闪一个 × 就结束。
     * 只有连截图都失败才真正放弃。
     */
    private fun finishRecording() {
        tickerJob?.cancel()
        tickerJob = null
        val recording = AudioRecorderHub.stop()
        stopAudioServiceIfNeeded()
        // 音频已经在内存里了，临时投影会话该还回去了（屏幕镜像会话不受影响）
        releaseAudioProjection()

        val settings = uiState.value.settings
        // 先切到 Processing：转写要等网络，别让胶囊停在计时上
        bubbleStateMachine.dispatch(BubbleEvent.StopRecording)

        pipelineJob?.cancel()
        pipelineJob = scope.launch {
            val bitmap = runCatching {
                ScreenCaptureManager.captureLatestBitmap(appContext, settings.screenCaptureMethod)
            }.getOrElse { error ->
                AppDebugLogStore.e(tag, "capture after recording failed", error)
                fail(error.message ?: "截屏失败")
                return@launch
            }

            var notice: String? = when {
                recording == null || recording.pcm.isEmpty() -> "没有录到音频"
                recording.isSilent -> if (recording.source == AudioRecorderHub.Source.PLAYBACK) {
                    "内录没有声音，这个应用可能禁止被录制（如微信语音），可改用麦克风"
                } else {
                    "麦克风没有拾到声音，可能被静音或被其它应用占用"
                }
                else -> null
            }

            var transcript: String? = null
            if (notice == null && recording != null) {
                runCatching { transcribe(recording, settings) }
                    .onSuccess { text ->
                        if (text.isBlank()) {
                            notice = "语音转写是空的，可能录得太短或全是噪音"
                        } else {
                            transcript = text
                            AppDebugLogStore.i(
                                tag,
                                "transcript ready length=${text.length} " +
                                    "durationMs=${recording.durationMillis} source=${recording.source}"
                            )
                        }
                    }
                    .onFailure { error ->
                        AppDebugLogStore.e(tag, "transcription failed", error)
                        notice = "语音转写失败：${error.message ?: "未知错误"}"
                    }
            }

            val fallbackNotice = notice?.let { "$it，本次仅用截图作答" }
            if (fallbackNotice != null) {
                AppDebugLogStore.i(tag, "voice degraded to image-only: $fallbackNotice")
            }
            onSolve(
                listOf(bitmap),
                transcript,
                recording?.durationMillis ?: 0L,
                fallbackNotice
            )
        }
    }

    fun cancel() {
        tickerJob?.cancel()
        tickerJob = null
        pipelineJob?.cancel()
        pipelineJob = null
        AudioProjectionRequest.cancel()
        AudioRecorderHub.cancel()
        stopAudioServiceIfNeeded()
        releaseAudioProjection()
        if (bubbleStateMachine.currentState is BubbleState.Recording) {
            bubbleStateMachine.dispatch(BubbleEvent.CancelRecording)
        }
    }

    val isRecording: Boolean get() = AudioRecorderHub.isRecording

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && AudioRecorderHub.isRecording) {
                val seconds = (AudioRecorderHub.elapsedMillis / 1000L).toInt()
                bubbleStateMachine.dispatch(BubbleEvent.RecordingTick(seconds))
                delay(500)
            }
            // 到达录音时长上限时引擎会自己停，这里补一次收尾，避免停在 Recording 状态
            if (!AudioRecorderHub.isRecording && bubbleStateMachine.currentState is BubbleState.Recording) {
                AppDebugLogStore.i(tag, "recording hit duration cap, stopping automatically")
                // 别在计时协程里直接收尾：finishRecording 会 cancel 掉 tickerJob（就是自己）
                scope.launch { finishRecording() }
            }
        }
    }

    /**
     * 转写：按 110 秒切片，逐段上传再拼接。
     *
     * DeepSeek 官方 ASR 单次上限 120 秒 / 8 MiB，切段后长录音也能一次走完；
     * 这个接口没有 language 参数（语种自动识别），[AppSettings.transcription] 里的 en
     * 只会在换成 OpenAI 兼容地址时真正发出去，这里传进去由客户端自行判断。
     */
    private suspend fun transcribe(
        recording: AudioRecorderHub.Recording,
        settings: AppSettings
    ): String {
        val apiKey = settings.resolveTranscriptionApiKey()
        if (apiKey.isBlank()) {
            error("没有可用的转写 API Key，请在「语音转写」设置里填写或先配置 DeepSeek 提供方")
        }
        val chunks = WavCodec.chunkPcmAsWav(
            pcm = recording.pcm,
            sampleRate = recording.sampleRate,
            channels = recording.channels
        )
        if (chunks.isEmpty()) error("录音数据为空")
        AppDebugLogStore.i(tag, "transcribing chunks=${chunks.size} durationMs=${recording.durationMillis}")

        val builder = StringBuilder()
        chunks.forEachIndexed { index, wav ->
            val result = client.transcribe(
                baseUrl = settings.transcription.baseUrl,
                apiKey = apiKey,
                path = settings.transcription.path,
                audioBytes = wav,
                fileName = "hanako-${index + 1}.wav",
                language = settings.transcription.language,
                trustAllHttpsCertificates = settings.trustAllHttpsCertificates
            )
            if (result.text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(result.text)
            }
        }
        val text = builder.toString().trim()
        if (settings.transcription.englishOnly) {
            val cjk = text.count { it.code in 0x4E00..0x9FFF }
            if (cjk > 0) {
                // DeepSeek 的 ASR 语种是自动识别的，客户端做不了强制；只记录，方便用户判断要不要换音源
                AppDebugLogStore.i(
                    tag,
                    "transcript contains $cjk CJK chars though englishOnly=true; DeepSeek ASR auto-detects language"
                )
            }
        }
        return text
    }

    /**
     * 失败时把原因摊开给用户看。
     *
     * 之前只改 uiState.error 而不打开面板，悬浮球闪一个 × 就没了，
     * 用户完全不知道是权限、静音还是网络问题。
     */
    private fun fail(message: String) {
        AppDebugLogStore.e(tag, "voice capture failed: $message")
        bubbleStateMachine.dispatch(BubbleEvent.ErrorOccurred(message))
        uiState.update {
            it.copy(
                error = message,
                working = false,
                // 自动模式本来就不弹面板，失败也只让悬浮球报错，别突然弹一个窗出来
                sheetVisible = if (it.launchMode == CaptureLaunchMode.AUTO) it.sheetVisible else true,
                sheetMode = OverlaySheetMode.RESULT
            )
        }
    }
}

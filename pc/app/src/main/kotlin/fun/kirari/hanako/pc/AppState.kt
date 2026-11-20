package `fun`.kirari.hanako.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.util.UUID

/** 所有可变状态与动作集中在这里，UI 只做渲染，托盘/热键也调同一套方法。 */
class AppState(private val scope: CoroutineScope) {

    var settings by mutableStateOf(PcSettings.load())
        private set

    var letters by mutableStateOf("")
        private set
    var answer by mutableStateOf("")
        private set
    var ocrText by mutableStateOf<String?>(null)
        private set
    /** 这次作答用到的语音转写。 */
    var transcript by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("就绪")
        private set
    var busy by mutableStateOf(false)
        private set

    // ---- 录音 ----
    var recording by mutableStateOf(false)
        private set
    var recordSeconds by mutableStateOf(0)
        private set

    // ---- 答案版本 ----
    var versions by mutableStateOf<List<SolveResult>>(emptyList())
        private set
    var versionIndex by mutableStateOf(0)
        private set
    val currentVersion: SolveResult? get() = versions.getOrNull(versionIndex)

    // ---- 历史 ----
    var history by mutableStateOf(PcHistory.load())
        private set
    var selectedHistory by mutableStateOf<HistoryEntry?>(null)
        private set

    /** 悬浮小球是否显示（显示答案字母） */
    var bubbleVisible by mutableStateOf(false)
    /** 主设置窗口是否显示，关闭时缩到托盘 */
    var showMain by mutableStateOf(true)
    /** 设置二级窗口（模型配置、音源、搜索等）是否显示 */
    var showSettings by mutableStateOf(false)
    /** 正在框选 */
    var selecting by mutableStateOf(false)

    var tray: TrayController? = null
    var hotkeyAvailable by mutableStateOf(false)

    private val solver = Solver()
    private val recorder = PcRecorder()
    private val transcriber = PcTranscriber()
    private var selector: RegionSelector? = null
    private var saveJob: Job? = null
    private var recordJob: Job? = null

    /** 上一张图，供「重新生成」用。 */
    private var lastImage: BufferedImage? = null

    fun updateSettings(transform: (PcSettings) -> PcSettings) {
        settings = transform(settings)
        // 输入框每敲一个字都会走到这里，落盘做个 300ms 去抖
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            settings.save()
        }
    }

    fun applyPreset(preset: ProviderPreset) {
        updateSettings { it.applyPreset(preset) }
        status = "已套用 ${preset.label} 预设"
    }

    // ---------------------------------------------------------------- 截图作答

    /** 按固定区域（或剪贴板）截图作答；语音模式下先录音。 */
    fun captureNow() {
        if (busy || recording) return
        if (settings.route == SolveRoute.AUDIO_AND_SCREEN) {
            // 先确认识别区域，否则录完音才发现没地方截图，白录一次
            if (settings.region == null) {
                status = "语音模式也需要先框选一次区域"
                startSelect()
                return
            }
            startRecording()
            return
        }
        val region = settings.region
        if (region == null) {
            status = "还没有固定区域，先框选一次"
            startSelect()
            return
        }
        runCapture(region)
    }

    fun startSelect() {
        if (selecting || busy || recording) return
        selecting = true
        showMain = false
        val s = RegionSelector { region ->
            selecting = false
            selector = null
            if (region != null) {
                updateSettings { it.copy(region = region) }
                status = "已固定区域 ${region.width}×${region.height} @ (${region.x}, ${region.y})"
                // 等遮罩彻底消失再截，否则会把绿色选择框一起截进去
                scope.launch {
                    delay(220)
                    if (settings.route == SolveRoute.AUDIO_AND_SCREEN) startRecording() else runCapture(region)
                }
            } else {
                status = "已取消框选"
                showMain = true
            }
        }
        selector = s
        s.show()
    }

    fun captureClipboard() {
        if (busy || recording) return
        status = "读取剪贴板…"
        runCapture(region = null) {
            Capture.clipboardImage() ?: Capture.clipboardImageFile()
        }
    }

    // ---------------------------------------------------------------- 录音

    /** 开始录音；到 [PcSettings.maxRecordSeconds] 会自动结束并作答。 */
    fun startRecording() {
        if (busy || recording) return
        when (val result = recorder.start(settings.audioSource)) {
            RecordStart.Ok -> Unit
            RecordStart.NoSystemDevice -> {
                status = "系统内录不可用：没有找到「立体声混音」设备。" +
                    "到 声音设置 → 录制 → 右键「显示禁用的设备」→ 启用「立体声混音」，或改用麦克风"
                return
            }
            RecordStart.NoMicrophone -> {
                status = "打不开麦克风：被其他程序占用，或系统没有输入设备"
                return
            }
            is RecordStart.Failed -> {
                status = "录音设备打不开：${result.message}"
                return
            }
        }
        recording = true
        recordSeconds = 0
        tray?.setRecording(true)
        status = if (settings.audioSource == AudioSource.SYSTEM) {
            "正在录系统声音… 再按一次结束"
        } else {
            "正在录麦克风… 再按一次结束"
        }
        recordJob?.cancel()
        recordJob = scope.launch {
            while (isActive && recording) {
                recordSeconds = (recorder.elapsedMillis() / 1000).toInt()
                if (recordSeconds >= settings.maxRecordSeconds) {
                    status = "已达到 ${settings.maxRecordSeconds} 秒上限，自动结束"
                    finishRecording()
                    return@launch
                }
                delay(200)
            }
        }
    }

    /** 结束录音 → 截图 → 转写 → 作答。 */
    fun finishRecording() {
        if (!recording) return
        recording = false
        tray?.setRecording(false)
        recordJob?.cancel()
        recordJob = null
        val clip = recorder.stop()
        if (clip == null) {
            status = "没有录到内容"
            return
        }
        if (clip.durationMillis < 400) {
            status = "录音太短（${clip.durationMillis} ms），已丢弃"
            return
        }
        scope.launch {
            busy = true
            bubbleVisible = false
            try {
                val region = settings.region
                if (region == null) {
                    status = "还没有固定区域：先框选一次再录"
                    return@launch
                }
                status = "截图中…"
                val image = Capture.capture(region)
                if (image == null) {
                    status = "截图失败"
                    return@launch
                }
                var voice: String? = null
                if (clip.silent) {
                    status = "录到的是静音，本次仅用画面作答"
                } else {
                    status = "转写中…"
                    voice = runCatching { transcriber.transcribe(settings, clip) }
                        .onFailure { status = "转写失败：${it.message}" }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    if (voice == null && status.startsWith("转写")) {
                        status = "转写为空，本次仅用画面作答"
                    }
                }
                lastImage = image
                solveImage(image, voice, appendVersion = true)
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busy = false
            }
        }
    }

    fun cancelRecording() {
        if (!recording) return
        recording = false
        tray?.setRecording(false)
        recordJob?.cancel()
        recordJob = null
        recorder.cancel()
        status = "已取消录音"
    }

    fun toggleRecording() {
        if (recording) finishRecording() else startRecording()
    }

    // ---------------------------------------------------------------- 作答

    fun regenerate() {
        val image = lastImage ?: run {
            status = "没有可重新生成的画面，先截一次图"
            return
        }
        if (busy || recording) return
        scope.launch {
            busy = true
            try {
                status = "重新作答…"
                solveImage(image, transcript, appendVersion = true)
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busy = false
            }
        }
    }

    fun showVersion(index: Int) {
        if (index !in versions.indices) return
        versionIndex = index
        val v = versions[index]
        answer = v.answer
        letters = v.letters
        ocrText = v.ocrText
        transcript = v.transcript
        status = "第 ${index + 1}/${versions.size} 个答案"
    }

    private fun runCapture(region: Region?, provider: (() -> BufferedImage?)? = null) {
        if (busy || recording) return
        scope.launch {
            busy = true
            bubbleVisible = false
            try {
                status = "截图中…"
                val image = provider?.invoke() ?: region?.let { Capture.capture(it) }
                if (image == null) {
                    status = "没拿到图像：截图失败或剪贴板里没有图片"
                    return@launch
                }
                lastImage = image
                solveImage(image, voiceTranscript = null, appendVersion = false)
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busy = false
            }
        }
    }

    /** 真正调模型；调用方负责 busy 与异常。 */
    private suspend fun solveImage(
        image: BufferedImage,
        voiceTranscript: String?,
        appendVersion: Boolean
    ) {
        val result = solver.solve(settings, image, voiceTranscript) { step -> status = step }
        answer = result.answer
        letters = result.letters
        ocrText = result.ocrText
        transcript = result.transcript

        versions = if (appendVersion) versions + result else listOf(result)
        versionIndex = versions.lastIndex

        status = when {
            result.letters.isNotEmpty() -> "答案 ${result.letters}"
            result.answer.isBlank() -> "模型没有返回内容"
            else -> "已回答（未识别出选项字母）"
        }
        bubbleVisible = true
        tray?.update(
            result.letters.ifBlank { "✓" },
            buildString {
                append(if (result.letters.isEmpty()) "已回答" else "答案 ${result.letters}")
                append(" · ")
                append(result.answer.replace('\n', ' ').take(70))
            }
        )
        recordHistory(result)
    }

    private fun recordHistory(result: SolveResult) {
        val entry = HistoryEntry(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            route = result.route,
            answer = result.answer,
            letters = result.letters,
            transcript = result.transcript,
            ocrText = result.ocrText,
            visionModel = settings.visionModel,
            textModel = settings.textModel,
            searchQuery = result.searchQuery
        )
        history = PcHistory.append(entry, settings.historyLimit)
    }

    private fun fail(t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "未知错误"
        status = "失败：$message"
        tray?.update("!", "失败：$message")
    }

    // ---------------------------------------------------------------- 历史

    fun openHistory(entry: HistoryEntry) {
        selectedHistory = entry
        answer = entry.answer
        letters = entry.letters
        ocrText = entry.ocrText
        transcript = entry.transcript
        versions = emptyList()
        versionIndex = 0
        bubbleVisible = false
        status = "历史记录 · ${entry.timeLabel}"
    }

    fun closeHistory() {
        selectedHistory = null
        answer = ""
        letters = ""
        ocrText = null
        transcript = null
        versions = emptyList()
        versionIndex = 0
        status = "就绪"
    }

    fun deleteHistory(entry: HistoryEntry) {
        history = PcHistory.delete(entry.id)
        if (selectedHistory?.id == entry.id) closeHistory()
    }

    fun clearHistory() {
        history = PcHistory.clear()
        closeHistory()
    }

    // ---------------------------------------------------------------- 其他

    fun copyAnswer() {
        if (answer.isBlank()) return
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(answer), null)
            status = "答案已复制到剪贴板"
        }.onFailure { status = "复制失败：${it.message}" }
    }

    fun copyText(text: String, label: String = "内容") {
        if (text.isBlank()) return
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            status = "$label 已复制"
        }.onFailure { status = "复制失败：${it.message}" }
    }
}

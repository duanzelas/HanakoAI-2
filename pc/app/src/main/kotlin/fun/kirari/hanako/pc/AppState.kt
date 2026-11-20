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
    var hotkeys: HotkeyController? = null

    /** 正在等待用户按键的快捷键动作；非 null 时全局动作全部暂停。 */
    var capturingAction by mutableStateOf<HotkeyAction?>(null)
        private set

    private val solver = Solver()
    private val recorder = PcRecorder()
    private val transcriber = PcTranscriber()
    private var selector: RegionSelector? = null
    private var saveJob: Job? = null
    private var recordJob: Job? = null

    /** 上一张图，供「重新生成」用。 */
    private var lastImage: BufferedImage? = null

    /** 从录音入口进来但还没框选过区域：选完就自动开始录音，不用再按一次。 */
    private var recordAfterSelect = false

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

    /**
     * 按固定区域截图作答；语音模式下先录音。
     *
     * [routeOverride] 给快捷键用：绑在「多模态识屏」上的键不管当前工作模式是什么，
     * 都直接截一张图问多模态模型——快捷键的意义就是「按了就按这个来」，
     * 还跟着全局设置漂移等于没绑。
     */
    fun captureNow(routeOverride: SolveRoute? = null) {
        if (busy || recording) return
        val route = routeOverride ?: settings.route
        if (route == SolveRoute.AUDIO_AND_SCREEN) {
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
        runCapture(region, routeOverride = routeOverride)
    }

    /** 多模态识屏：截一张固定区域，直接交给多模态模型。 */
    fun captureMultimodal() {
        captureNow(SolveRoute.MULTIMODAL_DIRECT)
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
                    when {
                        recordAfterSelect -> {
                            recordAfterSelect = false
                            startRecording()
                        }

                        settings.route == SolveRoute.AUDIO_AND_SCREEN -> startRecording()
                        else -> runCapture(region)
                    }
                }
            } else {
                recordAfterSelect = false
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
        val options = settings.recordOptions()
        if (!options.any) {
            status = "系统内录和麦克风都关着：到 设置 → 语音与录音 里打开至少一个"
            return
        }
        // 录完才截不到图是最亏的，所以先要区域：没有就弹框选，选完自动开录
        if (settings.region == null) {
            recordAfterSelect = true
            status = "还没有固定区域：先框选一次，选完自动开始录音"
            startSelect()
            return
        }
        val sources: List<String>
        val skipped: List<String>
        when (val result = recorder.start(options)) {
            is RecordStart.Ok -> {
                sources = result.sources
                skipped = result.skipped
            }
            RecordStart.NoSystemDevice -> {
                status = "系统内录不可用：没有找到「立体声混音」设备。" +
                    "到 声音设置 → 录制 → 右键「显示禁用的设备」→ 启用「立体声混音」，" +
                    "或在设置里单独打开麦克风"
                return
            }
            RecordStart.NoMicrophone -> {
                status = "打不开麦克风：被其他程序占用，或系统没有输入设备"
                return
            }
            RecordStart.NoSource -> {
                status = "没有选择录音音源"
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
        status = buildString {
            append("正在录音（")
            append(sources.joinToString(" + "))
            append("）… 再按一次结束")
            if (skipped.isNotEmpty()) {
                append("\n未启用：")
                append(skipped.joinToString())
            }
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

    private fun runCapture(
        region: Region?,
        routeOverride: SolveRoute? = null,
        provider: (() -> BufferedImage?)? = null
    ) {
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
                solveImage(
                    image = image,
                    voiceTranscript = null,
                    appendVersion = false,
                    routeOverride = routeOverride
                )
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
        appendVersion: Boolean,
        routeOverride: SolveRoute? = null
    ) {
        val effective = if (routeOverride == null) settings else settings.copy(route = routeOverride)
        val result = solver.solve(effective, image, voiceTranscript) { step -> status = step }
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

    // ---------------------------------------------------------------- 快捷键

    /**
     * 进入「按一个键来设置」的状态。
     *
     * 捕获走的是同一条全局监听（[HotkeyController.captureNext]），不是界面的 onKeyEvent：
     * 一来不用把 Compose 的 Key 翻译成 jnativehook 的 keyCode，二来捕获期间全局动作
     * 自动停摆，不会出现「设快捷键时顺手截了一张图」。
     */
    fun beginHotkeyCapture(action: HotkeyAction) {
        val controller = hotkeys
        if (controller == null || !controller.isAvailable) {
            status = "全局快捷键不可用：注册失败，可能被杀毒软件拦了"
            return
        }
        capturingAction = action
        controller.captureNext { spec ->
            scope.launch { finishHotkeyCapture(action, spec) }
        }
        status = "请按下「${action.label}」要用的键或组合键，Esc 取消"
    }

    fun cancelHotkeyCapture() {
        hotkeys?.cancelCapture()
        if (capturingAction == null) return
        capturingAction = null
        status = "已取消快捷键设置"
    }

    /** 恢复某个动作的默认快捷键。 */
    fun resetHotkey(action: HotkeyAction) {
        val spec = when (action) {
            HotkeyAction.CAPTURE -> HotkeySpec.DEFAULT_CAPTURE
            HotkeyAction.RECORD -> HotkeySpec.DEFAULT_RECORD
            HotkeyAction.SELECT -> HotkeySpec.DEFAULT_SELECT
        }
        hotkeys?.cancelCapture()
        capturingAction = null
        updateSettings { it.withHotkey(action, spec) }
        status = "「${action.label}」已恢复默认 ${spec.label}"
    }

    private fun finishHotkeyCapture(action: HotkeyAction, spec: HotkeySpec?) {
        // Esc 或窗口被关掉时 spec 为 null；也可能期间已经取消了
        if (capturingAction != action) return
        capturingAction = null
        if (spec == null) {
            status = "已取消快捷键设置"
            return
        }
        val conflict = HotkeyAction.entries
            .firstOrNull { it != action && settings.hotkeyOf(it) == spec }
        if (conflict != null) {
            status = "${spec.label} 已经是「${conflict.label}」的快捷键，换一个吧"
            return
        }
        updateSettings { it.withHotkey(action, spec) }
        status = if (!spec.ctrl && !spec.alt && !spec.meta && !spec.shift) {
            "「${action.label}」已设为 ${spec.label}：单键在打字时容易误触，建议加 Ctrl 或 Alt"
        } else {
            "「${action.label}」已设为 ${spec.label}"
        }
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

package `fun`.kirari.hanako.pc

import `fun`.kirari.llm.core.ProviderKind
import java.awt.Rectangle
import java.io.File
import java.util.Properties

/** 处理链路，对应 Android 端的 ProcessingRoute。 */
enum class SolveRoute {
    /** 截图直传多模态模型，一步出答案。 */
    MULTIMODAL_DIRECT,

    /** 先让视觉模型把图里的文字抄出来，再交给文本模型作答。 */
    OCR_THEN_LLM,

    /** 语音+画面：录一段音转写，再连同截图一起交给模型。 */
    AUDIO_AND_SCREEN;

    val label: String
        get() = when (this) {
            MULTIMODAL_DIRECT -> "多模态直答"
            OCR_THEN_LLM -> "先 OCR 再作答"
            AUDIO_AND_SCREEN -> "语音+画面"
        }
}

/**
 * 模型提供方预设，对应 Android 端「模型提供方」里的内置选项。
 *
 * 只负责把 Base URL、提供方类型和一组默认模型名填进去，用户仍可逐项改。
 */
enum class ProviderPreset(
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val visionModel: String,
    val textModel: String
) {
    DEEPSEEK(
        label = "DeepSeek",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.deepseek.com/v1",
        visionModel = "deepseek-flash",
        textModel = "deepseek-flash"
    ),
    OPENAI(
        label = "OpenAI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://api.openai.com/v1",
        visionModel = "gpt-4o-mini",
        textModel = "gpt-4o-mini"
    ),
    ANTHROPIC(
        label = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        visionModel = "claude-sonnet-4-5",
        textModel = "claude-sonnet-4-5"
    ),
    GEMINI(
        label = "Gemini",
        kind = ProviderKind.GOOGLE,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        visionModel = "gemini-2.5-flash",
        textModel = "gemini-2.5-flash"
    )
}

/** 联网搜索引擎，字段格式对齐各家官方 API。 */
enum class SearchKind(val label: String) {
    TAVILY("Tavily"),
    BRAVE("Brave"),
    SERPER("Serper")
}

/** 屏幕截图区域，坐标是虚拟屏绝对坐标（支持负值，多屏时用）。 */
data class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
    val isUsable: Boolean get() = width >= 16 && height >= 16
    fun toRectangle(): Rectangle = Rectangle(x, y, width, height)
}

data class PcSettings(
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    /** 视觉模型：多模态直答、以及 OCR 步骤都用它。 */
    val visionModel: String = "gpt-4o-mini",
    /** 文本模型：OCR_THEN_LLM 的第二步用它。 */
    val textModel: String = "gpt-4o-mini",
    val route: SolveRoute = SolveRoute.MULTIMODAL_DIRECT,
    val systemPrompt: String = DEFAULT_SOLVE_PROMPT,
    /** 固定截图区域；为空表示尚未框选。 */
    val region: Region? = null,

    // ---- 录音音源 ----
    /**
     * 系统内录，默认开启。
     *
     * PC 端没有 AudioPlaybackCapture，系统内录靠声卡的「立体声混音 / Stereo Mix」
     * 回环输入设备（Realtek 驱动自带，默认常被禁用）；设备缺失时明确报错，不静默换麦。
     */
    val systemAudioEnabled: Boolean = true,
    /** 麦克风，默认关闭，独立开关；和系统内录同时开启时两路混音。 */
    val micEnabled: Boolean = false,

    // ---- 语音转写 ----
    /** 转写服务地址，默认 DeepSeek 官方 ASR。 */
    val transcriptionBaseUrl: String = DEFAULT_TRANSCRIPTION_BASE_URL,
    /** 转写 Key；留空时复用 [apiKey]（DeepSeek 的 chat 与 ASR 是同一个 Key）。 */
    val transcriptionApiKey: String = "",
    val transcriptionPath: String = DEFAULT_TRANSCRIPTION_PATH,
    /** 录音上限，超过自动停，避免转写端 120 秒限制报错。 */
    val maxRecordSeconds: Int = 110,

    // ---- 联网搜索 ----
    val searchEnabled: Boolean = false,
    val searchKind: SearchKind = SearchKind.TAVILY,
    val searchApiKey: String = "",
    /** 搜索结果条数，1..10。 */
    val searchResultCount: Int = 5,

    // ---- 全局快捷键 ----
    /** 多模态识屏：按一下截一张固定区域，直接问多模态模型。 */
    val hotkeyCapture: HotkeySpec = HotkeySpec.DEFAULT_CAPTURE,
    /** 录音识屏：按一下开始录、再按一下结束并作答。 */
    val hotkeyRecord: HotkeySpec = HotkeySpec.DEFAULT_RECORD,
    /** 重新框选区域。 */
    val hotkeySelect: HotkeySpec = HotkeySpec.DEFAULT_SELECT,

    // ---- 历史 ----
    val historyLimit: Int = 200
) {
    /** 转写实际使用的 Key：单独填了就用单独的，否则复用主 Key。 */
    val effectiveTranscriptionKey: String
        get() = transcriptionApiKey.ifBlank { apiKey }

    /** 这次录音开哪几路：两个开关组合成系统内录 / 麦克风 / 混音。 */
    fun recordOptions(): RecordOptions = RecordOptions(
        system = systemAudioEnabled,
        mic = micEnabled
    )

    /** 改某个动作的快捷键，其余保持不变。 */
    fun withHotkey(action: HotkeyAction, spec: HotkeySpec): PcSettings = when (action) {
        HotkeyAction.CAPTURE -> copy(hotkeyCapture = spec)
        HotkeyAction.RECORD -> copy(hotkeyRecord = spec)
        HotkeyAction.SELECT -> copy(hotkeySelect = spec)
    }

    /** 取某个动作当前的快捷键，用于冲突检测与界面展示。 */
    fun hotkeyOf(action: HotkeyAction): HotkeySpec = when (action) {
        HotkeyAction.CAPTURE -> hotkeyCapture
        HotkeyAction.RECORD -> hotkeyRecord
        HotkeyAction.SELECT -> hotkeySelect
    }

    fun applyPreset(preset: ProviderPreset): PcSettings = copy(
        kind = preset.kind,
        baseUrl = preset.baseUrl,
        visionModel = preset.visionModel,
        textModel = preset.textModel
    )

    fun save() {
        runCatching {
            val file = configFile()
            file.parentFile?.mkdirs()
            val props = Properties()
            props["kind"] = kind.name
            props["baseUrl"] = baseUrl
            props["apiKey"] = apiKey
            props["visionModel"] = visionModel
            props["textModel"] = textModel
            props["route"] = route.name
            props["systemPrompt"] = systemPrompt
            props["systemAudioEnabled"] = systemAudioEnabled.toString()
            props["micEnabled"] = micEnabled.toString()
            props["transcriptionBaseUrl"] = transcriptionBaseUrl
            props["transcriptionApiKey"] = transcriptionApiKey
            props["transcriptionPath"] = transcriptionPath
            props["maxRecordSeconds"] = maxRecordSeconds.toString()
            props["searchEnabled"] = searchEnabled.toString()
            props["searchKind"] = searchKind.name
            props["searchApiKey"] = searchApiKey
            props["searchResultCount"] = searchResultCount.toString()
            props["historyLimit"] = historyLimit.toString()
            props["hotkey.capture"] = hotkeyCapture.encode()
            props["hotkey.record"] = hotkeyRecord.encode()
            props["hotkey.select"] = hotkeySelect.encode()
            region?.let {
                props["region.x"] = it.x.toString()
                props["region.y"] = it.y.toString()
                props["region.w"] = it.width.toString()
                props["region.h"] = it.height.toString()
            }
            file.outputStream().use { props.store(it, "HanakoAI-EX PC") }
        }
    }

    companion object {
        const val DEFAULT_TRANSCRIPTION_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_TRANSCRIPTION_PATH = "/tools/asr"

        val DEFAULT_SOLVE_PROMPT = """
            你是答题助手。先直接给出答案，再简要说明理由。
            如果是选择题，答案行只写选项字母，例如「答案：B」；多选题写「答案：ABD」。
            其他题型直接给出结论。
        """.trimIndent()

        fun load(): PcSettings = runCatching {
            val file = configFile()
            if (!file.exists()) return PcSettings()
            val props = Properties()
            file.inputStream().use { props.load(it) }
            PcSettings(
                kind = props.getProperty("kind")
                    ?.let { runCatching { ProviderKind.valueOf(it) }.getOrNull() }
                    ?: ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = props.getProperty("baseUrl") ?: "https://api.openai.com/v1",
                apiKey = props.getProperty("apiKey").orEmpty(),
                // 旧版本只存过 model，兼容读一次
                visionModel = props.getProperty("visionModel")
                    ?: props.getProperty("model") ?: "gpt-4o-mini",
                textModel = props.getProperty("textModel")
                    ?: props.getProperty("model") ?: "gpt-4o-mini",
                route = props.getProperty("route")
                    ?.let { runCatching { SolveRoute.valueOf(it) }.getOrNull() }
                    ?: SolveRoute.MULTIMODAL_DIRECT,
                systemPrompt = props.getProperty("systemPrompt") ?: DEFAULT_SOLVE_PROMPT,
                region = readRegion(props),
                systemAudioEnabled = readSystemEnabled(props),
                micEnabled = readMicEnabled(props),
                transcriptionBaseUrl = props.getProperty("transcriptionBaseUrl")
                    ?: DEFAULT_TRANSCRIPTION_BASE_URL,
                transcriptionApiKey = props.getProperty("transcriptionApiKey").orEmpty(),
                transcriptionPath = props.getProperty("transcriptionPath")
                    ?: DEFAULT_TRANSCRIPTION_PATH,
                maxRecordSeconds = props.getProperty("maxRecordSeconds")?.toIntOrNull() ?: 110,
                searchEnabled = props.getProperty("searchEnabled")?.toBooleanStrictOrNull() ?: false,
                searchKind = props.getProperty("searchKind")
                    ?.let { runCatching { SearchKind.valueOf(it) }.getOrNull() }
                    ?: SearchKind.TAVILY,
                searchApiKey = props.getProperty("searchApiKey").orEmpty(),
                searchResultCount = props.getProperty("searchResultCount")?.toIntOrNull() ?: 5,
                historyLimit = props.getProperty("historyLimit")?.toIntOrNull() ?: 200,
                hotkeyCapture = HotkeySpec.decode(props.getProperty("hotkey.capture"))
                    ?: HotkeySpec.DEFAULT_CAPTURE,
                hotkeyRecord = HotkeySpec.decode(props.getProperty("hotkey.record"))
                    ?: HotkeySpec.DEFAULT_RECORD,
                hotkeySelect = HotkeySpec.decode(props.getProperty("hotkey.select"))
                    ?: HotkeySpec.DEFAULT_SELECT
            )
        }.getOrDefault(PcSettings())

        /**
         * 系统内录是否开启。
         *
         * 0.0.30 及更早只有单一 `audioSource`（SYSTEM / MIC），这里做一次迁移：
         * 老配置选了麦克风的，转成「系统内录关、麦克风开」，语义不变。
         */
        private fun readSystemEnabled(props: Properties): Boolean {
            props.getProperty("systemAudioEnabled")?.toBooleanStrictOrNull()?.let { return it }
            val legacy = props.getProperty("audioSource") ?: return true
            return legacy != "MIC"
        }

        private fun readMicEnabled(props: Properties): Boolean {
            props.getProperty("micEnabled")?.toBooleanStrictOrNull()?.let { return it }
            return props.getProperty("audioSource") == "MIC"
        }

        private fun readRegion(props: Properties): Region? {
            val x = props.getProperty("region.x")?.toIntOrNull() ?: return null
            val y = props.getProperty("region.y")?.toIntOrNull() ?: return null
            val w = props.getProperty("region.w")?.toIntOrNull() ?: return null
            val h = props.getProperty("region.h")?.toIntOrNull() ?: return null
            return Region(x, y, w, h).takeIf { it.isUsable }
        }

        fun configFile(): File =
            File(System.getProperty("user.home"), ".hanako-pc/config.properties")
    }
}

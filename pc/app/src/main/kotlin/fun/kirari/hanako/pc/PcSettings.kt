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
    OCR_THEN_LLM
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
    val region: Region? = null
) {
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
            region?.let {
                props["region.x"] = it.x.toString()
                props["region.y"] = it.y.toString()
                props["region.w"] = it.width.toString()
                props["region.h"] = it.height.toString()
            }
            file.outputStream().use { props.store(it, "Hanako PC") }
        }
    }

    companion object {
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
                region = readRegion(props)
            )
        }.getOrDefault(PcSettings())

        private fun readRegion(props: Properties): Region? {
            val x = props.getProperty("region.x")?.toIntOrNull() ?: return null
            val y = props.getProperty("region.y")?.toIntOrNull() ?: return null
            val w = props.getProperty("region.w")?.toIntOrNull() ?: return null
            val h = props.getProperty("region.h")?.toIntOrNull() ?: return null
            return Region(x, y, w, h).takeIf { it.isUsable }
        }

        private fun configFile(): File =
            File(System.getProperty("user.home"), ".hanako-pc/config.properties")
    }
}

package `fun`.kirari.hanako.pc

import `fun`.kirari.llm.core.LlmClient
import `fun`.kirari.llm.core.LlmEvent
import `fun`.kirari.llm.core.ProviderConfig
import `fun`.kirari.llm.core.StreamRequest
import java.awt.image.BufferedImage

data class SolveResult(
    val answer: String,
    val letters: String,
    val route: SolveRoute,
    val ocrText: String?
)

/**
 * 复用 kirari-llm-core 的两条链路，和 Android 端的 ProcessingRoute 对应：
 * 多模态直答、或者先 OCR 再交给文本模型。
 */
class Solver(private val client: LlmClient = LlmClient()) {

    suspend fun solve(
        settings: PcSettings,
        image: BufferedImage,
        onProgress: (String) -> Unit = {}
    ): SolveResult {
        require(settings.visionModel.isNotBlank()) { "未配置视觉模型" }
        val provider = ProviderConfig(
            kind = settings.kind,
            baseUrl = settings.baseUrl.trim(),
            apiKey = settings.apiKey.trim()
        )
        val jpeg = Capture.toBase64Jpeg(image)

        return when (settings.route) {
            SolveRoute.MULTIMODAL_DIRECT -> {
                onProgress("多模态直答…")
                val answer = streamOnce(
                    provider, settings.visionModel, settings.systemPrompt,
                    "请解答图中题目。", listOf(jpeg)
                )
                SolveResult(answer, AnswerParser.lettersOf(answer), settings.route, null)
            }

            SolveRoute.OCR_THEN_LLM -> {
                onProgress("识别文字…")
                val ocrText = streamOnce(
                    provider, settings.visionModel, OCR_PROMPT,
                    "提取图中全部文字。", listOf(jpeg)
                )
                onProgress("作答…")
                val answer = streamOnce(
                    provider, settings.textModel, settings.systemPrompt,
                    ocrText.ifBlank { "（未识别到文字）" }, emptyList()
                )
                SolveResult(answer, AnswerParser.lettersOf(answer), settings.route, ocrText)
            }
        }
    }

    private suspend fun streamOnce(
        provider: ProviderConfig,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        images: List<String>
    ): String {
        val builder = StringBuilder()
        client.stream(
            StreamRequest(
                provider = provider,
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                imagesBase64 = images,
                firstDeltaTimeoutMillis = 60_000,
                trustAllHttpsCertificates = false
            )
        ).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> builder.append(event.text)
                is LlmEvent.ToolCall -> Unit
                LlmEvent.Done -> Unit
            }
        }
        return builder.toString().trim()
    }

    private companion object {
        const val OCR_PROMPT =
            "你是 OCR 引擎。只输出图中出现的文字内容，按原有行序换行；" +
                "不要解释、不要答题、不要添加任何前后缀。"
    }
}

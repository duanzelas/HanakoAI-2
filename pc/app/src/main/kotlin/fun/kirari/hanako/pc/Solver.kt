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
    val ocrText: String?,
    /** 这次用到的语音转写；null 表示没有语音输入。 */
    val transcript: String? = null,
    /** 实际发出的搜索关键词；空表示没联网。 */
    val searchQuery: String? = null
)

/**
 * 复用 kirari-llm-core 的链路，和 Android 端的 ProcessingRoute 对应：
 * 多模态直答、先 OCR 再交给文本模型、语音+画面。
 *
 * 联网搜索是叠加在任意链路之上的：搜索结果只作为「参考资料」拼进提示词，
 * 搜不到或接口报错都不影响作答（这是辅助信息，不是前置条件）。
 */
class Solver(
    private val client: LlmClient = LlmClient(),
    private val searchClient: PcSearchClient = PcSearchClient()
) {

    suspend fun solve(
        settings: PcSettings,
        image: BufferedImage,
        transcript: String? = null,
        onProgress: (String) -> Unit = {}
    ): SolveResult {
        require(settings.visionModel.isNotBlank()) { "未配置视觉模型" }
        val provider = ProviderConfig(
            kind = settings.kind,
            baseUrl = settings.baseUrl.trim(),
            apiKey = settings.apiKey.trim()
        )
        val jpeg = Capture.toBase64Jpeg(image)
        val voiceText = transcript?.trim().orEmpty()

        return when (settings.route) {
            SolveRoute.MULTIMODAL_DIRECT, SolveRoute.AUDIO_AND_SCREEN -> {
                onProgress(if (voiceText.isNotEmpty()) "结合语音与画面作答…" else "多模态直答…")
                val hits = searchIfEnabled(settings, voiceText, ocrText = null, onProgress)
                val prompt = buildUserPrompt(
                    base = "请解答图中题目。",
                    transcript = voiceText.takeIf { it.isNotEmpty() },
                    hits = hits
                )
                val answer = streamOnce(
                    provider, settings.visionModel, settings.systemPrompt, prompt, listOf(jpeg)
                )
                SolveResult(
                    answer = answer,
                    letters = AnswerParser.lettersOf(answer),
                    // 只要这次真的带了语音，历史里就记为「语音+画面」，
                    // 不然用户在纯视觉模式下录音作答，记录会标成「多模态直答」，回溯时分不清
                    route = if (voiceText.isNotEmpty()) SolveRoute.AUDIO_AND_SCREEN else settings.route,
                    ocrText = null,
                    transcript = voiceText.takeIf { it.isNotEmpty() },
                    searchQuery = hits.query
                )
            }

            SolveRoute.OCR_THEN_LLM -> {
                onProgress("识别文字…")
                val ocrText = streamOnce(
                    provider, settings.visionModel, OCR_PROMPT,
                    "提取图中全部文字。", listOf(jpeg)
                )
                val hits = searchIfEnabled(settings, voiceText, ocrText, onProgress)
                onProgress("作答…")
                val prompt = buildUserPrompt(
                    base = ocrText.ifBlank { "（未识别到文字）" },
                    transcript = voiceText.takeIf { it.isNotEmpty() },
                    hits = hits
                )
                val answer = streamOnce(
                    provider, settings.textModel, settings.systemPrompt, prompt, emptyList()
                )
                SolveResult(
                    answer = answer,
                    letters = AnswerParser.lettersOf(answer),
                    route = if (voiceText.isNotEmpty()) SolveRoute.AUDIO_AND_SCREEN else settings.route,
                    ocrText = ocrText,
                    transcript = voiceText.takeIf { it.isNotEmpty() },
                    searchQuery = hits.query
                )
            }
        }
    }

    /** 搜索结果 + 实际使用的查询词。 */
    private class SearchContext(val hits: List<SearchHit>, val query: String?)

    private suspend fun searchIfEnabled(
        settings: PcSettings,
        transcript: String,
        ocrText: String?,
        onProgress: (String) -> Unit
    ): SearchContext {
        if (!settings.searchEnabled || settings.searchApiKey.isBlank()) {
            return SearchContext(emptyList(), null)
        }
        // 查询词优先用语音（题干常靠听），其次用 OCR 文本；都太短就不搜
        val source = transcript.ifBlank { ocrText.orEmpty() }.replace(Regex("\\s+"), " ").trim()
        if (source.length < 6) return SearchContext(emptyList(), null)
        val query = source.take(QUERY_MAX_CHARS)
        onProgress("联网搜索：$query")
        val hits = runCatching { searchClient.search(settings, query) }
            .getOrElse {
                onProgress("搜索失败（${it.message ?: "接口异常"}），继续作答")
                emptyList()
            }
        if (hits.isEmpty()) onProgress("没搜到结果，直接作答")
        return SearchContext(hits, query.takeIf { hits.isNotEmpty() })
    }

    private fun buildUserPrompt(base: String, transcript: String?, hits: SearchContext): String {
        if (transcript == null && hits.hits.isEmpty()) return base
        return buildString {
            if (transcript != null) {
                appendLine("以下是这段画面同时录到的语音转写文本（可能存在个别识别错误）：")
                appendLine(transcript)
                appendLine()
                appendLine("请把它和截图结合起来理解题目：只在语音里出现的条件、提问、数字都算已知信息，")
                appendLine("若两者矛盾以画面中可见内容为准。")
                appendLine()
            }
            appendLine(base)
            if (hits.hits.isNotEmpty()) {
                appendLine()
                appendLine(PcSearchClient.renderHits(hits.hits))
            }
        }.trim()
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

        /** 查询词截断，太长会被搜索引擎截断且更容易跑偏。 */
        const val QUERY_MAX_CHARS = 120
    }
}

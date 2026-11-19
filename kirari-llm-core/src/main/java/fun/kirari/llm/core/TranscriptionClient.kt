package `fun`.kirari.llm.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 转写结果。
 *
 * [durationMillis] 由服务端返回，用于把「识别时长」和「录音时长」对齐排查问题。
 */
data class TranscriptionResult(
    val text: String,
    val durationMillis: Long = 0L,
    val requestId: String = ""
)

/**
 * 语音转文字客户端。
 *
 * 默认对接 DeepSeek 官方 ASR：`POST https://api.deepseek.com/v1/tools/asr`，
 * multipart/form-data 里放一个 `file` 字段，音频为 16 位 PCM WAV 或 Ogg Opus，
 * 单次上限 120 秒 / 8 MiB，返回 `{request_id, text, duration_ms}`。
 *
 * 这个接口没有 model、language 参数（语种自动识别），所以 [transcribe] 只收
 * 地址、路径和音频。换成别家 OpenAI 兼容的 `/audio/transcriptions` 时，
 * 把 [path] 改掉即可——那边的 `model` 字段留空通常也能被接受，需要时再扩展。
 */
class TranscriptionClient(
    private val clientProvider: NetworkClientProvider = NetworkClientProvider(),
    private val logger: LlmLogger = NoopLlmLogger,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val tag = "KirariTranscription"

    suspend fun transcribe(
        baseUrl: String,
        apiKey: String,
        path: String,
        audioBytes: ByteArray,
        fileName: String = "hanako-audio.wav",
        language: String? = null,
        trustAllHttpsCertificates: Boolean = false,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        require(audioBytes.isNotEmpty()) { "音频为空，无法转写" }
        val url = composeUrl(baseUrl, path)
        logger.i(tag, "transcribe url=$url bytes=${audioBytes.size} language=${language ?: "auto"}")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = fileName,
                body = audioBytes.toRequestBody("audio/wav".toMediaType())
            )
            .apply {
                // DeepSeek 的 /tools/asr 没有 language 参数（语种强制自动识别），
                // 传了会被当成非法字段，所以只在非 DeepSeek 路径上带。
                if (!language.isNullOrBlank() && !isAutodetectOnlyPath(path)) {
                    addFormDataPart("language", language)
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .apply {
                if (apiKey.isNotBlank()) {
                    // DeepSeek 的 ASR 走 Bearer，和 chat 接口一致
                    header("Authorization", "Bearer ${apiKey.trim()}")
                }
            }
            .build()

        val client = clientProvider.clientWithTimeout(trustAllHttpsCertificates, timeoutMillis)
        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = extractErrorMessage(rawBody)
                    ?: describeStatus(response.code)
                logger.e(tag, "transcribe failed http=${response.code} body=${rawBody.take(200)}")
                throw TranscriptionException(response.code, message)
            }
            val text = runCatching {
                json.parseToJsonElement(rawBody).jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (text == null) {
                logger.e(tag, "transcribe response missing text body=${rawBody.take(200)}")
                throw TranscriptionException(response.code, "转写服务返回了无法解析的内容")
            }
            val duration = runCatching {
                json.parseToJsonElement(rawBody).jsonObject["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }.getOrNull() ?: 0L
            val requestId = runCatching {
                json.parseToJsonElement(rawBody).jsonObject["request_id"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty()

            logger.i(tag, "transcribe ok length=${text.length} durationMs=$duration")
            TranscriptionResult(
                text = text.trim(),
                durationMillis = duration,
                requestId = requestId
            )
        }
    }

    /**
     * DeepSeek 的 ASR 只说「语种自动识别」，没有 language 入参；其它家（OpenAI whisper 系列）
     * 支持 language。靠路径判断，避免用户改成自定义地址后反而丢掉语言约束。
     */
    private fun isAutodetectOnlyPath(path: String): Boolean = path.contains("/tools/asr")

    /**
     * baseUrl 可能是 `https://api.deepseek.com`，也可能是 `https://api.deepseek.com/v1`。
     * 两种情况都要拼成唯一的 `https://api.deepseek.com/v1/tools/asr`。
     */
    internal fun composeUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        if (base.isBlank()) return normalizedPath
        val collapsedBase = if (normalizedPath.startsWith("/v1/") && base.endsWith("/v1")) {
            base.removeSuffix("/v1")
        } else {
            base
        }
        return collapsedBase + normalizedPath
    }

    private fun extractErrorMessage(rawBody: String): String? {
        if (rawBody.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawBody).jsonObject
            val errorObject = root["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
            errorObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
                ?: root["detail"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun describeStatus(code: Int): String = when (code) {
        400 -> "音频格式不被支持（需要 16 位 PCM WAV 或 Ogg Opus）"
        401, 403 -> "API Key 无效或没有语音识别权限"
        408 -> "音频上传超时"
        413 -> "音频超过 8 MiB 上限"
        429 -> "转写请求过于频繁，稍后重试"
        503 -> "转写服务繁忙，稍后重试"
        504 -> "转写超时，音频可能过长"
        else -> "转写失败（HTTP $code）"
    }

    companion object {
        const val DEFAULT_PATH = "/v1/tools/asr"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}

class TranscriptionException(
    val statusCode: Int,
    override val message: String
) : Exception(message)

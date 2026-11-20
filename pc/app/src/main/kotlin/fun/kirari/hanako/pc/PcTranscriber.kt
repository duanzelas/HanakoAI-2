package `fun`.kirari.hanako.pc

import `fun`.kirari.llm.core.TranscriptionClient

/**
 * 语音转写，复用 llm-core 里给 Android 端写的那份实现。
 *
 * 默认目标是 DeepSeek 官方 ASR（`POST /v1/tools/asr`，multipart 里一个 `file` 字段），
 * 单次上限 120 秒 / 8 MiB，所以录音超过 110 秒会在本地切片，逐段转写后拼接。
 */
class PcTranscriber(private val client: TranscriptionClient = TranscriptionClient()) {

    suspend fun transcribe(settings: PcSettings, recording: PcRecording): String {
        require(recording.pcm.isNotEmpty()) { "录音为空" }
        val key = settings.effectiveTranscriptionKey.trim()
        require(key.isNotBlank()) { "未配置转写 API Key" }

        val chunks = PcWav.chunkPcmAsWav(recording.pcm)
        val parts = ArrayList<String>(chunks.size)
        chunks.forEachIndexed { index, wav ->
            val result = client.transcribe(
                baseUrl = settings.transcriptionBaseUrl.trim(),
                apiKey = key,
                path = settings.transcriptionPath.trim(),
                audioBytes = wav,
                fileName = "hanako-pc-${index + 1}.wav"
            )
            val text = result.text.trim()
            if (text.isNotEmpty()) parts += text
        }
        return parts.joinToString(separator = "\n").trim()
    }
}

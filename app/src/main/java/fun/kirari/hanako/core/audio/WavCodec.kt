package `fun`.kirari.hanako.core.audio

import java.io.ByteArrayOutputStream

/**
 * PCM ↔ WAV 的极简封装。
 *
 * 只在录音结束后做一次头部封装，不需要流式写入，所以没有引入音频库。
 * DeepSeek ASR 要求 16 位 PCM WAV，采样率支持 8000~48000，这里统一录 16 kHz 单声道。
 */
internal object WavCodec {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /**
     * 给裸 PCM 补 44 字节 WAV 头。
     */
    fun wrapPcm16(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray {
        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8
        val dataLength = pcm.size
        val out = ByteArrayOutputStream(dataLength + 44)

        out.writeAscii("RIFF")
        out.writeIntLe(36 + dataLength)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeIntLe(16)              // fmt chunk size
        out.writeShortLe(1)             // PCM
        out.writeShortLe(channels)
        out.writeIntLe(sampleRate)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(BITS_PER_SAMPLE)
        out.writeAscii("data")
        out.writeIntLe(dataLength)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * 把整段 PCM 切成若干不超过 [maxChunkMillis] 的 WAV 分片。
     *
     * DeepSeek ASR 单次上限 120 秒，超过只会上传失败。录音侧允许更长，
     * 这里按 110 秒切片后逐段转写再拼接，用户感知不到「上限」。
     */
    fun chunkPcmAsWav(
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        maxChunkMillis: Long = DEFAULT_CHUNK_MILLIS
    ): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        val bytesPerMillis = (sampleRate.toLong() * channels * BITS_PER_SAMPLE / 8) / 1000L
        val chunkBytes = (bytesPerMillis * maxChunkMillis).toInt().coerceAtLeast(1)
        // 对齐到 16 位采样，避免半个采样点
        val alignedChunk = (chunkBytes / 2) * 2
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + alignedChunk, pcm.size)
            chunks += wrapPcm16(pcm.copyOfRange(offset, end), sampleRate, channels)
            offset = end
        }
        return chunks
    }

    /** 计算 PCM16 的峰值振幅（0..32767），用于判断「录到了静音」。 */
    fun peakAmplitude(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val abs = if (sample == Short.MIN_VALUE.toInt()) 32767 else kotlin.math.abs(sample)
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    fun durationMillis(pcmSize: Int, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): Long {
        val bytesPerSecond = sampleRate.toLong() * channels * BITS_PER_SAMPLE / 8
        if (bytesPerSecond <= 0) return 0L
        return pcmSize * 1000L / bytesPerSecond
    }

    /** 单次请求按 110 秒切；120 秒上限留 10 秒余量。 */
    const val DEFAULT_CHUNK_MILLIS = 110_000L
}

private fun ByteArrayOutputStream.writeAscii(text: String) {
    write(text.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}

private fun ByteArrayOutputStream.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

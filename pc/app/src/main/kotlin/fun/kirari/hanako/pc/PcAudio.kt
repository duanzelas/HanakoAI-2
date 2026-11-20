package `fun`.kirari.hanako.pc

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** 一段录好的音频。存裸 PCM，需要上传时再按需补 WAV 头（长录音要切片）。 */
data class PcRecording(
    val pcm: ByteArray,
    val durationMillis: Long,
    val peakAmplitude: Int,
    /** 峰值过低：基本可以判定没录到声音，别浪费一次转写请求。 */
    val silent: Boolean
) {
    val wav: ByteArray get() = PcWav.wrapPcm16(pcm)
}

/**
 * 麦克风录音。
 *
 * 桌面端没有 AudioPlaybackCapture 那种系统内录（Java Sound 拿不到 WASAPI loopback），
 * 所以 PC 端语音固定走麦克风——这也是 Android 端菜单里那个「麦克风」入口的等价物。
 *
 * 采样参数按 DeepSeek ASR 的要求固定成 16 kHz / 单声道 / 16 位 PCM，
 * 录音结束后只在内存里补一个 WAV 头，不落临时文件。
 */
class PcRecorder {

    @Volatile
    private var running = false
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var peak = 0
    private var startedAtNanos = 0L

    val recording: Boolean get() = running

    /** 打开麦克风；被占用或没有输入设备时返回 false，调用方负责提示。 */
    fun start(): Boolean {
        if (running) return true
        val format = AudioFormat(SAMPLE_RATE.toFloat(), BITS, CHANNELS, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) return false
        val opened = runCatching {
            (AudioSystem.getLine(info) as TargetDataLine).apply {
                open(format, BUFFER_BYTES * 4)
                start()
            }
        }.getOrNull() ?: return false

        synchronized(this) {
            buffer.reset()
            peak = 0
            line = opened
            running = true
            startedAtNanos = System.nanoTime()
        }
        worker = Thread({ readLoop(opened) }, "hanako-pc-recorder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun readLoop(target: TargetDataLine) {
        val chunk = ByteArray(4096)
        while (running) {
            val read = runCatching { target.read(chunk, 0, chunk.size) }.getOrDefault(-1)
            if (read <= 0) continue
            synchronized(this) {
                if (!running) return
                buffer.write(chunk, 0, read)
                val chunkPeak = PcWav.peakAmplitude(chunk, read)
                if (chunkPeak > peak) peak = chunkPeak
            }
        }
    }

    /** 结束录音并返回 WAV；没在录时返回 null。 */
    fun stop(): PcRecording? {
        val target: TargetDataLine?
        val thread: Thread?
        synchronized(this) {
            if (!running) return null
            running = false
            target = line
            thread = worker
            line = null
            worker = null
        }
        // 先 stop 打断阻塞的 read()，再 join，否则要等满缓冲区
        runCatching { target?.stop() }
        runCatching { target?.close() }
        runCatching { thread?.join(600) }

        val pcm: ByteArray
        val peakValue: Int
        synchronized(this) {
            pcm = buffer.toByteArray()
            peakValue = peak
            buffer.reset()
            peak = 0
        }
        if (pcm.isEmpty()) return null
        val duration = PcWav.durationMillis(pcm.size)
        return PcRecording(
            pcm = pcm,
            durationMillis = duration,
            peakAmplitude = peakValue,
            silent = peakValue < SILENCE_THRESHOLD
        )
    }

    /** 放弃这次录音，不返回数据。 */
    fun cancel() {
        stop()
    }

    /** 已录时长，给界面做计时用。 */
    fun elapsedMillis(): Long {
        if (!running) return 0L
        return (System.nanoTime() - startedAtNanos) / 1_000_000L
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS = 16
        private const val BUFFER_BYTES = 8192

        /** 低于这个峰值基本就是静音（环境底噪通常在 50 以内）。 */
        const val SILENCE_THRESHOLD = 220
    }
}

/**
 * PCM ↔ WAV 封装，逻辑与 Android 端 `WavCodec` 一致。
 *
 * 两端各自持有一份而不是抽到 llm-core：那层是模型协议，塞音频容器不合适；
 * 这里只有 40 行，重复的代价比错误的分层小。
 */
object PcWav {
    const val SAMPLE_RATE = PcRecorder.SAMPLE_RATE
    const val CHANNELS = PcRecorder.CHANNELS
    const val BITS_PER_SAMPLE = PcRecorder.BITS

    fun wrapPcm16(
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS
    ): ByteArray {
        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8
        val out = ByteArrayOutputStream(pcm.size + 44)
        out.writeAscii("RIFF")
        out.writeIntLe(36 + pcm.size)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeIntLe(16)
        out.writeShortLe(1)
        out.writeShortLe(channels)
        out.writeIntLe(sampleRate)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(BITS_PER_SAMPLE)
        out.writeAscii("data")
        out.writeIntLe(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** 按 110 秒切片：ASR 单次上限 120 秒，长录音分多次请求再拼接。 */
    fun chunkPcmAsWav(pcm: ByteArray, maxChunkMillis: Long = 110_000L): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        val bytesPerMillis = (SAMPLE_RATE.toLong() * CHANNELS * BITS_PER_SAMPLE / 8) / 1000L
        val aligned = ((bytesPerMillis * maxChunkMillis).toInt() / 2) * 2
        val chunkSize = aligned.coerceAtLeast(2)
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunkSize, pcm.size)
            chunks += wrapPcm16(pcm.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    fun peakAmplitude(pcm: ByteArray, length: Int = pcm.size): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val abs = if (sample == Short.MIN_VALUE.toInt()) 32767 else kotlin.math.abs(sample)
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    fun durationMillis(pcmSize: Int): Long {
        val bytesPerSecond = SAMPLE_RATE.toLong() * CHANNELS * BITS_PER_SAMPLE / 8
        if (bytesPerSecond <= 0) return 0L
        return pcmSize * 1000L / bytesPerSecond
    }
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

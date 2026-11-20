package `fun`.kirari.hanako.pc

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/** 一段录好的音频。存裸 PCM（16 kHz 单声道），上传时再补 WAV 头。 */
data class PcRecording(
    val pcm: ByteArray,
    val durationMillis: Long,
    val peakAmplitude: Int,
    /** 峰值过低：基本可以判定没录到声音，别浪费一次转写请求。 */
    val silent: Boolean
) {
    val wav: ByteArray get() = PcWav.wrapPcm16(pcm)
}

/** 开始录音的结果。失败原因要能直接展示给用户，所以不用 Boolean。 */
sealed interface RecordStart {
    data object Ok : RecordStart

    /** 系统内录：机器上没有「立体声混音 / Stereo Mix」这类回环设备。 */
    data object NoSystemDevice : RecordStart

    /** 麦克风打不开：被占用或没有输入设备。 */
    data object NoMicrophone : RecordStart

    data class Failed(val message: String) : RecordStart
}

/**
 * 录音。
 *
 * 两种音源：
 * - [AudioSource.SYSTEM]：系统声音，走声卡的「立体声混音 / Stereo Mix」回环输入。
 *   Java Sound 没有 WASAPI loopback 的 API，只能借这个回环设备；Realtek 等驱动自带，
 *   但默认常被禁用，所以找不到时明确报 [RecordStart.NoSystemDevice]，让人去系统设置里启用，
 *   而不是偷偷换成麦克风（那样录到的全是环境噪音，用户完全无法判断哪里错了）。
 * - [AudioSource.MIC]：麦克风。
 *
 * 统一产出 16 kHz / 单声道 / 16 位 PCM。设备不接受这个格式时（部分回环设备只给
 * 44.1k 立体声），按设备支持的格式打开，读线程里做下混 + 线性重采样。
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

    fun start(source: AudioSource): RecordStart {
        if (running) return RecordStart.Ok
        val opened = synchronized(this) {
            when (source) {
                AudioSource.SYSTEM -> openSystem() ?: return RecordStart.NoSystemDevice
                AudioSource.MIC -> openMic() ?: return RecordStart.NoMicrophone
            }
        }
        val ready = runCatching {
            opened.line.open(opened.format, BUFFER_BYTES * 4)
            opened.line.start()
            opened
        }.getOrElse { return RecordStart.Failed(it.message ?: "打开录音设备失败") }

        synchronized(this) {
            buffer.reset()
            peak = 0
            line = ready.line
            running = true
            startedAtNanos = System.nanoTime()
        }
        worker = Thread({ readLoop(ready) }, "hanako-pc-recorder").apply {
            isDaemon = true
            start()
        }
        return RecordStart.Ok
    }

    private fun readLoop(opened: OpenedLine) {
        val chunk = ByteArray(8192)
        val needsConvert = opened.format.sampleRate != 16_000f || opened.format.channels != 1
        while (running) {
            val read = runCatching { opened.line.read(chunk, 0, chunk.size) }.getOrDefault(-1)
            if (read <= 0) continue
            val pcm = if (needsConvert) {
                PcmConvert.to16kMono(chunk, read, opened.format)
            } else {
                chunk.copyOf(read)
            }
            if (pcm.isEmpty()) continue
            synchronized(this) {
                if (!running) return
                buffer.write(pcm, 0, pcm.size)
                val chunkPeak = PcWav.peakAmplitude(pcm)
                if (chunkPeak > peak) peak = chunkPeak
            }
        }
    }

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

    fun cancel() {
        stop()
    }

    fun elapsedMillis(): Long {
        if (!running) return 0L
        return (System.nanoTime() - startedAtNanos) / 1_000_000L
    }

    // ------------------------------------------------------------ 设备

    private class OpenedLine(val line: TargetDataLine, val format: AudioFormat)

    /** 依次尝试 16k 单声道（理想）、44.1k/48k 立体声（回环设备常见），取第一个能开的。 */
    private fun openFrom(mixer: Mixer?): OpenedLine? {
        for (format in CANDIDATE_FORMATS) {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val supported = runCatching {
                if (mixer != null) mixer.isLineSupported(info) else AudioSystem.isLineSupported(info)
            }.getOrDefault(false)
            if (!supported) continue
            val opened = runCatching {
                if (mixer != null) mixer.getLine(info) as TargetDataLine
                else AudioSystem.getLine(info) as TargetDataLine
            }.getOrNull()
            if (opened != null) return OpenedLine(opened, format)
        }
        return null
    }

    private fun openMic(): OpenedLine? {
        // 先让系统挑默认输入设备；不行再退回默认混音器上的输入线
        openFrom(null)?.let { return it }
        return openFrom(AudioSystem.getMixer(null))
    }

    private fun openSystem(): OpenedLine? {
        for (mixer in systemMixers()) {
            openFrom(mixer)?.let { return it }
        }
        return null
    }

    private fun systemMixers(): List<Mixer> {
        return AudioSystem.getMixerInfo()
            .filter { info ->
                val name = info.name.lowercase()
                SYSTEM_DEVICE_KEYWORDS.any { name.contains(it) }
            }
            .mapNotNull { info -> runCatching { AudioSystem.getMixer(info) }.getOrNull() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS = 16
        private const val BUFFER_BYTES = 8192

        /** 低于这个峰值基本就是静音（环境底噪通常在 50 以内）。 */
        const val SILENCE_THRESHOLD = 220

        private val CANDIDATE_FORMATS = listOf(
            AudioFormat(16_000f, 16, 1, true, false),
            AudioFormat(44_100f, 16, 2, true, false),
            AudioFormat(48_000f, 16, 2, true, false)
        )

        /**
         * 回环设备的名字。各家驱动叫法不同：Realtek 中文叫「立体声混音」，
         * 英文是 Stereo Mix，Creative 叫 What U Hear，虚拟声卡常带 Loopback / 监视器。
         */
        private val SYSTEM_DEVICE_KEYWORDS = listOf(
            "立体声混音", "stereo mix", "what u hear", "wave out",
            "loopback", "监视器", "monitor of"
        )

        /** 当前可用的系统内录音源名称；空表表示需要去系统设置里启用「立体声混音」。 */
        fun systemDevices(): List<String> {
            return AudioSystem.getMixerInfo()
                .filter { info ->
                    val name = info.name.lowercase()
                    SYSTEM_DEVICE_KEYWORDS.any { name.contains(it) }
                }
                .filter { info ->
                    // 只保留真能开 TargetDataLine 的设备，别把「Port xxx」列出来
                    runCatching {
                        val mixer = AudioSystem.getMixer(info)
                        CANDIDATE_FORMATS.any { format ->
                            mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, format))
                        }
                    }.getOrDefault(false)
                }
                .map { it.name }
        }

        fun hasSystemAudio(): Boolean = systemDevices().isNotEmpty()
    }
}

/**
 * 把任意 PCM 16 位输入转成 16 kHz 单声道。
 *
 * 只在设备不直接支持 16k 单声道时用到。每个读块独立重采样，边界会有极小的相位跳变，
 * 对语音识别没有实际影响（本来就要过一遍 ASR 的降噪与分帧）。
 */
object PcmConvert {

    fun to16kMono(src: ByteArray, length: Int, format: AudioFormat): ByteArray {
        val channels = format.channels.coerceAtLeast(1)
        val frameBytes = 2 * channels
        val frames = length / frameBytes
        if (frames <= 0) return ByteArray(0)

        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val idx = i * frameBytes + c * 2
                sum += readSample(src, idx, format.isBigEndian)
            }
            mono[i] = sum / channels
        }

        val srcRate = format.sampleRate
        if (srcRate == 16_000f) return toBytes(mono)

        val ratio = srcRate / 16_000f
        val outFrames = (frames / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outFrames)
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = pos - i0
            out[i] = mono[i0] * (1 - frac) + mono[i1] * frac
        }
        return toBytes(out)
    }

    private fun readSample(src: ByteArray, offset: Int, bigEndian: Boolean): Float {
        if (offset + 1 >= src.size) return 0f
        val lo: Int
        val hi: Int
        if (bigEndian) {
            hi = src[offset].toInt()
            lo = src[offset + 1].toInt() and 0xFF
        } else {
            lo = src[offset].toInt() and 0xFF
            hi = src[offset + 1].toInt()
        }
        return ((hi shl 8) or lo).toShort().toFloat()
    }

    private fun toBytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            val v = value.toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
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

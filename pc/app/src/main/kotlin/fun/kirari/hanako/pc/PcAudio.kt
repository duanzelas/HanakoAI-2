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

/**
 * 这次录音开哪几路。
 *
 * 两路可以同时开：系统声音（视频里的人声）+ 麦克风（自己说话），混成一条后一起送去转写。
 * 这是最常见的网课/会议场景——光录系统声音听不到自己提问，光录麦克风听不到对方。
 */
data class RecordOptions(
    val system: Boolean = true,
    val mic: Boolean = false
) {
    val any: Boolean get() = system || mic

    val label: String
        get() = when {
            system && mic -> "系统声音 + 麦克风"
            system -> "系统声音"
            mic -> "麦克风"
            else -> "未选择音源"
        }
}

/** 开始录音的结果。失败原因要能直接展示给用户，所以不用 Boolean。 */
sealed interface RecordStart {
    /**
     * 成功。[sources] 是真正开起来的音源，[skipped] 是用户开了但设备不可用而跳过的，
     * 两者都要显示出来——静默跳过会让人以为「我明明开了麦克风，怎么没录进去」。
     */
    data class Ok(val sources: List<String>, val skipped: List<String>) : RecordStart

    /** 系统内录：机器上没有「立体声混音 / Stereo Mix」这类回环设备。 */
    data object NoSystemDevice : RecordStart

    /** 麦克风打不开：被占用或没有输入设备。 */
    data object NoMicrophone : RecordStart

    /** 两路都关着。 */
    data object NoSource : RecordStart

    data class Failed(val message: String) : RecordStart
}

/**
 * 录音。
 *
 * 音源：
 * - 系统声音：走声卡的「立体声混音 / Stereo Mix」回环输入。Java Sound 没有
 *   WASAPI loopback 的 API，只能借这个设备；Realtek 等驱动自带，但默认常被禁用。
 * - 麦克风：普通输入设备。
 *
 * 两路同时开时各自跑一个读线程，统一转成 16 kHz / 单声道 / 16 位 PCM 后进各自队列，
 * 再由混音线程按「所有队列都够一段」的节奏取出、逐样本相加（超出就削顶）。
 * 短录音（上限 110 秒）的两个设备时钟漂移可以忽略，不必做重采样对齐。
 *
 * 设备不接受 16k 单声道时（部分回环设备只给 44.1k 立体声），按设备支持的格式打开，
 * 读线程里做下混 + 线性重采样。
 */
class PcRecorder {

    private class Channel(
        val name: String,
        val line: TargetDataLine,
        val format: AudioFormat
    ) {
        /** 已转成 16k 单声道的 PCM 块，按到达顺序排队。 */
        val queue = ArrayDeque<ByteArray>()
    }

    private val lock = Any()

    @Volatile
    private var running = false

    private val channels = ArrayList<Channel>()
    private val readers = ArrayList<Thread>()
    private var mixer: Thread? = null

    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var peak = 0

    private var startedAtNanos = 0L

    /** 本次实际开起来的音源名，用于状态栏展示。 */
    @Volatile
    var activeSources: List<String> = emptyList()
        private set

    val recording: Boolean get() = running

    fun start(options: RecordOptions): RecordStart {
        if (running) return RecordStart.Ok(activeSources, emptyList())
        if (!options.any) return RecordStart.NoSource

        val opened = ArrayList<Channel>()
        val skipped = ArrayList<String>()

        if (options.system) {
            val line = openSystem()
            if (line == null) {
                // 只开了系统内录：直接失败，让人去启用设备
                if (!options.mic) return RecordStart.NoSystemDevice
                skipped += "系统声音（未找到「立体声混音」设备）"
            } else {
                val channel = runCatching {
                    line.line.open(line.format, READ_CHUNK_BYTES * 2)
                    line.line.start()
                    Channel("系统声音", line.line, line.format)
                }.getOrElse {
                    runCatching { line.line.close() }
                    skipped += "系统声音（${it.message ?: "设备打不开"}）"
                    null
                }
                if (channel != null) opened += channel
            }
        }

        if (options.mic) {
            val line = openMic()
            if (line == null) {
                if (opened.isEmpty()) return RecordStart.NoMicrophone
                skipped += "麦克风（被占用或没有输入设备）"
            } else {
                val channel = runCatching {
                    line.line.open(line.format, READ_CHUNK_BYTES * 2)
                    line.line.start()
                    Channel("麦克风", line.line, line.format)
                }.getOrElse {
                    runCatching { line.line.close() }
                    if (opened.isEmpty()) return RecordStart.Failed(it.message ?: "麦克风打不开")
                    skipped += "麦克风（${it.message ?: "设备打不开"}）"
                    null
                }
                if (channel != null) opened += channel
            }
        }

        if (opened.isEmpty()) {
            return RecordStart.Failed(skipped.joinToString("；").ifBlank { "没有可用的录音设备" })
        }

        synchronized(lock) {
            channels.clear()
            channels += opened
            readers.clear()
            buffer.reset()
            peak = 0
            running = true
            startedAtNanos = System.nanoTime()
            activeSources = opened.map { it.name }
        }

        val readThreads = opened.map { channel ->
            Thread({ readLoop(channel) }, "hanako-pc-rec-${channel.name}").apply {
                isDaemon = true
                start()
            }
        }
        val mixThread = Thread({ mixLoop() }, "hanako-pc-mixer").apply {
            isDaemon = true
            start()
        }
        synchronized(lock) {
            readers += readThreads
            mixer = mixThread
        }
        return RecordStart.Ok(activeSources, skipped)
    }

    private fun readLoop(channel: Channel) {
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val needsConvert =
            channel.format.sampleRate != 16_000f || channel.format.channels != 1
        while (running) {
            val read = runCatching { channel.line.read(chunk, 0, chunk.size) }.getOrDefault(-1)
            if (read <= 0) {
                // -1 表示线被关掉了，正常结束
                if (read < 0) return
                continue
            }
            val pcm = if (needsConvert) {
                PcmConvert.to16kMono(chunk, read, channel.format)
            } else {
                chunk.copyOf(read)
            }
            if (pcm.isEmpty()) continue
            synchronized(lock) {
                // 收尾时 read 可能返回最后半块，先收下再退出，别把尾巴扔掉
                channel.queue.addLast(pcm)
                // 兜底：某一路异常快地推数据时不把内存吃光
                while (channel.queue.size > MAX_QUEUE_CHUNKS) channel.queue.removeFirst()
            }
            if (!running) return
        }
    }

    private fun mixLoop() {
        while (running) {
            val mixed = takeMixed()
            if (mixed == null) {
                runCatching { Thread.sleep(8) }
                continue
            }
            synchronized(lock) {
                buffer.write(mixed, 0, mixed.size)
            }
            val chunkPeak = PcWav.peakAmplitude(mixed)
            if (chunkPeak > peak) peak = chunkPeak
        }
    }

    /**
     * 取一段可混的音：所有通道里「最少的那一路」剩余量就是本次能取的量，
     * 这样两路不会错位。取不到（某一路还没数据）就返回 null 让混音线程歇一下。
     */
    private fun takeMixed(): ByteArray? {
        synchronized(lock) {
            if (channels.isEmpty()) return null
            var available = Int.MAX_VALUE
            for (channel in channels) {
                val size = channel.queue.sumOf { it.size }
                if (size < available) available = size
            }
            if (available == Int.MAX_VALUE || available < 2) return null
            val take = minOf(available, MIX_CHUNK_BYTES) / 2 * 2
            if (take <= 0) return null

            val parts = channels.map { drain(it, take) }
            if (parts.size == 1) return parts[0]

            val out = ByteArray(take)
            var i = 0
            while (i + 1 < take) {
                var sum = 0
                for (part in parts) {
                    if (i + 1 < part.size) sum += sampleAt(part, i)
                }
                writeSample(out, i, sum.coerceIn(-32768, 32767))
                i += 2
            }
            return out
        }
    }

    /** 从队列头部取走 [want] 字节；不足就返回实际取到的（其余当静音）。 */
    private fun drain(channel: Channel, want: Int): ByteArray {
        val out = ByteArray(want)
        var filled = 0
        while (filled < want) {
            val head = channel.queue.firstOrNull() ?: break
            val need = want - filled
            if (head.size <= need) {
                head.copyInto(out, filled)
                filled += head.size
                channel.queue.removeFirst()
            } else {
                head.copyInto(out, filled, 0, need)
                channel.queue[0] = head.copyOfRange(need, head.size)
                filled += need
            }
        }
        return if (filled == want) out else out.copyOf(filled)
    }

    fun stop(): PcRecording? {
        val target: List<Channel>
        val threads: List<Thread>
        val mixThread: Thread?
        synchronized(lock) {
            if (!running) return null
            running = false
            target = channels.toList()
            threads = readers.toList()
            mixThread = mixer
            readers.clear()
            mixer = null
        }
        // 先 stop 打断阻塞的 read()，再 join，否则要等满缓冲区
        target.forEach {
            runCatching { it.line.stop() }
            runCatching { it.line.close() }
        }
        threads.forEach { runCatching { it.join(600) } }
        runCatching { mixThread?.join(600) }

        // 把两路各自剩下的尾巴也混进去，别丢掉最后半秒
        var guard = 0
        while (guard++ < 200) {
            val rest = takeMixed() ?: break
            synchronized(lock) { buffer.write(rest, 0, rest.size) }
            val chunkPeak = PcWav.peakAmplitude(rest)
            if (chunkPeak > peak) peak = chunkPeak
        }

        val pcm: ByteArray
        val peakValue: Int
        synchronized(lock) {
            pcm = buffer.toByteArray()
            peakValue = peak
            buffer.reset()
            peak = 0
            channels.clear()
            activeSources = emptyList()
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
        /**
         * 单次 read 的字节数。16k 单声道下 4096 字节 ≈ 128 ms：
         * 停止时要等这一次 read 返回，块越大，末尾被丢掉的部分越多。
         */
        private const val READ_CHUNK_BYTES = 4096

        /** 单次混音最多取多少字节（约 64 ms），小块能让开始/结束的延迟更小。 */
        private const val MIX_CHUNK_BYTES = 2048

        /** 单路排队上限，正常远达不到；只在设备异常推流时兜底。 */
        private const val MAX_QUEUE_CHUNKS = 600

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

        private fun sampleAt(pcm: ByteArray, offset: Int): Int {
            val lo = pcm[offset].toInt() and 0xFF
            val hi = pcm[offset + 1].toInt()
            return ((hi shl 8) or lo).toShort().toInt()
        }

        private fun writeSample(out: ByteArray, offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
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

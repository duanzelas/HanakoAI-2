package `fun`.kirari.hanako.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import `fun`.kirari.hanako.platform.capture.projection.ProjectionSessionManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录音引擎。
 *
 * 两种音源：
 * - [Source.PLAYBACK]：`AudioPlaybackCapture`，直接拿系统混音里的媒体声音。需要 MediaProjection，
 *   但不受环境噪音影响，戴耳机也能录到视频声。
 * - [Source.MIC]：麦克风兜底。没有投影会话（例如用 Shizuku 截图），或目标应用声明了
 *   `ALLOW_CAPTURE_BY_NONE`、用 `USAGE_VOICE_COMMUNICATION`（微信语音就是这类）时只能走这条。
 *
 * 录音结果统一是 16 kHz 单声道 16 位 PCM，交给 [WavCodec] 封装。
 */
internal object AudioRecorderHub {
    private const val TAG = "HanakoAudioRecorder"
    private const val READ_BUFFER_BYTES = 4096
    private const val MAX_DURATION_MILLIS = 300_000L

    enum class Source { PLAYBACK, MIC }

    data class Recording(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val durationMillis: Long,
        val peakAmplitude: Int,
        val source: Source
    ) {
        val isSilent: Boolean get() = peakAmplitude < SILENCE_THRESHOLD
        fun toWav(): ByteArray = WavCodec.wrapPcm16(pcm, sampleRate, channels)

        private companion object {
            /** 大约 -54 dBFS，低于此值基本可认定没录到任何声音。 */
            const val SILENCE_THRESHOLD = 64
        }
    }

    class AudioRecordFailure(message: String) : Exception(message)

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var output: ByteArrayOutputStream? = null
    private var bytesWritten: Long = 0
    private var peak: Int = 0
    private var activeSource: Source = Source.MIC
    private var startedAtMillis: Long = 0
    private var readBufferBytes: Int = READ_BUFFER_BYTES

    val isRecording: Boolean get() = running.get()

    val elapsedMillis: Long
        get() {
            if (!running.get()) return 0L
            return (bytesWritten * 1000L) / (WavCodec.SAMPLE_RATE.toLong() * WavCodec.CHANNELS * 2)
        }

    val currentSource: Source? get() = if (running.get()) activeSource else null

    /**
     * 开始录音。
     *
     * 音源由调用方显式指定，不再自动降级：内录（[Source.PLAYBACK]）失败就直接报错，
     * 让用户自己决定要不要改用麦克风按钮——静默切麦克风会把环境噪音当成题目音频，
     * 比明确失败更难排查。
     */
    @Synchronized
    fun start(context: Context, source: Source): Source {
        if (running.get()) return activeSource
        val projection = if (source == Source.PLAYBACK) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw AudioRecordFailure("系统内录需要 Android 10 及以上版本")
            }
            ProjectionSessionManager.currentProjection()
                ?: throw AudioRecordFailure("没有可用的屏幕录制会话，无法内录，请改用麦克风录音")
        } else {
            null
        }
        val record = createRecord(context, source, projection)
        begin(record, source)
        return source
    }

    /**
     * 停止录音并取回结果。
     *
     * 不能持锁 join 工作线程：读线程每读到一块都要 `synchronized(this)` 写缓冲，
     * 一旦 `stop()` 拿着锁等它退出，join 必然等到超时（1.5 秒）才继续——调用方在主线程上，
     * 表现就是「点了停止要卡一下」。所以先把状态搬出来，再停 record 打断阻塞的 read()，
     * 最后才在锁内收尾。
     */
    fun stop(): Recording? {
        val thread: Thread?
        val record: AudioRecord?
        var recording: Recording? = null
        synchronized(this) {
            if (!running.get() && output == null) return null
            running.set(false)
            thread = worker
            worker = null
            record = audioRecord
            audioRecord = null
        }
        // 先停 AudioRecord，readLoop 里阻塞的 read() 才会立刻返回，不需要靠 join 超时兜底
        runCatching { record?.stop() }
        runCatching { thread?.join(800) }
        runCatching { record?.release() }

        synchronized(this) {
            val buffer = output
            output = null
            if (buffer != null) {
                val pcm = buffer.toByteArray()
                val duration = WavCodec.durationMillis(pcm.size, WavCodec.SAMPLE_RATE, WavCodec.CHANNELS)
                val result = Recording(
                    pcm = pcm,
                    sampleRate = WavCodec.SAMPLE_RATE,
                    channels = WavCodec.CHANNELS,
                    durationMillis = duration,
                    peakAmplitude = peak,
                    source = activeSource
                )
                recording = result
                AppDebugLogStore.i(
                    TAG,
                    "recording stopped source=${result.source} bytes=${pcm.size} " +
                        "durationMs=$duration peak=$peak"
                )
            }
            bytesWritten = 0
            peak = 0
        }
        return recording
    }

    fun cancel() {
        val thread: Thread?
        val record: AudioRecord?
        synchronized(this) {
            running.set(false)
            thread = worker
            record = audioRecord
            worker = null
            audioRecord = null
        }
        runCatching { record?.stop() }
        runCatching { thread?.join(800) }
        runCatching { record?.release() }
        synchronized(this) {
            output = null
            bytesWritten = 0
            peak = 0
        }
    }

    private fun begin(record: AudioRecord, source: Source) {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            throw AudioRecordFailure("录音初始化失败，可能没有录音权限")
        }
        audioRecord = record
        activeSource = source
        output = ByteArrayOutputStream(WavCodec.SAMPLE_RATE * 2 * 30)
        bytesWritten = 0
        peak = 0
        startedAtMillis = android.os.SystemClock.elapsedRealtime()
        running.set(true)
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running.set(false)
            runCatching { record.release() }
            audioRecord = null
            output = null
            throw AudioRecordFailure("录音启动失败，可能被系统或其它应用占用")
        }
        worker = Thread({ readLoop() }, "hanako-audio-record").apply {
            isDaemon = true
            start()
        }
        AppDebugLogStore.i(
            TAG,
            "recording started source=$source state=${record.state} " +
                "sampleRate=${record.sampleRate} session=${record.audioSessionId}"
        )
    }

    private fun readLoop() {
        val record = audioRecord ?: return
        val buffer = ByteArray(readBufferBytes)
        try {
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        AppDebugLogStore.e(TAG, "record read error=$read")
                        break
                    }
                    continue
                }
                synchronized(this) {
                    output?.write(buffer, 0, read)
                    bytesWritten += read
                    val chunkPeak = WavCodec.peakAmplitude(buffer.copyOf(read))
                    if (chunkPeak > peak) peak = chunkPeak
                }
                if (elapsedMillis >= MAX_DURATION_MILLIS) {
                    AppDebugLogStore.i(TAG, "recording reached max duration, auto stop")
                    break
                }
            }
        } catch (error: Throwable) {
            AppDebugLogStore.e(TAG, "record loop crashed", error)
        } finally {
            // 到时长上限时主动收尾，UI 侧随后调用 stop() 取结果
            running.set(false)
        }
    }

    private fun createRecord(
        context: Context,
        source: Source,
        projection: MediaProjection?
    ): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(WavCodec.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        if (source == Source.PLAYBACK) {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "系统内录需要 Android 10+" }
            requireNotNull(projection) { "系统内录需要屏幕录制会话" }
            @Suppress("DEPRECATION")
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                WavCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(READ_BUFFER_BYTES * 4)
            readBufferBytes = READ_BUFFER_BYTES
            AppDebugLogStore.i(
                TAG,
                "create playback record sampleRate=${WavCodec.SAMPLE_RATE} minBuffer=$minBuffer " +
                    "projection=${projection.javaClass.simpleName}"
            )
            return AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            WavCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(READ_BUFFER_BYTES * 4)
        readBufferBytes = READ_BUFFER_BYTES
        @Suppress("MissingPermission")
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            WavCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
    }
}

package ru.jarvis.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Офлайн-детектор слова «Джарвис» (sherpa-onnx, крошечная модель ~3 МБ).
 * Слушает микрофон в отдельном потоке и вызывает [onWake], когда услышит слово.
 * Звук никуда не отправляется и не сохраняется.
 */
class WakeWord(private val context: Context, private val onWake: () -> Unit) {

    private var spotter: KeywordSpotter? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    fun init(): Boolean = synchronized(LOCK) {
        try {
            val dir = File(context.filesDir, DIR)
            val marker = File(dir, ".ready-$DATA_VERSION")
            if (!marker.exists()) {
                dir.deleteRecursively()
                copyAssets(DIR, dir)
                marker.createNewFile()
            }
            val model = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.onnx").absolutePath,
                    decoder = File(dir, "decoder.onnx").absolutePath,
                    joiner = File(dir, "joiner.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                modelType = "zipformer2",
                numThreads = 1,
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                keywordsFile = File(dir, "keywords.txt").absolutePath,
            )
            spotter = KeywordSpotter(assetManager = null, config = config)
            true
        } catch (e: Throwable) {
            false
        }
    }

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        val kws = spotter ?: return
        if (running) return
        running = true
        worker = Thread {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE)
                )
            } catch (e: Throwable) {
                running = false
                return@Thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release(); running = false; return@Thread
            }
            val stream = kws.createStream()
            val buffer = ShortArray(SAMPLE_RATE / 10)          // 100 мс
            var woke = false
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        if (kws.getResult(stream).keyword.isNotBlank()) {
                            kws.reset(stream)
                            woke = true
                            running = false
                            break
                        }
                    }
                }
            } catch (e: Throwable) {
                running = false
            } finally {
                try { record.stop() } catch (_: Throwable) {}
                record.release()
                stream.release()
            }
            if (woke) onWake()
        }.apply { name = "jarvis-wake"; start() }
    }

    /** Останавливает прослушивание и ждёт, пока микрофон освободится. */
    fun stop() {
        running = false
        val w = worker
        if (w != null && w !== Thread.currentThread()) {
            try { w.join(1500) } catch (_: InterruptedException) {}
        }
        worker = null
    }

    fun release() {
        stop()
        spotter?.release()
        spotter = null
    }

    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            for (child in children) copyAssets("$path/$child", File(target, child))
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val DIR = "wakeword"
        private const val DATA_VERSION = 1
        private val LOCK = Any()
    }
}

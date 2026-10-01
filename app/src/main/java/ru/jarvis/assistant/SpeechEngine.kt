package ru.jarvis.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Офлайн-распознавание русской речи прямо на телефоне (sherpa-onnx, модель ~25 МБ).
 * Работает в фоне без интернета и без Google: слушает микрофон непрерывно и после
 * каждой законченной фразы (пауза ~0,8 с) отдаёт её текст в [onPhrase].
 */
class SpeechEngine(private val context: Context, private val onPhrase: (String) -> Unit) {

    private var recognizer: OnlineRecognizer? = null
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
                numThreads = 2,
            )
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.0f, 0.0f),   // долгая тишина без речи
                    rule2 = EndpointRule(true, 0.8f, 0.0f),    // пауза 0,8 с после речи — фраза закончена
                    rule3 = EndpointRule(false, 0.0f, 15.0f),  // не дольше 15 с на фразу
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            recognizer = OnlineRecognizer(assetManager = null, config = config)
            true
        } catch (e: Throwable) {
            false
        }
    }

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        val rec = recognizer ?: return
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
            val stream = rec.createStream()
            val buffer = ShortArray(SAMPLE_RATE / 10)          // 100 мс
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (rec.isReady(stream)) rec.decode(stream)
                    if (rec.isEndpoint(stream)) {
                        val text = rec.getResult(stream).text.trim()
                        rec.reset(stream)
                        if (text.isNotEmpty() && running) onPhrase(text)
                    }
                }
            } catch (e: Throwable) {
                running = false
            } finally {
                try { record.stop() } catch (_: Throwable) {}
                record.release()
                stream.release()
            }
        }.apply { name = "jarvis-asr"; start() }
    }

    /** Останавливает прослушивание и освобождает микрофон. */
    @Synchronized
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
        recognizer?.release()
        recognizer = null
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
        private const val DIR = "asr"
        private const val DATA_VERSION = 1
        private val LOCK = Any()

        private val WAKE_VARIANTS = setOf(
            "джарвис", "джарвиса", "джарвису", "джервис", "жарвис", "дарвис", "джарвиз", "джаврис",
            "чарвис", "гарвис", "жервис", "джарви", "jarvis", "джарвик", "зарвис", "ярвис"
        )

        /**
         * Ищет обращение «Джарвис» среди первых слов фразы (с учётом ошибок распознавания).
         * Возвращает текст команды после обращения ("" — только имя) или null, если обращения нет.
         */
        fun extractCommand(phrase: String): String? {
            val words = phrase.lowercase().replace('ё', 'е').split(Regex("\\s+")).filter { it.isNotBlank() }
            for (i in 0 until minOf(4, words.size)) {
                val one = words[i].trim(',', '.', '!', '?')
                if (isWake(one)) return words.drop(i + 1).joinToString(" ")
                if (i + 1 < words.size) {
                    val two = one + words[i + 1].trim(',', '.', '!', '?')      // «джар вис»
                    if (isWake(two)) return words.drop(i + 2).joinToString(" ")
                }
            }
            return null
        }

        private fun isWake(w: String): Boolean =
            w in WAKE_VARIANTS || (w.length in 5..9 && similarity(w, "джарвис") >= 0.75)

        private fun similarity(a: String, b: String): Double {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            }
            return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
        }
    }
}

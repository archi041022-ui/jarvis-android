package ru.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Офлайн-голос Джарвиса: Piper «Руслан» (через sherpa-onnx) + эффект ИИ —
 * короткое металлическое эхо и лёгкий цифровой призвук, как на ноутбуке.
 * Работает без интернета. Если что-то пошло не так, ready = false,
 * и приложение говорит стандартным голосом Android.
 */
class JarvisVoice(private val context: Context) {

    @Volatile var ready = false
        private set
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false
    private var tts: OfflineTts? = null

    /** Подготовка голоса. Вызывать в фоновом потоке: при первом запуске копирует ~25 МБ. */
    fun init(): Boolean = synchronized(LOCK) {
        try {
            val dir = File(context.filesDir, VOICE_DIR)
            val marker = File(dir, ".ready-$DATA_VERSION")
            if (!marker.exists()) {
                dir.deleteRecursively()
                copyAssets(VOICE_DIR, dir)
                marker.createNewFile()
            }
            val vits = OfflineTtsVitsModelConfig(
                model = File(dir, "model.onnx").absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath,
                dataDir = File(dir, "espeak-ng-data").absolutePath,
            )
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"),
            )
            tts = OfflineTts(assetManager = null, config = config)
            ready = true
            true
        } catch (e: Throwable) {
            ready = false
            false
        }
    }

    /**
     * Озвучивает [text] и блокирует поток до конца фразы. Вызывать в фоновом потоке.
     * [speed] — 1.0 обычная скорость; [aiEffect] — эффект «голоса ИИ».
     */
    fun speakBlocking(text: String, speed: Float, aiEffect: Boolean): Boolean {
        val engine = tts ?: return false
        stopped = false
        return try {
            val audio = engine.generate(text = text, sid = 0, speed = speed)
            if (stopped || audio.samples.isEmpty()) return true
            val samples = if (aiEffect) aiEffect(audio.samples, audio.sampleRate) else audio.samples
            play(toPcm16(samples), audio.sampleRate)
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun stop() {
        stopped = true
        try {
            track?.pause(); track?.flush(); track?.stop()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
        ready = false
    }

    // ───────────── Звук ─────────────

    private fun play(pcm: ShortArray, sampleRate: Int) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            val deadline = System.currentTimeMillis() + pcm.size * 1000L / sampleRate + 1500
            while (!stopped && t.playbackHeadPosition < pcm.size && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        } finally {
            try { t.stop() } catch (_: Throwable) {}
            t.release()
            track = null
        }
    }

    /** Эффект «голоса ИИ»: эхо 11/23/90/180 мс и лёгкая модуляция 30 Гц — как на ноутбуке. */
    private fun aiEffect(x: FloatArray, rate: Int): FloatArray {
        val out = x.copyOf()
        val echoes = arrayOf(11 to 0.35f, 23 to 0.22f, 90 to 0.18f, 180 to 0.08f)
        for ((ms, gain) in echoes) {
            val d = rate * ms / 1000
            for (i in d until x.size) out[i] += gain * x[i - d]
        }
        for (i in x.indices) {
            out[i] += x[i] * (sin(2.0 * PI * 30.0 * i / rate).toFloat() * 0.12f)
        }
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak > 0f) {
            val k = 0.8f / peak
            for (i in out.indices) out[i] *= k
        }
        return out
    }

    private fun toPcm16(x: FloatArray) = ShortArray(x.size) { i ->
        (x[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }

    // ───────────── Распаковка модели ─────────────

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
        private const val VOICE_DIR = "ruslan"
        private const val DATA_VERSION = 1
        private val LOCK = Any()
    }
}

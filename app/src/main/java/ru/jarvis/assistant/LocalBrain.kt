package ru.jarvis.assistant

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Автономная нейросеть прямо на телефоне (Qwen 2.5, 1,5 млрд параметров, ~1,6 ГБ):
 * работает без интернета и без ноутбука. Модель скачивается один раз по Wi-Fi.
 */
object LocalBrain {
    private const val URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task?download=true"
    private const val FILE = "qwen2.5-1.5b.task"
    private const val SIZE = 1598556720L
    private const val KEY_DL = "local_model_download"

    @Volatile private var llm: LlmInference? = null

    private fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), FILE)

    fun isReady(ctx: Context) = file(ctx).length() == SIZE

    /** Скачивание идёт прямо сейчас? */
    fun isDownloading(ctx: Context): Boolean {
        val id = ctx.getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE).getLong(KEY_DL, -1)
        if (id < 0) return false
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED
            }
        }
        return false
    }

    /** Прогресс скачивания в процентах (или -1). */
    fun progress(ctx: Context): Int {
        val id = ctx.getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE).getLong(KEY_DL, -1)
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return -1
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                return (done * 100 / SIZE).toInt()
            }
        }
        return -1
    }

    /** Начинает скачивание модели (только по Wi-Fi). Возвращает true, если скачивание запущено. */
    fun startDownload(ctx: Context): Boolean {
        if (isReady(ctx) || isDownloading(ctx)) return false
        file(ctx).delete()
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Руна: офлайн-нейросеть")
            .setDescription("1,6 ГБ — один раз, чтобы Руна работала без интернета")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, FILE)
        val id = dm.enqueue(req)
        ctx.getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DL, id).apply()
        return true
    }

    /** Ответ нейросети на телефоне. [messages] — пары роль/текст. Вызывать в фоновом потоке. */
    @Synchronized
    fun ask(ctx: Context, messages: List<Pair<String, String>>): String {
        val engine = llm ?: LlmInference.createFromOptions(
            ctx.applicationContext,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file(ctx).absolutePath)
                .setMaxTokens(2048)
                .build()
        ).also { llm = it }
        // Шаблон диалога Qwen: system / user / assistant
        val prompt = buildString {
            for ((role, text) in messages) append("<|im_start|>").append(role).append('\n').append(text).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
        return engine.generateResponse(prompt).substringBefore("<|im_end|>").trim()
    }
}

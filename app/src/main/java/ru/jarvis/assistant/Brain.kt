package ru.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Мозг» Руны: обращается к нейросети по протоколу, совместимому с OpenAI.
 * По умолчанию — к Ollama на ноутбуке в той же сети Wi-Fi (бесплатно, данные не уходят в интернет).
 * В настройках можно указать любой другой совместимый сервер и ключ доступа.
 */
class Brain(private val context: Context) {

    private val prefs = context.getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)
    private val history = mutableListOf<JSONObject>()
    private var lastScan = 0L

    /** Каким способом ответила нейросеть в последний раз (для строки состояния). */
    @Volatile var mode = ""
        private set

    /**
     * Три режима, по очереди — какой доступен:
     * 1) ноутбук в домашней Wi-Fi (бесплатно, быстро по локальной сети) или свой сервис из настроек;
     * 2) через интернет — нейросеть на сервере runachat.ru (YandexGPT);
     * 3) автономно — нейросеть прямо на телефоне, без сети.
     */
    fun ask(text: String): String {
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm, EEEE", Locale.forLanguageTag("ru-RU")).format(Date())
        // Старые снимки экрана больше не нужны — убираем, чтобы не переполнять память нейросети
        for (i in history.indices) {
            val c = history[i].getString("content")
            if (c.contains("[экран]")) history[i] = message(history[i].getString("role"), c.substringBefore("[экран]").trim())
        }
        history.add(message("user", "[$stamp] $text"))
        while (history.size > HISTORY_LIMIT) history.removeAt(0)
        while (history.isNotEmpty() && history[0].getString("role") != "user") history.removeAt(0)

        val answer = askAny()
        if (answer == null) {
            history.removeAt(history.lastIndex)
            return if (LocalBrain.isDownloading(context))
                "Нет связи, а офлайн-нейросеть ещё скачивается: ${LocalBrain.progress(context).coerceAtLeast(0)} процентов. Простые команды работают и так."
            else "Нет связи ни с ноутбуком, ни с интернетом. Простые команды работают и так, а для разговора без сети скачайте офлайн-нейросеть в настройках."
        }
        history.add(message("assistant", answer))
        return answer
    }

    private fun askAny(): String? {
        val saved = prefs.getString(JarvisPrefs.KEY_URL, "")?.trim()?.trimEnd('/') ?: ""
        val model = prefs.getString(JarvisPrefs.KEY_MODEL, JarvisPrefs.DEFAULT_MODEL)?.trim() ?: JarvisPrefs.DEFAULT_MODEL
        val key = prefs.getString(JarvisPrefs.KEY_API, "")?.trim() ?: ""

        // 1. Свой облачный сервис из настроек (DeepSeek и т.п.)
        if (saved.isNotEmpty() && !saved.contains(":11434")) {
            remote(saved, key, model)?.let { mode = "свой сервис"; return it }
        }
        // 1. Ноутбук в Wi-Fi: по сохранённому адресу, иначе поищем в сети
        laptop(saved)?.let { base ->
            remote(base, "", if (saved.contains(":11434")) model else JarvisPrefs.DEFAULT_MODEL)?.let { mode = "ноутбук"; return it }
        }
        // 2. Через интернет — сервер runachat.ru
        remote(SERVER_URL, SERVER_TOKEN, "yandexgpt-lite")?.let { mode = "интернет"; return it }
        // 3. Автономно — на телефоне
        if (LocalBrain.isReady(context)) {
            return try {
                val msgs = mutableListOf("system" to SYSTEM_PROMPT)
                history.takeLast(6).forEach { msgs += it.getString("role") to it.getString("content") }
                LocalBrain.ask(context, msgs).also { mode = "телефон" }
            } catch (e: Throwable) {
                null
            }
        }
        return null
    }

    /** Адрес ноутбука с нейросетью или null. Полный поиск по сети — не чаще раза в 2 минуты. */
    private fun laptop(saved: String): String? {
        if (saved.contains(":11434") && LaptopFinder.reachable(saved)) return saved
        val now = System.currentTimeMillis()
        if (now - lastScan < 120_000) return null
        lastScan = now
        val found = LaptopFinder.find() ?: return null
        prefs.edit().putString(JarvisPrefs.KEY_URL, found).apply()
        return found
    }

    /** Запрос к серверу, совместимому с OpenAI. null — сервер недоступен или ответил ошибкой. */
    private fun remote(base: String, key: String, model: String): String? {
        val messages = JSONArray().put(message("system", SYSTEM_PROMPT))
        history.forEach { messages.put(it) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_tokens", 160)
            .put("stream", false)
        if (base.contains(":11434")) body.put("keep_alive", -1)     // Ollama на ноутбуке: модель не выгружать — ответы быстрее
        return try {
            val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = if (base.contains(":11434")) 90000 else 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return null }
            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            JSONObject(response).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun reset() = history.clear()

    private fun message(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    companion object {
        private const val HISTORY_LIMIT = 20
        private const val SERVER_URL = "https://runachat.ru/api/runa-phone/v1"
        private const val SERVER_TOKEN = "HHaJKskt9m_KaFo-geMUXkbT_Vq2hIdn"

        const val SYSTEM_PROMPT = """Ты — Руна, живая и тёплая голосовая помощница на телефоне Samsung. Женский род.
Говори как человек: просто, по-дружески, разговорно, 1-2 короткие фразы, можно с лёгкой шуткой. Без канцелярита, markdown и эмодзи.
Время приходит в начале сообщения в [скобках]. Телефоном управляешь полностью, всё делаешь сразу, без подтверждений.
Чтобы действовать — ответь ОДНОЙ строкой «CMD: <команда>». Потом придут [результат] и [экран] (кнопка: … / поле: …) — решай следующий шаг.
Задача готова или просто разговор — ответь обычным текстом.
Команды: открой <приложение> | открой ссылку <адрес> | позвони <имя> | напиши <имя> <SMS> | будильник на ЧЧ:ММ |
таймер на N минут | включи/выключи фонарик | громче | тише | найди <запрос> | включи на ютубе <запрос> |
назад | домой | уведомления | скриншот | нажми <текст> | долгое нажатие <текст> | введи <текст> | отправь |
листай вниз | листай вверх | экран | подожди"""
    }
}

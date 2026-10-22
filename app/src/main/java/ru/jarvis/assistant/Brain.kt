package ru.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Мозг» Руны: обращается к нейросети по протоколу, совместимому с OpenAI.
 * По умолчанию — к Ollama на ноутбуке в той же сети Wi-Fi (бесплатно, данные не уходят в интернет).
 * В настройках можно указать любой другой совместимый сервер и ключ доступа.
 */
class Brain(context: Context) {

    private val prefs = context.getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)
    private val history = mutableListOf<JSONObject>()

    fun ask(text: String): String {
        val base = prefs.getString(JarvisPrefs.KEY_URL, "")?.trim()?.trimEnd('/') ?: ""
        val model = prefs.getString(JarvisPrefs.KEY_MODEL, JarvisPrefs.DEFAULT_MODEL)?.trim() ?: JarvisPrefs.DEFAULT_MODEL
        val key = prefs.getString(JarvisPrefs.KEY_API, "")?.trim() ?: ""
        if (base.isEmpty()) {
            return "Нейросеть не настроена. Откройте «Настройки» и выберите сервис."
        }

        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm, EEEE", Locale.forLanguageTag("ru-RU")).format(Date())
        // Старые снимки экрана больше не нужны — убираем, чтобы не переполнять память нейросети
        for (i in history.indices) {
            val c = history[i].getString("content")
            if (c.contains("[экран]")) history[i] = message(history[i].getString("role"), c.substringBefore("[экран]").trim())
        }
        history.add(message("user", "[$stamp] $text"))
        while (history.size > HISTORY_LIMIT) history.removeAt(0)
        while (history.isNotEmpty() && history[0].getString("role") != "user") history.removeAt(0)

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
            conn.connectTimeout = 6000
            conn.readTimeout = 90000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                history.removeAt(history.lastIndex)
                return when (code) {
                    401, 403 -> "Ключ доступа к нейросети не подошёл. Проверьте его в настройках."
                    402 -> "На счёте сервиса нейросети закончились средства."
                    404 -> "Сервис не знает такую модель. Проверьте название модели в настройках."
                    429 -> "Слишком много запросов к нейросети. Попробуйте через минуту."
                    else -> "Сервер нейросети ответил ошибкой $code."
                }
            }
            val content = JSONObject(response)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            history.add(message("assistant", content))
            content
        } catch (e: SocketTimeoutException) {
            if (history.isNotEmpty()) history.removeAt(history.lastIndex)
            "Нейросеть думает слишком долго. Попробуйте ещё раз."
        } catch (e: Exception) {
            if (history.isNotEmpty()) history.removeAt(history.lastIndex)
            if (base.contains(":11434")) "Не могу связаться с ноутбуком. Проверьте, что он включён и в той же сети Wi-Fi."
            else "Нет связи с нейросетью. Проверьте интернет."
        }
    }

    fun reset() = history.clear()

    private fun message(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    companion object {
        private const val HISTORY_LIMIT = 20

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

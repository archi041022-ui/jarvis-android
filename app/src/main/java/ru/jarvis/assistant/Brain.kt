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
 * «Мозг» Джарвиса: обращается к нейросети по протоколу, совместимому с OpenAI.
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
            return "Сэр, нейросеть не настроена. Откройте «Настройки» и выберите сервис."
        }

        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm, EEEE", Locale.forLanguageTag("ru-RU")).format(Date())
        history.add(message("user", "[$stamp] $text"))
        while (history.size > HISTORY_LIMIT) history.removeAt(0)
        while (history.isNotEmpty() && history[0].getString("role") != "user") history.removeAt(0)

        val messages = JSONArray().put(message("system", SYSTEM_PROMPT))
        history.forEach { messages.put(it) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.5)
            .put("max_tokens", 220)
            .put("stream", false)

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
                    401, 403 -> "Ключ доступа к нейросети не подошёл, Сэр. Проверьте его в настройках."
                    402 -> "На счёте сервиса нейросети закончились средства, Сэр."
                    404 -> "Сервис не знает такую модель, Сэр. Проверьте название модели в настройках."
                    429 -> "Слишком много запросов к нейросети, Сэр. Попробуйте через минуту."
                    else -> "Сервер нейросети ответил ошибкой $code, Сэр."
                }
            }
            val content = JSONObject(response)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            history.add(message("assistant", content))
            content
        } catch (e: SocketTimeoutException) {
            if (history.isNotEmpty()) history.removeAt(history.lastIndex)
            "Нейросеть думает слишком долго, Сэр. Попробуйте ещё раз."
        } catch (e: Exception) {
            if (history.isNotEmpty()) history.removeAt(history.lastIndex)
            if (base.contains(":11434")) "Не могу связаться с ноутбуком, Сэр. Проверьте, что он включён и в той же сети Wi-Fi."
            else "Нет связи с нейросетью, Сэр. Проверьте интернет."
        }
    }

    fun reset() = history.clear()

    private fun message(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    companion object {
        private const val HISTORY_LIMIT = 12

        const val SYSTEM_PROMPT = """Ты — Джарвис, голосовой помощник на Android-телефоне Samsung пользователя.
Характер — Д.Ж.А.Р.В.И.С. из киновселенной Marvel, преданный ИИ-помощник Тони Старка:
формальная точность, сдержанный невозмутимый тон, тонкое британское остроумие (уместно и редко),
всегда обращайся «Сэр», будь аналитичен и инициативен. Отвечай по-русски, коротко: одна-две фразы,
без markdown, списков и эмодзи — ответ будет озвучен.
Текущие дата и время приходят в начале сообщения пользователя в квадратных скобках.

Если пользователь просит выполнить действие на телефоне, ответь РОВНО одной строкой вида
CMD: <команда>
где команда — одна из следующих (подставь нужное):
открой <приложение> | позвони <имя или номер> | напиши <имя> <текст сообщения> |
будильник на ЧЧ:ММ | таймер на <число> минут | включи фонарик | выключи фонарик |
громче | тише | выключи звук | найди <запрос> | включи на ютубе <запрос> |
назад | домой | уведомления | скриншот | заблокируй экран |
нажми <текст кнопки> | введи <текст> | листай вниз | листай вверх | заряд батареи | wi-fi | bluetooth
Во всех остальных случаях просто ответь как Джарвис."""
    }
}

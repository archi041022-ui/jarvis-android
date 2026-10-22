package ru.jarvis.assistant

/**
 * Автоматизация Руны: выполняет просьбу целиком, шаг за шагом.
 * Нейросеть выбирает команду (CMD: …), Руна выполняет её, смотрит на экран
 * и сообщает результат нейросети — так до готового ответа. Без подтверждений.
 */
class Agent(private val commands: Commands, private val brain: Brain) {

    /** Вызывать в фоновом потоке. [onStatus] — что сейчас делается (для строки состояния). */
    fun run(text: String, onStatus: (String) -> Unit = {}): Commands.Reply {
        // Частые команды — мгновенно, без нейросети
        try {
            commands.handle(text)?.let { return it }
        } catch (e: Throwable) {
            return Commands.Reply("Не получилось: ${e.message ?: "ошибка"}.")
        }

        onStatus("Думаю…")
        var answer = brain.ask(text)
        repeat(MAX_STEPS) {
            val match = CMD.find(answer)
                ?: return Commands.Reply(answer.trim(), listenAfter = answer.trimEnd().endsWith("?"))
            val cmd = match.groupValues[1].lines().first().trim().trimEnd('.')
            onStatus(cmd)
            val reply = try {
                commands.handle(cmd)
            } catch (e: Throwable) {
                Commands.Reply("ошибка: ${e.message}")
            }
            Thread.sleep(STEP_PAUSE_MS)                       // даём приложению открыться
            val screen = JarvisAccessibilityService.instance?.screenText().orEmpty()
            val result = buildString {
                append("[результат] ").append(reply?.text ?: "команда не распознана, выбери другую")
                if (screen.isNotBlank()) append("\n[экран]\n").append(screen)
            }
            onStatus("Думаю…")
            answer = brain.ask(result)
        }
        return Commands.Reply("Задача оказалась очень длинной, я остановилась. Скажите, если продолжить.")
    }

    companion object {
        private const val MAX_STEPS = 12
        private const val STEP_PAUSE_MS = 1500L
        private val CMD = Regex("CMD:\\s*(.+)", RegexOption.IGNORE_CASE)
    }
}

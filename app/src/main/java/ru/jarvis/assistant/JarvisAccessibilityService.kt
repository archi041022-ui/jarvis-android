package ru.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба специальных возможностей: позволяет Руне нажимать кнопки, вводить текст,
 * листать и «видеть» текст на экране в других приложениях.
 * Также поднимает Руну после перезагрузки телефона (служба включается системой сама).
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Автозапуск Руны после перезагрузки: специальные возможности система включает сама
        val prefs = getSharedPreferences(JarvisPrefs.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(JarvisPrefs.KEY_ALWAYS, true) && !JarvisService.running &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { JarvisService.start(this) } catch (_: Throwable) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События не обрабатываем — служба работает только по команде.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun quickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun screenshot(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false

    fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    /** Нажимает (или долго нажимает) на элемент, в тексте или описании которого есть [text]. */
    fun clickText(text: String, long: Boolean = false): Boolean {
        if (text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val nodes = root.findAccessibilityNodeInfosByText(text).ifEmpty {
            // распознавание могло исказить регистр или окончание — ищем по началу слова
            val stem = text.lowercase().take(maxOf(3, text.length - 2))
            collect(root).filter { (it.text ?: it.contentDescription)?.toString()?.lowercase()?.contains(stem) == true }
        }
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if ((if (long) current.isLongClickable else current.isClickable) && current.isEnabled) {
                    return current.performAction(action)
                }
                current = current.parent
            }
        }
        return false
    }

    /** Нажимает кнопку «Отправить» (или Enter в поле ввода). */
    fun send(): Boolean {
        val root = rootInActiveWindow ?: return false
        for (word in listOf("Отправить", "Send", "Отправ")) {
            if (clickText(word)) return true
        }
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
    }

    /** Текст на экране для нейросети: приложение, кнопки и поля ввода (до 1500 символов). */
    fun screenText(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()
        for (node in collect(root)) {
            if (!node.isVisibleToUser) continue
            val label = (node.text ?: node.contentDescription)?.toString()?.trim()?.replace('\n', ' ') ?: continue
            if (label.isEmpty() || label.length > 120) continue
            lines += when {
                node.isEditable -> "поле: $label"
                node.isClickable || node.parent?.isClickable == true -> "кнопка: $label"
                else -> label
            }
        }
        val app = root.packageName?.toString() ?: ""
        return ("приложение: $app\n" + lines.joinToString("\n")).take(1500)
    }

    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < 400) {
            val node = queue.removeFirst()
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** Вводит [text] в активное поле ввода (или в первое найденное поле). */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Листает первый прокручиваемый список на экране. */
    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findScrollable(root) ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }
}

package ru.jarvis.assistant

import android.content.Context

/** Ключи и значения настроек по умолчанию. */
object JarvisPrefs {
    const val PREFS = "jarvis"
    const val KEY_URL = "server_url"
    const val KEY_MODEL = "model"
    const val KEY_API = "api_key"
    const val KEY_PITCH = "voice_pitch"
    const val KEY_RATE = "voice_rate"
    const val KEY_ALWAYS = "always_listen"
    const val DEFAULT_MODEL = "qwen2.5:3b"
    const val DEFAULT_RATE = 1.15f      // чуть быстрее обычного

    /** Один раз после обновления: голос чуть быстрее и тембр обычный (раньше был низкий, для Джарвиса). */
    fun migrate(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("runa_v2", false)) return
        val e = p.edit().putBoolean("runa_v2", true)
        if (p.getFloat(KEY_RATE, 1.0f) <= 1.0f) e.putFloat(KEY_RATE, DEFAULT_RATE)
        if (p.getFloat(KEY_PITCH, 0.8f) < 1.0f) e.putFloat(KEY_PITCH, 1.0f)
        e.apply()
    }
}

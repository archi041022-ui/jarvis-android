package ru.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Постоянно работающий Джарвис: слушает слово «Джарвис» без нажатий,
 * затем принимает команду, выполняет её и отвечает голосом.
 * Работает как служба с уведомлением в шторке (так требует Android для доступа к микрофону).
 */
class JarvisService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ears: SpeechEngine
    private lateinit var voice: JarvisVoice
    private lateinit var commands: Commands
    private lateinit var brain: Brain
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ready = false
    private var paused = false            // приложение открыто на экране — микрофоном занимается оно
    private var busy = false              // идёт обработка команды или ответ
    private var awaitUntil = 0L           // до этого момента ждём команду без слова «Джарвис»
    private var followUps = 0
    private var busySince = 0L
    // Все включения/выключения микрофона — строго по очереди, чтобы «выключить» не обогнало «включить»
    private val micQueue = Executors.newSingleThreadExecutor()
    private val watchdog = object : Runnable {
        override fun run() {
            checkHealth()
            main.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground("Джарвис готовится…")
        ears = SpeechEngine(this) { phrase -> main.post { onPhrase(phrase) } }
        voice = JarvisVoice(this)
        commands = Commands(this)
        brain = Brain(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("ru-RU")
                tts?.setPitch(0.8f)
                ttsReady = true
            }
        }
        Thread {
            val okEars = ears.init()
            voice.init()
            main.post {
                ready = okEars
                if (!okEars) updateNotification("Не удалось запустить распознавание речи")
                else listen()
                main.postDelayed(watchdog, 5000)
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(JarvisPrefs.KEY_ALWAYS, false).apply()
                stopSelf()
            }
            ACTION_PAUSE -> {
                paused = true
                if (::ears.isInitialized) micQueue.execute { ears.stop() }
                updateNotification("Пауза: приложение открыто")
            }
            ACTION_RESUME -> {
                paused = false
                if (!busy) listen()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(watchdog)
        micQueue.shutdownNow()
        ears.release()
        voice.release()
        tts?.shutdown()
        super.onDestroy()
    }

    // ───────────── Цикл работы ─────────────

    /** Включает микрофон: офлайн-распознавание русской речи работает непрерывно. */
    private fun listen() {
        if (!ready || paused) return
        busy = false
        updateNotification(if (System.currentTimeMillis() < awaitUntil) "Слушаю, Сэр…" else "Скажите «Джарвис»")
        micQueue.execute { ears.start() }
    }

    /**
     * Сторож: раз в 5 секунд проверяет, что Джарвис действительно слушает.
     * Если микрофон «отвалился» или ответ завис — перезапускает слух.
     */
    private fun checkHealth() {
        if (!ready || paused) return
        val now = System.currentTimeMillis()
        if (busy) {
            if (now - busySince > 60_000) { voice.stop(); tts?.stop(); awaitUntil = 0; listen() }
            return
        }
        val silentTooLong = ears.lastAudio != 0L && now - ears.lastAudio > 8000
        if (!ears.isRunning) listen()
        else if (silentTooLong) micQueue.execute { ears.stop(); ears.start() }
    }

    /** Каждая законченная фраза: ищем обращение «Джарвис» или ждём команду после сигнала. */
    private fun onPhrase(phrase: String) {
        if (paused || busy) return
        val waiting = System.currentTimeMillis() < awaitUntil
        val command = SpeechEngine.extractCommand(phrase)
        when {
            command != null && command.isNotBlank() -> handle(command)          // «Джарвис, который час»
            command != null -> {                                                  // только «Джарвис»
                followUps = 0
                awaitUntil = System.currentTimeMillis() + 8000
                beep()
                updateNotification("Слушаю, Сэр…")
            }
            waiting -> handle(phrase)                                             // команда после сигнала
            else -> Unit                                                          // посторонний разговор — игнорируем
        }
    }

    private fun handle(text: String) {
        busy = true
        busySince = System.currentTimeMillis()
        awaitUntil = 0
        micQueue.execute { ears.stop() }                     // не слушаем, пока думаем и говорим
        process(text)
    }

    private fun process(text: String) {
        val low = text.lowercase(Locale.forLanguageTag("ru-RU")).trim(' ', '.', '!')
        if (low in setOf("стоп", "хватит", "отдохни", "спасибо", "пока", "все", "всё", "ничего", "отмена")) {
            say("Буду рядом, Сэр.", false); return
        }
        commands.handle(text)?.let { reply ->
            say(reply.text, reply.text.trimEnd().endsWith("?")); return
        }
        updateNotification("Думаю…")
        Thread {
            val answer = brain.ask(text)
            main.post {
                val cmd = Regex("CMD:\\s*(.+)", RegexOption.IGNORE_CASE).find(answer)
                if (cmd != null) {
                    val reply = commands.handle(cmd.groupValues[1].lines().first().trim())
                    say(reply?.text ?: "Не удалось выполнить команду, Сэр.", reply?.text?.trimEnd()?.endsWith("?") == true)
                } else {
                    say(answer, answer.trimEnd().endsWith("?"))
                }
            }
        }.start()
    }

    /** Говорит ответ и снова включает слух; если Джарвис задал вопрос — ждёт ответа без «Джарвис». */
    private fun say(text: String, expectAnswer: Boolean) {
        updateNotification(text)
        val clean = text.replace(Regex("[\\[\\]{}<>*_#]"), " ")
        val next = {
            if (expectAnswer && followUps < 3) {
                followUps++
                awaitUntil = System.currentTimeMillis() + 8000
            } else {
                awaitUntil = 0
            }
            listen()
        }
        if (voice.ready) {
            val speed = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE).getFloat(JarvisPrefs.KEY_RATE, 1.0f)
            Thread {
                val ok = voice.speakBlocking(clean, speed, aiEffect = true)
                main.post { if (ok) next() else speakAndroid(clean, next) }
            }.start()
        } else speakAndroid(clean, next)
    }

    private fun speakAndroid(text: String, onDone: () -> Unit) {
        val engine = tts
        if (!ttsReady || engine == null) { onDone(); return }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { onDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { onDone() } }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.nanoTime()) != TextToSpeech.SUCCESS) onDone()
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tone.release() }, 400)
        } catch (_: Throwable) {
        }
    }

    // ───────────── Уведомление ─────────────

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.deleteNotificationChannel("jarvis_listening")     // старый, заметный канал
            // Минимальная важность: без значка в строке состояния, без звука, свёрнуто внизу шторки
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Джарвис в фоне", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        return builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Джарвис")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
            .build()
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)!!.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "ru.jarvis.assistant.STOP"
        const val ACTION_PAUSE = "ru.jarvis.assistant.PAUSE"
        const val ACTION_RESUME = "ru.jarvis.assistant.RESUME"
        private const val CHANNEL = "jarvis_quiet"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var running = false
            private set

        /** Запуск службы. [paused] = true — пока приложение открыто на экране. */
        fun start(context: Context, paused: Boolean = false) {
            val intent = Intent(context, JarvisService::class.java).setAction(if (paused) ACTION_PAUSE else ACTION_RESUME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun send(context: Context, action: String) {
            if (!running) return
            try {
                context.startService(Intent(context, JarvisService::class.java).setAction(action))
            } catch (_: Throwable) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }
}

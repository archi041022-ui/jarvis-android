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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Постоянно работающий Джарвис: слушает слово «Джарвис» без нажатий,
 * затем принимает команду, выполняет её и отвечает голосом.
 * Работает как служба с уведомлением в шторке (так требует Android для доступа к микрофону).
 */
class JarvisService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wake: WakeWord
    private lateinit var voice: JarvisVoice
    private lateinit var commands: Commands
    private lateinit var brain: Brain
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var ready = false
    private var paused = false        // приложение открыто на экране — микрофоном занимается оно
    private var busy = false          // идёт диалог: команда, размышление или ответ
    private var followUps = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground("Скажите «Джарвис»")
        wake = WakeWord(this) { main.post { onWake() } }
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
            val okWake = wake.init()
            voice.init()
            main.post {
                ready = okWake
                if (!okWake) {
                    updateNotification("Не удалось запустить распознавание слова «Джарвис»")
                } else {
                    resumeHotword()
                }
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
                wake.stop()
                updateNotification("Приостановлено: приложение открыто")
            }
            ACTION_RESUME -> {
                paused = false
                if (!busy) resumeHotword()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wake.release()
        voice.release()
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ───────────── Цикл работы ─────────────

    private fun resumeHotword() {
        if (!ready || paused) return
        busy = false
        followUps = 0
        updateNotification("Слушаю слово «Джарвис»")
        wake.start()
    }

    private fun onWake() {
        if (paused) return
        busy = true
        beep()
        main.postDelayed({ listenCommand() }, 250)
    }

    private fun listenCommand() {
        if (paused) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Распознавание речи недоступно, Сэр.", false); return
        }
        updateNotification("Слушаю команду…")
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) resumeHotword() else process(text)
                }
                override fun onError(error: Int) {
                    resumeHotword()
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    private fun process(heard: String) {
        val text = heard.replace(Regex("^(джарвис|джервис|жарвис|jarvis)[\\s,]*", RegexOption.IGNORE_CASE), "").trim()
        if (text.isEmpty()) {
            say("Слушаю, Сэр.", true); return
        }
        val low = text.lowercase(Locale.forLanguageTag("ru-RU")).trim(' ', '.', '!')
        if (low in setOf("стоп", "хватит", "отдохни", "спасибо", "пока", "все", "всё", "ничего", "отмена")) {
            say("Буду рядом, Сэр.", false); return
        }
        commands.handle(text)?.let { reply ->
            say(reply.text, reply.listenAfter && reply.text.trimEnd().endsWith("?")); return
        }
        updateNotification("Думаю…")
        Thread {
            val answer = brain.ask(text)
            main.post {
                val cmd = Regex("CMD:\\s*(.+)", RegexOption.IGNORE_CASE).find(answer)
                if (cmd != null) {
                    val reply = commands.handle(cmd.groupValues[1].lines().first().trim())
                    say(reply?.text ?: "Не удалось выполнить команду, Сэр.", false)
                } else {
                    say(answer, answer.trimEnd().endsWith("?"))
                }
            }
        }.start()
    }

    /** Говорит ответ; если Джарвис задал вопрос — ждёт ответа без слова «Джарвис». */
    private fun say(text: String, expectAnswer: Boolean) {
        updateNotification(text)
        val clean = text.replace(Regex("[\\[\\]{}<>*_#]"), " ")
        val next = {
            if (expectAnswer && followUps < 3 && !paused) {
                followUps++; listenCommand()
            } else resumeHotword()
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
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.nanoTime())
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

package ru.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var orb: TextView
    private lateinit var brain: Brain
    private lateinit var commands: Commands

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var voice: JarvisVoice
    private lateinit var alwaysButton: Button
    @Volatile private var speechId = 0L
    private var listening = false
    private var thinking = false
    private var listenAfterSpeech = false
    private var missedInRow = 0
    private var greeted = false
    private val main = Handler(Looper.getMainLooper())

    private val cyan = Color.parseColor("#4FD8FF")
    private val dimCyan = Color.parseColor("#1E5A70")

    // ───────────── Жизненный цикл ─────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        brain = Brain(this)
        commands = Commands(this)
        buildUi()
        initTts()
        voice = JarvisVoice(this)
        Thread {
            val ok = voice.init()
            main.post {
                if (!ok) Toast.makeText(this, "Голос Джарвиса недоступен, использую голос Android", Toast.LENGTH_LONG).show()
            }
        }.start()
        askPermissions()
        if (prefs().getBoolean(JarvisPrefs.KEY_ALWAYS, false) && hasMic()) {
            JarvisService.start(this, paused = true)
        }
    }

    private fun prefs() = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Повторное открытие (кнопка, боковая клавиша) — сразу слушаем
        if (ttsReady) startListening()
    }

    override fun onResume() {
        super.onResume()
        JarvisService.send(this, JarvisService.ACTION_PAUSE)   // пока приложение на экране, слушает оно само
        updateAlwaysButton()
    }

    override fun onPause() {
        super.onPause()
        stopListening()
        JarvisService.send(this, JarvisService.ACTION_RESUME)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        voice.release()
        super.onDestroy()
    }

    // ───────────── Интерфейс ─────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05090F"))
            setPadding(dp(20), dp(36), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            setTextColor(cyan)
            textSize = 28f
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            text = "Инициализация систем…"
            setTextColor(Color.parseColor("#9BE9FF"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        }

        orb = TextView(this).apply {
            text = "ГОВОРИТЕ"
            setTextColor(cyan)
            textSize = 16f
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0A1A26"))
                setStroke(dp(4), cyan)
            }
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        val orbBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(orb, LinearLayout.LayoutParams(dp(200), dp(200)))
        }

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#D8F6FF"))
            textSize = 15f
            setLineSpacing(0f, 1.15f)
        }
        scroll = ScrollView(this).apply {
            addView(logView)
            setPadding(0, dp(18), 0, dp(12))
        }

        alwaysButton = makeButton("Слушать всегда: выкл") { toggleAlways() }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(makeButton("Настройки") { showSettings() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        buttons.addView(makeButton("Управление\nприложениями") { openAccessibility() }, LinearLayout.LayoutParams(0, dp(48), 1f))

        root.addView(title)
        root.addView(statusView)
        root.addView(orbBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(alwaysButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) })
        root.addView(buttons)
        setContentView(root)
    }

    private fun makeButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(cyan)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#0A1A26"))
            setStroke(dp(1), dimCyan)
        }
        setOnClickListener { onClick() }
    }

    private fun setOrb(label: String, active: Boolean) {
        orb.text = label
        (orb.background as GradientDrawable).setStroke(dp(if (active) 7 else 4), if (active) Color.WHITE else cyan)
    }

    private fun log(who: String, text: String) {
        logView.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ───────────── Разрешения ─────────────

    private fun askPermissions() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        val wanted = list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMic() && ttsReady && !greeted) greet()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ───────────── Голос ─────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                statusView.text = "Синтез речи недоступен. Установите «Синтезатор речи Google»."
                return@TextToSpeech
            }
            val prefs = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)
            tts?.language = Locale.forLanguageTag("ru-RU")
            tts?.setPitch(prefs.getFloat(JarvisPrefs.KEY_PITCH, 0.8f))
            tts?.setSpeechRate(prefs.getFloat(JarvisPrefs.KEY_RATE, 1.0f))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { afterSpeech() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { afterSpeech() }
                }
            })
            ttsReady = true
            if (hasMic()) greet()
        }
    }

    private fun greet() {
        greeted = true
        val firstRun = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)
            .getString(JarvisPrefs.KEY_URL, "").isNullOrBlank()
        say(
            if (firstRun) "Джарвис на связи, Сэр. Для свободного разговора выберите нейросеть в настройках. Команды телефона доступны уже сейчас."
            else "Слушаю, Сэр.",
            listenAfter = true
        )
    }

    private fun say(text: String, listenAfter: Boolean = true) {
        log("Джарвис", text)
        listenAfterSpeech = listenAfter
        setOrb("ГОВОРЮ", false)
        statusView.text = "Отвечаю"
        val id = ++speechId
        val clean = text.replace(Regex("[\\[\\]{}<>*_#]"), " ")
        if (voice.ready) {
            val speed = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE).getFloat(JarvisPrefs.KEY_RATE, 1.0f)
            Thread {
                val ok = voice.speakBlocking(clean, speed, aiEffect = true)
                main.post {
                    if (id != speechId) return@post          // фразу прервали новой командой
                    if (ok) afterSpeech() else speakAndroid(clean)
                }
            }.start()
        } else {
            speakAndroid(clean)
        }
    }

    /** Запасной голос — встроенный синтезатор Android. */
    private fun speakAndroid(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            afterSpeech()
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.nanoTime())
    }

    private fun afterSpeech() {
        if (listenAfterSpeech && hasWindowFocus()) startListening() else setIdle()
    }

    private fun setIdle() {
        listening = false
        setOrb("ГОВОРИТЕ", false)
        statusView.text = "Нажмите на кольцо, чтобы дать команду"
    }

    // ───────────── Слух ─────────────

    private fun startListening() {
        if (!hasMic()) {
            askPermissions(); return
        }
        if (thinking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "Распознавание речи недоступно на этом телефоне."
            return
        }
        tts?.stop()
        speechId++
        voice.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        listening = true
        setOrb("СЛУШАЮ", true)
        statusView.text = "Слушаю, Сэр…"
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        if (listening) recognizer?.cancel()
        listening = false
        if (!thinking) setIdle()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 60f)
            orb.scaleX = scale; orb.scaleY = scale
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            orb.scaleX = 1f; orb.scaleY = 1f
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrBlank()) statusView.text = partial
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            listening = false
            orb.scaleX = 1f; orb.scaleY = 1f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onMiss() else {
                missedInRow = 0
                process(text)
            }
        }

        override fun onError(error: Int) {
            listening = false
            orb.scaleX = 1f; orb.scaleY = 1f
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onMiss()
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    setIdle(); statusView.text = "Нет связи с сервисом распознавания речи."
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    setIdle(); askPermissions()
                }
                SpeechRecognizer.ERROR_CLIENT -> setIdle()
                else -> {
                    setIdle(); statusView.text = "Ошибка распознавания ($error). Нажмите на кольцо ещё раз."
                }
            }
        }
    }

    private fun onMiss() {
        missedInRow++
        if (missedInRow < 2 && listenAfterSpeech) startListening() else {
            missedInRow = 0
            setIdle()
        }
    }

    // ───────────── Обработка команды ─────────────

    private fun process(heard: String) {
        log("Вы", heard)
        val text = heard.replace(Regex("^(джарвис|джервис|жарвис|jarvis)[\\s,]*", RegexOption.IGNORE_CASE), "").trim()
        if (text.isEmpty()) {
            say("Слушаю, Сэр."); return
        }
        val low = text.lowercase(Locale.forLanguageTag("ru-RU")).trim(' ', '.', '!')
        if (low in setOf("стоп", "хватит", "отдохни", "спасибо", "пока", "все", "всё", "выключись")) {
            say("Буду рядом, Сэр.", listenAfter = false); return
        }

        commands.handle(text)?.let { reply ->
            say(reply.text, reply.listenAfter); return
        }

        thinking = true
        setOrb("ДУМАЮ", false)
        statusView.text = "Анализирую…"
        Thread {
            val answer = brain.ask(text)
            main.post {
                thinking = false
                val cmd = Regex("CMD:\\s*(.+)", RegexOption.IGNORE_CASE).find(answer)
                if (cmd != null) {
                    val reply = commands.handle(cmd.groupValues[1].lines().first().trim())
                    say(reply?.text ?: "Не удалось выполнить команду, Сэр.", reply?.listenAfter ?: true)
                } else {
                    say(answer, listenAfter = answer.trimEnd().endsWith("?"))
                }
            }
        }.start()
    }

    // ───────────── Настройки ─────────────

    private fun showSettings() {
        val prefs = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            isSingleLine = true
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            box.addView(this)
        }
        fun label(text: String) = box.addView(TextView(this).apply {
            this.text = text; textSize = 12f; setPadding(0, dp(10), 0, 0)
        })

        label("Где думает Джарвис — выберите вариант:")
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(android.widget.HorizontalScrollView(this).apply { addView(presets) })
        label("Адрес нейросети")
        val url = field("https://api.deepseek.com/v1", prefs.getString(JarvisPrefs.KEY_URL, "") ?: "")
        label("Модель")
        val model = field(JarvisPrefs.DEFAULT_MODEL, prefs.getString(JarvisPrefs.KEY_MODEL, JarvisPrefs.DEFAULT_MODEL) ?: "")
        fun preset(title: String, address: String, modelName: String) {
            presets.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 12f
                setOnClickListener { url.setText(address); model.setText(modelName) }
            })
        }
        preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat")
        preset("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat")
        preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
        preset("Ноутбук", "http://192.168.0.157:11434/v1", JarvisPrefs.DEFAULT_MODEL)
        label("Ключ доступа (только для облачных сервисов, иначе пусто)")
        val key = field("необязательно", prefs.getString(JarvisPrefs.KEY_API, "") ?: "", password = true)
        label("Тембр голоса (0.5 — низкий, 1.0 — обычный)")
        val pitch = field("0.8", prefs.getFloat(JarvisPrefs.KEY_PITCH, 0.8f).toString())
        label("Скорость речи (0.8 — медленно, 1.2 — быстро)")
        val rate = field("1.0", prefs.getFloat(JarvisPrefs.KEY_RATE, 1.0f).toString())

        AlertDialog.Builder(this)
            .setTitle("Настройки Джарвиса")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                var address = url.text.toString().trim()
                if (address.isNotEmpty() && !address.startsWith("http")) address = "http://$address"
                if (address.isNotEmpty() && !address.contains(":11434") && !address.contains("/v1") && address.count { it == ':' } < 2) {
                    address = "$address:11434/v1"
                }
                val p = pitch.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.3f, 2f) ?: 0.8f
                val r = rate.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1.0f
                prefs.edit()
                    .putString(JarvisPrefs.KEY_URL, address)
                    .putString(JarvisPrefs.KEY_MODEL, model.text.toString().trim().ifEmpty { JarvisPrefs.DEFAULT_MODEL })
                    .putString(JarvisPrefs.KEY_API, key.text.toString().trim())
                    .putFloat(JarvisPrefs.KEY_PITCH, p)
                    .putFloat(JarvisPrefs.KEY_RATE, r)
                    .apply()
                tts?.setPitch(p); tts?.setSpeechRate(r)
                brain.reset()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Разрешения") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ───────────── Постоянное прослушивание ─────────────

    private fun updateAlwaysButton() {
        if (::alwaysButton.isInitialized) {
            alwaysButton.text = if (JarvisService.running) "Слушать всегда: ВКЛ (скажите «Джарвис»)" else "Слушать всегда: выкл"
        }
    }

    private fun toggleAlways() {
        if (JarvisService.running) {
            prefs().edit().putBoolean(JarvisPrefs.KEY_ALWAYS, false).apply()
            JarvisService.stop(this)
            main.postDelayed({ updateAlwaysButton() }, 300)
            return
        }
        if (!hasMic()) { askPermissions(); return }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Ещё одно разрешение")
                .setMessage("Чтобы Джарвис мог открывать приложения по голосу, когда вы в другом приложении, " +
                    "включите для него «Поверх других приложений». Затем вернитесь и нажмите кнопку ещё раз.")
                .setPositiveButton("Открыть") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Пропустить") { _, _ -> enableAlways() }
                .show()
            return
        }
        enableAlways()
    }

    private fun enableAlways() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }
        prefs().edit().putBoolean(JarvisPrefs.KEY_ALWAYS, true).apply()
        JarvisService.start(this, paused = true)
        main.postDelayed({ updateAlwaysButton() }, 500)
        Toast.makeText(this, "Готово. Сверните приложение и скажите «Джарвис»", Toast.LENGTH_LONG).show()
    }

    private fun openAccessibility() {
        if (JarvisAccessibilityService.instance != null) {
            Toast.makeText(this, "Управление приложениями уже включено", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Управление приложениями")
            .setMessage(
                "Чтобы Джарвис мог нажимать кнопки и вводить текст в других приложениях:\n\n" +
                    "1. Откроется список «Специальные возможности».\n" +
                    "2. Выберите «Установленные приложения» → «Джарвис» и включите.\n\n" +
                    "Если переключатель серый: «Настройки» → «Приложения» → «Джарвис» → ⋮ (справа вверху) → " +
                    "«Разрешить ограниченные настройки», затем повторите."
            )
            .setPositiveButton("Открыть") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}

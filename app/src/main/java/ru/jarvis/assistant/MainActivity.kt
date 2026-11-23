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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var orb: TextView
    private lateinit var brain: Brain
    private lateinit var commands: Commands
    private lateinit var agent: Agent

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var voice: JarvisVoice
    private lateinit var alwaysButton: Button
    @Volatile private var speechId = 0L
    private var listening = false
    private var recognitionAttempt = 0
    private var foreground = false
    private var openingMessenger = false
    private var listenWhenResumed = false
    private var busyRetries = 0
    private var thinking = false
    private var listenAfterSpeech = false
    private var missedInRow = 0
    private var greeted = false
    private val main = Handler(Looper.getMainLooper())

    private val backgroundColor = Color.parseColor("#121026")
    private val panelColor = Color.parseColor("#211D3A")
    private val borderColor = Color.parseColor("#50466E")
    private val accentColor = Color.parseColor("#CBB9FF")
    private val textColor = Color.parseColor("#F7F2FF")

    // ───────────── Жизненный цикл ─────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        JarvisPrefs.migrate(this)
        brain = Brain(this)
        commands = Commands(this)
        agent = Agent(commands, brain)
        buildUi()
        initTts()
        voice = JarvisVoice(this)
        Thread {
            val ok = voice.init()
            main.post {
                if (!ok) Toast.makeText(this, "Голос Руны недоступен, использую голос Android", Toast.LENGTH_LONG).show()
            }
        }.start()
        askPermissions()
        // Офлайн-нейросеть: скачиваем один раз по Wi-Fi, чтобы Руна работала и без сети
        if (LocalBrain.startDownload(this)) {
            Toast.makeText(this, "Скачиваю офлайн-нейросеть (1,6 ГБ, только по Wi-Fi) — потом Руна сможет говорить и без интернета", Toast.LENGTH_LONG).show()
        }
        startBackgroundListeningIfEnabled()
    }

    private fun prefs() = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Повторное открытие (кнопка, боковая клавиша) — сразу слушаем
        if (ttsReady && foreground) startListening()
        else if (ttsReady) listenWhenResumed = true
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        openingMessenger = false
        JarvisService.send(this, JarvisService.ACTION_PAUSE)   // пока приложение на экране, слушает оно само
        updateAlwaysButton()
        if (listenWhenResumed) {
            listenWhenResumed = false
            startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        listenAfterSpeech = false
        speechId++
        tts?.stop()
        voice.stop()
        stopListening()
        if (!openingMessenger) JarvisService.send(this, JarvisService.ACTION_RESUME)
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#292147"), backgroundColor))
            setPadding(dp(20), dp(12), dp(20), dp(14))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(20), dp(12) + insets.systemWindowInsetTop,
                dp(20), dp(14) + insets.systemWindowInsetBottom)
            insets
        }

        val title = TextView(this).apply {
            text = "Руна"
            setTextColor(textColor)
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, dp(54), 1f))
            addView(makeButton("Настройки") { showSettings() }, LinearLayout.LayoutParams(-2, dp(48)))
        }
        val messenger = makeButton("Руна в мессенджере") {
            openingMessenger = true
            startActivity(Intent(this, MessengerActivity::class.java))
        }
        statusView = TextView(this).apply {
            text = "Запускаюсь…"
            setTextColor(accentColor)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        orb = TextView(this).apply {
            text = "Микрофон"
            setTextColor(accentColor)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(panelColor)
                setStroke(dp(1), borderColor)
            }
            setOnClickListener {
                busyRetries = 0
                if (listening) stopListening() else startListening()
            }
        }
        val orbBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(orb, LinearLayout.LayoutParams(-1, dp(52)))
        }

        logView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll = ScrollView(this).apply {
            addView(logView)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(panelColor)
            }
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }

        alwaysButton = makeButton("Слушать всегда: выкл") { toggleAlways() }

        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val field = EditText(this).apply {
            hint = "Спросите Руну"
            setTextColor(textColor)
            setHintTextColor(accentColor)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            textSize = 16f
        }
        val send: () -> Unit = {
            val question = field.text.toString().trim()
            if (question.isNotEmpty() && !thinking) {
                stopListening()
                field.text.clear()
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                process(question)
            }
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        composer.addView(field, LinearLayout.LayoutParams(0, dp(50), 1f))
        composer.addView(makeButton("Отправить", send), LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(header)
        root.addView(messenger, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        root.addView(statusView)
        root.addView(orbBox)
        root.addView(composer)
        root.addView(alwaysButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        root.addView(makeButton("Управление приложениями") { openAccessibility() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun makeButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(textColor)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(panelColor)
            setStroke(dp(1), borderColor)
        }
        setOnClickListener { onClick() }
    }

    private fun setOrb(label: String, active: Boolean) {
        orb.text = label.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        orb.setTextColor(if (active) backgroundColor else accentColor)
        (orb.background as GradientDrawable).apply {
            setColor(if (active) accentColor else panelColor)
            setStroke(dp(1), if (active) accentColor else borderColor)
        }
    }

    private fun log(who: String, text: String) {
        val mine = who == "Вы"
        val bubble = TextView(this).apply {
            this.text = "$who\n$text"
            textSize = 15f
            setTextColor(textColor)
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(if (mine) "#534473" else "#32294E"))
            }
        }
        logView.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = if (mine) dp(24) else 0
            rightMargin = if (mine) 0 else dp(24)
            bottomMargin = dp(8)
        })
        if (logView.childCount > 80) logView.removeViewAt(0)
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
        if (requestCode == 1 && hasMic()) {
            startBackgroundListeningIfEnabled()
            if (ttsReady && !greeted) greet()
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startBackgroundListeningIfEnabled() {
        if (hasMic() && !JarvisService.running && prefs().getBoolean(JarvisPrefs.KEY_ALWAYS, true)) {
            JarvisService.start(this, paused = true)
        }
    }

    // ───────────── Голос ─────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                statusView.text = "Синтез речи недоступен. Установите «Синтезатор речи Google»."
                return@TextToSpeech
            }
            val prefs = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE)
            tts?.language = Locale.forLanguageTag("ru-RU")
            tts?.setPitch(prefs.getFloat(JarvisPrefs.KEY_PITCH, 1.0f))
            tts?.setSpeechRate(prefs.getFloat(JarvisPrefs.KEY_RATE, JarvisPrefs.DEFAULT_RATE))
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
        if (foreground) startListening() else listenWhenResumed = true
    }

    private fun say(text: String, listenAfter: Boolean = true) {
        log("Руна", text)
        listenAfterSpeech = listenAfter
        setOrb("ГОВОРЮ", false)
        statusView.text = "Отвечаю"
        val id = ++speechId
        val clean = text.replace(Regex("[\\[\\]{}<>*_#]"), " ")
        if (voice.ready) {
            val speed = getSharedPreferences(JarvisPrefs.PREFS, Context.MODE_PRIVATE).getFloat(JarvisPrefs.KEY_RATE, JarvisPrefs.DEFAULT_RATE)
            Thread {
                val ok = voice.speakBlocking(clean, speed, aiEffect = false)
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
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "runa-" + System.nanoTime())
    }

    private fun afterSpeech() {
        if (foreground && listenAfterSpeech && hasWindowFocus()) startListening() else setIdle()
    }

    private fun setIdle() {
        listening = false
        orb.alpha = 1f
        setOrb("Микрофон", false)
        statusView.text = "Готова к разговору"
    }

    // ───────────── Слух ─────────────

    private fun startListening() {
        if (!hasMic()) {
            askPermissions(); return
        }
        if (thinking || !foreground || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "Распознавание речи недоступно на этом телефоне."
            return
        }
        tts?.stop()
        speechId++
        voice.stop()
        recognizer?.destroy()
        val attempt = ++recognitionAttempt
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener(attempt))
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        listening = true
        setOrb("СЛУШАЮ", true)
        statusView.text = "Слушаю…"
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        recognitionAttempt++
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        listening = false
        if (!thinking) setIdle()
    }

    private fun listener(attempt: Int) = object : RecognitionListener {
        private fun current() = foreground && attempt == recognitionAttempt
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (current()) orb.alpha = 0.85f + rmsdB.coerceIn(0f, 10f) / 67f
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (current()) orb.alpha = 1f
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!current()) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrBlank()) statusView.text = partial
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            if (!current()) return
            listening = false
            orb.alpha = 1f
            busyRetries = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onMiss() else {
                missedInRow = 0
                process(text)
            }
        }

        override fun onError(error: Int) {
            if (!current()) return
            listening = false
            orb.alpha = 1f
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> {
                    setIdle()
                    if (++busyRetries <= 2) {
                        statusView.text = "Подключаю микрофон…"
                        main.postDelayed({ if (current() && !thinking && !listening) startListening() }, 450L * busyRetries)
                    } else statusView.text = "Микрофон занят. Попробуйте ещё раз или напишите."
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onMiss()
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    setIdle(); statusView.text = "Нет связи с сервисом распознавания речи."
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    setIdle(); askPermissions()
                }
                SpeechRecognizer.ERROR_CLIENT -> setIdle()
                else -> {
                    setIdle(); statusView.text = "Не удалось распознать речь. Попробуйте ещё раз или напишите."
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
        if (thinking) return
        log("Вы", heard)
        val text = SpeechEngine.extractCommand(heard) ?: heard.trim()
        if (text.isEmpty()) {
            say(listOf("Да?", "Слушаю.", "Да, слушаю.").random()); return
        }
        val low = text.lowercase(Locale.forLanguageTag("ru-RU")).trim(' ', '.', '!')
        if (low in setOf("стоп", "хватит", "отдохни", "спасибо", "пока", "все", "всё", "выключись")) {
            say(listOf("Хорошо, я рядом.", "Ладно, если что — зовите.", "Поняла.").random(), listenAfter = false); return
        }

        thinking = true
        setOrb("ДУМАЮ", false)
        statusView.text = "Выполняю…"
        Thread {
            val reply = agent.run(text) { status -> main.post { statusView.text = status } }
            main.post {
                thinking = false
                if (isDestroyed) return@post
                if (foreground) say(reply.text, reply.listenAfter)
                else { log("Руна", reply.text); setIdle() }
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

        label(when {
            LocalBrain.isReady(this) -> "Офлайн-нейросеть на телефоне: готова ✓"
            LocalBrain.isDownloading(this) -> "Офлайн-нейросеть: скачивается, ${LocalBrain.progress(this).coerceAtLeast(0)}%"
            else -> "Офлайн-нейросеть: не скачана (скачается по Wi-Fi при открытии Руны)"
        })
        label("По умолчанию Руна отвечает через runachat.ru. Можно выбрать свой сервис или ноутбук. " +
            "Если сервер недоступен, Руна попробует ответить через ноутбук в Wi-Fi или с помощью скачанной модели.")
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
        val pitch = field("1.0", prefs.getFloat(JarvisPrefs.KEY_PITCH, 1.0f).toString())
        label("Скорость речи (0.8 — медленно, 1.2 — быстро)")
        val rate = field("1.15", prefs.getFloat(JarvisPrefs.KEY_RATE, JarvisPrefs.DEFAULT_RATE).toString())

        AlertDialog.Builder(this)
            .setTitle("Настройки Руны")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                var address = url.text.toString().trim()
                if (address.isNotEmpty() && !address.startsWith("http")) address = "http://$address"
                if (address.isNotEmpty() && !address.contains(":11434") && !address.contains("/v1") && address.count { it == ':' } < 2) {
                    address = "$address:11434/v1"
                }
                val p = pitch.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.3f, 2f) ?: 1.0f
                val r = rate.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.5f, 2f) ?: JarvisPrefs.DEFAULT_RATE
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
            alwaysButton.text = if (JarvisService.running) "Слушать всегда: ВКЛ (скажите «Руна»)" else "Слушать всегда: выкл"
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
                .setMessage("Чтобы Руна могла открывать приложения по голосу, когда вы в другом приложении, " +
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
        Toast.makeText(this, "Готово. Сверните приложение и скажите «Руна»", Toast.LENGTH_LONG).show()
    }

    private fun openAccessibility() {
        if (JarvisAccessibilityService.instance != null) {
            Toast.makeText(this, "Управление приложениями уже включено", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Управление приложениями")
            .setMessage(
                "Чтобы Руна могла нажимать кнопки, вводить текст и видеть экран в других приложениях:\n\n" +
                    "1. Откроется список «Специальные возможности».\n" +
                    "2. Выберите «Установленные приложения» → «Руна» и включите.\n\n" +
                    "Если переключатель серый: «Настройки» → «Приложения» → «Руна» → ⋮ (справа вверху) → " +
                    "«Разрешить ограниченные настройки», затем повторите."
            )
            .setPositiveButton("Открыть") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}

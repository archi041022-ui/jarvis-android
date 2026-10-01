package ru.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Мгновенные команды: выполняются на телефоне сразу, без нейросети и без интернета.
 * handle() возвращает ответ для озвучки или null, если команда не распознана.
 */
class Commands(private val activity: Activity) {

    data class Reply(val text: String, val listenAfter: Boolean = true)

    private val ru = Locale.forLanguageTag("ru-RU")
    private var pending: (() -> Reply)? = null
    private var torchOn = false

    fun handle(raw: String): Reply? {
        val t = raw.lowercase(ru).replace('ё', 'е').trim().trimEnd('.', '!', '?', ',').trim()
        if (t.isEmpty()) return null

        // Подтверждение действия (звонок, SMS)
        pending?.let { action ->
            pending = null
            return if (isYes(t)) action() else Reply("Отменено, Сэр.")
        }

        // ── Время и дата ──
        if (Regex("который час|сколько (сейчас )?времени|какое время").containsMatchIn(t)) {
            val now = SimpleDateFormat("HH:mm", ru).format(Date())
            return Reply("Сейчас $now, Сэр.")
        }
        if (Regex("какое (сегодня )?число|какая (сегодня )?дата|какой сегодня день").containsMatchIn(t)) {
            val now = SimpleDateFormat("EEEE, d MMMM", ru).format(Date())
            return Reply("Сегодня $now, Сэр.")
        }

        // ── Фонарик ──
        if (t.contains("фонар")) {
            val on = !Regex("выключ|погас|убери").containsMatchIn(t)
            return if (setTorch(on)) Reply(if (on) "Фонарик включён, Сэр." else "Фонарик выключен, Сэр.")
            else Reply("Не удалось управлять фонариком, Сэр.")
        }

        // ── Батарея ──
        if (Regex("заряд|батаре").containsMatchIn(t)) {
            val bm = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            return Reply("Заряд $level процентов${if (charging) ", идёт зарядка" else ""}, Сэр.")
        }

        // ── Громкость ──
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Regex("^(сделай )?громче|прибавь (звук|громкость)|увеличь громкость").containsMatchIn(t)) {
            repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            return Reply("Громче, Сэр.")
        }
        if (Regex("^(сделай )?тише|убавь (звук|громкость)|уменьши громкость").containsMatchIn(t)) {
            repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            return Reply("Тише, Сэр.")
        }
        if (Regex("(выключи|отключи) звук|без звука").containsMatchIn(t)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return Reply("Звук выключен, Сэр.", listenAfter = false)
        }

        // ── Будильник и таймер ──
        Regex("(будильник|разбуди)\\D*(\\d{1,2})(?:[:. ]+(\\d{2}))?").find(t)?.let { m ->
            val hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (hour in 0..23 && minute in 0..59) {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Джарвис")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                return if (start(intent)) Reply("Будильник на ${hour}:${"%02d".format(minute)} установлен, Сэр.")
                else Reply("Не удалось поставить будильник, Сэр.")
            }
        }
        Regex("таймер\\D*(\\d+)\\s*(сек|мин|час)").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            val seconds = when (m.groupValues[2]) { "сек" -> n; "мин" -> n * 60; else -> n * 3600 }
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Джарвис")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return if (start(intent)) Reply("Таймер запущен, Сэр.") else Reply("Не удалось запустить таймер, Сэр.")
        }

        // ── Wi-Fi, Bluetooth, авиарежим (Android не даёт приложениям переключать их сам — открываем панель) ──
        if (Regex("wi-?fi|вай ?фай|вайфай|интернет").containsMatchIn(t) && Regex("включи|выключи|открой|^wi|^вай").containsMatchIn(t)) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
            start(intent)
            return Reply("Открываю Wi-Fi, Сэр.", listenAfter = false)
        }
        if (Regex("блютуз|bluetooth|блютус").containsMatchIn(t)) {
            start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return Reply("Открываю Bluetooth, Сэр.", listenAfter = false)
        }
        if (Regex("авиарежим|режим полета").containsMatchIn(t)) {
            start(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            return Reply("Открываю настройки авиарежима, Сэр.", listenAfter = false)
        }

        // ── YouTube и поиск ──
        Regex("(?:включи|найди|открой|поставь) (?:на|в) (?:ютубе|ютуб|youtube) (.+)").find(t)?.let { m ->
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(m.groupValues[1]))))
            return Reply("Ищу на YouTube, Сэр.", listenAfter = false)
        }
        Regex("^(?:найди|поищи|загугли)(?: в интернете)? (.+)").find(t)?.let { m ->
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(m.groupValues[1]))))
            return Reply("Ищу, Сэр.", listenAfter = false)
        }

        // ── Звонки и SMS (с подтверждением) ──
        Regex("^(?:позвони|набери|вызови|звони) (.+)").find(t)?.let { m ->
            if (!has(Manifest.permission.CALL_PHONE)) return Reply("Нет разрешения на звонки, Сэр. Разрешите его в настройках приложения.")
            val (name, number) = findContact(m.groupValues[1]) ?: return Reply("Не нашёл такой контакт, Сэр.")
            pending = {
                start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
                Reply("Звоню, Сэр.", listenAfter = false)
            }
            return Reply("Позвонить: $name? Скажите да или нет.")
        }
        Regex("^(?:напиши|отправь (?:смс|сообщение)) (\\S+) (.+)").find(t)?.let { m ->
            if (!has(Manifest.permission.SEND_SMS)) return Reply("Нет разрешения на SMS, Сэр. Разрешите его в настройках приложения.")
            val (name, number) = findContact(m.groupValues[1]) ?: return Reply("Не нашёл такой контакт, Сэр.")
            val text = m.groupValues[2].replaceFirstChar { it.uppercase(ru) }
            pending = {
                try {
                    @Suppress("DEPRECATION")
                    val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        activity.getSystemService(SmsManager::class.java)!! else SmsManager.getDefault()
                    sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
                    Reply("Сообщение отправлено, Сэр.")
                } catch (e: Exception) {
                    Reply("Не удалось отправить сообщение, Сэр.")
                }
            }
            return Reply("Отправить $name сообщение: «$text»? Скажите да или нет.")
        }

        // ── Управление экраном (служба специальных возможностей) ──
        val screenCmd = Regex("^(назад|домой|на главный|недавние|последние приложения|уведомления|шторк|быстрые настройки|скриншот|снимок экрана|заблокируй|блокировка|листай|прокрути|нажми|введи|напечатай)").find(t)
        if (screenCmd != null) {
            val svc = JarvisAccessibilityService.instance
                ?: return Reply("Сэр, сначала включите управление приложениями: «Настройки» Джарвиса, кнопка «Управление приложениями».")
            val ok = when {
                t.startsWith("назад") -> svc.back()
                t.startsWith("домой") || t.startsWith("на главный") -> svc.home()
                t.startsWith("недавние") || t.startsWith("последние") -> svc.recents()
                t.startsWith("уведомления") || t.startsWith("шторк") -> svc.notifications()
                t.startsWith("быстрые настройки") -> svc.quickSettings()
                t.startsWith("скриншот") || t.startsWith("снимок экрана") -> svc.screenshot()
                t.startsWith("заблокируй") || t.startsWith("блокировка") -> svc.lockScreen()
                t.startsWith("листай") || t.startsWith("прокрути") -> svc.scroll(!t.contains("вверх"))
                t.startsWith("нажми") -> svc.clickText(raw.trim().substringAfter(' ').trim().trimEnd('.', '!'))
                else -> svc.typeText(raw.trim().substringAfter(' ').trim().trimEnd('.'))
            }
            return if (ok) Reply("Готово, Сэр.", listenAfter = false) else Reply("Не получилось, Сэр.")
        }

        // ── Открыть приложение ──
        Regex("^(?:открой|запусти|включи) (.+)").find(t)?.let { m ->
            val target = m.groupValues[1]
            val pkg = findApp(target) ?: return Reply("Не нашёл приложение «$target», Сэр.")
            val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return Reply("Это приложение нельзя открыть, Сэр.")
            start(intent)
            return Reply("Открываю, Сэр.", listenAfter = false)
        }

        return null
    }

    // ───────────── Вспомогательное ─────────────

    private fun isYes(t: String) = Regex("^(да|давай|подтверждаю|звони|отправляй|конечно|ага|угу)").containsMatchIn(t)

    private fun has(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun setTorch(on: Boolean): Boolean = try {
        val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else {
            cm.setTorchMode(id, on); torchOn = on; true
        }
    } catch (e: Exception) {
        false
    }

    /** Ищет контакт по имени («маме» найдёт «Мама»). Возвращает имя и номер. */
    private fun findContact(query: String): Pair<String, String>? {
        val q = query.trim()
        val digits = q.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5) return q to digits
        if (!has(Manifest.permission.READ_CONTACTS)) return null

        val wanted = q.lowercase(ru).replace('ё', 'е')
        val stem = wanted.take(maxOf(3, wanted.length - 2))
        var best: Pair<String, String>? = null
        var bestScore = 0
        activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val low = name.lowercase(ru).replace('ё', 'е')
                val words = low.split(' ', '-', '.')
                val score = when {
                    low == wanted -> 100
                    words.any { it == wanted } -> 90
                    words.any { it.startsWith(stem) } -> 70
                    low.contains(wanted) -> 60
                    else -> 0
                }
                if (score > bestScore) { bestScore = score; best = name to number }
            }
        }
        return best
    }

    private val appAliases = mapOf(
        "телеграм" to "telegram", "телеграмм" to "telegram", "ватсап" to "whatsapp", "вотсап" to "whatsapp",
        "ютуб" to "youtube", "хром" to "chrome", "гугл" to "google", "вк" to "vk", "вконтакте" to "vk",
        "инстаграм" to "instagram", "тикток" to "tiktok", "спотифай" to "spotify", "макс" to "max",
        "яндекс" to "яндекс", "госуслуги" to "госуслуги", "плей маркет" to "play маркет", "почта" to "gmail",
        "сбер" to "сбербанк", "карты" to "maps", "галерею" to "галерея", "камеру" to "камера", "заметки" to "samsung notes"
    )

    /** Ищет установленное приложение по названию. */
    private fun findApp(name: String): String? {
        val pm = activity.packageManager
        val wanted = name.lowercase(ru).replace('ё', 'е').trim()
        val alias = appAliases[wanted] ?: wanted
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        var best: String? = null
        var bestScore = 0
        for (info in apps) {
            val label = info.loadLabel(pm).toString().lowercase(ru).replace('ё', 'е')
            val pkg = info.activityInfo.packageName
            val score = when {
                label == wanted || label == alias -> 100
                label.startsWith(wanted) || label.startsWith(alias) -> 80
                label.contains(wanted) || label.contains(alias) -> 60
                pkg.lowercase().contains(alias.replace(" ", "")) -> 50
                wanted.length >= 4 && wanted.contains(label) -> 40
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = pkg }
        }
        return best
    }
}

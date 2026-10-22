package ru.jarvis.assistant

import android.Manifest
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
 * Мгновенные команды Руны: выполняются на телефоне сразу, без подтверждений, без нейросети и без интернета.
 * handle() возвращает ответ для озвучки или null, если команда не распознана.
 */
class Commands(private val activity: Context) {

    data class Reply(val text: String, val listenAfter: Boolean = true)

    private val ru = Locale.forLanguageTag("ru-RU")
    private var torchOn = false

    fun handle(raw: String): Reply? {
        val t = raw.lowercase(ru).replace('ё', 'е').trim().trimEnd('.', '!', '?', ',').trim()
        if (t.isEmpty()) return null

        // ── Время и дата ──
        if (Regex("который час|сколько (сейчас )?времени|какое время").containsMatchIn(t)) {
            val now = SimpleDateFormat("HH:mm", ru).format(Date())
            return Reply("Сейчас $now.")
        }
        if (Regex("какое (сегодня )?число|какая (сегодня )?дата|какой сегодня день").containsMatchIn(t)) {
            val now = SimpleDateFormat("EEEE, d MMMM", ru).format(Date())
            return Reply("Сегодня $now.")
        }

        // ── Фонарик ──
        if (t.contains("фонар")) {
            val on = !Regex("выключ|погас|убери").containsMatchIn(t)
            return if (setTorch(on)) Reply(if (on) "Фонарик включён." else "Фонарик выключен.")
            else Reply("Не удалось управлять фонариком.")
        }

        // ── Батарея ──
        if (Regex("заряд|батаре").containsMatchIn(t)) {
            val bm = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            return Reply("Заряд $level процентов${if (charging) ", идёт зарядка" else ""}.")
        }

        // ── Громкость ──
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Regex("^(сделай )?громче|прибавь (звук|громкость)|увеличь громкость").containsMatchIn(t)) {
            repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            return Reply("Громче.")
        }
        if (Regex("^(сделай )?тише|убавь (звук|громкость)|уменьши громкость").containsMatchIn(t)) {
            repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            return Reply("Тише.")
        }
        if (Regex("(выключи|отключи) звук|без звука").containsMatchIn(t)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return Reply("Звук выключен.", listenAfter = false)
        }

        // ── Будильник и таймер ──
        Regex("(будильник|разбуди)\\D*(\\d{1,2})(?:[:. ]+(\\d{2}))?").find(t)?.let { m ->
            val hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (hour in 0..23 && minute in 0..59) {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Руна")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                return if (start(intent)) Reply("Будильник на ${hour}:${"%02d".format(minute)} установлен.")
                else Reply("Не удалось поставить будильник.")
            }
        }
        Regex("таймер\\D*(\\d+)\\s*(сек|мин|час)").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            val seconds = when (m.groupValues[2]) { "сек" -> n; "мин" -> n * 60; else -> n * 3600 }
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Руна")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return if (start(intent)) Reply("Таймер запущен.") else Reply("Не удалось запустить таймер.")
        }

        // ── Wi-Fi, Bluetooth, авиарежим (Android не даёт приложениям переключать их сам — открываем панель) ──
        if (Regex("wi-?fi|вай ?фай|вайфай|интернет").containsMatchIn(t) && Regex("включи|выключи|открой|^wi|^вай").containsMatchIn(t)) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
            start(intent)
            return Reply("Открываю Wi-Fi.", listenAfter = false)
        }
        if (Regex("блютуз|bluetooth|блютус").containsMatchIn(t)) {
            start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return Reply("Открываю Bluetooth.", listenAfter = false)
        }
        if (Regex("авиарежим|режим полета").containsMatchIn(t)) {
            start(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            return Reply("Открываю настройки авиарежима.", listenAfter = false)
        }

        // ── YouTube и поиск ──
        Regex("(?:включи|найди|открой|поставь) (?:на|в) (?:ютубе|ютуб|youtube) (.+)").find(t)?.let { m ->
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(m.groupValues[1]))))
            return Reply("Ищу на YouTube.", listenAfter = false)
        }
        Regex("^(?:найди|поищи|загугли)(?: в интернете)? (.+)").find(t)?.let { m ->
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/search/?text=" + Uri.encode(m.groupValues[1]))))
            return Reply("Ищу.", listenAfter = false)
        }

        // ── Ссылка ──
        Regex("^открой (?:ссылку|сайт|страницу) (.+)").find(t)?.let {
            var url = raw.trim().split(Regex("\\s+"), 3).getOrElse(2) { "" }.replace(" ", "")
            if (!url.startsWith("http")) url = "https://$url"
            return if (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) Reply("Открываю.", listenAfter = false)
            else Reply("Не удалось открыть ссылку.")
        }

        // ── Звонки и SMS (сразу, без подтверждения) ──
        Regex("^(?:позвони|набери|вызови|звони) (.+)").find(t)?.let { m ->
            if (!has(Manifest.permission.CALL_PHONE)) return Reply("Нет разрешения на звонки. Разрешите его в настройках приложения.")
            val (name, number) = findContact(m.groupValues[1]) ?: return Reply("Не нашла такой контакт.")
            start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
            return Reply("Звоню: $name.", listenAfter = false)
        }
        Regex("^(?:напиши|отправь (?:смс|сообщение)) (\\S+) (.+)").find(t)
            ?.takeIf { it.groupValues[1].length >= 3 && it.groupValues[1] !in setOf("в", "во", "на", "по", "смс", "сообщение") }
            ?.let { m ->
            if (!has(Manifest.permission.SEND_SMS)) return Reply("Нет разрешения на SMS. Разрешите его в настройках приложения.")
            val (name, number) = findContact(m.groupValues[1]) ?: return Reply("Не нашла такой контакт.")
            val text = m.groupValues[2].replaceFirstChar { it.uppercase(ru) }
            return try {
                @Suppress("DEPRECATION")
                val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    activity.getSystemService(SmsManager::class.java)!! else SmsManager.getDefault()
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
                Reply("Сообщение для $name отправлено.")
            } catch (e: Exception) {
                Reply("Не удалось отправить сообщение.")
            }
        }

        // ── Управление экраном (служба специальных возможностей) ──
        if (t == "подожди" || t == "жди") {
            Thread.sleep(2000); return Reply("Подождала 2 секунды.")
        }
        val screenCmd = Regex("^(назад|домой|на главный|недавние|последние приложения|уведомления|шторк|быстрые настройки|скриншот|снимок экрана|заблокируй|блокировка|листай|прокрути|долгое нажатие|нажми|введи|напечатай|отправь$|экран$|что на экране|прочитай экран)").find(t)
        if (screenCmd != null) {
            val svc = JarvisAccessibilityService.instance
                ?: return Reply("Сначала включите управление приложениями: кнопка «Управление приложениями» в Руне.")
            if (t.startsWith("экран") || t.startsWith("что на экране") || t.startsWith("прочитай экран")) {
                return Reply(svc.screenText().ifBlank { "Экран пустой." })
            }
            val ok = when {
                t.startsWith("назад") -> svc.back()
                t.startsWith("домой") || t.startsWith("на главный") -> svc.home()
                t.startsWith("недавние") || t.startsWith("последние") -> svc.recents()
                t.startsWith("уведомления") || t.startsWith("шторк") -> svc.notifications()
                t.startsWith("быстрые настройки") -> svc.quickSettings()
                t.startsWith("скриншот") || t.startsWith("снимок экрана") -> svc.screenshot()
                t.startsWith("заблокируй") || t.startsWith("блокировка") -> svc.lockScreen()
                t.startsWith("листай") || t.startsWith("прокрути") -> svc.scroll(!t.contains("вверх"))
                t.startsWith("долгое нажатие") -> svc.clickText(raw.trim().split(Regex("\\s+"), 3).getOrElse(2) { "" }.trimEnd('.', '!'), long = true)
                t.startsWith("нажми") -> svc.clickText(raw.trim().substringAfter(' ').trim().trimEnd('.', '!'))
                t.startsWith("отправь") -> svc.send()
                else -> svc.typeText(raw.trim().substringAfter(' ').trim().trimEnd('.'))
            }
            return if (ok) Reply("Готово.", listenAfter = false) else Reply("Не получилось.")
        }

        // ── Открыть приложение ──
        Regex("^(?:открой|запусти|включи) (.+)").find(t)?.let { m ->
            val target = m.groupValues[1]
            val pkg = findApp(target) ?: return Reply("Не нашла приложение «$target».")
            val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return Reply("Это приложение нельзя открыть.")
            start(intent)
            return Reply("Открываю.", listenAfter = false)
        }

        return null
    }

    // ───────────── Вспомогательное ─────────────

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

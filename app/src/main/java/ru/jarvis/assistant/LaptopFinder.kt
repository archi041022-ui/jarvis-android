package ru.jarvis.assistant

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Сам находит нейросеть Ollama на ноутбуке в домашней сети Wi-Fi:
 * сначала по известному адресу, затем перебором адресов своей подсети (порт 11434).
 * Ничего настраивать вручную не нужно.
 */
object LaptopFinder {
    private const val PORT = 11434
    private const val KNOWN = "192.168.0.157"          // адрес ноутбука на момент установки

    fun url(ip: String) = "http://$ip:$PORT/v1"

    /** Отвечает ли сервер по адресу вида http://ip:11434/v1. */
    fun reachable(base: String): Boolean {
        val host = base.substringAfter("://").substringBefore(':').substringBefore('/')
        return open(host, 600)
    }

    /** Ищет ноутбук. Возвращает адрес для настроек или null. Вызывать в фоновом потоке. */
    fun find(): String? {
        if (open(KNOWN, 600)) return url(KNOWN)
        val prefix = myPrefix() ?: return null
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(32)
        for (i in 1..254) {
            val ip = "$prefix.$i"
            pool.execute { if (found.get() == null && open(ip, 500)) found.compareAndSet(null, ip) }
        }
        pool.shutdown()
        pool.awaitTermination(8, TimeUnit.SECONDS)
        pool.shutdownNow()
        return found.get()?.let { url(it) }
    }

    private fun open(host: String, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, PORT), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    /** Первые три числа своего адреса в Wi-Fi, например «192.168.0». */
    private fun myPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            ?.substringBeforeLast('.')
    } catch (_: Exception) {
        null
    }
}

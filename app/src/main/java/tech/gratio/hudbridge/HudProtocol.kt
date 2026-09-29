package tech.gratio.hudbridge

import android.util.Base64

/**
 * Протокол HUD C1 (WiiYii), восстановленный из официального приложения.
 *
 * Кадр: 31 31 CK CK CMD SUB [данные]
 *   31 31   — заголовок
 *   CK CK   — сумма всех байтов кадра (байты суммы при подсчёте равны нулю), старший первый
 *   CMD 07  — навигация
 *   SUB 01  — манёвр, 06 — название улицы, 09 — остаток пути
 *
 * Кадр кодируется в Base64, оборачивается в ^…$ и отправляется как ASCII
 * в характеристику d44bc439-…-9600 сервиса 0000fee9.
 */
object HudProtocol {

    const val WRITE_CHAR_PREFIX = "d44bc439"

    const val DIR_STRAIGHT = 0   // прямо и разворот
    const val DIR_LEFT = 2       // любой левый поворот
    const val DIR_RIGHT = 3      // любой правый поворот

    /** Манёвр: направление и расстояние до него в метрах. */
    fun nav(direction: Int, meters: Int): ByteArray {
        val head = byteArrayOf(0x31, 0x31, 0, 0, 0x07, 0x01, 0x00, direction.toByte())
        return checksum(head + distance(meters) + byteArrayOf(0, 0))
    }

    /** Название улицы. Кодировка GBK — так делает оригинальное приложение. */
    fun roadName(name: String): ByteArray {
        val bytes = try { name.toByteArray(charset("GBK")) } catch (_: Throwable) { name.toByteArray() }
        val cut = if (bytes.size > 40) bytes.copyOf(40) else bytes
        return checksum(byteArrayOf(0x31, 0x31, 0, 0, 0x07, 0x06, cut.size.toByte()) + cut)
    }

    /** Остаток маршрута: часы, минуты, расстояние в метрах. */
    fun remaining(hours: Int, minutes: Int, meters: Int): ByteArray {
        val d = meters / 100
        return checksum(byteArrayOf(0x31, 0x31, 0, 0, 0x07, 0x09,
            hours.toByte(), minutes.toByte(), ((d shr 8) and 0xFF).toByte(), (d and 0xFF).toByte()))
    }

    /** Готовые к отправке байты: ^base64$ в ASCII. */
    fun wire(frame: ByteArray): ByteArray {
        val b64 = Base64.encodeToString(frame, Base64.NO_WRAP)
        return ("^$b64$").toByteArray(Charsets.US_ASCII)
    }

    private fun distance(meters: Int): ByteArray {
        val m = meters.coerceIn(0, 65535)
        if (m < 256) return byteArrayOf(0, m.toByte(), 0, 0, 0)
        val hi = ((m shr 8) and 0xFF).toByte()
        val lo = (m and 0xFF).toByte()
        return byteArrayOf(hi, 0, lo)
    }

    private fun checksum(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        out[2] = 0; out[3] = 0
        var sum = 0
        for (b in out) sum += (b.toInt() and 0xFF)
        out[2] = ((sum shr 8) and 0xFF).toByte()
        out[3] = (sum and 0xFF).toByte()
        return out
    }

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}

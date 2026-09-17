package tech.gratio.hudbridge

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Файловый лог (JSON Lines) + шина для вывода строк на экран. */
object LogStore {
    private const val LOG_NAME = "hud_log.jsonl"
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private fun logFile(ctx: Context) = File(ctx.filesDir, LOG_NAME)
    private fun iconDir(ctx: Context) = File(ctx.filesDir, "icons").apply { mkdirs() }

    @Synchronized
    fun append(ctx: Context, type: String, obj: JSONObject) {
        obj.put("type", type)
        if (!obj.has("ts")) obj.put("ts", System.currentTimeMillis())
        logFile(ctx).appendText(obj.toString() + "\n")
        ui("[$type] " + obj.toString().take(400))
    }

    fun ui(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        main.post { listeners.forEach { it("$t $line") } }
    }

    /** Сохраняет картинку (например, стрелку манёвра) и возвращает её имя по хэшу. */
    fun saveBitmap(ctx: Context, bmp: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val bytes = bos.toByteArray()
        val hash = MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(12)
        val name = "icon_$hash.png"
        val f = File(iconDir(ctx), name)
        if (!f.exists()) f.writeBytes(bytes)
        return name
    }

    fun drawableToBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 96
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 96
        if (w > 2048 || h > 2048) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp
    }

    fun clear(ctx: Context) {
        logFile(ctx).delete()
        iconDir(ctx).deleteRecursively()
        ui("Логи очищены")
    }

    /** Копирует лог и иконки в «Загрузки/HudBridge/<время>/». */
    fun export(ctx: Context): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val rel = Environment.DIRECTORY_DOWNLOADS + "/HudBridge/$stamp"
        val files = mutableListOf<File>()
        logFile(ctx).takeIf { it.exists() }?.let { files += it }
        iconDir(ctx).listFiles()?.let { files += it }
        for (f in files) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, if (f.name.endsWith(".png")) "image/png" else "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: continue
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
        }
        return "$rel (${files.size} файлов)"
    }
}

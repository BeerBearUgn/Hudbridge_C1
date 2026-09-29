package tech.gratio.hudbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/** Что удалось вытащить из подсказки навигатора. */
data class NavInfo(val meters: Int, val direction: Int, val title: String, val street: String?)

/**
 * Достаёт манёвр из уведомления Яндекс Карт / Навигатора и отправляет его в HUD.
 *
 * В уведомлении: titleView — расстояние («150 м», «1,2 км»), descriptionView — улица,
 * primaryIconTinted — картинка стрелки. Тип поворота определяем по самой картинке:
 * считаем, в какую сторону смещена масса пикселей.
 */
object NavBridge {

    private var lastSent = ""

    fun handle(ctx: Context, sbn: StatusBarNotification) {
        val p = Prefs.get(ctx)
        if (!p.getBoolean(Prefs.BRIDGE, false)) return
        if (!sbn.packageName.startsWith("ru.yandex")) return
        val info = extract(ctx, sbn) ?: return
        val limit = p.getInt(Prefs.THRESHOLD, 800)

        val o = JSONObject().put("m", info.meters).put("dir", info.direction)
            .put("title", info.title).put("street", info.street ?: JSONObject.NULL)
        if (info.meters !in 1..limit) {
            LogStore.append(ctx, "nav_skip", o.put("limit", limit))
            return
        }
        val key = "${info.direction}/${info.meters}"
        if (key == lastSent) return
        lastSent = key
        LogStore.append(ctx, "nav_send", o)
        HudLink.send(ctx, HudProtocol.nav(info.direction, info.meters))
    }

    fun extract(ctx: Context, sbn: StatusBarNotification): NavInfo? {
        val n = sbn.notification
        @Suppress("DEPRECATION")
        val rv = n.contentView ?: n.headsUpContentView ?: return null
        val root = try { rv.apply(ctx, FrameLayout(ctx)) } catch (_: Throwable) { return null }

        var title: String? = null
        var street: String? = null
        var arrow: Bitmap? = null
        walk(root) { v, id ->
            when {
                v is TextView && id == "titleView" -> title = v.text?.toString()
                v is TextView && id == "descriptionView" -> street = v.text?.toString()
                v is ImageView && id == "primaryIconTinted" ->
                    arrow = v.drawable?.let { LogStore.drawableToBitmap(it) }
            }
        }
        val t = title ?: return null
        val meters = parseDistance(t) ?: return null
        val dir = arrow?.let { direction(it) } ?: HudProtocol.DIR_STRAIGHT
        return NavInfo(meters, dir, t, street)
    }

    /** «150 м» → 150, «1,2 км» → 1200. */
    fun parseDistance(text: String): Int? {
        val t = text.lowercase().replace(',', '.')
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(км|km|м|m)\\b").find(t) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val km = m.groupValues[2].startsWith("к") || m.groupValues[2].startsWith("k")
        return (if (km) value * 1000 else value).toInt()
    }

    /** Куда смотрит стрелка: считаем непрозрачные пиксели слева и справа. */
    fun direction(b: Bitmap): Int {
        var left = 0L
        var right = 0L
        val w = b.width
        val h = b.height
        if (w < 4 || h < 4) return HudProtocol.DIR_STRAIGHT
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val a = Color.alpha(b.getPixel(x, y))
                if (a > 40) {
                    if (x < w * 0.40) left += a
                    else if (x > w * 0.60) right += a
                }
                x += 2
            }
            y += 2
        }
        return when {
            left + right == 0L -> HudProtocol.DIR_STRAIGHT
            left > right * 1.5 -> HudProtocol.DIR_LEFT
            right > left * 1.5 -> HudProtocol.DIR_RIGHT
            else -> HudProtocol.DIR_STRAIGHT
        }
    }

    private fun walk(v: View, visit: (View, String) -> Unit) {
        val id = try {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else ""
        } catch (_: Throwable) { "" }
        visit(v, id)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), visit)
    }
}

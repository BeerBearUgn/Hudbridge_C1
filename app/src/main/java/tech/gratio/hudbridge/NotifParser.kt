package tech.gratio.hudbridge

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Разбирает уведомление целиком: все extras (включая поля Samsung Now Bar /
 * Live Notifications) и содержимое кастомных RemoteViews (тексты и картинки).
 */
object NotifParser {

    fun parse(ctx: Context, sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val o = JSONObject()
        o.put("pkg", sbn.packageName)
        o.put("id", sbn.id)
        o.put("tag", sbn.tag ?: JSONObject.NULL)
        o.put("ongoing", sbn.isOngoing)
        o.put("category", n.category ?: JSONObject.NULL)
        o.put("channel", n.channelId ?: JSONObject.NULL)
        o.put("flags", n.flags)
        o.put("extras", dumpBundle(ctx, n.extras, 0))
        n.smallIcon?.let { o.put("smallIcon", iconToFile(ctx, it)) }
        n.getLargeIcon()?.let { o.put("largeIcon", iconToFile(ctx, it)) }

        @Suppress("DEPRECATION")
        val views = linkedMapOf(
            "contentView" to n.contentView,
            "bigContentView" to n.bigContentView,
            "headsUpContentView" to n.headsUpContentView,
        )
        val rv = JSONObject()
        for ((name, v) in views) {
            if (v != null) rv.put(name, dumpRemoteViews(ctx, v))
        }
        o.put("remoteViews", rv)
        return o
    }

    /** Короткая «подпись» содержимого — чтобы не писать одинаковые обновления. */
    fun signature(o: JSONObject): String {
        val e = o.optJSONObject("extras")
        return listOf(
            e?.optString("android.title"), e?.optString("android.text"),
            e?.optString("android.subText"), e?.optString("android.bigText"),
            o.optJSONObject("remoteViews")?.toString(), o.optString("largeIcon"),
        ).joinToString("|")
    }

    private fun dumpBundle(ctx: Context, b: Bundle?, depth: Int): JSONObject {
        val out = JSONObject()
        if (b == null || depth > 3) return out
        for (k in b.keySet()) {
            @Suppress("DEPRECATION")
            val v = try { b.get(k) } catch (t: Throwable) { "<err ${t.javaClass.simpleName}>" }
            out.put(k, describe(ctx, v, depth))
        }
        return out
    }

    private fun describe(ctx: Context, v: Any?, depth: Int): Any = when (v) {
        null -> JSONObject.NULL
        is CharSequence -> v.toString()
        is Number, is Boolean -> v
        is Bitmap -> "bitmap:" + LogStore.saveBitmap(ctx, v)
        is Icon -> iconToFile(ctx, v)
        is Bundle -> dumpBundle(ctx, v, depth + 1)
        is Array<*> -> JSONArray(v.map { describe(ctx, it, depth + 1) })
        is IntArray -> JSONArray(v.toList())
        is LongArray -> JSONArray(v.toList())
        is RemoteViews -> dumpRemoteViews(ctx, v)
        is Notification.Action -> "action:" + v.title
        else -> v.javaClass.simpleName + ":" + v.toString().take(200)
    }

    private fun iconToFile(ctx: Context, icon: Icon): String = try {
        val d = icon.loadDrawable(ctx)
        val bmp = d?.let { LogStore.drawableToBitmap(it) }
        if (bmp != null) "icon:" + LogStore.saveBitmap(ctx, bmp) else "icon:<empty>"
    } catch (t: Throwable) {
        "icon:<err ${t.javaClass.simpleName}>"
    }

    /** Разворачивает RemoteViews в настоящие View и собирает дерево текстов/картинок. */
    private fun dumpRemoteViews(ctx: Context, rv: RemoteViews): JSONArray {
        val arr = JSONArray()
        try {
            val root = rv.apply(ctx, FrameLayout(ctx))
            walk(ctx, root, arr, 0)
        } catch (t: Throwable) {
            arr.put("apply failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return arr
    }

    private fun walk(ctx: Context, v: View, arr: JSONArray, depth: Int) {
        if (v.visibility != View.VISIBLE) return
        val idName = try {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else ""
        } catch (_: Throwable) { "#${v.id}" }
        when (v) {
            is TextView -> if (!v.text.isNullOrEmpty()) {
                arr.put(JSONObject().put("d", depth).put("id", idName).put("text", v.text.toString()))
            }
            is ImageView -> v.drawable?.let { d ->
                LogStore.drawableToBitmap(d)?.let { bmp ->
                    arr.put(JSONObject().put("d", depth).put("id", idName)
                        .put("image", LogStore.saveBitmap(ctx, bmp)))
                }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(ctx, v.getChildAt(i), arr, depth + 1)
    }
}

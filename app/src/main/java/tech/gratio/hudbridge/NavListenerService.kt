package tech.gratio.hudbridge

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NavListenerService : NotificationListenerService() {

    private val lastSig = HashMap<String, String>()

    override fun onListenerConnected() {
        LogStore.ui("Доступ к уведомлениям активен")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = Prefs.get(this)
        if (!p.getBoolean(Prefs.LOG_ON, true)) return
        val all = p.getBoolean(Prefs.ALL_APPS, false)
        if (!all && sbn.packageName !in NAV_PACKAGES) return
        try {
            val o = NotifParser.parse(this, sbn)
            val key = sbn.key
            val sig = NotifParser.signature(o)
            if (lastSig[key] == sig) return
            lastSig[key] = sig
            LogStore.append(this, "notif", o)
        } catch (t: Throwable) {
            LogStore.append(this, "error", JSONObject().put("pkg", sbn.packageName).put("err", t.toString()))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val p = Prefs.get(this)
        if (!p.getBoolean(Prefs.ALL_APPS, false) && sbn.packageName !in NAV_PACKAGES) return
        lastSig.remove(sbn.key)
        LogStore.append(this, "removed", JSONObject().put("pkg", sbn.packageName).put("id", sbn.id))
    }

    companion object {
        val NAV_PACKAGES = setOf(
            "ru.yandex.yandexnavi",      // Яндекс Навигатор
            "ru.yandex.yandexmaps",      // Яндекс Карты
            "com.google.android.apps.maps",
            "com.google.android.projection.gearhead", // Android Auto
        )
    }
}

object Prefs {
    const val LOG_ON = "log_on"
    const val ALL_APPS = "all_apps"
    fun get(ctx: Context) = ctx.getSharedPreferences("hud", Context.MODE_PRIVATE)
}

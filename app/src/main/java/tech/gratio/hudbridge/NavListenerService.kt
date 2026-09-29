package tech.gratio.hudbridge

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NavListenerService : NotificationListenerService() {

    private val lastSig = HashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        LogStore.ui("Доступ к уведомлениям активен")
        dumpActive("connected")
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = Prefs.get(this)
        if (!p.getBoolean(Prefs.LOG_ON, true)) return
        val all = p.getBoolean(Prefs.ALL_APPS, false)
        if (!all && sbn.packageName !in NAV_PACKAGES) return
        log(sbn, "notif", dedup = true)
        try { NavBridge.handle(this, sbn) } catch (t: Throwable) {
            LogStore.append(this, "error", JSONObject().put("where", "bridge").put("err", t.toString()))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val p = Prefs.get(this)
        if (!p.getBoolean(Prefs.ALL_APPS, false) && sbn.packageName !in NAV_PACKAGES) return
        lastSig.remove(sbn.key)
        LogStore.append(this, "removed", JSONObject().put("pkg", sbn.packageName).put("id", sbn.id))
    }

    /**
     * Снимок уже висящих уведомлений. Нужен для навигатора: его подсказка создаётся
     * один раз в начале маршрута и дальше только обновляется, поэтому onNotificationPosted
     * может не сработать, если служба подключилась позже.
     */
    fun dumpActive(reason: String) {
        try {
            val all = Prefs.get(this).getBoolean(Prefs.ALL_APPS, false)
            val list = activeNotifications ?: emptyArray()
            val mine = list.filter { all || it.packageName in NAV_PACKAGES }
            LogStore.ui("Снимок ($reason): всего ${list.size}, подходящих ${mine.size}")
            if (mine.isEmpty() && list.isNotEmpty()) {
                LogStore.append(this, "active_pkgs",
                    JSONObject().put("pkgs", list.joinToString(",") { it.packageName }))
            }
            for (sbn in mine) log(sbn, "active", dedup = false)
        } catch (t: Throwable) {
            LogStore.append(this, "error", JSONObject().put("where", "dumpActive").put("err", t.toString()))
        }
    }

    private fun log(sbn: StatusBarNotification, type: String, dedup: Boolean) {
        try {
            val o = NotifParser.parse(this, sbn)
            if (dedup) {
                val sig = NotifParser.signature(o)
                if (lastSig[sbn.key] == sig) return
                lastSig[sbn.key] = sig
            }
            LogStore.append(this, type, o)
        } catch (t: Throwable) {
            LogStore.append(this, "error", JSONObject().put("pkg", sbn.packageName).put("err", t.toString()))
        }
    }

    companion object {
        @Volatile
        var instance: NavListenerService? = null

        /** Вызывается кнопкой на экране. */
        fun snapshot() {
            val s = instance
            if (s == null) LogStore.ui("Служба не подключена — выдайте доступ к уведомлениям")
            else s.dumpActive("кнопка")
        }

        val NAV_PACKAGES = setOf(
            "ru.yandex.yandexnavi",      // Яндекс Навигатор
            "ru.yandex.yandexmaps",      // Яндекс Карты
            "com.google.android.apps.maps",
            "com.google.android.projection.gearhead", // Android Auto
            "com.avashield.wiiyiihudnavigator",       // стороннее приложение для HUD
        )
    }
}

object Prefs {
    const val LOG_ON = "log_on"
    const val ALL_APPS = "all_apps"
    const val BRIDGE = "bridge_on"
    const val THRESHOLD = "threshold_m"
    fun get(ctx: Context) = ctx.getSharedPreferences("hud", Context.MODE_PRIVATE)
}

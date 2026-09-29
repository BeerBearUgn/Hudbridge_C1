package tech.gratio.hudbridge

import android.content.Context

/** Одно соединение с HUD на всё приложение: им пользуются и экран, и служба уведомлений. */
object HudLink {

    @Volatile
    private var ble: BleExplorer? = null

    fun get(ctx: Context): BleExplorer {
        val existing = ble
        if (existing != null) return existing
        synchronized(this) {
            val again = ble
            if (again != null) return again
            val fresh = BleExplorer(ctx.applicationContext)
            ble = fresh
            return fresh
        }
    }

    /** Отправляет готовый кадр протокола, при необходимости подключаясь к сохранённому HUD. */
    fun send(ctx: Context, frame: ByteArray) {
        val b = get(ctx)
        if (!b.isConnected) {
            val mac = Prefs.get(ctx).getString("mac", "") ?: ""
            if (mac.isBlank()) { LogStore.ui("HUD не подключён, MAC не задан"); return }
            b.connectMac(mac)
            LogStore.ui("HUD был отключён, переподключаюсь — повторите через пару секунд")
            return
        }
        b.writeBytes(HudProtocol.WRITE_CHAR_PREFIX, HudProtocol.wire(frame), false)
    }
}

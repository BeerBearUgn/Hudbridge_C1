package tech.gratio.hudbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var ble: BleExplorer
    private lateinit var logView: TextView
    private lateinit var devices: LinearLayout
    private val lines = ArrayDeque<String>()
    private val listener: (String) -> Unit = { line ->
        lines.addFirst(line)
        while (lines.size > 150) lines.removeLast()
        logView.text = lines.joinToString("\n")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleExplorer(this)
        val prefs = Prefs.get(this)
        val pad = (12 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        fun header(t: String) = col.addView(TextView(this).apply {
            text = t; textSize = 18f; setPadding(0, pad, 0, pad / 2)
        })
        fun button(t: String, onClick: () -> Unit) = col.addView(Button(this).apply {
            text = t; setOnClickListener { onClick() }
        })

        header("1. Подсказки навигатора")
        button("Выдать доступ к уведомлениям") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        col.addView(CheckBox(this).apply {
            text = "Записывать уведомления навигаторов"
            isChecked = prefs.getBoolean(Prefs.LOG_ON, true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(Prefs.LOG_ON, c).apply() }
        })
        col.addView(CheckBox(this).apply {
            text = "Записывать ВСЕ приложения (чтобы найти нужное)"
            isChecked = prefs.getBoolean(Prefs.ALL_APPS, false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(Prefs.ALL_APPS, c).apply() }
        })

        header("2. Bluetooth HUD")
        button("Сканировать BLE (15 с)") { withBlePerms { devices.removeAllViews(); ble.startScan() } }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(devices)
        ble.onDeviceFound = { d, name, rssi ->
            devices.addView(Button(this).apply {
                text = "$name  ${d.address}  ${rssi}dBm"
                isAllCaps = false
                setOnClickListener { ble.connect(d) }
            })
        }
        button("Отключиться") { ble.disconnect() }

        val charEdit = EditText(this).apply { hint = "UUID характеристики (начало, напр. 0000ffe1)" }
        val hexEdit = EditText(this).apply {
            hint = "HEX, напр. AA 55 01 02"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val withResp = CheckBox(this).apply { text = "Запись с подтверждением" }
        col.addView(charEdit); col.addView(hexEdit); col.addView(withResp)
        button("Отправить в HUD") {
            prefs.edit().putString("char", charEdit.text.toString()).apply()
            ble.write(charEdit.text.toString(), hexEdit.text.toString(), withResp.isChecked)
        }
        charEdit.setText(prefs.getString("char", ""))

        header("3. Логи")
        button("Экспорт в «Загрузки/HudBridge»") {
            val where = LogStore.export(this)
            Toast.makeText(this, "Сохранено: $where", Toast.LENGTH_LONG).show()
            LogStore.ui("Экспорт: $where")
        }
        button("Очистить логи") { LogStore.clear(this) }

        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(logView)

        setContentView(ScrollView(this).apply { addView(col) })
        LogStore.listeners.add(listener)
        if (!isListenerEnabled()) LogStore.ui("⚠ Нет доступа к уведомлениям — нажмите кнопку выше")
    }

    override fun onDestroy() {
        LogStore.listeners.remove(listener)
        ble.disconnect()
        super.onDestroy()
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private var pending: (() -> Unit)? = null

    private fun withBlePerms(action: () -> Unit) {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            pending = action
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            pending?.invoke()
        } else LogStore.ui("Без разрешений Bluetooth скан не работает")
        pending = null
    }
}

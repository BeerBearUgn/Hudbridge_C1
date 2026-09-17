package tech.gratio.hudbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

/**
 * Исследователь BLE: скан, подключение, дамп GATT, подписка на все notify,
 * ручная запись hex в выбранную характеристику. Всё пишется в лог.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleExplorer(private val ctx: Context) {

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val ops = ArrayDeque<() -> Unit>()
    private var busy = false
    private val seen = HashSet<String>()

    var onDeviceFound: ((BluetoothDevice, String, Int) -> Unit)? = null

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val d = r.device
            if (!seen.add(d.address)) return
            val name = r.scanRecord?.deviceName ?: d.name ?: "(без имени)"
            val adv = r.scanRecord?.bytes?.let { hex(it) } ?: ""
            LogStore.append(ctx, "scan", JSONObject().put("mac", d.address).put("name", name)
                .put("rssi", r.rssi).put("adv", adv))
            main.post { onDeviceFound?.invoke(d, name, r.rssi) }
        }
        override fun onScanFailed(errorCode: Int) = LogStore.ui("Скан не удался: $errorCode")
    }

    fun startScan() {
        seen.clear()
        adapter.bluetoothLeScanner?.startScan(scanCb) ?: LogStore.ui("Bluetooth выключен")
        LogStore.ui("Сканирование 15 с…")
        main.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        try { adapter.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Throwable) {}
    }

    fun connect(d: BluetoothDevice) {
        stopScan()
        disconnect()
        LogStore.ui("Подключение к ${d.address}…")
        gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        ops.clear(); busy = false
    }

    /** Запись hex-строки. uuid можно указать коротко (первые символы). */
    fun write(uuidPrefix: String, hexStr: String, withResponse: Boolean) {
        val g = gatt ?: return LogStore.ui("Нет подключения")
        val ch = g.services.flatMap { it.characteristics }
            .firstOrNull { it.uuid.toString().startsWith(uuidPrefix.lowercase().trim()) }
            ?: return LogStore.ui("Характеристика $uuidPrefix не найдена")
        val data = parseHex(hexStr) ?: return LogStore.ui("Неверный hex")
        enqueue {
            ch.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = data
            if (!g.writeCharacteristic(ch)) { LogStore.ui("write() вернул false"); next() }
            else if (!withResponse) next()
            LogStore.append(ctx, "tx", JSONObject().put("char", ch.uuid.toString()).put("hex", hex(data)))
        }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            LogStore.append(ctx, "conn", JSONObject().put("status", status).put("state", newState))
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ops.clear(); busy = false
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            LogStore.ui("MTU=$mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val arr = JSONArray()
            for (s in g.services) {
                val chars = JSONArray()
                for (c in s.characteristics) {
                    chars.put(JSONObject().put("uuid", c.uuid.toString())
                        .put("props", props(c.properties))
                        .put("descriptors", JSONArray(c.descriptors.map { it.uuid.toString() })))
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        enqueue { if (!g.readCharacteristic(c)) next() }
                    }
                    val notify = c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE)
                    if (notify != 0) {
                        enqueue {
                            g.setCharacteristicNotification(c, true)
                            val d = c.getDescriptor(CCCD)
                            if (d == null) next() else {
                                d.value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                if (!g.writeDescriptor(d)) next()
                            }
                        }
                    }
                }
                arr.put(JSONObject().put("service", s.uuid.toString()).put("chars", chars))
            }
            LogStore.append(ctx, "gatt", JSONObject().put("mac", g.device.address).put("services", arr))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value ?: ByteArray(0)
            LogStore.append(ctx, "read", JSONObject().put("char", c.uuid.toString())
                .put("hex", hex(v)).put("ascii", ascii(v)))
            next()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                LogStore.ui("write status=$status"); next()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            LogStore.ui("notify on ${d.characteristic.uuid.toString().take(8)} status=$status")
            next()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: ByteArray(0)
            LogStore.append(ctx, "rx", JSONObject().put("char", c.uuid.toString())
                .put("hex", hex(v)).put("ascii", ascii(v)))
        }
    }

    private fun enqueue(op: () -> Unit) = main.post {
        ops.add(op)
        if (!busy) next(fromQueue = true)
    }

    private fun next(fromQueue: Boolean = false) {
        main.post {
            if (!fromQueue) busy = false
            if (busy) return@post
            val op = ops.poll() ?: return@post
            busy = true
            op()
        }
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
        fun ascii(b: ByteArray) = String(b.map { if (it in 32..126) it.toInt().toChar() else '.' }.toCharArray())
        fun parseHex(s: String): ByteArray? = try {
            val clean = s.replace(Regex("[^0-9A-Fa-f]"), "")
            if (clean.length % 2 != 0) null
            else ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: Throwable) { null }

        fun props(p: Int): String = buildList {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResp")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }.joinToString(",")
    }
}

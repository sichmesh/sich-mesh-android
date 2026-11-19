// One SICH device over Bluetooth LE (sich-s3 docs/phone-link.md): connect, pair (the 6-digit code shown on
// the device), HELLO, the device API, its events, and carrying ORLYK's relay exchange to the internet.
//
// Android allows one GATT operation at a time: every write goes through one queue (ops), the next one starts
// in the callback of the previous. All state is touched on the main thread; HTTPS runs on its own thread.
package org.sichmesh.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")   // the activity asks for BLUETOOTH_CONNECT before a link is made
class DeviceLink(
    private val ctx: Context,
    val address: String,
    private val phoneName: String,
    private val lang: String,
    private val changed: () -> Unit,
) {
    enum class State { IDLE, CONNECTING, PAIRING, READY, LOST }

    class Answer(val status: Int, val json: JSONObject) {
        val ok get() = status == 200
        val error: String get() = json.optString("error", "HTTP $status")
    }

    var state = State.IDLE; private set
    var error = ""; private set
    var deviceName = ""; private set
    var status: JSONObject? = null; private set          // the latest "status" event: the same JSON as the web panel's
    var netExchanges = 0; private set                    // ORLYK exchanges carried by this phone (never their contents)
    var netFailures = 0; private set
    var netLastError = ""; private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var gatt: BluetoothGatt? = null
    private var toDev: BluetoothGattCharacteristic? = null
    private var fromDev: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var nextId = 0
    private val inbox = Assembler()
    private val pending = HashMap<Int, (Answer) -> Unit>()
    private val ops = ArrayDeque<() -> Boolean>()       // GATT operations waiting; true = started
    private var opBusy = false
    private var closing = false
    private var bondWatch: BroadcastReceiver? = null

    private val device: BluetoothDevice? get() =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(address)

    fun start() {
        closing = false
        val d = device ?: return fail("Bluetooth is off")
        deviceName = d.name ?: address
        set(State.CONNECTING)
        gatt?.close()
        gatt = d.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun stop() {
        closing = true
        unwatchBond()
        gatt?.disconnect(); gatt?.close(); gatt = null
        failPending("disconnected")
        set(State.IDLE)
    }

    /** One device API call: the web panel's names ("status", "orlyk.msgs", "orlyk.send" ...). A read takes a
     *  query string ("id=14"), a change a JSON object. The answer comes on the main thread. */
    fun api(op: String, body: Any? = null, done: (Answer) -> Unit) {
        if (state != State.READY) return done(Answer(0, JSONObject().put("error", "not connected")))
        val id = newId()
        val b = when (body) { null -> ""; is String -> body; else -> body.toString() }
        pending[id] = done
        main.postDelayed({ pending.remove(id)?.invoke(Answer(0, JSONObject().put("error", "no answer"))) }, 10_000)
        send(Proto.API, id, "$op\n$b".toByteArray())
    }

    // ---------------------------------------------------------------- GATT
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) { main.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) { g.requestMtu(Proto.ASK_MTU) }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close(); gatt = null; ops.clear(); opBusy = false
                failPending("disconnected")
                if (!closing) fail("the device went away (Bluetooth off on it, or out of range)")
            }
        } }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) { main.post {
            if (st == BluetoothGatt.GATT_SUCCESS) mtu = m
            g.discoverServices()
        } }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) { main.post {
            val svc = g.getService(Proto.SERVICE)
            toDev = svc?.getCharacteristic(Proto.TO_DEVICE); fromDev = svc?.getCharacteristic(Proto.FROM_DEVICE)
            if (toDev == null || fromDev == null) return@post fail("not a SICH device (no phone link service)")
            val d = g.device
            if (d.bondState == BluetoothDevice.BOND_BONDED) subscribe()
            else {                                       // Android asks for the code from the device screen
                set(State.PAIRING)
                watchBond()
                if (d.bondState != BluetoothDevice.BOND_BONDING && !d.createBond()) fail("pairing did not start")
            }
        } }

        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, st: Int) { main.post {
            opDone()
            if (desc.uuid == Proto.CCCD) {
                if (st != BluetoothGatt.GATT_SUCCESS) return@post fail("the device refused the link ($st): pair again")
                inbox.reset()
                sayHello()
            }
        } }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { main.post {
            opDone()
            if (st != BluetoothGatt.GATT_SUCCESS) fail("write failed ($st)")
        } }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            main.post { onChunk(v) }
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return
            main.post { onChunk(v) }
        }
    }

    private fun watchBond() {
        if (bondWatch != null) return
        bondWatch = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                          else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> { unwatchBond(); subscribe() }
                    BluetoothDevice.BOND_NONE -> { unwatchBond(); fail("pairing failed or was cancelled: check the code on the device") }
                }
            }
        }
        val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(bondWatch, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(bondWatch, f)
    }

    private fun unwatchBond() {
        bondWatch?.let { runCatching { ctx.unregisterReceiver(it) } }
        bondWatch = null
    }

    private fun subscribe() {
        val g = gatt ?: return
        val c = fromDev ?: return
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Proto.CCCD) ?: return fail("the device has no notification switch")
        enqueue {
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            else { @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } }
        }
    }

    private fun sayHello() {
        val hello = JSONObject().put("app", "sichmesh").put("v", 1).put("lang", lang).put("device", phoneName)
        send(Proto.HELLO, 0, hello.toString().toByteArray())
        main.postDelayed({ if (state != State.READY && state != State.IDLE && gatt != null) fail("the device did not answer HELLO") }, 10_000)
    }

    // ---------------------------------------------------------------- messages
    private fun onChunk(c: ByteArray) {
        val m = inbox.feed(c) ?: return
        when (m.type) {
            Proto.WELCOME -> {
                runCatching { JSONObject(String(m.data)) }.getOrNull()?.optString("name")?.takeIf { it.isNotEmpty() }?.let { deviceName = it }
                error = ""
                set(State.READY)
            }
            Proto.API_ANSWER -> {
                val cb = pending.remove(m.id) ?: return
                if (m.data.size < 2) return cb(Answer(0, JSONObject().put("error", "empty answer")))
                val code = ((m.data[0].toInt() and 0xFF) shl 8) or (m.data[1].toInt() and 0xFF)
                val j = runCatching { JSONObject(String(m.data, 2, m.data.size - 2)) }.getOrElse { JSONObject() }
                cb(Answer(code, j))
            }
            Proto.EVENT -> runCatching { JSONObject(String(m.data)) }.getOrNull()?.let {
                if (it.optString("t") == "status") { status = it; changed() }
            }
            Proto.NET -> carry(m)
        }
    }

    // ORLYK: post the sealed request to the relay and give the answer back. The bytes are not kept, changed or logged.
    private fun carry(m: Message) {
        io.execute {
            var code = 0
            var body = ByteArray(0)
            try {
                val ul = m.data[0].toInt() and 0xFF
                val url = String(m.data, 1, ul)
                require(url.startsWith("https://")) { "not an https relay" }
                val req = m.data.copyOfRange(1 + ul, m.data.size)
                val c = URI("$url/v1/x").toURL().openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.useCaches = false
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.setFixedLengthStreamingMode(req.size)
                c.outputStream.use { it.write(req) }
                code = c.responseCode
                if (code == 200) body = c.inputStream.use { it.readBytes() }
                c.disconnect()
                main.post { netExchanges++; netLastError = if (code == 200) "" else "HTTP $code" }
            } catch (e: Exception) {
                main.post { netFailures++; netLastError = e.message ?: e.javaClass.simpleName }
            }
            val out = ByteArray(2 + body.size)
            out[0] = (code shr 8).toByte(); out[1] = code.toByte()
            System.arraycopy(body, 0, out, 2, body.size)
            main.post { send(Proto.NET_ANSWER, m.id, out); changed() }
        }
    }

    // one message, chunk by chunk, through the operation queue
    private fun send(type: Int, id: Int, data: ByteArray) {
        val chunks = Chunks.split(type, id, data, mtu) ?: return
        for (c in chunks) enqueue {
            val g = gatt; val ch = toDev
            if (g == null || ch == null) false
            else if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, c, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            else { @Suppress("DEPRECATION") run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; ch.value = c; g.writeCharacteristic(ch) } }
        }
    }

    private fun enqueue(op: () -> Boolean) { ops.addLast(op); pump() }
    private fun opDone() { opBusy = false; pump() }
    private fun pump() {
        while (!opBusy && ops.isNotEmpty()) {
            val op = ops.removeFirst()
            if (op()) opBusy = true                       // its callback calls opDone()
        }
    }

    private fun newId(): Int { nextId = nextId % 255 + 1; return nextId }

    private fun failPending(why: String) {
        val cbs = pending.values.toList(); pending.clear()
        cbs.forEach { it(Answer(0, JSONObject().put("error", why))) }
    }

    private fun fail(why: String) {
        error = why
        gatt?.let { it.disconnect(); it.close() }
        gatt = null; ops.clear(); opBusy = false
        unwatchBond()
        failPending(why)
        set(State.LOST)
    }

    private fun set(s: State) { state = s; changed() }

    fun close() { stop(); io.shutdownNow() }

    companion object {
        fun bluetoothOn(ctx: Context): Boolean =
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
    }
}

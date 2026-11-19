// SICH Mesh 0.1: find and pair a SICH device, show its state, and be ORLYK's way to the internet when the
// device has no Wi-Fi. Two screens, built in code (no layout files, no libraries): FIND and the DEVICE.
package org.sichmesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.sichmesh.app.link.DeviceLink
import org.sichmesh.app.link.Proto

@SuppressLint("MissingPermission", "SetTextI18n")   // permissions are asked for before any Bluetooth call
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private val found = LinkedHashMap<String, Pair<String, Int>>()   // address -> name, rssi
    private var scanning = false
    private var note = ""
    private var afterPermission: (() -> Unit)? = null
    private val onHub: () -> Unit = { render() }

    // ---------------------------------------------------------------- life
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = VOID
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        val page = root
        val scroll = ScrollView(this).apply { setBackgroundColor(VOID); addView(page) }
        // Android 15 draws apps under the status bar and the navigation bar (edge to edge): keep the
        // content clear of them, or the title sits under the clock
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = barSizes(insets)
            page.setPadding(dp(16), dp(16) + top, dp(16), dp(24) + bottom)
            insets
        }
        setContentView(scroll)
        // a remembered device: straight back to it
        val saved = Prefs.device(this)
        if (saved != null && LinkHub.link == null && hasPermissions()) LinkService.start(this, saved.first)
    }

    override fun onStart() { super.onStart(); LinkHub.listen(onHub); render() }
    override fun onStop() { LinkHub.unlisten(onHub); stopScan(); super.onStop() }

    // ---------------------------------------------------------------- permissions
    private fun needed(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    private fun hasPermissions() = needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun withPermissions(then: () -> Unit) {
        if (hasPermissions()) return then()
        afterPermission = then
        requestPermissions(needed(), 1)
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val then = afterPermission; afterPermission = null
        // notifications are welcome but not required: Bluetooth is
        val bt = perms.indices.filter { perms[it] != Manifest.permission.POST_NOTIFICATIONS }.all { results.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
        if (bt) then?.invoke() else { note = getString(R.string.need_perm); render() }
    }

    // ---------------------------------------------------------------- scanning
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: r.device.address
            found[r.device.address] = name to r.rssi
            render()
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; note = getString(R.string.scan_failed, errorCode); render() }
    }
    private fun scan() = withPermissions {
        if (!DeviceLink.bluetoothOn(this)) { note = getString(R.string.need_bt); render(); return@withPermissions }
        val sc = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner ?: return@withPermissions
        found.clear(); note = ""; scanning = true; render()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE)).build()
        sc.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ stopScan(); render() }, 12_000)
    }
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner?.stopScan(scanCb) }
    }
    private fun pick(address: String) = withPermissions { stopScan(); LinkService.start(this, address) }

    // ---------------------------------------------------------------- screens
    private fun render() {
        if (!::root.isInitialized) return
        root.removeAllViews()
        val l = LinkHub.link
        if (l == null) renderFind() else renderDevice(l)
    }

    private fun renderFind() {
        title(getString(R.string.app_name))
        text(getString(R.string.tagline), TRACE)
        gap(16)
        text(getString(R.string.how_on))
        gap(8)
        text(getString(R.string.how_code))
        gap(16)
        button(getString(if (scanning) R.string.searching else R.string.find), solid = true, enabled = !scanning) { scan() }
        if (note.isNotEmpty()) { gap(10); text(note, FLARE) }
        gap(12)
        if (!scanning && found.isEmpty() && note.isEmpty()) text(getString(R.string.none), TRACE)
        for ((addr, v) in found.entries.sortedByDescending { it.value.second }) {
            card(null) {
                text(v.first, FLARE, bold = true, size = 17f)
                text("${v.second} dBm · $addr", TRACE, size = 12f)
            }.setOnClickListener { pick(addr) }
        }
        gap(24)
        text(getString(R.string.licence), TRACE, size = 12f, center = true)
    }

    private fun renderDevice(l: DeviceLink) {
        val st = l.status
        val node = st?.optJSONObject("node") ?: JSONObject()
        val gps = st?.optJSONObject("gps") ?: JSONObject()
        val lora = st?.optJSONObject("lora") ?: JSONObject()
        val orl = st?.optJSONObject("orlyk")
        title(l.deviceName.ifEmpty { l.address })
        val state = when (l.state) {
            DeviceLink.State.READY -> getString(R.string.ready)
            DeviceLink.State.PAIRING -> getString(R.string.pairing)
            DeviceLink.State.CONNECTING -> getString(R.string.connecting)
            DeviceLink.State.LOST -> getString(R.string.lost)
            DeviceLink.State.IDLE -> ""
        }
        text(state, if (l.state == DeviceLink.State.READY) PHOS else FLARE, bold = true)
        if (l.error.isNotEmpty() && l.state != DeviceLink.State.READY) text(l.error, TRACE, size = 12f)
        gap(12)
        card(getString(R.string.orlyk)) {
            val t = when {
                orl == null -> "–"
                !orl.optBoolean("on") -> getString(R.string.orlyk_off)
                else -> when (orl.optString("link")) {
                    "online_phone" -> getString(R.string.via_phone)
                    "online" -> getString(R.string.via_wifi)
                    "connecting" -> getString(R.string.net_connecting)
                    "error" -> getString(R.string.net_error)
                    else -> getString(R.string.no_net)
                }
            }
            text(t, FLARE, bold = true, size = 18f)
            val unread = orl?.optInt("unread") ?: 0
            if (unread > 0) text(getString(R.string.unread, unread))
            text(getString(R.string.carried, l.netExchanges) + if (l.netLastError.isNotEmpty()) " · ${l.netLastError}" else "")
            gap(6)
            text(getString(R.string.carried_note), TRACE, size = 12f)
        }
        card(getString(R.string.device)) {
            text("${node.optString("name", l.deviceName)}  ${node.optString("id")}")
            val up = node.optLong("up", -1)
            text(getString(R.string.fw_up, node.optString("fw", "–"), if (up >= 0) dur(up) else "–"))
        }
        card(getString(R.string.lora)) {
            text(if (lora.length() == 0) "–" else if (lora.optBoolean("on")) "${lora.optDouble("freq")} MHz · SF${lora.optInt("sf")} · " + getString(R.string.nodes, lora.optInt("nodes")) else getString(R.string.off))
            if (lora.optInt("unread") > 0) text(getString(R.string.unread, lora.optInt("unread")))
        }
        card(getString(R.string.gps)) {
            text(when {
                gps.length() == 0 -> "–"
                !gps.optBoolean("on") -> getString(R.string.off)
                gps.optBoolean("fix") -> getString(R.string.fix_sats, gps.optInt("sats"))
                else -> getString(R.string.no_fix)
            })
        }
        gap(12)
        button(getString(R.string.disconnect)) { LinkService.stop(this) }
        gap(8)
        button(getString(R.string.forget)) { Prefs.forget(this); LinkService.stop(this) }
        gap(6)
        text(getString(R.string.forget_how), TRACE, size = 12f)
    }

    // ---------------------------------------------------------------- small view helpers
    // the status bar (with the camera cut-out) on top, the navigation bar at the bottom, in pixels
    private fun barSizes(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            b.top to b.bottom
        } else legacyBarSizes(insets)
    @Suppress("DEPRECATION")
    private fun legacyBarSizes(insets: WindowInsets): Pair<Int, Int> = insets.systemWindowInsetTop to insets.systemWindowInsetBottom
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dur(s: Long) = (if (s >= 86400) "${s / 86400}d " else "") + "%02d:%02d".format(s / 3600 % 24, s / 60 % 60)
    private fun gap(h: Int) { root.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun title(t: String) = text(t, FLARE, bold = true, size = 24f)
    private fun text(t: String, color: Int = PHOS, bold: Boolean = false, size: Float = 15f, center: Boolean = false, into: LinearLayout = root): TextView =
        TextView(this).apply {
            text = t; setTextColor(color); textSize = size; typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (center) gravity = Gravity.CENTER
            into.addView(this)
        }
    private fun button(t: String, solid: Boolean = false, enabled: Boolean = true, go: () -> Unit) {
        root.addView(Button(this).apply {
            text = t; isEnabled = enabled; isAllCaps = false; typeface = Typeface.MONOSPACE
            setTextColor(if (solid) GROUND else FLARE)
            background = GradientDrawable().apply { setColor(if (solid) FLARE else GROUND); setStroke(dp(1), if (solid) FLARE else TRACE); cornerRadius = dp(4).toFloat() }
            alpha = if (enabled) 1f else 0.5f
            setOnClickListener { go() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
    }
    private fun card(heading: String?, fill: LinearLayout.() -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(GROUND); setStroke(dp(1), TRACE); cornerRadius = dp(6).toFloat() }
        }
        val saved = root
        root = box
        if (heading != null) text(heading.uppercase(), TRACE, size = 12f)
        box.fill()
        root = saved
        root.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return box
    }

    companion object {
        // the device's green phosphor look
        val VOID = 0xFF05090A.toInt(); val GROUND = 0xFF081008.toInt(); val PHOS = 0xFF8FE388.toInt()
        val TRACE = 0xFF3F6E3B.toInt(); val FLARE = 0xFFD2FFB0.toInt()
    }
}

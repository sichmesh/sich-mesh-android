// Keeps the link to the SICH device alive with the screen off: a foreground service (type connectedDevice)
// with a notification. It owns the DeviceLink; the screens look at it through LinkHub.
package org.sichmesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.sichmesh.app.link.DeviceLink

/** What the screens see: the one link, and a way to hear about changes (always on the main thread). */
object LinkHub {
    var link: DeviceLink? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.toList().forEach { it() } }
}

class LinkService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var retry: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutDown(); return START_NOT_STICKY }
            else -> {
                val addr = intent?.getStringExtra(EXTRA_ADDRESS) ?: Prefs.device(this)?.first ?: run { stopSelf(); return START_NOT_STICKY }
                goForeground(getString(R.string.notif_connecting))
                if (LinkHub.link?.address != addr) {
                    LinkHub.link?.close()
                    LinkHub.link = DeviceLink(applicationContext, addr, Build.MODEL ?: "Android", getString(R.string.lang_code)) { onLinkChanged() }
                    LinkHub.link?.start()
                }
            }
        }
        return START_STICKY                                    // Android restarts it after killing it: back to the remembered device
    }

    private fun onLinkChanged() {
        val l = LinkHub.link ?: return
        when (l.state) {
            DeviceLink.State.READY -> {
                Prefs.setDevice(this, l.address, l.deviceName)
                goForeground(getString(R.string.notif_ready, l.deviceName))
                retry?.let { main.removeCallbacks(it) }; retry = null
            }
            DeviceLink.State.LOST -> {
                goForeground(getString(R.string.notif_lost, l.deviceName))
                if (retry == null) {                            // the device comes back: so do we (every 15 s)
                    retry = Runnable { retry = null; if (LinkHub.link === l) l.start() }
                    main.postDelayed(retry!!, 15_000)
                }
            }
            else -> {}
        }
        LinkHub.changed()
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
    }

    private fun shutDown() {
        retry?.let { main.removeCallbacks(it) }; retry = null
        LinkHub.link?.close(); LinkHub.link = null
        LinkHub.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        retry?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.sichmesh.app.STOP"
        const val EXTRA_ADDRESS = "address"
        private const val CHANNEL = "sich_link"
        private const val NOTIF_ID = 2026

        fun start(ctx: Context, address: String) =
            ctx.startForegroundService(Intent(ctx, LinkService::class.java).putExtra(EXTRA_ADDRESS, address))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_STOP)) }
    }
}

/** The remembered device (to reconnect by itself). */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("sich", Context.MODE_PRIVATE)
    fun device(ctx: Context): Pair<String, String>? = p(ctx).getString("dev.addr", null)?.let { it to (p(ctx).getString("dev.name", null) ?: it) }
    fun setDevice(ctx: Context, addr: String, name: String) { p(ctx).edit().putString("dev.addr", addr).putString("dev.name", name).apply() }
    fun forget(ctx: Context) { p(ctx).edit().remove("dev.addr").remove("dev.name").apply() }
}

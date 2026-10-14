package com.tvbridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service: keeps the Sinric Pro (internet) and TV (local network) connections
 * alive even when the screen is off, so Google Home voice commands reach the TV.
 */
class BridgeService : Service() {
    companion object {
        const val ACTION_STOP = "com.tvbridge.app.STOP"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
    }

    private var sinric: SinricClient? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    /**
     * Android silently kills sockets when the Wi-Fi sleeps or the phone switches between
     * Wi-Fi and mobile data ("Software caused connection abort"). This watches the default
     * network and reconnects right away instead of waiting for the old sockets to time out.
     */
    private fun watchNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    val changed = lastNetwork != null && lastNetwork != network
                    lastNetwork = network
                    if (changed) BridgeState.log("La red cambió: reconectando…")
                    TvHub.kick(changed)
                    sinric?.kick(changed)
                }
            }

            override fun onLost(network: Network) {
                handler.post { if (lastNetwork == network) lastNetwork = null }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            BridgeState.log("No pude vigilar la red: ${e.message}")
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        lastNetwork = null
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // ignore
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridge()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        try {
            val n = buildNotification("Iniciando…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            BridgeState.log("No pude iniciar en primer plano: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        // Pressing "Save and start" again restarts the bridge with the new settings.
        if (BridgeState.serviceRunning) stopBridge()
        startBridge()
        return START_STICKY
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- puente

    private fun startBridge() {
        val cfg = Config.load(this)
        if (!cfg.isComplete) {
            BridgeState.log("Faltan datos: IP del TV, App Key, App Secret y Device ID.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        BridgeState.serviceRunning = true
        BridgeState.log("Puente de voz iniciado")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvbridge:wake").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "tvbridge:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }

        TvHub.acquire(applicationContext, "service")
        val client = SinricClient(cfg.appKey, cfg.appSecret, cfg.deviceId) { TvHub.controller }
        sinric = client
        client.start()
        watchNetwork()
        handler.post(tick)
    }

    private fun stopBridge() {
        handler.removeCallbacks(tick)
        unwatchNetwork()
        sinric?.stop()
        sinric = null
        TvHub.release("service")
        try {
            wake?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        wake = null
        wifiLock = null
        if (BridgeState.serviceRunning) BridgeState.log("Puente de voz detenido")
        BridgeState.bridgeStopped()
    }

    // ---------------------------------------------------------------- notificación

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "Puente del TV", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_tv), "Detener", stopPi,
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle("TvBridge")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification() {
        val text = "TV: ${if (BridgeState.tvConnected) "conectado" else "sin conexión"}" +
            "  ·  Google: ${if (BridgeState.cloudConnected) "conectado" else "sin conexión"}"
        if (text == lastText) return
        lastText = text
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}

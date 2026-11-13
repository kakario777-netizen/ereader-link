package com.kakario.ereaderlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** 화면이 꺼지거나 다른 앱을 써도 연결이 유지되도록 뒤에서 계속 도는 부분 */
class LinkService : Service() {
    private var server: FileServer? = null
    private var discovery: Discovery? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (server == null) startAll()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "무선 연결", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("이북 링크 켜짐")
            .setContentText("PC에서 연결 프로그램을 켜면 자동으로 연결돼요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun startAll() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ereaderlink:wifi").apply {
            setReferenceCounted(false); acquire()
        }
        multicastLock = wm.createMulticastLock("ereaderlink:multicast").apply {
            setReferenceCounted(false); acquire()
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ereaderlink:cpu").apply {
            setReferenceCounted(false); acquire()
        }
        try {
            server = FileServer(applicationContext).also { it.start(30_000, false) }
            LinkState.error = null
        } catch (e: Exception) {
            LinkState.error = "서버를 켜지 못했어요: ${e.message}"
        }
        discovery = Discovery().also { it.start() }
        LinkState.running = true
    }

    override fun onDestroy() {
        LinkState.running = false
        runCatching { server?.stop() }
        discovery?.close()
        runCatching { wifiLock?.release() }
        runCatching { multicastLock?.release() }
        runCatching { wakeLock?.release() }
        server = null
        super.onDestroy()
    }

    companion object {
        const val CH = "link"
        const val ACTION_STOP = "stop"
    }
}

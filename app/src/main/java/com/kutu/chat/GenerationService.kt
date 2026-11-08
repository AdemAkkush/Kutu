package com.kutu.chat

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Yanıt üretimi sürerken uygulamayı canlı tutan ön plan servisi.
 *
 * Uygulama arka plana atılınca Android, sıradan bir süreci dondurup ağı kesebilir ("bağlantı koptu").
 * Bildirimli bir ön plan servisi + kısmi uyku kilidi + Wi-Fi kilidi bunu önler. Servis yalnızca bir
 * üretim ya da arka plan işi sürerken açıktır; [AppContainer] işler bitince kapatır.
 */
class GenerationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Yanıt üretimi", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Bir yanıt hazırlanırken uygulamanın arka planda çalışmaya devam etmesini sağlar."
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as KutuApp).container.engine.stopAll()
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        acquireLocks()
        return START_NOT_STICKY
    }

    /** Android 15: dataSync servisleri günde ~6 saatle sınırlıdır; süre dolunca kendini kapatır. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null; wifiLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GenerationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kutu)
            .setContentTitle("Kutu yanıt hazırlıyor")
            .setContentText("Yanıt arka planda sürüyor; bitince burada belirir.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .addAction(0, "Durdur", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kutu:generation")
                    .apply { setReferenceCounted(false) }
            }
            // Güvenlik sınırı: servis takılı kalsa bile kilit 10 dakikada kendiliğinden düşer.
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        runCatching {
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(mode, "Kutu:generation").apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        }
    }

    companion object {
        const val CHANNEL_ID = "generation"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.kutu.chat.STOP_GENERATION"
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

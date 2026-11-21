package ca.pwalker.ultratone

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
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Keeps already-requested photo work alive when the camera screen is stopped. */
class PhotoProcessingService : Service() {
    companion object {
        private const val CHANNEL = "photo_processing"
        private const val NOTIFICATION = 21
        private val main = Handler(Looper.getMainLooper())
        private val pending = AtomicInteger()
        private var instance: PhotoProcessingService? = null
        // One full-resolution job at a time, shared across camera Activity instances.
        val executor = Executors.newSingleThreadExecutor()
        val pendingCount: Int get() = pending.get()

        /** Called while the user is on the camera screen, before requesting capture. */
        fun begin(context: Context): Boolean {
            pending.incrementAndGet()
            return try {
                context.applicationContext.startForegroundService(Intent(context, PhotoProcessingService::class.java))
                true
            } catch (e: RuntimeException) {
                pending.decrementAndGet()
                android.util.Log.w("UltraTone", "Could not start photo processing service", e)
                false
            }
        }

        fun complete() {
            pending.updateAndGet { (it - 1).coerceAtLeast(0) }
            main.post { instance?.refresh() }
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Photo processing", NotificationManager.IMPORTANCE_LOW)
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltraTone:PhotoProcessing")
        wakeLock.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION, notification, type)
            } else startForeground(NOTIFICATION, notification)
        } catch (e: RuntimeException) {
            android.util.Log.w("UltraTone", "Background processing unavailable", e)
            android.widget.Toast.makeText(this, "Keep UltraTone open to finish saving: background processing is unavailable.", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        // A bounded lease also prevents a stuck capture callback holding the CPU indefinitely.
        wakeLock.acquire(30 * 60 * 1000L)
        refresh()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("UltraTone · processing photos")
            .setContentText("${pending.get()} photo(s) remaining · continues with screen locked")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS).build()
    }

    private fun refresh() {
        if (pending.get() == 0) {
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (wakeLock.isHeld) wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package me.weishu.kernelsu.ui.webmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.getNativeWebManagerUrl
import me.weishu.kernelsu.ui.util.startNativeWebManager

class WebManagerService : Service() {
    @Volatile
    private var nativeManaged = false

    @Volatile
    private var destroyed = false

    private val startupLock = Any()
    private var startupThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            createNotificationChannel()
            // Android requires this before any potentially blocking Root or
            // server probe. Delaying it caused ForegroundServiceDidNotStartInTimeException.
            startForegroundCompat(null)
        }.onFailure {
            Log.e(TAG, "failed to enter foreground for web manager service", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !WebManagerPreferences.isAutoStartEnabled(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        ensureServerStarted()
        return if (WebManagerPreferences.isAutoStartEnabled(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        startupThread?.interrupt()
        if (!nativeManaged) {
            WebManagerServer.stop()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.web_manager_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun ensureServerStarted() {
        synchronized(startupLock) {
            if (nativeManaged || startupThread?.isAlive == true) return
            startupThread = Thread({
                try {
                    val nativeUrl = runCatching { getNativeWebManagerUrl() }.getOrNull()
                    if (destroyed) return@Thread
                    if (nativeUrl != null) {
                        nativeManaged = true
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@Thread
                    }

                    WebManagerServer.start()
                    if (destroyed) {
                        WebManagerServer.stop()
                        return@Thread
                    }
                    updateForegroundNotification(WebManagerServer.port())
                } catch (error: Throwable) {
                    Log.e(TAG, "failed to start web manager service", error)
                    if (!destroyed) stopSelf()
                } finally {
                    synchronized(startupLock) {
                        startupThread = null
                    }
                }
            }, "ApkeSU-WebManager-Startup").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun buildNotification(port: Int?): Notification {
        val text = if (port != null && port > 0) {
            getString(R.string.web_manager_notification_text, port)
        } else {
            getString(R.string.web_manager_notification_starting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.web_manager_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateForegroundNotification(port: Int) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(port),
        )
    }

    private fun startForegroundCompat(port: Int?) {
        val notification = buildNotification(port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ApkeSU-WebManager"
        private const val CHANNEL_ID = "web_manager"
        private const val NOTIFICATION_ID = 10240

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, WebManagerService::class.java),
            )
        }

        fun openInBrowser(context: Context): Result<Unit> = runCatching {
            val applicationContext = context.applicationContext
            // Native ksud owns the persistent server. Keep the APK service only
            // as a compatibility fallback for older installed ksud binaries.
            val nativeStarted = startNativeWebManager()
            val nativeUrl = nativeStarted.takeIf { it }?.let { getNativeWebManagerUrl() }
            if (nativeUrl != null) {
                applicationContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(nativeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return@runCatching
            }
            val url = WebManagerServer.start()
            start(applicationContext)
            applicationContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            WebManagerServer.stop()
        }
    }
}

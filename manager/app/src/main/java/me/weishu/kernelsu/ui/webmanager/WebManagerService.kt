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
    private var nativeManaged = false

    override fun onCreate() {
        super.onCreate()
        nativeManaged = runCatching { getNativeWebManagerUrl() != null }.getOrDefault(false)
        if (nativeManaged) {
            stopSelf()
            return
        }
        runCatching {
            createNotificationChannel()
            // 先启动服务拿到实际端口，通知里才能显示正确的访问地址
            WebManagerServer.start()
            startForegroundCompat()
        }.onFailure {
            Log.e(TAG, "failed to start web manager service", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (nativeManaged || runCatching { getNativeWebManagerUrl() != null }.getOrDefault(false)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent == null && !WebManagerPreferences.isAutoStartEnabled(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        runCatching { WebManagerServer.start() }
            .onFailure {
                Log.e(TAG, "failed to ensure web manager server is running", it)
                stopSelfResult(startId)
            }
        return if (WebManagerPreferences.isAutoStartEnabled(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!nativeManaged) {
            WebManagerServer.stop()
        }
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

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.web_manager_notification_title))
            .setContentText(getString(R.string.web_manager_notification_text, WebManagerServer.port()))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
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

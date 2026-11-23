package com.mystore.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mystore.app.automation.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutomationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { AppPreferences(this) }
    private val processor by lazy { OrderProcessor(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            prefs.isServiceEnabled = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            prefs.isServiceEnabled = true
        }

        // إذا تم إيقاف الخدمة يدوياً، تبقى متوقفة ولا تعيد تشغيل نفسها تلقائياً
        if (!prefs.isServiceEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(FOREGROUND_ID, buildNotification("الخدمة نشطة وتعمل بوضع صارم"))

        if (action == ACTION_PROCESS_SMS) {
            val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
            val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
            if (body.isNotBlank()) {
                serviceScope.launch {
                    processor.handleIncomingSms(sender, body)
                }
            }
        }

        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("إدارة كروت الإنترنت")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمة الأتمتة",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mystore.app.engine.ACTION_START"
        const val ACTION_STOP = "com.mystore.app.engine.ACTION_STOP"
        const val ACTION_PROCESS_SMS = "com.mystore.app.engine.ACTION_PROCESS_SMS"

        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_BODY = "extra_body"

        private const val CHANNEL_ID = "automation_service_channel"
        private const val FOREGROUND_ID = 101

        fun startService(context: Context) {
            AppPreferences(context).isServiceEnabled = true
            val intent = Intent(context, AutomationService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            AppPreferences(context).isServiceEnabled = false
            val intent = Intent(context, AutomationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun enqueueIncomingSms(context: Context, sender: String, body: String) {
            if (!AppPreferences(context).isServiceEnabled) return
            val intent = Intent(context, AutomationService::class.java).apply {
                action = ACTION_PROCESS_SMS
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_BODY, body)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

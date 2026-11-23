package com.mystore.app.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutomationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val notification = NotificationHelper.buildServiceNotification(
            this,
            "خدمة مراقبة الرسائل والطلبات نشطة"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        val action = intent?.action

        when (action) {
            ACTION_PROCESS_QUEUE -> {
                serviceScope.launch {
                    OrderProcessor(this@AutomationService).processPendingQueue()
                }
            }
            ACTION_INCOMING_SMS -> {
                val sender = intent.getStringExtra(EXTRA_SENDER_PHONE).orEmpty()
                val body = intent.getStringExtra(EXTRA_SMS_BODY).orEmpty()
                if (sender.isNotBlank() && body.isNotBlank()) {
                    serviceScope.launch {
                        OrderProcessor(this@AutomationService).handleIncomingSms(sender, body)
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRunning: Boolean = false

        const val ACTION_START = "com.mystore.app.action.START_SERVICE"
        const val ACTION_PROCESS_QUEUE = "com.mystore.app.action.PROCESS_QUEUE"
        const val ACTION_INCOMING_SMS = "com.mystore.app.action.INCOMING_SMS"

        const val EXTRA_SENDER_PHONE = "extra_sender_phone"
        const val EXTRA_SMS_BODY = "extra_sms_body"

        fun startService(context: Context) {
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
            val intent = Intent(context, AutomationService::class.java)
            context.stopService(intent)
        }

        fun triggerIncomingSms(context: Context, senderPhone: String, body: String) {
            val intent = Intent(context, AutomationService::class.java).apply {
                action = ACTION_INCOMING_SMS
                putExtra(EXTRA_SENDER_PHONE, senderPhone)
                putExtra(EXTRA_SMS_BODY, body)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun triggerProcessQueue(context: Context) {
            val intent = Intent(context, AutomationService::class.java).apply {
                action = ACTION_PROCESS_QUEUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}


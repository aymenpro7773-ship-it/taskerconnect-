package com.mystore.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // احترام حالة الإيقاف اليدوي للخدمة
        if (!ServiceStateManager.isServiceEnabled(context)) {
            Log.d("SmsReceiver", "Service is manually disabled; ignoring incoming SMS.")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val groupedBySender = messages.groupBy { it.displayOriginatingAddress ?: "" }

        for ((sender, pdus) in groupedBySender) {
            val fullBody = pdus.joinToString(separator = "") { it.messageBody ?: "" }.trim()
            if (fullBody.isEmpty()) continue

            val serviceIntent = Intent(context, AutomationService::class.java).apply {
                action = AutomationService.ACTION_PROCESS_SMS
                putExtra(AutomationService.EXTRA_SENDER, sender)
                putExtra(AutomationService.EXTRA_BODY, fullBody)
            }
            context.startService(serviceIntent)
        }
    }
}

package com.mystore.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.mystore.app.automation.AppPreferences

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = AppPreferences(context)
        if (!prefs.isServiceEnabled) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val groupedBySender = messages.groupBy { it.originatingAddress.orEmpty() }
        for ((sender, parts) in groupedBySender) {
            val fullBody = parts.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
            if (fullBody.isNotEmpty()) {
                AutomationService.enqueueIncomingSms(context, sender, fullBody)
            }
        }
    }
}

package com.mystore.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val groupedBySender = messages.groupBy { it.originatingAddress.orEmpty() }
        for ((sender, parts) in groupedBySender) {
            val fullBody = parts.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
            if (sender.isNotBlank() && fullBody.isNotBlank()) {
                AutomationService.triggerIncomingSms(context, sender, fullBody)
            }
        }
    }
}


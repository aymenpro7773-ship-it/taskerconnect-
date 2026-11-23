package com.mystore.app.engine

import android.content.Context
import android.telephony.SmsManager
import android.os.Build

object SmsSender {

    data class SendResult(
        val success: Boolean,
        val error: String? = null
    )

    fun sendSms(context: Context, phoneNumber: String, message: String): SendResult {
        val cleanPhone = phoneNumber.trim()
        val cleanMsg = message.trim()

        if (cleanPhone.isEmpty()) {
            return SendResult(false, "رقم المستلم فارغ")
        }
        if (cleanMsg.isEmpty()) {
            return SendResult(false, "نص الرسالة فارغ")
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            if (cleanMsg.length > 160) {
                val parts = smsManager.divideMessage(cleanMsg)
                smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanPhone, null, cleanMsg, null, null)
            }

            SendResult(true, null)
        } catch (e: Exception) {
            SendResult(false, e.message ?: "فشل إرسال الرسالة SMS")
        }
    }
}


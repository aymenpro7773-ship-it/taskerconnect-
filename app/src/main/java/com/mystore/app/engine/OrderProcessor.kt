package com.mystore.app.engine

import android.content.Context
import com.mystore.app.data.AppDatabase
import com.mystore.app.data.PendingSmsEntity
import com.mystore.app.data.SaleLogEntity

class OrderProcessor(private val context: Context) {

    private val db by lazy { AppDatabase.getInstance(context) }

    suspend fun handleIncomingSms(senderPhone: String, rawBody: String): String {
        val parsed = MessageParser.parse(rawBody)
        val pendingId = db.pendingSmsDao().insertPending(
            PendingSmsEntity(
                senderPhone = senderPhone,
                rawBody = rawBody,
                parsedCategory = parsed.category,
                parsedAmount = parsed.amount,
                status = "PENDING"
            )
        )

        val category = parsed.category
        if (category == null) {
            val reason = "تعذر تحديد فئة الكرت من الرسالة"
            db.pendingSmsDao().updateStatus(pendingId, "FAILED", reason, System.currentTimeMillis())
            NotificationHelper.showAlert(
                context,
                "طلب غير مفهوم",
                "من $senderPhone: $rawBody",
                notificationId = (pendingId % 1000 + 2100).toInt()
            )
            return reason
        }

        return processSingleOrder(
            pendingId = pendingId,
            customerPhone = senderPhone,
            category = category,
            explicitAmount = parsed.amount
        )
    }

    suspend fun processPendingQueue(): String {
        val pendingList = db.pendingSmsDao().getPendingSmsList()
        if (pendingList.isEmpty()) {
            return "لا توجد طلبات معلقة في قائمة الانتظار."
        }

        var processedCount = 0
        var failedCount = 0

        for (item in pendingList) {
            val category = item.parsedCategory ?: MessageParser.parse(item.rawBody).category
            if (category == null) {
                failedCount++
                db.pendingSmsDao().updateStatus(
                    item.id,
                    "FAILED",
                    "فئة الكرت غير محددة",
                    System.currentTimeMillis()
                )
                continue
            }

            val result = processSingleOrder(
                pendingId = item.id,
                customerPhone = item.senderPhone,
                category = category,
                explicitAmount = item.parsedAmount
            )
            if (result.startsWith("تم")) {
                processedCount++
            } else {
                failedCount++
            }
        }

        return "تمت معالجة القائمة: ناجح ($processedCount) | فاشل/معلق ($failedCount)"
    }

    private suspend fun processSingleOrder(
        pendingId: Long,
        customerPhone: String,
        category: String,
        explicitAmount: Double?
    ): String {
        val card = db.cardDao().getNextAvailableCard(category)
        if (card == null) {
            val reason = "نفاد الكروت المتاحة للفئة: $category"
            db.pendingSmsDao().updateStatus(pendingId, "FAILED", reason, System.currentTimeMillis())
            NotificationHelper.showAlert(
                context,
                "نفاد المخزون",
                "لا توجد كروت متاحة للفئة $category لطلب الرقم $customerPhone",
                notificationId = 2002
            )
            return reason
        }

        val amount = explicitAmount ?: if (card.price > 0.0) card.price else (category.toDoubleOrNull() ?: 0.0)
        val activeWallet = db.walletDao().getActiveWallet()
        if (activeWallet != null && amount > 0.0) {
            if (activeWallet.balance < amount) {
                val reason = "رصيد المحفظة (${activeWallet.name}) غير كافٍ للمبلغ $amount"
                db.pendingSmsDao().updateStatus(pendingId, "FAILED", reason, System.currentTimeMillis())
                NotificationHelper.showAlert(
                    context,
                    "تنبيه رصيد المحفظة",
                    reason,
                    notificationId = 2003
                )
                return reason
            }
        }

        val updatedRows = db.cardDao().markCardSold(
            cardId = card.id,
            phone = customerPhone,
            soldAt = System.currentTimeMillis()
        )
        if (updatedRows <= 0) {
            val reason = "تعذر حجز الكرت، حاول مرة أخرى"
            db.pendingSmsDao().updateStatus(pendingId, "FAILED", reason, System.currentTimeMillis())
            return reason
        }

        val replyMessage = "تم استلام طلبك بنجاح. كرت فئة ${card.category}: ${card.code}"
        val sendResult = SmsSender.sendSms(context, customerPhone, replyMessage)

        if (!sendResult.success) {
            val reason = sendResult.error ?: "فشل إرسال كود الكرت عبر SMS"
            db.pendingSmsDao().updateStatus(pendingId, "FAILED", reason, System.currentTimeMillis())
            NotificationHelper.showAlert(
                context,
                "فشل إرسال الرسالة",
                "للرقم $customerPhone: $reason",
                notificationId = 2004
            )
            return reason
        }

        if (activeWallet != null && amount > 0.0) {
            val newBalance = (activeWallet.balance - amount).coerceAtLeast(0.0)
            db.walletDao().updateWallet(activeWallet.copy(balance = newBalance))
        }

        db.saleLogDao().insertSale(
            SaleLogEntity(
                customerPhone = customerPhone,
                cardCategory = card.category,
                cardCode = card.code,
                amount = amount,
                walletId = activeWallet?.id
            )
        )

        db.pendingSmsDao().updateStatus(pendingId, "SENT", null, System.currentTimeMillis())
        return "تم تنفيذ الطلب وإرسال الكرت فئة ${card.category} إلى $customerPhone"
    }
}


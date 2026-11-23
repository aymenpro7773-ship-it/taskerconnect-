package com.mystore.app.engine

import android.content.Context
import com.mystore.app.automation.AppPreferences
import com.mystore.app.data.AppDatabase
import com.mystore.app.data.PendingSmsEntity
import com.mystore.app.data.SaleLogEntity

class OrderProcessor(private val context: Context) {

    private val db by lazy { AppDatabase.getInstance(context) }
    private val prefs by lazy { AppPreferences(context) }

    suspend fun handleIncomingSms(senderPhone: String, rawBody: String): String {
        if (!prefs.isServiceEnabled) {
            return "الخدمة متوقفة يدوياً."
        }

        val cleanBody = MessageParser.sanitize(rawBody)
        val parsed = MessageParser.parse(
            body = cleanBody,
            keywordPattern = prefs.keywordPattern,
            phonePattern = prefs.phonePattern
        )

        val allowedCategories = prefs.getAllowedCategories()
        val candidateCategory = parsed.category?.trim()
            ?: extractAllowedCategoryFromText(cleanBody, allowedCategories)

        // تجاهل صارم لأي رسالة لا تحتوي على فئة صريحة ضمن الفئات المسموحة فقط
        // (يمنع تماماً اعتبار أرقام مثل 6761865 كفئة أو إطلاق "نفاد مخزون" عليها)
        if (candidateCategory.isNullOrEmpty() || candidateCategory !in allowedCategories) {
            return "تم تجاهل الرسالة: لا توجد فئة معتمدة ضمن الفئات المسموحة."
        }

        // استخراج رقم صريح كامل فقط، أو رقم بديل مفعّل مسبقاً صراحةً (بدون أي ربط بالبادئة المشابهة)
        val resolvedPhone = resolveExplicitRecipientPhone(
            senderPhone = senderPhone,
            parsedPhone = parsed.customerPhone,
            rawBody = cleanBody
        )

        val pendingId = db.pendingSmsDao().insertPending(
            PendingSmsEntity(
                senderPhone = resolvedPhone ?: senderPhone,
                rawBody = rawBody,
                parsedCategory = candidateCategory,
                parsedAmount = parsed.amount,
                status = if (resolvedPhone != null) "PENDING" else "WAITING_PHONE"
            )
        )

        if (resolvedPhone == null) {
            val partialDigits = extractPartialDigits(cleanBody) ?: "غير مذكور"
            val reason = "بانتظار رقم صريح أو رقم بديل مفعّل (الظاهر: $partialDigits)"
            db.pendingSmsDao().updateStatus(pendingId, "WAITING_PHONE", reason, System.currentTimeMillis())
            NotificationHelper.showAlert(
                context,
                "تحويل بانتظار رقم صريح",
                "فئة $candidateCategory محفوظة بالانتظار ولم يُرسل أي كرت (الرقم الظاهر: $partialDigits)",
                notificationId = (pendingId % 1000 + 2150).toInt()
            )
            return reason
        }

        return processSingleOrder(
            pendingId = pendingId,
            customerPhone = resolvedPhone,
            category = candidateCategory,
            explicitAmount = parsed.amount
        )
    }

    suspend fun processPendingQueue(): String {
        val pendingList = db.pendingSmsDao().getPendingSmsList()
        if (pendingList.isEmpty()) {
            return "لا توجد طلبات معلقة في قائمة الانتظار."
        }

        val allowedCategories = prefs.getAllowedCategories()
        var processedCount = 0
        var skippedOrFailedCount = 0

        for (item in pendingList) {
            val cleanBody = MessageParser.sanitize(item.rawBody)
            val parsed = MessageParser.parse(
                body = cleanBody,
                keywordPattern = prefs.keywordPattern,
                phonePattern = prefs.phonePattern
            )
            val category = item.parsedCategory?.trim()
                ?: parsed.category?.trim()
                ?: extractAllowedCategoryFromText(cleanBody, allowedCategories)

            if (category.isNullOrEmpty() || category !in allowedCategories) {
                skippedOrFailedCount++
                db.pendingSmsDao().updateStatus(
                    item.id,
                    "IGNORED",
                    "فئة غير معتمدة",
                    System.currentTimeMillis()
                )
                continue
            }

            val resolvedPhone = resolveExplicitRecipientPhone(
                senderPhone = item.senderPhone,
                parsedPhone = parsed.customerPhone,
                rawBody = cleanBody
            )

            if (resolvedPhone == null) {
                skippedOrFailedCount++
                db.pendingSmsDao().updateStatus(
                    item.id,
                    "WAITING_PHONE",
                    "لا يوجد رقم صريح أو رقم بديل مفعّل",
                    System.currentTimeMillis()
                )
                continue
            }

            val result = processSingleOrder(
                pendingId = item.id,
                customerPhone = resolvedPhone,
                category = category,
                explicitAmount = item.parsedAmount ?: parsed.amount
            )
            if (result.startsWith("تم")) {
                processedCount++
            } else {
                skippedOrFailedCount++
            }
        }

        return "تمت معالجة القائمة: ناجح ($processedCount) | بانتظار/غير مكتمل ($skippedOrFailedCount)"
    }

    private suspend fun processSingleOrder(
        pendingId: Long,
        customerPhone: String,
        category: String,
        explicitAmount: Double?
    ): String {
        if (!prefs.isServiceEnabled) {
            return "الخدمة متوقفة يدوياً."
        }
        if (!prefs.isValidYemeniPhone(customerPhone)) {
            val reason = "رقم المستلم غير صريح أو غير مكتمل ($customerPhone)"
            db.pendingSmsDao().updateStatus(pendingId, "WAITING_PHONE", reason, System.currentTimeMillis())
            return reason
        }

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
        if (activeWallet != null && amount > 0.0 && activeWallet.balance < amount) {
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

        val replyMessage = MessageParser.buildReplyMessage(
            amount = category,
            cardCode = card.code,
            bundles = prefs.getBundlesMap(),
            template = prefs.replyTemplate
        )

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

    private fun resolveExplicitRecipientPhone(
        senderPhone: String,
        parsedPhone: String?,
        rawBody: String
    ): String? {
        // 1. رقم صريح كامل (9 أرقام تبدأ بـ 7) في الرسالة المحللة أو في نص الرسالة
        val sanitizedBody = rawBody.replace(Regex("""رص\s*:?\s*\d+(?:\.\d+)?"""), " ")
        val explicitInBody = Regex("""\b(7\d{8})\b""").findAll(sanitizedBody)
            .map { it.value }
            .lastOrNull { prefs.isValidYemeniPhone(it) }

        if (prefs.isValidYemeniPhone(parsedPhone)) return parsedPhone!!.filter { it.isDigit() }
        if (explicitInBody != null) return explicitInBody

        // 2. رقم بديل مفعّل مسبقاً صراحةً للأرقام النهائية الظاهرة أو للمرسل
        val trailingDigits = extractPartialDigits(rawBody)
        if (!trailingDigits.isNullOrBlank()) {
            val aliasByTrailing = prefs.getExplicitAlias(trailingDigits)
            if (aliasByTrailing != null) return aliasByTrailing
        }

        val aliasBySender = prefs.getExplicitAlias(senderPhone)
        if (aliasBySender != null) return aliasBySender

        // 3. إذا كان المرسل نفسه رقماً يمنياً كاملاً صريحاً في رسالة طلب مباشرة
        if (prefs.isValidYemeniPhone(senderPhone)) {
            return senderPhone.filter { it.isDigit() }
        }

        return null
    }

    private fun extractAllowedCategoryFromText(body: String, allowedCategories: List<String>): String? {
        // يلتقط فئة صريحة تلي كلمة (فئة / كرت / باقة / اضيف) وتكون ضمن الفئات المسموحة فقط
        val match = Regex("""(?:فئة|كرت|باقة|اضيف)\s*(\d+)""").find(body)
        val candidate = match?.groupValues?.get(1)?.trim()
        return if (candidate != null && candidate in allowedCategories) candidate else null
    }

    private fun extractPartialDigits(body: String): String? {
        val match = Regex("""[-:\s](\d{4,8})\s*$""").find(body.trim())
        return match?.groupValues?.get(1)
    }
}

package com.mystore.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object ServiceStateManager {
    private const val PREFS_NAME = "automation_strict_prefs"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_ALLOWED_CATEGORIES = "allowed_categories"
    private const val KEY_ALIAS_PREFIX = "explicit_alias_"

    private val DEFAULT_CATEGORIES = setOf(100, 200, 300, 500, 1000, 2000)

    fun isServiceEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SERVICE_ENABLED, true)
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_ENABLED, enabled)
            .apply()
    }

    fun getAllowedCategories(context: Context): Set<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ALLOWED_CATEGORIES, null)
        return saved?.mapNotNull { it.toIntOrNull() }?.toSet() ?: DEFAULT_CATEGORIES
    }

    fun setAllowedCategories(context: Context, categories: Set<Int>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ALLOWED_CATEGORIES, categories.map { it.toString() }.toSet())
            .apply()
    }

    /**
     * تفعيل رقم بديل صريح ومعتمد مسبقاً لرقم أو طلب معين فقط
     * بدون أي ربط تلقائي بالبادئة المشابهة
     */
    fun setExplicitAlias(context: Context, sourceKey: String, verifiedFullPhone: String?) {
        val cleanKey = sourceKey.filter { it.isDigit() }
        val cleanTarget = verifiedFullPhone?.filter { it.isDigit() }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (cleanKey.isNotEmpty() && isValidFullPhone(cleanTarget)) {
            editor.putString(KEY_ALIAS_PREFIX + cleanKey, cleanTarget)
        } else if (cleanKey.isNotEmpty()) {
            editor.remove(KEY_ALIAS_PREFIX + cleanKey)
        }
        editor.apply()
    }

    fun getExplicitAlias(context: Context, sourceKey: String): String? {
        val cleanKey = sourceKey.filter { it.isDigit() }
        if (cleanKey.isEmpty()) return null
        val target = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ALIAS_PREFIX + cleanKey, null)
        return if (isValidFullPhone(target)) target else null
    }

    fun isValidFullPhone(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        val digits = phone.filter { it.isDigit() }
        // رقم يمني كامل مكوّن من 9 أرقام يبدأ بـ 7 (مثلاً 77xxxxxxx أو 78xxxxxxx)
        return digits.length == 9 && digits.startsWith("7")
    }
}

class AutomationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_PROCESS_SMS = "com.mystore.app.ACTION_PROCESS_SMS"
        const val ACTION_STOP_SERVICE_MANUAL = "com.mystore.app.ACTION_STOP_SERVICE_MANUAL"
        const val ACTION_START_SERVICE_MANUAL = "com.mystore.app.ACTION_START_SERVICE_MANUAL"

        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_BODY = "extra_body"

        private const val CHANNEL_ID = "automation_service_channel"
        private const val ALERT_CHANNEL_ID = "automation_alerts_channel"
        private const val FOREGROUND_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE_MANUAL -> {
                ServiceStateManager.setServiceEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_SERVICE_MANUAL -> {
                ServiceStateManager.setServiceEnabled(this, true)
                startForeground(FOREGROUND_ID, buildForegroundNotification("الخدمة نشطة وتعمل بوضع صارم"))
                return START_STICKY
            }
        }

        if (!ServiceStateManager.isServiceEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(FOREGROUND_ID, buildForegroundNotification("الخدمة نشطة لمراقبة التحويلات"))

        if (intent?.action == ACTION_PROCESS_SMS) {
            val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
            val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
            serviceScope.launch {
                handleIncomingMessage(sender, body)
            }
        }

        // إذا تم إيقافها يدوياً لن تُعاد، أما إذا كانت مفعلة فتبقى جاهزة
        return if (ServiceStateManager.isServiceEnabled(this)) START_STICKY else START_NOT_STICKY
    }

    private suspend fun handleIncomingMessage(sender: String, body: String) {
        if (!ServiceStateManager.isServiceEnabled(this)) return

        // 1. فحص هل الرسالة تحويل مالي (مثل رسائل Jaib بصيغة "اضيف 100ر.ي ...")
        val amount = extractTransferAmount(body)
        if (amount != null) {
            processDepositTransfer(sender, body, amount)
            return
        }

        // 2. إذا لم تكن رسالة تحويل: نتأكد هل تمثل فئة كرت صريحة من الفئات المسموحة فقط
        // أي رقم عشوائي مثل 6761865 لا يُعتبر فئة ولا يطلق "نفاد مخزون"
        val explicitCategory = extractExplicitCategoryIfAllowed(body)
        if (explicitCategory == null) {
            Log.d("AutomationService", "Ignored non-category / non-transfer message from $sender")
            return
        }

        // في حال وجود طلب فئة صريح مع رقم مستلم صريح كامل فقط
        val recipientPhone = extractExplicitFullPhone(body)
            ?: ServiceStateManager.getExplicitAlias(this, sender)

        if (!ServiceStateManager.isValidFullPhone(recipientPhone)) {
            postAlertNotification(
                title = "طلب غير مكتمل الرقم",
                text = "تم تجاهل إرسال فئة $explicitCategory لعدم وجود رقم مستلم صريح كامل."
            )
            return
        }

        fulfillCardOrder(category = explicitCategory, recipientPhone = recipientPhone!!)
    }

    /**
     * استخراج مبلغ التحويل حتى لو التصق بـ "ر.ي" مثل: اضيف 100ر.ي
     */
    private fun extractTransferAmount(body: String): Int? {
        val hasTransferKeyword = body.contains("اضيف") ||
            body.contains("تحويل") ||
            body.contains("ر.ي") ||
            body.contains("Jaib", ignoreCase = true)

        if (!hasTransferKeyword) return null

        // يلتقط الرقم الملاصق لـ "ر.ي" أو المسبوق بـ "اضيف"
        val attachedMatch = Regex("""(\d+)\s*ر\.?\s*ي""").find(body)
        if (attachedMatch != null) {
            return attachedMatch.groupValues[1].toIntOrNull()
        }

        val addedMatch = Regex("""اضيف\s*(\d+)""").find(body)
        return addedMatch?.groupValues[1]?.toIntOrNull()
    }

    private suspend fun processDepositTransfer(sender: String, body: String, amount: Int) {
        val allowedCategories = ServiceStateManager.getAllowedCategories(this)
        if (amount !in allowedCategories) {
            Log.d("AutomationService", "Transfer amount $amount is not in allowed categories.")
            return
        }

        // استخراج الرقم الصريح الكامل (9 أرقام تبدأ بـ 7) فقط، بدون تخمين أو إكمال بالبادئة
        val directFullPhone = extractExplicitFullPhone(body)
        val rawTrailingDigits = extractTrailingDigits(body)
        val explicitAlias = rawTrailingDigits?.let { ServiceStateManager.getExplicitAlias(this, it) }
            ?: ServiceStateManager.getExplicitAlias(this, sender)

        val finalRecipient = when {
            ServiceStateManager.isValidFullPhone(directFullPhone) -> directFullPhone
            ServiceStateManager.isValidFullPhone(explicitAlias) -> explicitAlias
            else -> null
        }

        if (finalRecipient == null) {
            // الرقم ناقص أو غير صريح: حفظ كطلب معلق بانتظار رقم صريح أو رقم بديل مفعّل مسبقاً
            val partialInfo = rawTrailingDigits ?: "غير مذكور"
            savePendingTransferWithoutCard(
                sender = sender,
                amount = amount,
                partialPhone = partialInfo,
                rawBody = body
            )
            postAlertNotification(
                title = "تحويل بانتظار رقم صريح",
                text = "تحويل فئة $amount (الرقم الظاهر: $partialInfo) محفوظ بالانتظار ولم يُرسل أي كرت."
            )
            return
        }

        fulfillCardOrder(category = amount, recipientPhone = finalRecipient)
    }

    /**
     * التحقق من أن الرقم في الرسالة فئة حقيقية موجودة ضمن الفئات المسموحة فقط،
     * وليس رقماً عشوائياً مثل 6761865
     */
    private fun extractExplicitCategoryIfAllowed(body: String): Int? {
        val allowed = ServiceStateManager.getAllowedCategories(this)
        val categoryRegex = Regex("""(?:فئة|كرت|باقة)\s*(\d+)""")
        val match = categoryRegex.find(body) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return if (value in allowed) value else null
    }

    /**
     * يستخرج رقماً كاملاً صريحاً من 9 أرقام يبدأ بـ 7 فقط
     */
    private fun extractExplicitFullPhone(body: String): String? {
        // نستبعد أرقام الرصيد المسبوقة بـ "رص:" أو "رص"
        val sanitized = body.replace(Regex("""رص\s*:?\s*\d+(?:\.\d+)?"""), " ")
        val matches = Regex("""\b(7\d{8})\b""").findAll(sanitized).map { it.value }.toList()
        return matches.lastOrNull { ServiceStateManager.isValidFullPhone(it) }
    }

    private fun extractTrailingDigits(body: String): String? {
        val match = Regex("""[-:\s](\d{4,8})\s*$""").find(body.trim())
        return match?.groupValues?.get(1)
    }

    private suspend fun fulfillCardOrder(category: Int, recipientPhone: String) {
        if (!ServiceStateManager.isServiceEnabled(this)) return
        if (!ServiceStateManager.isValidFullPhone(recipientPhone)) return

        // هنا تربط بدالة جلب كرت حقيقي غير مستخدم من قاعدة البيانات Room الخاصة بك
        // شرط أساسي: لا يتم استخدام أي كرت تجريبي مثل CRD100A1001
        val realCardCode: String? = fetchRealUnusedCardFromDb(category)

        if (realCardCode.isNullOrBlank()) {
            postAlertNotification(
                title = "نفاد المخزون",
                text = "لا توجد كروت متاحة للفئة $category لطلب الرقم $recipientPhone"
            )
            return
        }

        val smsText = "تم استلام طلبك بنجاح. كرت فئة $category: $realCardCode"
        val sent = sendSmsSafely(recipientPhone, smsText)
        if (sent) {
            markCardAsUsedInDb(realCardCode, recipientPhone)
        }
    }

    private fun sendSmsSafely(phone: String, message: String): Boolean {
        if (!ServiceStateManager.isServiceEnabled(this)) return false
        if (!ServiceStateManager.isValidFullPhone(phone)) return false

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phone, null, message, null, null)
            true
        } catch (e: Exception) {
            Log.e("AutomationService", "Failed to send SMS to $phone", e)
            false
        }
    }

    // اربط هذه الدوال بمستودع Room الفعلي لديك دون إدراج بيانات تجريبية وهمية
    private suspend fun fetchRealUnusedCardFromDb(category: Int): String? {
        // TODO: استبدلها باستعلام Room الفعلي لكروت الفئة غير المباعة وغير التجريبية
        return null
    }

    private suspend fun markCardAsUsedInDb(cardCode: String, recipientPhone: String) {
        // TODO: استبدلها بتحديث حالة الكرت في Room إلى مباع/مستخدم
    }

    private suspend fun savePendingTransferWithoutCard(
        sender: String,
        amount: Int,
        partialPhone: String,
        rawBody: String
    ) {
        // TODO: احفظ السجل في جدول الطلبات المعلقة (Pending) في Room بدون صرف كرت
        Log.i("AutomationService", "Pending transfer saved: amount=$amount, partial=$partialPhone, from=$sender")
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("إدارة كروت الإنترنت")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun postAlertNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "خدمة الأتمتة",
                NotificationManager.IMPORTANCE_LOW
            )
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "تنبيهات العمليات",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

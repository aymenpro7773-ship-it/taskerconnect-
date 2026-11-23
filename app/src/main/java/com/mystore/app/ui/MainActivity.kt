package com.mystore.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mystore.app.R
import com.mystore.app.automation.AppPreferences
import com.mystore.app.data.AppDatabase
import com.mystore.app.data.CardEntity
import com.mystore.app.databinding.ActivityMainBinding
import com.mystore.app.engine.AutomationService
import com.mystore.app.engine.MessageParser
import com.mystore.app.engine.NotificationHelper
import com.mystore.app.engine.OrderProcessor
import com.mystore.app.engine.SmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private val db by lazy { AppDatabase.getInstance(this) }
    private val processor by lazy { OrderProcessor(this) }
    private val eventLogs = ArrayDeque<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this)
        AppPreferences.applyTheme(prefs.isDarkMode)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNeededPermissions()
        setupBottomNavigation()
        setupActions()
        loadRulesIntoSettingsFields()
        refreshDashboardSummary()
        recordLog("تم تشغيل التطبيق وتهيئة الأقسام.")

        if (prefs.isLockEnabled && prefs.hasSecret()) {
            showLockDialog()
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.sectionHome.visibility = View.GONE
            binding.sectionSend.visibility = View.GONE
            binding.sectionCustomers.visibility = View.GONE
            binding.sectionStock.visibility = View.GONE
            binding.sectionArchive.visibility = View.GONE
            binding.sectionSettings.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_home -> {
                    binding.sectionHome.visibility = View.VISIBLE
                    refreshDashboardSummary()
                }
                R.id.nav_send -> binding.sectionSend.visibility = View.VISIBLE
                R.id.nav_customers -> {
                    binding.sectionCustomers.visibility = View.VISIBLE
                    loadCustomersReport("")
                }
                R.id.nav_stock -> binding.sectionStock.visibility = View.VISIBLE
                R.id.nav_archive -> {
                    binding.sectionArchive.visibility = View.VISIBLE
                    loadSalesReport(1, "يومي")
                }
                R.id.nav_settings -> {
                    binding.sectionSettings.visibility = View.VISIBLE
                    loadRulesIntoSettingsFields()
                }
            }
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun setupActions() {
        binding.btnToggleTheme.setOnClickListener {
            prefs.isDarkMode = !prefs.isDarkMode
            AppPreferences.applyTheme(prefs.isDarkMode)
            val modeText = if (prefs.isDarkMode) "الوضع الليلي" else "الوضع النهاري"
            toast("تم تفعيل $modeText")
            recordLog("تبديل المظهر إلى $modeText.")
        }

        binding.btnStartService.setOnClickListener {
            AutomationService.startService(this)
            binding.tvServiceStatus.text = "حالة الخدمة: نشطة"
            appendOutput("تم تشغيل خدمة الأتمتة.")
            recordLog("تشغيل خدمة الأتمتة.")
        }

        binding.btnStopService.setOnClickListener {
            AutomationService.stopService(this)
            binding.tvServiceStatus.text = "حالة الخدمة: متوقفة"
            appendOutput("تم إيقاف خدمة الأتمتة.")
            recordLog("إيقاف خدمة الأتمتة.")
        }

        binding.btnReportDaily.setOnClickListener { loadSalesReport(1, "يومي") }
        binding.btnReportWeekly.setOnClickListener { loadSalesReport(7, "أسبوعي") }
        binding.btnReportMonthly.setOnClickListener { loadSalesReport(30, "شهري") }

        binding.btnProcessQueue.setOnClickListener {
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { processor.processPendingQueue() }
                appendOutput(res)
                recordLog("قائمة الانتظار: $res")
                refreshDashboardSummary()
            }
        }

        binding.btnSimulate.setOnClickListener {
            val rawSms = binding.etMessage.text.toString()
            val manualPhone = binding.etPhone.text.toString().trim()
            runSimulation(rawSms, manualPhone)
        }

        binding.btnSendReal.setOnClickListener {
            val phone = binding.etPhone.text.toString().trim()
            val msg = binding.etMessage.text.toString().trim()
            if (phone.isEmpty() || msg.isEmpty()) {
                toast("أدخل رقم المستلم ونص الرسالة")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    SmsSender.sendSms(this@MainActivity, phone, msg)
                }
                val statusText = if (result.success) {
                    "تم الإرسال الفعلي بنجاح إلى $phone"
                } else {
                    "فشل الإرسال إلى $phone: ${result.error ?: "خطأ غير معروف"}"
                }
                appendOutput(statusText)
                recordLog(statusText)
            }
        }

        binding.btnLoadCustomers.setOnClickListener {
            val query = binding.etSearchCustomer.text.toString().trim()
            loadCustomersReport(query)
        }

        binding.btnAddCodes.setOnClickListener {
            val category = binding.etStockCategory.text.toString().trim()
            val rawLines = binding.etBulkCodes.text.toString()
            if (category.isEmpty() || rawLines.isBlank()) {
                toast("حدد الفئة والصق الأكواد")
                return@setOnClickListener
            }
            val codes = rawLines.split(Regex("\\r?\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            lifecycleScope.launch {
                val inserted = withContext(Dispatchers.IO) {
                    val entities = codes.map { code ->
                        CardEntity(
                            category = category,
                            code = code,
                            price = category.toDoubleOrNull() ?: 0.0,
                            isSold = false
                        )
                    }
                    db.cardDao().insertCards(entities).count { it > 0 }
                }
                binding.etBulkCodes.setText("")
                val msg = "تمت إضافة $inserted كرت جديد إلى فئة $category."
                appendOutput(msg)
                recordLog(msg)
                refreshDashboardSummary()
            }
        }

        binding.btnLoadArchive.setOnClickListener {
            loadSalesReport(7, "آخر 7 أيام")
        }

        binding.btnAddCategory.setOnClickListener {
            val newCat = binding.etNewCategory.text.toString().trim()
            if (newCat.isEmpty()) {
                toast("أدخل اسم أو قيمة الفئة الجديدة")
                return@setOnClickListener
            }
            val currentList = prefs.getAllowedCategories().toMutableList()
            if (newCat !in currentList) {
                currentList.add(newCat)
                prefs.allowedCategoriesCsv = currentList.joinToString(",")
                binding.etAllowedCategories.setText(prefs.allowedCategoriesCsv)
            }
            binding.etNewCategory.setText("")
            val msg = "تمت إضافة الفئة ($newCat) إلى قائمة الفئات المسموحة."
            appendOutput(msg)
            recordLog(msg)
        }

        binding.btnSaveRules.setOnClickListener {
            val cats = binding.etAllowedCategories.text.toString().trim()
            val threshold = binding.etLowStockThreshold.text.toString().trim().toIntOrNull()
            val kw = binding.etKeywordPattern.text.toString().trim()
            val bundles = binding.etBundlesCsv.text.toString().trim()
            val tpl = binding.etReplyTemplate.text.toString()

            if (cats.isNotEmpty()) prefs.allowedCategoriesCsv = cats
            if (threshold != null) prefs.lowStockThreshold = threshold
            if (kw.isNotEmpty()) prefs.keywordPattern = kw
            if (bundles.isNotEmpty()) prefs.bundlesCsv = bundles
            if (tpl.isNotBlank()) prefs.replyTemplate = tpl

            val msg = "تم حفظ الفئات (${prefs.allowedCategoriesCsv}) وحد التنبيه (${prefs.lowStockThreshold}) والقواعد."
            appendOutput(msg)
            recordLog(msg)
        }

        binding.btnSaveLock.setOnClickListener {
            val secret = binding.etNewSecret.text.toString().trim()
            if (secret.length < 4) {
                toast("أدخل نقشاً أو رمزاً من 4 خانات أو أكثر")
                return@setOnClickListener
            }
            prefs.setSecret(secret, "PATTERN")
            binding.etNewSecret.setText("")
            val msg = "تم تفعيل/تحديث قفل الدخول الداخلي بنجاح."
            appendOutput(msg)
            recordLog(msg)
        }

        binding.btnDisableLock.setOnClickListener {
            prefs.isLockEnabled = false
            val msg = "تم إيقاف قفل الدخول الداخلي."
            appendOutput(msg)
            recordLog(msg)
        }

        binding.btnClearLog.setOnClickListener {
            eventLogs.clear()
            binding.tvEventLog.text = "سجل اللوج فارغ."
        }
    }

    private fun runSimulation(rawSms: String, fallbackPhone: String) {
        lifecycleScope.launch {
            val cleanSms = MessageParser.sanitize(rawSms)
            val parsed = MessageParser.parse(
                body = cleanSms,
                keywordPattern = prefs.keywordPattern,
                phonePattern = prefs.phonePattern
            )
            val category = parsed.category.orEmpty()
            val phone = (parsed.customerPhone ?: fallbackPhone).trim()
            val allowed = prefs.getAllowedCategories()

            if (category.isEmpty() || phone.isEmpty()) {
                val msg = "[محاكاة] تعذر استخراج الفئة أو رقم العميل من النص: $cleanSms"
                appendOutput(msg)
                recordLog(msg)
                return@launch
            }
            if (category !in allowed) {
                val msg = "[محاكاة] فئة غير مدعومة: $category (المسموح: ${allowed.joinToString()})"
                appendOutput(msg)
                recordLog(msg)
                return@launch
            }

            val nextCard = withContext(Dispatchers.IO) {
                db.cardDao().getNextAvailableCard(category)
            }
            if (nextCard == null) {
                val alertMsg = "المخزون فارغ لفئة $category للرقم $phone"
                NotificationHelper.showAlert(
                    this@MainActivity,
                    "نفاد المخزون",
                    alertMsg,
                    notificationId = 2010
                )
                appendOutput("[محاكاة] $alertMsg")
                recordLog("إشعار نفاد مخزون: $alertMsg")
                return@launch
            }

            val summaries = withContext(Dispatchers.IO) {
                db.cardDao().getAvailableCountsByCategory()
            }
            val currentCount = summaries.firstOrNull { it.category == category }?.availableCount ?: 0
            val remainingAfter = (currentCount - 1).coerceAtLeast(0)
            val lowStockAlert = if (remainingAfter <= prefs.lowStockThreshold) {
                val text = "فئة $category متبقي فيها $remainingAfter كرت فقط!"
                NotificationHelper.showAlert(
                    this@MainActivity,
                    "تنبيه انخفاض المخزون",
                    text,
                    notificationId = 2011
                )
                recordLog("إشعار انخفاض مخزون: $text")
                "\n⚠️ تنبيه انخفاض المخزون: $text"
            } else ""

            val replyPreview = MessageParser.buildReplyMessage(
                amount = category,
                cardCode = nextCard.code,
                bundles = prefs.getBundlesMap(),
                template = prefs.replyTemplate
            )

            val outputText =
                "[محاكاة ناجحة - لم يتم إرسال SMS]\n" +
                    "الرقم: $phone | الفئة: $category | الكرت المرشح: ${nextCard.code}\n" +
                    "المتبقي بعد الصرف: $remainingAfter$lowStockAlert\n" +
                    "--- نص الرد ---\n$replyPreview"

            appendOutput(outputText)
            recordLog("محاكاة: فئة $category للرقم $phone | متبقي $remainingAfter")
        }
    }

    private fun refreshDashboardSummary() {
        lifecycleScope.launch {
            val startOfDay = dayStartMillis()
            val (todayCount, totalStock) = withContext(Dispatchers.IO) {
                val c = db.saleLogDao().getSalesCountSince(startOfDay)
                val s = db.cardDao().getTotalAvailableCards()
                c to s
            }
            binding.tvTodaySalesCount.text = todayCount.toString()
            binding.tvTotalStockCount.text = totalStock.toString()
        }
    }

    private fun loadSalesReport(daysBack: Int, label: String) {
        lifecycleScope.launch {
            val since = System.currentTimeMillis() - (daysBack * 24L * 60L * 60L * 1000L)
            val reportText = withContext(Dispatchers.IO) {
                val count = db.saleLogDao().getSalesCountSince(since)
                val revenue = db.saleLogDao().getTotalRevenueSince(since)
                val sales = db.saleLogDao().getSalesSince(since).take(15)
                val stockByCat = db.cardDao().getAvailableCountsByCategory()

                val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                val stockLines = if (stockByCat.isEmpty()) "لا يوجد مخزون متاح"
                else stockByCat.joinToString("\n") { "• فئة ${it.category}: ${it.availableCount} كرت" }

                val saleLines = if (sales.isEmpty()) "لا توجد عمليات مسجلة"
                else sales.joinToString("\n") {
                    "• ${fmt.format(Date(it.timestamp))} | ${it.customerPhone} | فئة ${it.cardCategory} | ${it.cardCode}"
                }

                "تقرير ($label):\nالعمليات: $count | الإجمالي: $revenue\n" +
                    "--- المخزون المتاح ---\n$stockLines\n" +
                    "--- آخر العمليات ---\n$saleLines"
            }
            appendOutput(reportText)
            recordLog("عرض تقرير $label.")
        }
    }

    private fun loadCustomersReport(query: String) {
        lifecycleScope.launch {
            val since = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L)
            val text = withContext(Dispatchers.IO) {
                val allSales = db.saleLogDao().getSalesSince(since)
                val filtered = if (query.isBlank()) allSales
                else allSales.filter { it.customerPhone.contains(query, ignoreCase = true) }

                val grouped = filtered.groupBy { it.customerPhone }
                if (grouped.isEmpty()) {
                    "لا توجد سجلات زبائن مطابقة."
                } else {
                    grouped.entries.take(20).joinToString("\n\n") { (phone, items) ->
                        val codes = items.joinToString(", ") { "${it.cardCategory}(${it.cardCode})" }
                        "الزبون: $phone (${items.size} عملية)\nالكروت: $codes"
                    }
                }
            }
            appendOutput(text)
        }
    }

    private fun loadRulesIntoSettingsFields() {
        binding.etAllowedCategories.setText(prefs.allowedCategoriesCsv)
        binding.etLowStockThreshold.setText(prefs.lowStockThreshold.toString())
        binding.etKeywordPattern.setText(prefs.keywordPattern)
        binding.etBundlesCsv.setText(prefs.bundlesCsv)
        binding.etReplyTemplate.setText(prefs.replyTemplate)
    }

    private fun recordLog(entry: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        eventLogs.addFirst("[$stamp] $entry")
        while (eventLogs.size > 40) {
            eventLogs.removeLast()
        }
        binding.tvEventLog.text = eventLogs.joinToString("\n")
    }

    private fun showLockDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "أدخل النقش أو رمز الدخول"
        }
        AlertDialog.Builder(this)
            .setTitle("قفل التطبيق")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("دخول") { _, _ ->
                val entered = input.text.toString()
                if (!prefs.verifySecret(entered)) {
                    toast("الرمز أو النقش غير صحيح")
                    showLockDialog()
                }
            }
            .setNegativeButton("خروج") { _, _ -> finish() }
            .show()
    }

    private fun dayStartMillis(): Long {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        return now - (now % dayMs)
    }

    private fun appendOutput(msg: String) {
        binding.tvOutput.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

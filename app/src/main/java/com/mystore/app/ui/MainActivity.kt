package com.mystore.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private val db by lazy { AppDatabase.getInstance(this) }
    private val processor by lazy { OrderProcessor(this) }
    private val eventLogs = ArrayDeque<String>()
    private var selectedStockCategory: String = "100"
    private var lastExportContent: String = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val importCodesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importCodesFromUri(uri)
        }
    }

    private val exportReportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            exportContentToUri(uri, lastExportContent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this)
        AppPreferences.applyTheme(prefs.isDarkMode, this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyPaletteDirectly(prefs.isDarkMode)

        if (prefs.isServiceEnabled) {
            AutomationService.startService(this)
        }

        syncServiceStatusUi()
        requestNeededPermissions()
        setupBottomNavigation()
        setupActions()
        loadRulesIntoSettingsFields()
        refreshDashboardSummary()
        recordLog("تم تشغيل التطبيق بنمط 2027 (المصادر: ${prefs.allowedSourcesCsv}).")

        if (prefs.isLockEnabled) {
            triggerBiometricOrSecretLock()
        }
    }

    override fun onResume() {
        super.onResume()
        syncServiceStatusUi()
    }

    private fun applyPaletteDirectly(darkMode: Boolean) {
        val bgColor = ContextCompat.getColor(this, if (darkMode) R.color.night_app_bg else R.color.app_bg)
        val cardColor = ContextCompat.getColor(this, if (darkMode) R.color.night_card_bg else R.color.card_bg)
        val strokeColor = ContextCompat.getColor(this, if (darkMode) R.color.night_card_stroke else R.color.card_stroke)
        val inputColor = ContextCompat.getColor(this, if (darkMode) R.color.night_input_bg else R.color.input_bg)
        val logColor = ContextCompat.getColor(this, if (darkMode) R.color.night_log_bg else R.color.log_bg)
        val textPrimary = ContextCompat.getColor(this, if (darkMode) R.color.night_text_primary else R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, if (darkMode) R.color.night_text_secondary else R.color.text_secondary)
        val textHint = ContextCompat.getColor(this, if (darkMode) R.color.night_text_hint else R.color.text_hint)
        val accent = ContextCompat.getColor(this, if (darkMode) R.color.night_accent_primary else R.color.accent_primary)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = bgColor
        }
        binding.rootCoordinator.setBackgroundColor(bgColor)
        binding.headerCard.setCardBackgroundColor(cardColor)
        binding.headerCard.strokeColor = strokeColor
        binding.resultCard.setCardBackgroundColor(cardColor)
        binding.resultCard.strokeColor = strokeColor
        binding.tvHeaderTitle.setTextColor(accent)
        binding.tvResultLabel.setTextColor(accent)
        binding.tvServiceStatus.setTextColor(textSecondary)
        binding.tvOutput.setTextColor(textPrimary)
        binding.tvEventLog.setTextColor(textSecondary)
        binding.tvEventLog.setBackgroundColor(logColor)
        binding.bottomNav.setBackgroundColor(cardColor)
        binding.bottomNav.itemTextColor = android.content.res.ColorStateList.valueOf(textPrimary)
        binding.bottomNav.itemIconTintList = android.content.res.ColorStateList.valueOf(accent)

        val inputs = listOf(
            binding.etPhone,
            binding.etMessage,
            binding.etStockCategory,
            binding.etBulkCodes,
            binding.etSearchCustomer,
            binding.etCustomerAliasKey,
            binding.etCustomerAliasPhone,
            binding.etAllowedCategories,
            binding.etNewCategory,
            binding.etLowStockThreshold,
            binding.etKeywordPattern,
            binding.etBundlesCsv,
            binding.etReplyTemplate,
            binding.etCategoryTemplateCat,
            binding.etCategoryTemplateText,
            binding.etNewSecret
        )
        inputs.forEach { edit ->
            edit.setBackgroundColor(inputColor)
            edit.setTextColor(textPrimary)
            edit.setHintTextColor(textHint)
        }
    }

    private fun syncServiceStatusUi() {
        binding.tvServiceStatus.text = if (prefs.isServiceEnabled) {
            "حالة الخدمة: نشطة (تعمل بالخلفية)"
        } else {
            "حالة الخدمة: متوقفة"
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
            binding.sectionStock.visibility = View.GONE
            binding.sectionCustomers.visibility = View.GONE
            binding.sectionSettings.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_home -> {
                    binding.sectionHome.visibility = View.VISIBLE
                    refreshDashboardSummary()
                }
                R.id.nav_send -> binding.sectionSend.visibility = View.VISIBLE
                R.id.nav_stock -> {
                    binding.sectionStock.visibility = View.VISIBLE
                    if (binding.etStockCategory.text.isNullOrBlank()) {
                        binding.etStockCategory.setText(selectedStockCategory)
                    }
                }
                R.id.nav_customers -> {
                    binding.sectionCustomers.visibility = View.VISIBLE
                    loadSalesAndReceiptSummary()
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
            AppPreferences.applyTheme(prefs.isDarkMode, this)
            applyPaletteDirectly(prefs.isDarkMode)
            val modeText = if (prefs.isDarkMode) "الوضع الليلي" else "الوضع النهاري"
            toast("تم تفعيل $modeText")
            recordLog("تبديل المظهر إلى $modeText.")
        }

        binding.btnStartService.setOnClickListener {
            prefs.isServiceEnabled = true
            AutomationService.startService(this)
            syncServiceStatusUi()
            appendOutput("تم تشغيل خدمة الأتمتة بالخلفية.")
            recordLog("تشغيل خدمة الأتمتة.")
        }

        binding.btnStopService.setOnClickListener {
            prefs.isServiceEnabled = false
            AutomationService.stopService(this)
            syncServiceStatusUi()
            appendOutput("تم إيقاف خدمة الأتمتة يدوياً ولن تعود للعمل إلا بتشغيلها.")
            recordLog("إيقاف خدمة الأتمتة يدوياً.")
        }

        binding.btnReportDaily.setOnClickListener { loadSalesReport(1, "يومي") }
        binding.btnReportWeekly.setOnClickListener { loadSalesReport(7, "أسبوعي") }
        binding.btnReportMonthly.setOnClickListener { loadSalesReport(30, "شهري") }

        binding.btnProcessQueue.setOnClickListener {
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { processor.processPendingQueue() }
                appendOutput(res)
                recordLog("معالجة الانتظار: $res")
                refreshDashboardSummary()
            }
        }

        binding.btnRetryFailed.setOnClickListener {
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { processor.processPendingQueue() }
                val msg = "إعادة محاولة الفاشل/المنتظر: $res"
                NotificationHelper.showAlert(
                    this@MainActivity,
                    "إعادة الإرسال والاحتياطات",
                    msg,
                    notificationId = 2027
                )
                appendOutput(msg)
                recordLog(msg)
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
                val firstTry = withContext(Dispatchers.IO) {
                    SmsSender.sendSms(this@MainActivity, phone, msg)
                }
                val finalResult = if (firstTry.success) {
                    firstTry
                } else {
                    withContext(Dispatchers.IO) {
                        SmsSender.sendSms(this@MainActivity, phone, msg)
                    }
                }
                val statusText = if (finalResult.success) {
                    "تم الإرسال الفعلي بنجاح إلى $phone"
                } else {
                    "فشل الإرسال إلى $phone بعد إعادة المحاولة: ${finalResult.error ?: "خطأ غير معروف"}"
                }
                NotificationHelper.showAlert(
                    this@MainActivity,
                    "حالة إرسال SMS",
                    statusText,
                    notificationId = 2028
                )
                appendOutput(statusText)
                recordLog(statusText)
            }
        }

        binding.btnCat100.setOnClickListener { selectCategoryForStock("100") }
        binding.btnCat200.setOnClickListener { selectCategoryForStock("200") }
        binding.btnCat500.setOnClickListener { selectCategoryForStock("500") }
        binding.btnCat1000.setOnClickListener { selectCategoryForStock("1000") }

        binding.btnAddCodes.setOnClickListener {
            val category = binding.etStockCategory.text.toString().trim().ifEmpty { selectedStockCategory }
            selectedStockCategory = category
            val rawLines = binding.etBulkCodes.text.toString()
            if (rawLines.isBlank()) {
                toast("أدخل الأكواد أولاً")
                return@setOnClickListener
            }
            val codes = rawLines.split(Regex("\\r?\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            insertCodesList(category, codes, clearBox = true)
        }

        binding.btnEditCategoryStock.setOnClickListener {
            val category = binding.etStockCategory.text.toString().trim().ifEmpty { selectedStockCategory }
            selectedStockCategory = category
            val rawLines = binding.etBulkCodes.text.toString()
            val newCodes = rawLines.split(Regex("\\r?\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (newCodes.isNotEmpty()) {
                insertCodesList(category, newCodes, clearBox = true)
            } else {
                selectCategoryForStock(category)
            }
        }

        binding.btnImportFile.setOnClickListener {
            val category = binding.etStockCategory.text.toString().trim().ifEmpty { selectedStockCategory }
            selectedStockCategory = category
            binding.etStockCategory.setText(category)
            importCodesLauncher.launch(arrayOf("text/plain", "text/csv", "*/*"))
        }

        binding.btnExportFile.setOnClickListener {
            val category = binding.etStockCategory.text.toString().trim().ifEmpty { selectedStockCategory }
            selectedStockCategory = category
            lifecycleScope.launch {
                val content = withContext(Dispatchers.IO) {
                    buildCategoryExportText(category)
                }
                lastExportContent = content
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                exportReportLauncher.launch("category_${category}_$stamp.txt")
            }
        }

        binding.btnSalesReceiptReport.setOnClickListener {
            loadSalesAndReceiptSummary()
        }

        binding.btnLoadCustomers.setOnClickListener {
            val query = binding.etSearchCustomer.text.toString().trim()
            loadCustomersReport(query)
        }

        binding.btnLoadArchive.setOnClickListener {
            loadSalesReport(7, "الأرشيف (آخر 7 أيام)")
        }

        binding.btnSaveCustomerAlias.setOnClickListener {
            val key = binding.etCustomerAliasKey.text.toString().trim()
            val targetPhone = binding.etCustomerAliasPhone.text.toString().trim()
            if (key.isEmpty() || !prefs.isValidYemeniPhone(targetPhone)) {
                toast("أدخل المفتاح/الرقم الظاهر ورقماً يمنياً صحيحاً من 9 أرقام يبدأ بـ 7")
                return@setOnClickListener
            }
            prefs.setExplicitAlias(key, targetPhone)
            binding.etCustomerAliasKey.setText("")
            binding.etCustomerAliasPhone.setText("")
            val msg = "تم حفظ الرقم البديل ($targetPhone) للمفتاح ($key)."
            appendOutput(msg)
            recordLog(msg)
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

        binding.btnSaveCategoryTemplate.setOnClickListener {
            val cat = binding.etCategoryTemplateCat.text.toString().trim()
            val catTpl = binding.etCategoryTemplateText.text.toString()
            if (cat.isEmpty() || catTpl.isBlank()) {
                toast("أدخل رقم الفئة ونص القالب المخصص لها")
                return@setOnClickListener
            }
            prefs.setCategoryTemplate(cat, catTpl)
            binding.etCategoryTemplateCat.setText("")
            binding.etCategoryTemplateText.setText("")
            val msg = "تم حفظ قالب الرد المخصص لفئة $cat."
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

        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                recordLog("فتح إعدادات الظهور فوق التطبيقات.")
            } else {
                toast("إذن الظهور فوق التطبيقات مفعّل مسبقاً")
                recordLog("إذن الظهور فوق التطبيقات مفعّل.")
            }
        }

        binding.btnSaveLock.setOnClickListener {
            val secret = binding.etNewSecret.text.toString().trim()
            if (secret.length < 4) {
                toast("أدخل رمزاً أو نقشاً من 4 خانات أو أكثر")
                return@setOnClickListener
            }
            prefs.setSecret(secret, "PATTERN")
            binding.etNewSecret.setText("")
            val msg = "تم تفعيل/تحديث قفل الدخول بنجاح."
            appendOutput(msg)
            recordLog(msg)
        }

        binding.btnDisableLock.setOnClickListener {
            prefs.isLockEnabled = false
            val msg = "تم إيقاف قفل الدخول الداخلي."
            appendOutput(msg)
            recordLog(msg)
        }

        binding.btnBiometricLock.setOnClickListener {
            prefs.isLockEnabled = true
            triggerBiometricOrSecretLock()
            recordLog("تم تشغيل التحقق بالبصمة.")
        }

        binding.btnClearLog.setOnClickListener {
            eventLogs.clear()
            binding.tvEventLog.text = "سجل اللوج فارغ."
        }
    }

    private fun selectCategoryForStock(cat: String) {
        selectedStockCategory = cat
        binding.etStockCategory.setText(cat)
        lifecycleScope.launch {
            val infoText = withContext(Dispatchers.IO) {
                val summaries = db.cardDao().getAvailableCountsByCategory()
                val availableCount = summaries.firstOrNull { it.category == cat }?.availableCount ?: 0
                val nextCard = db.cardDao().getNextAvailableCard(cat)
                val nextCodeInfo = if (nextCard != null) "\nالكرت التالي المتاح: ${nextCard.code}" else "\nلا توجد كروت متاحة حالياً"
                "الفئة المحددة: $cat\nالكمية المتاحة: $availableCount كرت$nextCodeInfo"
            }
            appendOutput(infoText)
            recordLog("فئة $cat: عرض المخزون المتاح.")
        }
    }

    private fun insertCodesList(category: String, codes: List<String>, clearBox: Boolean) {
        if (codes.isEmpty()) {
            toast("لا توجد أكواد صالحة للإضافة")
            return
        }
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
            if (clearBox) {
                binding.etBulkCodes.setText("")
            }
            val msg = "تمت إضافة $inserted كرت إلى فئة $category."
            appendOutput(msg)
            recordLog(msg)
            refreshDashboardSummary()
        }
    }

    private fun importCodesFromUri(uri: Uri) {
        val category = binding.etStockCategory.text.toString().trim().ifEmpty { selectedStockCategory }
        lifecycleScope.launch {
            val codes = withContext(Dispatchers.IO) {
                val list = mutableListOf<String>()
                contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val clean = line.trim()
                            if (clean.isNotEmpty()) {
                                list.add(clean)
                            }
                            line = reader.readLine()
                        }
                    }
                }
                list
            }
            insertCodesList(category, codes, clearBox = false)
            recordLog("استيراد ملف لفئة $category (${codes.size} سطر).")
        }
    }

    private suspend fun buildCategoryExportText(category: String): String {
        val summaries = db.cardDao().getAvailableCountsByCategory()
        val count = summaries.firstOrNull { it.category == category }?.availableCount ?: 0
        val nextCard = db.cardDao().getNextAvailableCard(category)
        return buildString {
            append("=== ملف الفئة: $category ===\n")
            append("الكمية المتاحة في المخزون: $count\n")
            if (nextCard != null) {
                append("الكرت التالي المتاح: ${nextCard.code}\n")
            }
            append("الباقة: ${prefs.getBundlesMap()[category].orEmpty()}\n")
            append("التاريخ: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}\n")
        }
    }

    private fun exportContentToUri(uri: Uri, contentToSave: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(contentToSave.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val msg = if (ok) {
                "تم تصدير ملف الفئة بنجاح."
            } else {
                "تعذر تصدير الملف."
            }
            appendOutput(msg)
            recordLog(msg)
        }
    }

    private fun triggerBiometricOrSecretLock() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        toast("تم التحقق بالبصمة بنجاح")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (prefs.hasSecret()) {
                            showLockDialog()
                        }
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("قفل التطبيق بالبصمة")
                .setSubtitle("أكد هويتك للمتابعة")
                .setNegativeButtonText("استخدام الرمز/النقش")
                .build()
            prompt.authenticate(info)
        } else if (prefs.hasSecret()) {
            showLockDialog()
        }
    }

    private fun loadSalesAndReceiptSummary() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val dayAgo = now - (24L * 60L * 60L * 1000L)
            val weekAgo = now - (7L * 24L * 60L * 60L * 1000L)
            val monthAgo = now - (30L * 24L * 60L * 60L * 1000L)

            val summaryText = withContext(Dispatchers.IO) {
                val dCount = db.saleLogDao().getSalesCountSince(dayAgo)
                val dRev = db.saleLogDao().getTotalRevenueSince(dayAgo)
                val wCount = db.saleLogDao().getSalesCountSince(weekAgo)
                val wRev = db.saleLogDao().getTotalRevenueSince(weekAgo)
                val mCount = db.saleLogDao().getSalesCountSince(monthAgo)
                val mRev = db.saleLogDao().getTotalRevenueSince(monthAgo)
                val stockByCat = db.cardDao().getAvailableCountsByCategory()
                val recentSales = db.saleLogDao().getSalesSince(monthAgo)
                val aliases = prefs.getAllAliases()

                val stockLines = if (stockByCat.isEmpty()) {
                    "لا يوجد مخزون متاح"
                } else {
                    stockByCat.joinToString("\n") {
                        val unitPrice = it.category.toDoubleOrNull() ?: 0.0
                        val catTotal = unitPrice * it.availableCount
                        "• الفئة: ${it.category} | الكمية: ${it.availableCount} | إجمالي السعر: $catTotal"
                    }
                }

                val soldByCat = recentSales.groupBy { it.cardCategory }.entries.joinToString("\n") { (cat, list) ->
                    val sumAmt = list.sumOf { it.amount }
                    "• الفئة المباعة: $cat | الكمية: ${list.size} | إجمالي السعر: $sumAmt"
                }.ifEmpty { "لا توجد مبيعات مسجلة" }

                val aliasLines = if (aliases.isEmpty()) {
                    "لا توجد أرقام بديلة محفوظة"
                } else {
                    aliases.entries.joinToString("\n") { "• المفتاح: ${it.key} -> الرقم البديل: ${it.value}" }
                }

                buildString {
                    append("=== الإجماليات ===\n")
                    append("• اليوم: الكمية $dCount | إجمالي السعر: $dRev\n")
                    append("• الأسبوع: الكمية $wCount | إجمالي السعر: $wRev\n")
                    append("• الشهر: الكمية $mCount | إجمالي السعر: $mRev\n\n")
                    append("=== المخزون حسب الفئة ===\n$stockLines\n\n")
                    append("=== المبيعات حسب الفئة ===\n$soldByCat\n\n")
                    append("=== الأرقام البديلة ===\n$aliasLines")
                }
            }
            appendOutput(summaryText)
            recordLog("تقرير: عرض البيع والاستلام والإجماليات.")
        }
    }

    private fun runSimulation(rawSms: String, fallbackPhone: String) {
        lifecycleScope.launch {
            val cleanSms = MessageParser.sanitize(rawSms)
            val phone = (MessageParser.extractPhone(cleanSms, prefs.phonePattern) ?: fallbackPhone).trim()
            if (phone.isEmpty()) {
                val msg = "[محاكاة] اكتب رقم الزبون أولاً (مثال: 771234567)."
                appendOutput(msg)
                recordLog("محاكاة: لم يُدخل رقم الزبون.")
                return@launch
            }

            val parsed = MessageParser.parse(
                body = cleanSms,
                keywordPattern = prefs.keywordPattern,
                phonePattern = prefs.phonePattern,
                allowedSourcesCsv = prefs.allowedSourcesCsv
            )
            val category = (parsed.category ?: selectedStockCategory).trim()
            val allowed = prefs.getAllowedCategories()

            if (category !in allowed) {
                val msg = "[محاكاة] الفئة ($category) غير مسموحة. المسموح: ${allowed.joinToString()}"
                appendOutput(msg)
                recordLog("محاكاة: فئة غير مسموحة ($category).")
                return@launch
            }

            val nextCard = withContext(Dispatchers.IO) {
                db.cardDao().getNextAvailableCard(category)
            }
            if (nextCard == null) {
                val alertMsg = "لا يوجد كرت متاح في فئة $category للرقم $phone"
                NotificationHelper.showAlert(
                    this@MainActivity,
                    "نفاد المخزون",
                    alertMsg,
                    notificationId = 2010
                )
                appendOutput("[محاكاة] $alertMsg")
                recordLog("تنبيه: نفاد كروت فئة $category.")
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
                recordLog("تنبيه: انخفاض مخزون فئة $category ($remainingAfter متبقي).")
                "\nتنبيه: $text"
            } else {
                ""
            }

            val replyPreview = MessageParser.buildReplyMessage(
                amount = category,
                cardCode = nextCard.code,
                bundles = prefs.getBundlesMap(),
                template = prefs.replyTemplate,
                categoryTemplates = prefs.getCategoryTemplatesMap()
            )

            val outputText = buildString {
                append("الرقم: $phone | الفئة: $category | المتبقي بعد الصرف: $remainingAfter$lowStockAlert\n")
                append("----------------------------------\n")
                append(replyPreview)
            }

            appendOutput(outputText)
            recordLog("محاكاة: الفئة $category للرقم $phone | الكرت ${nextCard.code}.")
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

                val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                val stockLines = if (stockByCat.isEmpty()) {
                    "لا يوجد مخزون متاح"
                } else {
                    stockByCat.joinToString("\n") {
                        val unitPrice = it.category.toDoubleOrNull() ?: 0.0
                        "• الفئة: ${it.category} | الكمية: ${it.availableCount} | إجمالي السعر: ${unitPrice * it.availableCount}"
                    }
                }

                val saleLines = if (sales.isEmpty()) {
                    "لا توجد عمليات مسجلة"
                } else {
                    sales.joinToString("\n\n") { sale ->
                        val sentPreview = MessageParser.buildReplyMessage(
                            amount = sale.cardCategory,
                            cardCode = sale.cardCode,
                            bundles = prefs.getBundlesMap(),
                            template = prefs.replyTemplate,
                            categoryTemplates = prefs.getCategoryTemplatesMap()
                        )
                        "• الوقت: ${fmt.format(Date(sale.timestamp))}\n" +
                            "  الرد: تم الإرسال | الزبون: ${sale.customerPhone}\n" +
                            "  الفئة: ${sale.cardCategory} | الكمية: 1 | السعر: ${sale.amount} | الكرت: ${sale.cardCode}\n" +
                            "  النص المرسل: ${sentPreview.replace("\n", " | ")}"
                    }
                }

                buildString {
                    append("=== تقرير ($label) ===\n")
                    append("الكمية الإجمالية: $count | إجمالي السعر: $revenue\n\n")
                    append("=== المخزون حسب الفئة ===\n$stockLines\n\n")
                    append("=== سجل العمليات ===\n$saleLines")
                }
            }
            appendOutput(reportText)
            recordLog("تقرير: عرض التقرير ($label).")
        }
    }

    private fun loadCustomersReport(query: String) {
        lifecycleScope.launch {
            val since = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L)
            val text = withContext(Dispatchers.IO) {
                val allSales = db.saleLogDao().getSalesSince(since)
                val filtered = if (query.isBlank()) {
                    allSales
                } else {
                    allSales.filter { it.customerPhone.contains(query, ignoreCase = true) }
                }

                val grouped = filtered.groupBy { it.customerPhone }
                if (grouped.isEmpty()) {
                    "لا توجد سجلات زبائن مطابقة."
                } else {
                    grouped.entries.take(20).joinToString("\n\n") { (phone, items) ->
                        val totalAmt = items.sumOf { it.amount }
                        val codes = items.joinToString(", ") { "${it.cardCategory}(${it.cardCode})" }
                        "• الزبون: $phone | الكمية: ${items.size} | إجمالي السعر: $totalAmt\n  الكروت: $codes"
                    }
                }
            }
            appendOutput(text)
            recordLog("الزبائن: عرض سجل الزبائن.")
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
        val stamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
        eventLogs.addFirst("[$stamp] $entry")
        while (eventLogs.size > 40) {
            eventLogs.removeLast()
        }
        binding.tvEventLog.text = eventLogs.joinToString("\n")
    }

    private fun showLockDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "أدخل الرمز أو النقش"
        }
        AlertDialog.Builder(this)
            .setTitle("قفل التطبيق")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("دخول") { _, _ ->
                val entered = input.text.toString().trim()
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

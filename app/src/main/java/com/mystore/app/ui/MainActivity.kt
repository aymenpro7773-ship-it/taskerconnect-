package com.mystore.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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
import java.io.File
import java.io.FileOutputStream
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

    private val categoryCardsCache = linkedMapOf<String, MutableList<String>>()
    private var selectedStockCategory: String = "100"
    private var lastExportContent: String = ""
    private var lockShownInSession: Boolean = false
    private var isUnlockedInSession: Boolean = false
    private var isEditModeEnabled: Boolean = false
    private var categorySpinner: Spinner? = null
    private var updatingSpinnerProgrammatically: Boolean = false

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

    private val exportTextLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            exportTextToUri(uri, lastExportContent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this)
        AppPreferences.applyTheme(prefs.isDarkMode, this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyPaletteDirectly(prefs.isDarkMode)
        loadLocalCardCacheFromPrefs()
        setupCategorySelectorBeforeStockActions()
        setCardsEditorReadOnly(true)

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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && prefs.isLockEnabled && !isUnlockedInSession && !lockShownInSession) {
            lockShownInSession = true
            triggerBiometricOrSecretLock()
        }
    }

    override fun onResume() {
        super.onResume()
        syncServiceStatusUi()
    }

    private fun setupCategorySelectorBeforeStockActions() {
        val parent = binding.etStockCategory.parent as? ViewGroup ?: return
        val indexOfCatField = parent.indexOfChild(binding.etStockCategory)
        if (indexOfCatField < 0) return

        val spinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        categorySpinner = spinner
        parent.addView(spinner, indexOfCatField)
        refreshCategorySpinner(selectedStockCategory)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentAdapter: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingSpinnerProgrammatically) return
                val chosen = spinner.selectedItem?.toString()?.trim().orEmpty()
                if (chosen.isNotEmpty() && chosen != selectedStockCategory) {
                    selectCategoryForStock(chosen)
                }
            }

            override fun onNothingSelected(parentAdapter: AdapterView<*>?) {}
        }
    }

    private fun refreshCategorySpinner(activeCat: String) {
        val spinner = categorySpinner ?: return
        val allCats = linkedSetOf("100", "200", "300", "500", "1000", "2000")
            .apply {
                addAll(prefs.getAllowedCategories())
                addAll(categoryCardsCache.keys)
                add(activeCat)
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, allCats).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        updatingSpinnerProgrammatically = true
        spinner.adapter = adapter
        val idx = allCats.indexOf(activeCat)
        if (idx >= 0) {
            spinner.setSelection(idx, false)
        }
        updatingSpinnerProgrammatically = false
    }

    private fun setCardsEditorReadOnly(readOnly: Boolean) {
        isEditModeEnabled = !readOnly
        binding.etBulkCodes.isFocusable = !readOnly
        binding.etBulkCodes.isFocusableInTouchMode = !readOnly
        binding.etBulkCodes.isCursorVisible = !readOnly
        binding.etBulkCodes.isLongClickable = !readOnly
        binding.btnEditCategoryStock.text = if (readOnly) {
            "تعديل / حذف الكروت"
        } else {
            "حفظ التعديل / الحذف"
        }
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

    private fun loadLocalCardCacheFromPrefs() {
        val sp = getSharedPreferences("cards_list_cache", MODE_PRIVATE)
        val raw = sp.getString("cache_blob", "") ?: ""
        categoryCardsCache.clear()
        if (raw.isNotBlank()) {
            raw.split("###").forEach { block ->
                val parts = block.split("::", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    val codes = parts[1].split("\n")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toMutableList()
                    categoryCardsCache[parts[0].trim()] = codes
                }
            }
        }
        prefs.getAllowedCategories().forEach { cat ->
            if (!categoryCardsCache.containsKey(cat)) {
                categoryCardsCache[cat] = mutableListOf()
            }
        }
        if (!categoryCardsCache.containsKey("100")) {
            categoryCardsCache["100"] = mutableListOf()
        }
    }

    private fun saveLocalCardCacheToPrefs() {
        val sp = getSharedPreferences("cards_list_cache", MODE_PRIVATE)
        val blob = categoryCardsCache.entries.joinToString("###") { (cat, list) ->
            "$cat::${list.joinToString("\n")}"
        }
        sp.edit().putString("cache_blob", blob).apply()
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
                    refreshCategorySpinner(selectedStockCategory)
                    binding.etStockCategory.setText(selectedStockCategory)
                    loadCategoryView(selectedStockCategory)
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
            appendOutput("تم إيقاف خدمة الأتمتة يدوياً.")
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

        binding.btnCat100.text = "100"
        binding.btnCat200.text = "200"
        binding.btnCat500.text = "300"
        binding.btnCat1000.text = "500"
        binding.btnCat2000.text = "2000"

        binding.btnCat100.setOnClickListener { selectCategoryForStock("100") }
        binding.btnCat200.setOnClickListener { selectCategoryForStock("200") }
        binding.btnCat500.setOnClickListener { selectCategoryForStock("300") }
        binding.btnCat1000.setOnClickListener { selectCategoryForStock("500") }
        binding.btnCat2000.setOnClickListener { selectCategoryForStock("2000") }

        binding.etStockCategory.setOnEditorActionListener { _, _, _ ->
            val typedCat = binding.etStockCategory.text.toString().trim()
            if (typedCat.isNotEmpty()) {
                selectCategoryForStock(typedCat)
            }
            true
        }

        // إضافة أكواد جديدة (يفعل التعديل لكتابة الأكواد الجديدة ثم يضيفها)
        binding.btnAddCodes.setOnClickListener {
            val category = binding.etStockCategory.text.toString().trim().ifEmpty { selectedStockCategory }
            selectedStockCategory = category
            if (!isEditModeEnabled && binding.etBulkCodes.text.isNullOrBlank()) {
                setCardsEditorReadOnly(false)
                binding.etBulkCodes.setText("")
                binding.etBulkCodes.hint = "اكتب الأكواد الجديدة (كود بكل سطر) ثم اضغط إضافة الأكواد..."
                toast("اكتب الأكواد الجديدة ثم اضغط إضافة الأكواد")
                return@setOnClickListener
            }
            val codes = extractLines(binding.etBulkCodes.text.toString())
            if (codes.isEmpty()) {
                toast("أدخل كوداً واحداً على الأقل")
                return@setOnClickListener
            }
            insertCodesList(category, codes)
        }

        // زر التعديل/الحذف: يفتح وضع التحرير عند الضغط عليه، وعند الضغط مرة أخرى يحفظ التعديلات والحذف ويعود للقراءة فقط
        binding.btnEditCategoryStock.setOnClickListener {
            val category = binding.etStockCategory.text.toString().trim().ifEmpty { selectedStockCategory }
            selectedStockCategory = category
            val currentList = categoryCardsCache.getOrPut(category) { mutableListOf() }

            if (!isEditModeEnabled) {
                setCardsEditorReadOnly(false)
                binding.etBulkCodes.setText(currentList.joinToString("\n"))
                binding.etBulkCodes.hint = "عدّل أو احذف أي سطر ثم اضغط حفظ التعديل / الحذف..."
                toast("وضع التعديل مفعّل: عدّل أو احذف الأسطر ثم اضغط حفظ التعديل / الحذف")
            } else {
                val updatedCodes = extractLines(binding.etBulkCodes.text.toString())
                saveBulkEditsForCategory(category, updatedCodes)
                setCardsEditorReadOnly(true)
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
            val list = categoryCardsCache[category].orEmpty()
            lastExportContent = list.mapIndexed { idx, code -> "${idx + 1}. $code" }.joinToString("\n")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            exportTextLauncher.launch("cards_${category}_$stamp.txt")
        }

        binding.btnSalesReceiptReport.setOnClickListener {
            loadSalesAndReceiptSummary()
        }

        binding.btnLoadCustomers.setOnClickListener {
            val query = binding.etSearchCustomer.text.toString().trim()
            loadCustomersReport(query)
        }

        binding.btnLoadArchive.setOnClickListener {
            exportSalesSummaryPdf()
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
            if (!categoryCardsCache.containsKey(newCat)) {
                categoryCardsCache[newCat] = mutableListOf()
                saveLocalCardCacheToPrefs()
            }
            selectedStockCategory = newCat
            binding.etStockCategory.setText(newCat)
            binding.etNewCategory.setText("")
            refreshCategorySpinner(newCat)
            loadCategoryView(newCat)
            val msg = "تمت إضافة الفئة ($newCat) وتحديدها."
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

            refreshCategorySpinner(selectedStockCategory)
            val backupPath = createLocalBackupFile()
            val msg = "تم حفظ القواعد وأُنشئت نسخة احتياطية:\n$backupPath"
            appendOutput(msg)
            recordLog("حفظ القواعد + نسخة احتياطية.")
        }

        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            recordLog("فتح إعدادات التطبيق والبطارية.")
        }

        binding.btnSaveLock.setOnClickListener {
            val secret = binding.etNewSecret.text.toString().trim()
            if (secret.length < 4) {
                toast("أدخل رمزاً أو نقشاً من 4 خانات أو أكثر")
                return@setOnClickListener
            }
            prefs.setSecret(secret, "PATTERN")
            binding.etNewSecret.setText("")
            val msg = "تم تفعيل/حفظ الرمز أو النقش ($secret)."
            appendOutput(msg)
            recordLog("تفعيل قفل الرمز/النقش.")
        }

        binding.btnDisableLock.setOnClickListener {
            prefs.isLockEnabled = false
            isUnlockedInSession = true
            val msg = "تم إيقاف قفل الدخول."
            appendOutput(msg)
            recordLog("إيقاف قفل الدخول.")
        }

        binding.btnBiometricLock.setOnClickListener {
            prefs.isLockEnabled = true
            isUnlockedInSession = false
            lockShownInSession = false
            triggerBiometricOrSecretLock()
        }

        binding.btnClearLog.setOnClickListener {
            eventLogs.clear()
            binding.tvEventLog.text = "سجل اللوج فارغ."
        }
    }

    private fun extractLines(raw: String): List<String> {
        return raw.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun selectCategoryForStock(cat: String) {
        selectedStockCategory = cat
        binding.etStockCategory.setText(cat)
        refreshCategorySpinner(cat)
        setCardsEditorReadOnly(true)
        loadCategoryView(cat)
    }

    private fun loadCategoryView(cat: String) {
        val list = categoryCardsCache.getOrPut(cat) { mutableListOf() }
        binding.etBulkCodes.setText("")
        binding.etBulkCodes.hint = "اضغط (إضافة الأكواد) لإضافة أكواد جديدة أو (تعديل / حذف الكروت) لتحرير القائمة..."

        val allCats = linkedSetOf("100", "200", "300", "500", "1000", "2000")
            .apply {
                addAll(prefs.getAllowedCategories())
                addAll(categoryCardsCache.keys)
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val countersLine = allCats.joinToString(" | ") { c ->
            "$c:${categoryCardsCache[c]?.size ?: 0}"
        }

        val numberedCards = if (list.isEmpty()) {
            "لا توجد كروت في فئة $cat حالياً."
        } else {
            list.mapIndexed { idx, code -> "${idx + 1} - $code" }.joinToString("\n")
        }

        appendOutput(
            "الفئة المحددة: $cat | عدد كروت الفئة: ${list.size}\n" +
                "عداد الفئات: $countersLine\n" +
                "----------------------------------\n" +
                numberedCards
        )
        recordLog("عرض كروت فئة $cat (العدد: ${list.size}).")
    }

    private fun saveBulkEditsForCategory(category: String, updatedCodes: List<String>) {
        val previousCount = categoryCardsCache[category]?.size ?: 0
        categoryCardsCache[category] = updatedCodes.toMutableList()
        saveLocalCardCacheToPrefs()
        loadCategoryView(category)

        val diff = updatedCodes.size - previousCount
        val summaryChange = when {
            diff < 0 -> "تم حذف ${-diff} كرت من فئة $category (المتبقي: ${updatedCodes.size})."
            diff > 0 -> "تم تحديث فئة $category بإجمالي ${updatedCodes.size} كرت."
            else -> "تم حفظ تعديلات كروت فئة $category (${updatedCodes.size} كرت)."
        }
        toast(summaryChange)
        recordLog(summaryChange)
    }

    private fun insertCodesList(category: String, codes: List<String>) {
        val targetList = categoryCardsCache.getOrPut(category) { mutableListOf() }
        targetList.addAll(codes)
        saveLocalCardCacheToPrefs()
        setCardsEditorReadOnly(true)
        loadCategoryView(category)

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
            refreshDashboardSummary()
            recordLog("إضافة ${codes.size} كرت إلى فئة $category (قاعدة البيانات: $inserted).")
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
            if (codes.isNotEmpty()) {
                insertCodesList(category, codes)
            } else {
                toast("الملف فارغ")
            }
        }
    }

    private fun exportTextToUri(uri: Uri, contentToSave: String) {
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
            val msg = if (ok) "تم تصدير ملف الفئة بنجاح." else "تعذر تصدير الملف."
            appendOutput(msg)
            recordLog(msg)
        }
    }

    private fun createLocalBackupFile(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "backup_$stamp.txt")
        val content = buildString {
            append("=== نسخة احتياطية ===\n")
            append("التاريخ: $stamp\n")
            append("الفئات المسموحة: ${prefs.allowedCategoriesCsv}\n")
            append("الباقات: ${prefs.bundlesCsv}\n")
            append("قالب الرد: ${prefs.replyTemplate}\n")
            categoryCardsCache.forEach { (cat, list) ->
                append("\n[فئة $cat - العدد ${list.size}]\n")
                list.forEachIndexed { idx, code -> append("${idx + 1} - $code\n") }
            }
        }
        FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return file.absolutePath
    }

    private fun exportSalesSummaryPdf() {
        lifecycleScope.launch {
            val pdfPath = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val dayAgo = now - (24L * 60L * 60L * 1000L)
                val weekAgo = now - (7L * 24L * 60L * 60L * 1000L)
                val monthAgo = now - (30L * 24L * 60L * 60L * 1000L)

                val dCount = db.saleLogDao().getSalesCountSince(dayAgo)
                val dRev = db.saleLogDao().getTotalRevenueSince(dayAgo)
                val wCount = db.saleLogDao().getSalesCountSince(weekAgo)
                val wRev = db.saleLogDao().getTotalRevenueSince(weekAgo)
                val mCount = db.saleLogDao().getSalesCountSince(monthAgo)
                val mRev = db.saleLogDao().getTotalRevenueSince(monthAgo)
                val sales = db.saleLogDao().getSalesSince(monthAgo).take(15)

                val doc = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                val paint = Paint().apply { textSize = 13f }

                var y = 45f
                val lines = mutableListOf(
                    "Sales Summary Report (Day / Week / Month)",
                    "Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}",
                    "Today: Count=$dCount | Total=$dRev",
                    "Week: Count=$wCount | Total=$wRev",
                    "Month: Count=$mCount | Total=$mRev",
                    "----------------------------------------"
                )
                sales.forEach { s ->
                    lines.add("Phone=${s.customerPhone} | Cat=${s.cardCategory} | Code=${s.cardCode} | Amt=${s.amount}")
                }
                for (line in lines) {
                    canvas.drawText(line, 30f, y, paint)
                    y += 24f
                    if (y > 790f) break
                }
                doc.finishPage(page)

                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                val dir = getExternalFilesDir(null) ?: filesDir
                val outFile = File(dir, "sales_summary_$stamp.pdf")
                FileOutputStream(outFile).use { doc.writeTo(it) }
                doc.close()
                outFile.absolutePath
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("تم إنشاء ملف PDF")
                .setMessage("ملخص العمليات (اليوم / الأسبوع / الشهر) جاهز في:\n$pdfPath")
                .setPositiveButton("حسناً", null)
                .show()
            appendOutput("تم إنشاء ملف PDF بنجاح:\n$pdfPath")
            recordLog("تصدير ملخص العمليات PDF.")
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
                        isUnlockedInSession = true
                        toast("تم فتح القفل بالبصمة")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        showLockDialog()
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("قفل التطبيق")
                .setSubtitle("أكد البصمة أو استخدم الرمز")
                .setNegativeButtonText("استخدام الرمز")
                .build()
            prompt.authenticate(info)
        } else {
            showLockDialog()
        }
    }

    private fun showLockDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "أدخل الرمز أو النقش"
        }
        AlertDialog.Builder(this)
            .setTitle("قفل الدخول")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("دخول") { _, _ ->
                val entered = input.text.toString().trim()
                if (!prefs.hasSecret() || prefs.verifySecret(entered)) {
                    isUnlockedInSession = true
                } else {
                    toast("الرمز غير صحيح")
                    showLockDialog()
                }
            }
            .setNegativeButton("خروج") { _, _ -> finish() }
            .show()
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
                val recentSales = db.saleLogDao().getSalesSince(monthAgo)
                val aliases = prefs.getAllAliases()

                val soldByCat = recentSales.groupBy { it.cardCategory }.entries.joinToString("\n") { (cat, list) ->
                    val sumAmt = list.sumOf { it.amount }
                    "• الفئة: $cat | الكمية: ${list.size} | إجمالي السعر: $sumAmt"
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
                appendOutput("[محاكاة] اكتب رقم الزبون أولاً (مثال: 771234567).")
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
                appendOutput("[محاكاة] الفئة ($category) غير مسموحة. المسموح: ${allowed.joinToString()}")
                return@launch
            }

            val localList = categoryCardsCache[category]
            val localCard = localList?.firstOrNull()
            val dbCard = withContext(Dispatchers.IO) { db.cardDao().getNextAvailableCard(category) }
            val chosenCode = localCard ?: dbCard?.code

            if (chosenCode == null) {
                val alertMsg = "لا يوجد كرت متاح في فئة $category للرقم $phone"
                NotificationHelper.showAlert(this@MainActivity, "نفاد المخزون", alertMsg, notificationId = 2010)
                appendOutput("[محاكاة] $alertMsg")
                return@launch
            }

            val remainingAfter = ((localList?.size ?: 1) - 1).coerceAtLeast(0)
            val replyPreview = MessageParser.buildReplyMessage(
                amount = category,
                cardCode = chosenCode,
                bundles = prefs.getBundlesMap(),
                template = prefs.replyTemplate,
                categoryTemplates = prefs.getCategoryTemplatesMap()
            )

            appendOutput("الرقم: $phone | الفئة: $category | المتبقي: $remainingAfter\n----------------------------------\n$replyPreview")
            recordLog("محاكاة: الفئة $category للرقم $phone.")
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
                val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

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
                            "  الفئة: ${sale.cardCategory} | الكمية: 1 | السعر: ${sale.amount} | الكود: ${sale.cardCode}\n" +
                            "  النص المرسل: ${sentPreview.replace("\n", " | ")}"
                    }
                }

                buildString {
                    append("=== تقرير ($label) ===\n")
                    append("الكمية: $count | إجمالي السعر: $revenue\n\n")
                    append("=== العمليات ===\n$saleLines")
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
                val filtered = if (query.isBlank()) allSales else allSales.filter { it.customerPhone.contains(query, ignoreCase = true) }
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

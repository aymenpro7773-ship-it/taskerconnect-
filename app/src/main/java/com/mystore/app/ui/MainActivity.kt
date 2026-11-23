package com.mystore.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mystore.app.R
import com.mystore.app.data.AppDatabase
import com.mystore.app.data.CardEntity
import com.mystore.app.data.WalletEntity
import com.mystore.app.databinding.ActivityMainBinding
import com.mystore.app.engine.AutomationService
import com.mystore.app.engine.OrderProcessor
import com.mystore.app.engine.SmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getInstance(this) }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "تم منح الصلاحيات المطلوبة", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "بعض الصلاحيات مرفوضة، قد تتأثر ميزات الرسائل والإشعارات", Toast.LENGTH_LONG).show()
        }
    }

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val phone = extractPhoneNumber(uri)
        if (!phone.isNullOrBlank()) {
            binding.etPhone.setText(phone)
        } else {
            Toast.makeText(this, "لم يتم العثور على رقم هاتف لجهة الاتصال المختارة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRequiredPermissionsIfNeeded()
        seedInitialCardsIfEmpty()
        setupListeners()
        refreshServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    private fun setupListeners() {
        binding.btnStartService.setOnClickListener {
            AutomationService.startService(this)
            refreshServiceStatus()
            appendOutput("تم تشغيل خدمة الأتمتة.")
        }

        binding.btnStopService.setOnClickListener {
            AutomationService.stopService(this)
            refreshServiceStatus()
            appendOutput("تم إيقاف خدمة الأتمتة.")
        }

        binding.btnPickContact.setOnClickListener {
            pickContactLauncher.launch(null)
        }

        binding.btnSendManual.setOnClickListener {
            val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val message = binding.etMessage.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "أدخل رقم المستلم ونص الرسالة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val result = SmsSender.sendSms(this@MainActivity, phone, message)
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        appendOutput("تم إرسال الرسالة إلى $phone بنجاح.")
                    } else {
                        appendOutput("فشل إرسال الرسالة إلى $phone: ${result.error ?: "خطأ غير معروف"}")
                    }
                }
            }
        }

        binding.btnProcessQueue.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val summary = OrderProcessor(this@MainActivity).processPendingQueue()
                withContext(Dispatchers.Main) {
                    appendOutput(summary)
                }
            }
        }

        binding.btnAddWallet.setOnClickListener {
            val name = binding.etWalletName.text?.toString()?.trim().orEmpty()
            val balanceText = binding.etWalletBalance.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(this, "أدخل اسم المحفظة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val balance = balanceText.toDoubleOrNull() ?: 0.0

            lifecycleScope.launch(Dispatchers.IO) {
                db.walletDao().insertWallet(
                    WalletEntity(
                        name = name,
                        balance = balance,
                        isActive = true
                    )
                )
                withContext(Dispatchers.Main) {
                    binding.etWalletName.text?.clear()
                    binding.etWalletBalance.text?.clear()
                    appendOutput("تمت إضافة المحفظة '$name' برصيد $balance.")
                }
            }
        }

        binding.btnSalesReport.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis

                val count = db.saleLogDao().getSalesCountSince(startOfDay)
                val revenue = db.saleLogDao().getTotalRevenueSince(startOfDay)
                val stock = db.cardDao().getAvailableCountsByCategory()
                val wallets = db.walletDao().getAllWallets()

                val stockText = if (stock.isEmpty()) {
                    "لا توجد كروت متاحة"
                } else {
                    stock.joinToString(" | ") { "فئة ${it.category}: ${it.availableCount}" }
                }

                val walletsText = if (wallets.isEmpty()) {
                    "لا توجد محافظ"
                } else {
                    wallets.joinToString(" | ") { "${it.name}: ${it.balance}" }
                }

                val report = buildString {
                    appendLine("=== تقرير المبيعات اليومي ===")
                    appendLine("عدد العمليات اليوم: $count")
                    appendLine("إجمالي المبيعات اليوم: $revenue")
                    appendLine("المخزون المتاح: $stockText")
                    appendLine("المحافظ: $walletsText")
                }.trim()

                withContext(Dispatchers.Main) {
                    binding.tvOutput.text = report
                }
            }
        }
    }

    private fun refreshServiceStatus() {
        val running = AutomationService.isRunning
        binding.tvServiceStatus.text = if (running) {
            "حالة الخدمة: نشطة"
        } else {
            "حالة الخدمة: متوقفة"
        }
    }

    private fun appendOutput(line: String) {
        val current = binding.tvOutput.text?.toString().orEmpty()
        binding.tvOutput.text = if (current.isBlank() || current == getString(R.string.app_name) || current == "لا توجد بيانات معروضة بعد.") {
            line
        } else {
            "$current\n$line"
        }
    }

    private fun extractPhoneNumber(contactUri: Uri): String? {
        contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.HAS_PHONE_NUMBER),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                val contactId = if (idIdx >= 0) cursor.getString(idIdx) else null
                val hasPhone = if (hasPhoneIdx >= 0) cursor.getInt(hasPhoneIdx) else 0

                if (contactId != null && hasPhone > 0) {
                    contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )?.use { phoneCursor ->
                        if (phoneCursor.moveToFirst()) {
                            val numIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            if (numIdx >= 0) {
                                return phoneCursor.getString(numIdx)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun seedInitialCardsIfEmpty() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (db.cardDao().getTotalAvailableCards() == 0) {
                val sampleCards = listOf(
                    CardEntity(category = "100", code = "CRD100A1001", price = 100.0),
                    CardEntity(category = "200", code = "CRD200B2001", price = 200.0),
                    CardEntity(category = "500", code = "CRD500C5001", price = 500.0),
                    CardEntity(category = "1000", code = "CRD1000D1001", price = 1000.0)
                )
                db.cardDao().insertCards(sampleCards)
            }
        }
    }

    private fun requestRequiredPermissionsIfNeeded() {
        val needed = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }
}


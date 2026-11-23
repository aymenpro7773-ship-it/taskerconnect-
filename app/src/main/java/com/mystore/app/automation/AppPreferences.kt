package com.mystore.app.automation

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.mystore.app.engine.MessageParser
import java.security.MessageDigest

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("mystore_prefs", Context.MODE_PRIVATE)

    var isDarkMode: Boolean
        get() = prefs.getBoolean("dark_mode", true)
        set(value) {
            prefs.edit().putBoolean("dark_mode", value).apply()
            applyTheme(value)
        }

    var keywordPattern: String
        get() = prefs.getString("keyword_pattern", """اضيف\s*(\d+)""") ?: """اضيف\s*(\d+)"""
        set(value) = prefs.edit().putString("keyword_pattern", value.trim()).apply()

    var phonePattern: String
        get() = prefs.getString("phone_pattern", """7\d{8}""") ?: """7\d{8}"""
        set(value) = prefs.edit().putString("phone_pattern", value.trim()).apply()

    var allowedCategoriesCsv: String
        get() = prefs.getString("allowed_categories", "100,200,300,500,1000,2000")
            ?: "100,200,300,500,1000,2000"
        set(value) = prefs.edit().putString("allowed_categories", value.trim()).apply()

    fun getAllowedCategories(): List<String> =
        allowedCategoriesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    var bundlesCsv: String
        get() = prefs.getString(
            "bundles_csv",
            "100:350ميجا,200:700ميجا,300:1100ميجا,500:2جيجا,1000:3جيجا,2000:7جيجا"
        ) ?: "100:350ميجا,200:700ميجا,300:1100ميجا,500:2جيجا,1000:3جيجا,2000:7جيجا"
        set(value) = prefs.edit().putString("bundles_csv", value.trim()).apply()

    fun getBundlesMap(): Map<String, String> {
        val map = MessageParser.defaultBundles.toMutableMap()
        bundlesCsv.split(",").forEach { pair ->
            val parts = pair.split(":")
            if (parts.size == 2) {
                val k = parts[0].trim()
                val v = parts[1].trim()
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    var replyTemplate: String
        get() = prefs.getString("reply_template", MessageParser.defaultReplyTemplate)
            ?: MessageParser.defaultReplyTemplate
        set(value) = prefs.edit().putString("reply_template", value).apply()

    var lowStockThreshold: Int
        get() = prefs.getInt("low_stock_threshold", 1)
        set(value) = prefs.edit().putInt("low_stock_threshold", value.coerceAtLeast(0)).apply()

    // قفل النقش أو رمز الدخول الداخلي (بدون بصمة خارجية)
    var isLockEnabled: Boolean
        get() = prefs.getBoolean("lock_enabled", false)
        set(value) = prefs.edit().putBoolean("lock_enabled", value).apply()

    var lockType: String // "PATTERN" أو "PIN"
        get() = prefs.getString("lock_type", "PATTERN") ?: "PATTERN"
        set(value) = prefs.edit().putString("lock_type", value).apply()

    private var secretHash: String?
        get() = prefs.getString("secret_hash", null)
        set(value) = prefs.edit().putString("secret_hash", value).apply()

    fun hasSecret(): Boolean = !secretHash.isNullOrBlank()

    fun setSecret(rawSecret: String, type: String = lockType) {
        lockType = type
        secretHash = sha256(rawSecret.trim())
        isLockEnabled = true
    }

    fun verifySecret(candidate: String): Boolean {
        val saved = secretHash ?: return true
        return sha256(candidate.trim()) == saved
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun applyTheme(dark: Boolean) {
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}


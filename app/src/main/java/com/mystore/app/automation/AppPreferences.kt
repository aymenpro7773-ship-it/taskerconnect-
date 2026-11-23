package com.mystore.app.automation

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.mystore.app.engine.MessageParser

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("connect_net_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", true)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("dark_mode", true)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()

    var requireJaibSender: Boolean
        get() = prefs.getBoolean("require_jaib_sender", true)
        set(value) = prefs.edit().putBoolean("require_jaib_sender", value).apply()

    var allowedCategoriesCsv: String
        get() = prefs.getString("allowed_categories", "100,200,300,500,1000,2000")
            ?: "100,200,300,500,1000,2000"
        set(value) = prefs.edit().putString("allowed_categories", value).apply()

    var lowStockThreshold: Int
        get() = prefs.getInt("low_stock_threshold", 1)
        set(value) = prefs.edit().putInt("low_stock_threshold", value).apply()

    var keywordPattern: String
        get() = prefs.getString("keyword_pattern", "اضيف\\s*(\\d+)") ?: "اضيف\\s*(\\d+)"
        set(value) = prefs.edit().putString("keyword_pattern", value).apply()

    var phonePattern: String
        get() = prefs.getString("phone_pattern", "(7\\d{8})") ?: "(7\\d{8})"
        set(value) = prefs.edit().putString("phone_pattern", value).apply()

    var bundlesCsv: String
        get() = prefs.getString(
            "bundles_csv",
            "100:350ميجا,200:700ميجا,300:1100ميجا,500:2جيجا,1000:3جيجا,2000:7جيجا"
        ) ?: "100:350ميجا,200:700ميجا,300:1100ميجا,500:2جيجا,1000:3جيجا,2000:7جيجا"
        set(value) = prefs.edit().putString("bundles_csv", value).apply()

    var replyTemplate: String
        get() = prefs.getString("reply_template", MessageParser.defaultReplyTemplate)
            ?: MessageParser.defaultReplyTemplate
        set(value) = prefs.edit().putString("reply_template", value).apply()

    var categoryTemplatesRaw: String
        get() = prefs.getString("category_templates_raw", "") ?: ""
        set(value) = prefs.edit().putString("category_templates_raw", value).apply()

    var isLockEnabled: Boolean
        get() = prefs.getBoolean("lock_enabled", false)
        set(value) = prefs.edit().putBoolean("lock_enabled", value).apply()

    fun hasSecret(): Boolean {
        return !prefs.getString("lock_secret", null).isNullOrEmpty()
    }

    fun setSecret(secret: String, mode: String = "PATTERN") {
        prefs.edit()
            .putString("lock_secret", secret)
            .putString("lock_mode", mode)
            .putBoolean("lock_enabled", true)
            .apply()
    }

    fun verifySecret(entered: String): Boolean {
        val saved = prefs.getString("lock_secret", null) ?: return true
        return saved == entered
    }

    fun getAllowedCategories(): List<String> {
        return allowedCategoriesCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun getBundlesMap(): Map<String, String> {
        val merged = MessageParser.defaultBundles.toMutableMap()
        if (bundlesCsv.isNotBlank()) {
            bundlesCsv.split(",").forEach { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    merged[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        return merged
    }

    fun setCategoryTemplate(category: String, template: String) {
        val map = getCategoryTemplatesMap().toMutableMap()
        val cleanCat = category.trim()
        if (cleanCat.isEmpty()) return
        if (template.isBlank()) {
            map.remove(cleanCat)
        } else {
            map[cleanCat] = template.trim()
        }
        categoryTemplatesRaw = map.entries.joinToString("||") { "${it.key}==${it.value.replace("\n", "\\n")}" }
    }

    fun getCategoryTemplatesMap(): Map<String, String> {
        if (categoryTemplatesRaw.isBlank()) return emptyMap()
        return categoryTemplatesRaw.split("||").mapNotNull { item ->
            val parts = item.split("==", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) {
                parts[0].trim() to parts[1].replace("\\n", "\n")
            } else null
        }.toMap()
    }

    fun setExplicitAlias(sourceKey: String, verifiedFullPhone: String?) {
        val cleanKey = sourceKey.filter { it.isDigit() }
        val cleanTarget = verifiedFullPhone?.filter { it.isDigit() }
        val editor = prefs.edit()
        if (cleanKey.isNotEmpty() && isValidYemeniPhone(cleanTarget)) {
            editor.putString("explicit_alias_$cleanKey", cleanTarget)
            val allKeys = (prefs.getString("alias_keys_csv", "") ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            allKeys.add(cleanKey)
            editor.putString("alias_keys_csv", allKeys.joinToString(","))
        } else if (cleanKey.isNotEmpty()) {
            editor.remove("explicit_alias_$cleanKey")
        }
        editor.apply()
    }

    fun getExplicitAlias(sourceKey: String): String? {
        val cleanKey = sourceKey.filter { it.isDigit() }
        if (cleanKey.isEmpty()) return null
        val target = prefs.getString("explicit_alias_$cleanKey", null)
        return if (isValidYemeniPhone(target)) target else null
    }

    fun getAllAliases(): Map<String, String> {
        val keys = (prefs.getString("alias_keys_csv", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val result = linkedMapOf<String, String>()
        keys.forEach { k ->
            val v = getExplicitAlias(k)
            if (v != null) result[k] = v
        }
        return result
    }

    fun isValidYemeniPhone(phone: String?): Boolean {
        val digits = phone?.filter { it.isDigit() }.orEmpty()
        return digits.length == 9 && digits.startsWith("7")
    }

    companion object {
        fun applyTheme(darkMode: Boolean) {
            AppCompatDelegate.setDefaultNightMode(
                if (darkMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}

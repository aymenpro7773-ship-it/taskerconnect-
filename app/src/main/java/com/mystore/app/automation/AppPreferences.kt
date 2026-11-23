package com.mystore.app.automation

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("connect_net_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", true)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("dark_mode", true)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()

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
        get() = prefs.getString("bundles_csv", "100:350ميجا,500:2جيجا")
            ?: "100:350ميجا,500:2جيجا"
        set(value) = prefs.edit().putString("bundles_csv", value).apply()

    var replyTemplate: String
        get() = prefs.getString(
            "reply_template",
            "تم شحن فئة {amount} ({bundle}) بنجاح. الكرت: {card}"
        ) ?: "تم شحن فئة {amount} ({bundle}) بنجاح. الكرت: {card}"
        set(value) = prefs.edit().putString("reply_template", value).apply()

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
        if (bundlesCsv.isBlank()) return emptyMap()
        return bundlesCsv.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    parts[0].trim() to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()
    }

    fun setExplicitAlias(sourceKey: String, verifiedFullPhone: String?) {
        val cleanKey = sourceKey.filter { it.isDigit() }
        val cleanTarget = verifiedFullPhone?.filter { it.isDigit() }
        val editor = prefs.edit()
        if (cleanKey.isNotEmpty() && isValidYemeniPhone(cleanTarget)) {
            editor.putString("explicit_alias_$cleanKey", cleanTarget)
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

package com.mystore.app.automation

data class AutomationConfig(
    val keywordPattern: String = """اضيف\s*(\d+)""",
    val phonePattern: String = """7\d{8}""",
    val allowedAmounts: List<String> = listOf("100", "200", "300", "500", "1000", "2000"),
    val bundlesByAmount: Map<String, String> = mapOf(
        "100" to "350ميجا",
        "200" to "700ميجا",
        "300" to "1100ميجا",
        "500" to "2جيجا",
        "1000" to "3جيجا",
        "2000" to "7جيجا"
    ),
    val replyTemplate: String = "وصل طلبك⚡\nكرت {amount} ({bundle})\nالكرت:{card}\nشبكة كونكت.. راحتك تهمنا",
    val lowStockThreshold: Int = 1
)

sealed class SmsProcessResult {
    data class Success(
        val amount: String,
        val customerPhone: String,
        val cardCode: String,
        val remainingCards: Int,
        val replyMessage: String,
        val isLowStock: Boolean
    ) : SmsProcessResult()

    data class Ignored(val reason: String) : SmsProcessResult()
    data class UnsupportedAmount(val amount: String) : SmsProcessResult()
    data class OutOfStock(val amount: String) : SmsProcessResult()
}

class SmsRuleEngine(
    private val config: AutomationConfig = AutomationConfig()
) {
    fun sanitizeSms(rawSms: String): String {
        return rawSms.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e]"), "").trim()
    }

    fun parseRequest(rawSms: String): Pair<String, String> {
        val cleanSms = sanitizeSms(rawSms)
        val amountRegex = Regex(config.keywordPattern)
        val phoneRegex = Regex(config.phonePattern)

        val amount = amountRegex.find(cleanSms)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val phone = phoneRegex.find(cleanSms)?.value?.trim().orEmpty()
        return amount to phone
    }

    fun buildReply(amount: String, cardCode: String): String {
        val bundle = config.bundlesByAmount[amount].orEmpty()
        return config.replyTemplate
            .replace("{amount}", amount)
            .replace("{bundle}", bundle)
            .replace("{card}", cardCode)
    }

    fun validateAmount(amount: String): Boolean {
        return amount in config.allowedAmounts
    }
}


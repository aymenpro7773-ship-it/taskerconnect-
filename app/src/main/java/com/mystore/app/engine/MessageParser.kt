package com.mystore.app.engine

object MessageParser {

    data class ParsedOrder(
        val category: String?,
        val amount: Double?,
        val customerPhone: String? = null
    )

    val defaultBundles: Map<String, String> = mapOf(
        "100" to "350ميجا",
        "200" to "700ميجا",
        "300" to "1100ميجا",
        "500" to "2جيجا",
        "1000" to "3جيجا",
        "2000" to "7جيجا"
    )

    const val defaultReplyTemplate =
        "وصل طلبك⚡\nكرت {amount} ({bundle})\nالكرت:{card}\nشبكة كونكت.. راحتك تهمنا"

    fun sanitize(raw: String): String {
        return raw.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e]"), "").trim()
    }

    fun extractPhone(body: String, phonePattern: String = """7\d{8}"""): String? {
        val clean = sanitize(body)
        return Regex(phonePattern).find(clean)?.value?.trim()
    }

    fun isJaibSource(senderPhone: String, body: String): Boolean {
        val s = sanitize(senderPhone).lowercase()
        val b = sanitize(body).lowercase()
        return s.contains("jaib") || s.contains("جيب") || b.contains("jaib") || b.contains("جيب")
    }

    fun parse(
        body: String,
        keywordPattern: String = """اضيف\s*(\d+)""",
        phonePattern: String = """7\d{8}"""
    ): ParsedOrder {
        val cleaned = sanitize(body)
        if (cleaned.isEmpty()) return ParsedOrder(null, null, null)

        val phone = extractPhone(cleaned, phonePattern)
        val kwMatch = Regex(keywordPattern).find(cleaned)?.groupValues?.getOrNull(1)?.trim()

        val lower = cleaned.lowercase()
        val category = when {
            !kwMatch.isNullOrBlank() -> kwMatch
            lower.contains("2000") -> "2000"
            lower.contains("1000") || lower.contains("1k") || cleaned.contains("الف") || cleaned.contains("ألف") -> "1000"
            lower.contains("500") || cleaned.contains("خمسمئة") || cleaned.contains("خمسمائة") -> "500"
            lower.contains("300") || cleaned.contains("ثلاثمئة") || cleaned.contains("ثلاثمائة") -> "300"
            lower.contains("200") || cleaned.contains("مئتان") || cleaned.contains("مائتان") -> "200"
            lower.contains("100") || cleaned.contains("مئة") || cleaned.contains("مائة") -> "100"
            else -> null
        }

        val amount = category?.toDoubleOrNull()
        return ParsedOrder(category = category, amount = amount, customerPhone = phone)
    }

    fun buildReplyMessage(
        amount: String,
        cardCode: String,
        bundles: Map<String, String> = defaultBundles,
        template: String = defaultReplyTemplate,
        categoryTemplates: Map<String, String> = emptyMap()
    ): String {
        val bundle = bundles[amount].orEmpty()
        val chosenTemplate = categoryTemplates[amount]?.takeIf { it.isNotBlank() } ?: template
        return chosenTemplate
            .replace("{amount}", amount)
            .replace("{bundle}", bundle)
            .replace("{card}", cardCode)
    }

    fun extractCardCode(body: String): String? {
        val codeRegex = Regex("""\b[A-Za-z0-9]{6,20}\b""")
        return codeRegex.find(body)?.value
    }
}

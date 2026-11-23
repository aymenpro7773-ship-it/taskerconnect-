package com.mystore.app.engine

object MessageParser {

    data class ParsedOrder(
        val category: String?,
        val amount: Double?
    )

    fun parse(body: String): ParsedOrder {
        val cleaned = body.trim()
        if (cleaned.isEmpty()) return ParsedOrder(null, null)

        val amountRegex = Regex("""(\d+(?:\.\d+)?)""")
        val amountMatch = amountRegex.find(cleaned)
        val amount = amountMatch?.value?.toDoubleOrNull()

        val lower = cleaned.lowercase()
        val category = when {
            lower.contains("100") || cleaned.contains("مئة") || cleaned.contains("مائة") -> "100"
            lower.contains("200") || cleaned.contains("مئتان") || cleaned.contains("مائتان") -> "200"
            lower.contains("500") || cleaned.contains("خمسمئة") || cleaned.contains("خمسمائة") -> "500"
            lower.contains("1000") || lower.contains("1k") || cleaned.contains("الف") || cleaned.contains("ألف") -> "1000"
            amount != null -> amount.toInt().toString()
            else -> null
        }

        return ParsedOrder(category = category, amount = amount)
    }

    fun extractCardCode(body: String): String? {
        val codeRegex = Regex("""\b[A-Za-z0-9]{6,20}\b""")
        return codeRegex.find(body)?.value
    }
}


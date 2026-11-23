    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", true)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

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

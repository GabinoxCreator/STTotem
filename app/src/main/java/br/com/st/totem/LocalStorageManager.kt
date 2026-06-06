package br.com.st.totem

import android.content.Context

class LocalStorageManager(context: Context) {

    private val prefs = context.getSharedPreferences("sttotem_prefs", Context.MODE_PRIVATE)

    fun saveActivationToken(token: String) {
        prefs.edit().putString("activation_token", token).apply()
    }

    fun getActivationToken(): String? {
        return prefs.getString("activation_token", null)
    }

    fun saveTotemId(value: String?) {
        prefs.edit().putString("totem_id", value).apply()
    }

    fun getTotemId(): String? {
        return prefs.getString("totem_id", null)
    }

    fun saveCompanyId(value: String?) {
        prefs.edit().putString("company_id", value).apply()
    }

    fun getCompanyId(): String? {
        return prefs.getString("company_id", null)
    }

    fun saveLocationId(value: String?) {
        prefs.edit().putString("location_id", value).apply()
    }

    fun getLocationId(): String? {
        return prefs.getString("location_id", null)
    }

    fun saveIdentifier(value: String?) {
        prefs.edit().putString("identifier", value).apply()
    }

    fun getIdentifier(): String? {
        return prefs.getString("identifier", null)
    }

    fun saveSitefOtp(value: String?) {
        prefs.edit().putString("sitef_otp", value).apply()
    }

    fun getSitefOtp(): String? {
        return prefs.getString("sitef_otp", null)
    }

    fun isActivated(): Boolean {
        return !getActivationToken().isNullOrBlank()
    }

    fun clearActivation() {
        prefs.edit()
            .remove("activation_token")
            .remove("totem_id")
            .remove("company_id")
            .remove("location_id")
            .remove("identifier")
            .remove("sitef_otp")
            .apply()
    }
}
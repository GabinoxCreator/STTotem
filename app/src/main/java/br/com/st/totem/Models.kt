package br.com.st.totem

data class ActivationRequest(
    val activation_code: String,
    val device_serial: String? = null,
    val imei: String? = null,
    val mdm_identifier: String? = null,
    val app_version: String? = null
)

data class BootstrapRequest(
    val activationToken: String
)

data class ActivationResponse(
    val success: Boolean,
    val activationToken: String?,
    val totemId: String?,
    val companyId: String?,
    val locationId: String?,
    val identifier: String?,
    val rawJson: String? = null,
    val errorMessage: String? = null
)

data class BootstrapResponse(
    val success: Boolean,
    val rawJson: String? = null,
    val identifier: String? = null,
    val totemId: String? = null,
    val companyId: String? = null,
    val locationId: String? = null,
    val errorMessage: String? = null
)

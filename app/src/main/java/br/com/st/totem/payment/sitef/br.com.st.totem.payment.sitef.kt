package br.com.st.totem.payment.sitef

data class SitefPaymentResult(
    val success: Boolean,
    val codResp: String? = null,
    val codTrans: String? = null,
    val bandeira: String? = null,
    val nsuSitef: String? = null,
    val nsuHost: String? = null,
    val codAutorizacao: String? = null,
    val viaEstabelecimento: String? = null,
    val viaCliente: String? = null,
    val rawData: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)

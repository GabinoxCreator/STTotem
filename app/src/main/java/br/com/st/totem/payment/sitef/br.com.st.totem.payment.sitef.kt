package br.com.st.totem.payment.sitef

data class SitefPaymentResult(
    val success: Boolean,
    val codResp: String? = null,
    val codTrans: String? = null,
    /** Nome da bandeira quando o comprovante diz; senão o código cru. */
    val bandeira: String? = null,
    /** Código de 5 posições da tabela padrão SiTef (campo 132 / extra BANDEIRA). */
    val bandeiraCodigo: String? = null,
    /** Índice da instituição/rede (campo 131 / extra REDE_AUT). */
    val rede: String? = null,
    val nsuSitef: String? = null,
    val nsuHost: String? = null,
    val codAutorizacao: String? = null,
    val viaEstabelecimento: String? = null,
    val viaCliente: String? = null,
    val rawData: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)

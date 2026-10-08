package br.com.st.totem

import org.json.JSONObject

/**
 * Lê a resposta da `totem-activate` (totem-web). Sem `activation_token` não há
 * ativação: falha alto. Antes, `optString("activation_token", null)` devolvia a
 * PALAVRA "null" se o campo viesse nulo, e ela passava no teste de "vazio" da tela.
 * Ver `ContratoAtivacaoTest` (OS-161).
 */
internal fun lerRespostaAtivacao(corpo: String): ActivationResponse {
    val json = JSONObject(corpo)
    if (json.logico("success") != true) {
        throw RespostaInvalida(json.texto("error") ?: "Ativação inválida")
    }
    val totem = json.optJSONObject("totem")
    return ActivationResponse(
        success = true,
        // Mesmo texto que a tela de ativação já mostrava para token vazio.
        activationToken = json.texto("activation_token")
            ?: throw RespostaInvalida("Token de ativação não retornado."),
        // O bootstrap logo em seguida confirma e regrava estes quatro.
        totemId = totem?.texto("id"),
        companyId = json.optJSONObject("company")?.texto("id"),
        locationId = json.optJSONObject("location")?.texto("id"),
        identifier = totem?.texto("identifier"),
        rawJson = corpo,
        companyName = json.optJSONObject("company")?.texto("name"),
        aparelho = lerInfoAparelho(json.optJSONObject("device_identity"))
    )
}

/**
 * "Entrar na conta" (`totem-activate`). Desde a OS-203 (08/10/2026) manda a
 * identidade do aparelho (`hardware_id` no corpo, `x-device-key` no cabeçalho)
 * e devolve o erro com status e código da porta, para a tela escolher a faixa
 * certa (código errado, vencido, sem internet, servidor instável, bloqueio).
 */
class ActivationRepository {

    fun activate(
        requestData: ActivationRequest,
        hardwareId: String?,
        deviceKey: String?,
        onSuccess: (ActivationResponse) -> Unit,
        onError: (ErroPorta) -> Unit
    ) {
        val json = JSONObject()
            .put("activation_code", requestData.activation_code)
            .put("device_serial", requestData.device_serial ?: hardwareId)
            .put("imei", requestData.imei)
            .put("mdm_identifier", requestData.mdm_identifier)
            .put("app_version", requestData.app_version)
        if (!hardwareId.isNullOrBlank()) json.put("hardware_id", hardwareId)

        Portas.post(
            "totem-activate", json,
            mapOf("x-device-key" to deviceKey, "x-app-version" to requestData.app_version),
            { corpo ->
                try {
                    onSuccess(lerRespostaAtivacao(corpo))
                } catch (e: RespostaInvalida) {
                    onError(ErroPorta(e.message ?: "Ativação inválida"))
                } catch (e: Exception) {
                    onError(ErroPorta("Erro ao processar resposta da ativação: ${e.message}"))
                }
            },
            onError
        )
    }
}

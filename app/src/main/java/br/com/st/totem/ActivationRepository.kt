package br.com.st.totem

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

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
        rawJson = corpo
    )
}

class ActivationRepository {

    private val client = OkHttpClient()

    private val baseUrl = "https://buviakhfibcsamucnjwu.supabase.co/functions/v1"
    private val activateUrl = "$baseUrl/totem-activate"

    fun activate(
        requestData: ActivationRequest,
        onSuccess: (ActivationResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val json = JSONObject()
                .put("activation_code", requestData.activation_code)
                .put("device_serial", requestData.device_serial)
                .put("imei", requestData.imei)
                .put("mdm_identifier", requestData.mdm_identifier)
                .put("app_version", requestData.app_version)

            val requestBody = json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(activateUrl)
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onError("Falha de rede ao ativar totem: ${e.message ?: "sem detalhes"}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bodyString = it.body?.string().orEmpty()

                        if (!it.isSuccessful) {
                            onError("Falha na ativação: HTTP ${it.code} - $bodyString")
                            return
                        }

                        try {
                            onSuccess(lerRespostaAtivacao(bodyString))
                        } catch (e: RespostaInvalida) {
                            onError(e.message ?: "Ativação inválida")
                        } catch (e: Exception) {
                            onError("Erro ao processar resposta da ativação: ${e.message ?: bodyString}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            onError("Erro ao preparar ativação: ${e.message ?: "sem detalhes"}")
        }
    }
}
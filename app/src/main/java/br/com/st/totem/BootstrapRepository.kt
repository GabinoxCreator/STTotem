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

/*
 * ⚠️ 11/09/2026: o `optString` do org.json devolve a PALAVRA "null" quando o campo
 * vem nulo. A loja do SiTef do totem da Porcada virou "null", o padrão THEO0167 nunca
 * rodou e a `configure()` do CliSiTef recusou com código 2. Desde a OS-161 toda leitura
 * do servidor passa por `LeituraJson.kt` (texto / textoObrigatorio / ...).
 */

/**
 * Lê a resposta da `totem-bootstrap` (totem-web). Falha alto ([RespostaInvalida])
 * quando falta o que o app não tem como adivinhar: o próprio totem e o id dele.
 * O resto da resposta (config, branding, form, home_config) é da página, não do app:
 * ver `ContratoBootstrapTest`.
 */
internal fun lerRespostaBootstrap(corpo: String): BootstrapResponse {
    val json = JSONObject(corpo)
    if (json.logico("success") == false) {
        throw RespostaInvalida(json.texto("error") ?: "Bootstrap inválido")
    }
    val totem = json.optJSONObject("totem")
        ?: throw RespostaInvalida("campo 'totem' ausente na resposta do bootstrap")
    return BootstrapResponse(
        success = true,
        rawJson = corpo,
        identifier = totem.texto("identifier"),
        totemId = totem.textoObrigatorio("id"),
        companyId = json.optJSONObject("company")?.texto("id"),
        locationId = json.optJSONObject("location")?.texto("id"),
        // Nulo/vazio = o app usa o padrão dele (loja THEO0167; sem OTP/terminal o
        // pagamento recusa na hora com mensagem). Nunca a palavra "null".
        sitefOtp = totem.texto("sitef_otp"),
        sitefTerminalId = totem.texto("sitef_terminal_id"),
        sitefLoja = totem.texto("sitef_loja")
    )
}

class BootstrapRepository {

    private val client = OkHttpClient()

    private val baseUrl = "https://buviakhfibcsamucnjwu.supabase.co/functions/v1"
    private val bootstrapUrl = "$baseUrl/totem-bootstrap"

    fun bootstrap(
        activationToken: String,
        onSuccess: (BootstrapResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val requestBody = "{}"
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(bootstrapUrl)
                .post(requestBody)
                .addHeader("x-activation-token", activationToken)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onError("Falha de rede no bootstrap: ${e.message ?: "sem detalhes"}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bodyString = it.body?.string().orEmpty()

                        if (!it.isSuccessful) {
                            onError("Falha no bootstrap: HTTP ${it.code} - $bodyString")
                            return
                        }

                        try {
                            onSuccess(lerRespostaBootstrap(bodyString))
                        } catch (e: RespostaInvalida) {
                            onError("Resposta inválida do bootstrap: ${e.message}")
                        } catch (e: Exception) {
                            onError("Erro ao processar bootstrap: ${e.message ?: bodyString}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            onError("Erro ao preparar bootstrap: ${e.message ?: "sem detalhes"}")
        }
    }
}

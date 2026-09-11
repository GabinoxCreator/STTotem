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
 * Lê um texto do JSON tratando o NULO DE VERDADE.
 *
 * ⚠️ POR QUE ISTO EXISTE, e custou um totem parado em 11/09/2026: o
 * `optString` do org.json, quando o campo vem `null` no JSON, NÃO devolve null
 * nem vazio — devolve a STRING "null", com quatro letras. Ela passa em qualquer
 * teste de "está preenchido?" e viaja como se fosse valor.
 *
 * Foi o que aconteceu com a loja do SiTef: o totem da Porcada não tinha loja
 * (o certo, porque vazio significa "usa a loja de sempre"), o app guardou a
 * palavra "null", o fallback para THEO0167 nunca rodou, e a `configure()` do
 * CliSiTef recusou com código 2 — sem nem acender o pinpad. Os 4 campos abaixo
 * corriam o mesmo risco: OTP, terminal, loja e localização.
 */
private fun JSONObject.textoOuNulo(campo: String): String? =
    if (isNull(campo)) null
    else optString(campo).trim().takeIf { it.isNotBlank() && it != "null" }

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
                            val bodyJson = JSONObject(bodyString)
                            val success = bodyJson.optBoolean("success", true)

                            if (!success) {
                                onError(bodyJson.optString("error", "Bootstrap inválido"))
                                return
                            }

                            val totem = bodyJson.optJSONObject("totem")
                            val company = bodyJson.optJSONObject("company")
                            val location = bodyJson.optJSONObject("location")

                            onSuccess(
                                BootstrapResponse(
                                    success = true,
                                    rawJson = bodyString,
                                    identifier = totem?.optString("identifier"),
                                    totemId = totem?.optString("id"),
                                    companyId = company?.optString("id"),
                                    locationId = location?.optString("id"),
                                    sitefOtp = totem?.textoOuNulo("sitef_otp"),
                                    sitefTerminalId = totem?.textoOuNulo("sitef_terminal_id"),
                                    sitefLoja = totem?.textoOuNulo("sitef_loja")
                                )
                            )
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

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
                            val bodyJson = JSONObject(bodyString)

                            val success = bodyJson.optBoolean("success", false)
                            if (!success) {
                                onError(bodyJson.optString("error", "Ativação inválida"))
                                return
                            }

                            val totem = bodyJson.optJSONObject("totem")
                            val company = bodyJson.optJSONObject("company")
                            val location = bodyJson.optJSONObject("location")

                            val result = ActivationResponse(
                                success = true,
                                activationToken = bodyJson.optString("activation_token", null),
                                totemId = totem?.optString("id"),
                                companyId = company?.optString("id"),
                                locationId = location?.optString("id"),
                                identifier = totem?.optString("identifier"),
                                rawJson = bodyString
                            )

                            onSuccess(result)

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
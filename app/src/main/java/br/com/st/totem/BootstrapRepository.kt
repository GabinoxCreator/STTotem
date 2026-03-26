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
                                    locationId = location?.optString("id")
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
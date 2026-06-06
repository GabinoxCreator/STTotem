package br.com.st.totem

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Envio de logs para o backend (Supabase) — assíncrono e fire-and-forget.
 *
 * REGRAS DE SEGURANÇA (não alterar sem motivo):
 *  - NUNCA bloqueia o fluxo: usa enqueue (assíncrono), nunca .execute().
 *  - NUNCA propaga erro: todo o corpo é try/catch; se algo falhar, falha em
 *    silêncio. Um log perdido é aceitável; uma venda travada por log não é.
 *  - NUNCA loga dado sensível: não passe PAN, senha, CVV ou trilha em `detail`.
 */
object TotemLogger {

    private const val TAG = "TOTEM_LOGGER"
    private const val ENDPOINT =
        "https://buviakhfibcsamucnjwu.supabase.co/functions/v1/totem-log"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Timeouts curtos: se o backend demorar, desiste rápido e em silêncio.
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Preenchido uma vez na inicialização do app.
    @Volatile var totemId: String? = null

    fun log(
        stage: String,
        event: String,
        detail: Map<String, Any?>? = null,
        severity: String = "info"
    ) {
        try {
            val body = JSONObject().apply {
                put("totem_id", totemId)
                put("stage", stage)
                put("event", event)
                put("severity", severity)
                if (detail != null) put("detail", JSONObject(detail))
            }.toString()

            val request = Request.Builder()
                .url(ENDPOINT)
                .post(body.toRequestBody(JSON))
                .build()

            // enqueue = assíncrono. Não espera resposta, não bloqueia.
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Falha de rede: ignora. Só registra local pra debug.
                    Log.w(TAG, "log falhou (ignorado): ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            // Qualquer erro inesperado ao montar/enviar: engole.
            Log.w(TAG, "log exceção (ignorado): ${e.message}")
        }
    }
}
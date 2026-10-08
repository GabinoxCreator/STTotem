package br.com.st.totem

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
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

/*
 * Identidade do totem físico (OS-203, 08/10/2026). Igual à maquininha (STSmartPOS,
 * 16/09): o totem é ativado UMA vez pela FestPag (código do /st → device_key) e
 * depois só entra e sai da conta do cliente. OTP, terminal e loja do SiTef ficam
 * presos ao aparelho, então trocar de cliente não pede reset de OTP ao Marcel.
 */

/** Quem é este aparelho. O SK-210 não tem leitor de série: vale o ANDROID_ID. */
object DeviceIdentity {

    /** Lido uma vez e guardado: um ANDROID_ID diferente nunca troca a identidade. */
    @SuppressLint("HardwareIds")
    fun hardwareId(context: Context, storage: LocalStorageManager): String {
        storage.getHardwareId()?.let { return it }
        val lido = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        val id = lido?.trim()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: "sem-android-id-${java.util.UUID.randomUUID()}"
        storage.saveHardwareId(id)
        return id
    }

    const val MODELO = "sk210"

    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** "4f3a…c21": o bastante para achar no /st sem ocupar o rodapé. */
    fun curto(hardwareId: String?): String =
        if (hardwareId == null || hardwareId.length <= 8) hardwareId.orEmpty()
        else "${hardwareId.take(4)}…${hardwareId.takeLast(3)}"
}

/** O que o servidor contou sobre o aparelho (bloco `device` / `device_identity`). */
data class InfoAparelho(
    val assetTag: String?,
    val terminal: String?,
    val temOtp: Boolean,
)

internal fun lerInfoAparelho(json: JSONObject?): InfoAparelho? {
    if (json == null) return null
    return InfoAparelho(
        assetTag = json.texto("asset_tag"),
        terminal = json.texto("sitef_terminal_id"),
        temOtp = json.logico("has_otp") ?: false,
    )
}

/** Resposta de `device-activate` e `device-adopt`: a credencial do aparelho. */
data class CredencialAparelho(val deviceKey: String, val info: InfoAparelho?, val adotado: Boolean)

internal fun lerCredencialAparelho(corpo: String): CredencialAparelho {
    val json = JSONObject(corpo)
    if (json.logico("success") != true) throw RespostaInvalida(json.texto("error") ?: "Resposta inválida")
    return CredencialAparelho(
        deviceKey = json.texto("device_key") ?: throw RespostaInvalida("O servidor não devolveu a credencial do aparelho."),
        info = lerInfoAparelho(json.optJSONObject("device")),
        adotado = json.logico("adopted") ?: false,
    )
}

/**
 * Erro de uma porta, do jeito que a tela precisa para escolher a faixa:
 * `semRede` = nem chegou ao servidor (âmbar, tenta sozinho); `status` 5xx =
 * servidor instável (âmbar); 429 = muitas tentativas (âmbar com relógio);
 * o resto = o código tem problema (vermelho), com o `codigo` da porta quando há.
 */
data class ErroPorta(val mensagem: String, val status: Int? = null, val codigo: String? = null, val semRede: Boolean = false) {
    val servidorInstavel get() = (status ?: 0) >= 500
    val muitasTentativas get() = status == 429 || codigo == "too_many_attempts"
}

internal fun lerErroPorta(status: Int, corpo: String?): ErroPorta {
    val json = try { corpo?.let { JSONObject(it) } } catch (_: Exception) { null }
    return ErroPorta(
        mensagem = json?.texto("error") ?: "Falha no servidor (HTTP $status)",
        status = status,
        codigo = json?.texto("code"),
    )
}

internal object Portas {
    const val BASE = "https://buviakhfibcsamucnjwu.supabase.co/functions/v1"
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val JSON_TIPO = "application/json; charset=utf-8".toMediaType()

    /** POST com resposta em duas mãos: sucesso (corpo) ou [ErroPorta]. */
    fun post(
        porta: String,
        corpo: JSONObject,
        cabecalhos: Map<String, String?>,
        aoChegar: (String) -> Unit,
        aoFalhar: (ErroPorta) -> Unit,
    ) {
        try {
            val req = Request.Builder().url("$BASE/$porta").post(corpo.toString().toRequestBody(JSON_TIPO))
            cabecalhos.forEach { (k, v) -> if (!v.isNullOrBlank()) req.addHeader(k, v) }
            http.newCall(req.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    aoFalhar(ErroPorta("Sem conexão: ${e.message ?: "sem detalhes"}", semRede = true))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val texto = it.body?.string().orEmpty()
                        if (it.isSuccessful) aoChegar(texto) else aoFalhar(lerErroPorta(it.code, texto))
                    }
                }
            })
        } catch (e: Exception) {
            aoFalhar(ErroPorta("Erro ao preparar a chamada: ${e.message}"))
        }
    }
}

/** "Ativação do aparelho": o código de 8 números do /st vira a device_key. */
class DeviceActivateRepository {
    fun activate(
        code: String, hardwareId: String, appVersion: String,
        onSuccess: (CredencialAparelho) -> Unit, onError: (ErroPorta) -> Unit,
    ) {
        val corpo = JSONObject().put("code", code).put("hardware_id", hardwareId)
            .put("kind", "totem").put("model", DeviceIdentity.MODELO).put("app_version", appVersion)
        Portas.post("device-activate", corpo, emptyMap(), { texto ->
            try { onSuccess(lerCredencialAparelho(texto)) } catch (e: Exception) { onError(ErroPorta(e.message ?: "Resposta inválida")) }
        }, onError)
    }
}

/**
 * Adoção da frota em campo: o totem que já tem conta (token) mas não tem
 * credencial de aparelho se apresenta; o servidor cria o aparelho copiando OTP,
 * terminal e loja da vaga. Ninguém digita nada. Idempotente.
 */
class DeviceAdoptRepository {
    fun adopt(
        activationToken: String, hardwareId: String, appVersion: String,
        onSuccess: (CredencialAparelho) -> Unit, onError: (ErroPorta) -> Unit,
    ) {
        val corpo = JSONObject().put("hardware_id", hardwareId).put("kind", "totem")
            .put("model", DeviceIdentity.MODELO).put("app_version", appVersion)
        Portas.post("device-adopt", corpo, mapOf("x-activation-token" to activationToken), { texto ->
            try { onSuccess(lerCredencialAparelho(texto)) } catch (e: Exception) { onError(ErroPorta(e.message ?: "Resposta inválida")) }
        }, onError)
    }
}

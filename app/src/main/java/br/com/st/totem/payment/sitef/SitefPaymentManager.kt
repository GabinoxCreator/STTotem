package br.com.st.totem.payment.sitef

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import br.com.st.totem.LocalStorageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SitefPaymentManager(
    private val context: Context,
    private val launcher: ActivityResultLauncher<Intent>
) {

    companion object {
        private const val TAG = "SITEF_MANAGER"

        private const val SITEF_PACKAGE = "br.com.softwareexpress.msitef.mobile.p6"
        private const val SITEF_ACTION = "br.com.softwareexpress.sitef.msitef.ACTIVITY_CLISITEF"

        private const val EMPRESA_SITEF = "THEO0167"
        private const val ENDERECO_SITEF = "127.0.0.1:4096"
        private const val CNPJ_CPF = "43941698000152"
        private const val CNPJ_AUTOMACAO = "61126523000173"
        private const val CNPJ_FACILITADOR = "43941698000152"
        private const val COM_EXTERNA = "3"
        private const val TIPO_PINPAD = "ANDROID_USB"
        private const val OPERADOR = "0001"
        private const val TIMEOUT_COLETA = "30"

        // Códigos aceitos pelo m-SiTef neste fluxo atual
        private const val MODALIDADE_DEBITO = "2"
        private const val MODALIDADE_CREDITO = "3"
        private const val MODALIDADE_PIX = "122"
    }

    private val storage = LocalStorageManager(context)

    fun isSitefAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SITEF_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun canStartPayment(): Boolean {
        return isSitefAvailable() && !storage.getSitefOtp().isNullOrBlank()
    }

    fun getPaymentReadinessError(): String? {
        if (!isSitefAvailable()) {
            return "m-SiTef não disponível no dispositivo"
        }

        if (storage.getSitefOtp().isNullOrBlank()) {
            return "OTP SiTef não configurado para este totem"
        }

        return null
    }

    fun startPayment(
        paymentMethod: String,
        amountCents: Int,
        referenceId: String
    ): Boolean {
        if (!isSitefAvailable()) return false

        val sitefOtp = storage.getSitefOtp()?.trim().orEmpty()
        if (sitefOtp.isBlank()) return false

        val now = Date()
        val dataFiscal = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
        val horaFiscal = SimpleDateFormat("HHmmss", Locale.getDefault()).format(now)

        val numeroCupom = referenceId
            .replace("-", "")
            .replace("/", "")
            .replace(" ", "")
            .takeLast(20)
            .ifBlank { "123456" }

        val modalidade = mapPaymentMethodToModalidade(paymentMethod)

        Log.d(TAG, "Método recebido do frontend: $paymentMethod")
        Log.d(TAG, "Modalidade enviada ao m-SiTef: $modalidade")
        Log.d(TAG, "OTP usado: $sitefOtp")
        Log.d(TAG, "Número do cupom: $numeroCupom")
        Log.d(TAG, "Valor em centavos: $amountCents")

        val intent = Intent(SITEF_ACTION).apply {
            `package` = SITEF_PACKAGE

            putExtra("empresaSitef", EMPRESA_SITEF)
            putExtra("enderecoSitef", ENDERECO_SITEF)
            putExtra("CNPJ_CPF", CNPJ_CPF)
            putExtra("cnpj_automacao", CNPJ_AUTOMACAO)
            putExtra("cnpj_facilitador", CNPJ_FACILITADOR)
            putExtra("comExterna", COM_EXTERNA)
            putExtra("otp", sitefOtp)
            putExtra("numeroCupom", numeroCupom)
            putExtra("valor", amountCents.toString())
            putExtra("tipoPinpad", TIPO_PINPAD)
            putExtra("data", dataFiscal)
            putExtra("hora", horaFiscal)

            putExtra("operador", OPERADOR)
            putExtra("modalidade", modalidade)
            putExtra("timeoutColeta", TIMEOUT_COLETA)
        }

        launcher.launch(intent)
        return true
    }

    private fun mapPaymentMethodToModalidade(paymentMethod: String): String {
        return when (paymentMethod.trim().lowercase(Locale.getDefault())) {
            "pix" -> MODALIDADE_PIX

            "débito", "debito", "debit" -> MODALIDADE_DEBITO

            "crédito", "credito", "credit" -> MODALIDADE_CREDITO

            // fallback de compatibilidade
            "card" -> MODALIDADE_CREDITO

            else -> MODALIDADE_CREDITO
        }
    }
}
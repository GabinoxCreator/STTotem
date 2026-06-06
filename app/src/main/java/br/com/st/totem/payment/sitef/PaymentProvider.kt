package br.com.st.totem.payment.sitef

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import br.com.st.totem.LocalStorageManager

class PaymentProvider(
    private val context: Context,
    private val storage: LocalStorageManager,
    private val launcher: ActivityResultLauncher<Intent>,
    private val onStateChange: (PaymentState) -> Unit,
    private val onResult: (SitefPaymentResult) -> Unit,
    // 🆘 Último recurso: ciclo nativo travado / configure -9/-12. A MainActivity
    // reinicia o processo para resetar a libclisitef.so.
    private val onUnrecoverable: () -> Unit = {}
) {
    companion object {
        private const val TAG = "PAYMENT_PROVIDER"
        const val USE_CLISITEF = true
    }

    private val mSiTef = SitefPaymentManager(context, launcher)

    private val cliSiTef: CliSitefManager by lazy {
        CliSitefManager(context, storage, onStateChange, onResult, onUnrecoverable)
    }

    fun isSitefAvailable(): Boolean {
        return if (USE_CLISITEF) {
            !storage.getSitefOtp().isNullOrBlank()
        } else {
            mSiTef.isSitefAvailable()
        }
    }

    fun getPaymentReadinessError(): String? {
        return if (USE_CLISITEF) {
            if (storage.getSitefOtp().isNullOrBlank()) "OTP SiTef não configurado" else null
        } else {
            mSiTef.getPaymentReadinessError()
        }
    }

    fun startPayment(paymentMethod: String, amountCents: Int, referenceId: String): Boolean {
        Log.d(TAG, "startPayment | useCliSiTef=$USE_CLISITEF | method=$paymentMethod")
        return if (USE_CLISITEF) {
            try {
                cliSiTef.startPayment(paymentMethod, amountCents, referenceId)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "libclisitef.so não encontrada: ${e.message}")
                onResult(SitefPaymentResult(success = false, errorMessage = "CliSiTef não disponível: biblioteca nativa ausente"))
                false
            }
        } else {
            mSiTef.startPayment(paymentMethod, amountCents, referenceId)
        }
    }

    fun abort() {
        if (USE_CLISITEF) {
            try { cliSiTef.abort() } catch (_: Exception) {}
        }
    }

    // ✅ Delega para CliSitefManager — indica se houve abort com pinpad em uso
    // Quando true, MainActivity deve forçar re-descoberta USB (pinpad se re-enumera após abort)
    fun hadMidTransactionAbort(): Boolean {
        return if (USE_CLISITEF) {
            try { cliSiTef.hadMidTransactionAbort } catch (_: Exception) { false }
        } else {
            false
        }
    }

    // ✅ Reseta a flag após a MainActivity consumir o valor
    fun clearMidTransactionAbortFlag() {
        if (USE_CLISITEF) {
            try { cliSiTef.clearMidTransactionAbortFlag() } catch (_: Exception) {}
        }
    }
}
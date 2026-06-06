package br.com.st.totem.payment.sitef

/**
 * Estados do fluxo de pagamento CliSiTef.
 */
sealed class PaymentState {
    object Idle : PaymentState()
    object Processing : PaymentState()
    data class ShowMessage(val message: String) : PaymentState()
    object ClearMessage : PaymentState()
    data class ShowQRCode(val qrData: String) : PaymentState()
    object HideQRCode : PaymentState()
    // ✅ Countdown do PIX — segundos restantes para expirar o QR Code
    data class PixCountdown(val seconds: Int) : PaymentState()
    data class ShowMenu(val title: String, val options: List<String>) : PaymentState()
    data class Finished(val success: Boolean, val resultCode: Int, val message: String?) : PaymentState()
}
package br.com.st.totem

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object PrinterUtils {

    fun sanitizeText(text: String?): String {
        if (text.isNullOrBlank()) return ""

        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("\r", "")
            .replace("\n", " ")
            .replace("\t", " ")
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun truncate(text: String?, maxLen: Int): String {
        val safe = sanitizeText(text)
        if (safe.length <= maxLen) return safe
        return safe.take(maxLen).trim()
    }

    fun formatDate(isoString: String?): String {
        if (isoString.isNullOrBlank()) return "-"

        return try {
            val cleaned = isoString
                .replace("Z", "")
                .split(".")
                .first()

            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date: Date = parser.parse(cleaned) ?: return "-"

            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
            formatter.format(date)
        } catch (_: Exception) {
            sanitizeText(isoString)
        }
    }

    fun formatCurrency(value: Double?): String {
        val safe = value ?: 0.0
        return "R$ %.2f".format(Locale.US, safe).replace(".", ",")
    }

    fun formatShortCode(orderId: String?): String {
        if (orderId.isNullOrBlank()) return "-"
        return sanitizeText(orderId.replace("-", "").takeLast(4).uppercase())
    }

    fun padLine(left: String, right: String, width: Int = 32): String {
        val safeLeft = sanitizeText(left)
        val safeRight = sanitizeText(right)

        val dotsCount = (width - safeLeft.length - safeRight.length).coerceAtLeast(1)
        val dots = ".".repeat(dotsCount)

        return "$safeLeft$dots$safeRight"
    }

    fun separator(width: Int = 32): String {
        return "-".repeat(width)
    }
}
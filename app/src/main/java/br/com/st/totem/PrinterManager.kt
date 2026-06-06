package br.com.st.totem

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import br.com.gertec.easylayer.printer.CutType
import br.com.gertec.easylayer.printer.Printer
import br.com.gertec.easylayer.printer.PrinterError
import br.com.gertec.easylayer.printer.PrinterErrorCode
import br.com.gertec.easylayer.utils.Status
import okhttp3.OkHttpClient
import okhttp3.Request

class PrinterManager(
    private val context: Context
) {

    private var printer: Printer? = null
    // MUDANÇA: cliente HTTP para baixar a logo
    private val httpClient = OkHttpClient()

    private val readyPollIntervalMs = 120L
    private val readyPollTimeoutMs = 12_000L

    private val voucherPreCutFeedLines = 3
    private val voucherPostCutFeedLines = 1
    private val voucherFlushDelayMs = 180L
    private val voucherAfterCutDelayMs = 520L
    private val voucherAfterPostCutFeedDelayMs = 160L
    private val voucherInterPrintDelayMs = 420L

    private val summaryPreFeedLines = 3
    private val summaryPostFeedLines = 4
    private val summaryBeforePrintDelayMs = 220L
    private val summaryAfterPrintDelayMs = 260L
    private val summaryAfterCutDelayMs = 500L

    private val summaryAfterTicketsDelayMs = 400L

    fun initialize(
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            printer = Printer.getInstance(context, object : Printer.Listener {
                override fun onPrinterError(error: PrinterError) {
                    val message = mapPrinterError(error)
                    Log.e("PrinterManager", "Erro na impressora: $message")
                    onError(message)
                }

                override fun onPrinterSuccessful(status: Int) {
                    Log.d("PrinterManager", "Impressora pronta: $status")
                }
            })

            onReady()
        } catch (e: Exception) {
            Log.e("PrinterManager", "Falha ao inicializar impressora", e)
            onError("Falha ao inicializar impressora: ${e.message}")
        }
    }

    fun getPrinterStatusLabel(): String {
        return try {
            when (printer?.status) {
                Status.OK -> "OK"
                Status.OUT_OF_PAPER -> "Sem papel"
                Status.OVERHEAT -> "Superaquecida"
                Status.UNKNOWN_ERROR -> "Erro desconhecido"
                null -> "Impressora nao inicializada"
                else -> "Status nao mapeado"
            }
        } catch (e: Exception) {
            "Falha ao consultar status: ${e.message}"
        }
    }

    fun printTestReceipt(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val printerInstance = requirePrinterInitialized(onError) ?: return
            waitUntilPrinterReady(printerInstance, "antes_print_test")

            val html = """
                <html>
                <body style="font-family: monospace; width: 100%; margin: 0; padding: 0;">
                    <div style="text-align: center;">
                        <h2 style="margin: 5px 0;">ST Totem</h2>
                        <h3 style="margin: 5px 0;">Teste de Impressao</h3>
                    </div>
                    <hr style="border: none; border-top: 1px dashed #000; margin: 10px 0;">
                    <p style="margin: 5px 0;">Data/Hora: 27/03/2026 09:30</p>
                    <p style="margin: 5px 0;">Totem: SK210</p>
                    <hr style="border: none; border-top: 1px dashed #000; margin: 10px 0;">
                    <div style="display: flex; justify-content: space-between;">
                        <span>1x Produto Teste</span>
                        <span>R$ 10,00</span>
                    </div>
                    <div style="display: flex; justify-content: space-between;">
                        <span>2x Agua</span>
                        <span>R$ 4,00</span>
                    </div>
                    <hr style="border: none; border-top: 1px dashed #000; margin: 10px 0;">
                    <div style="display: flex; justify-content: space-between; font-weight: bold;">
                        <span>TOTAL</span>
                        <span>R$ 14,00</span>
                    </div>
                    <hr style="border: none; border-top: 1px dashed #000; margin: 10px 0;">
                    <div style="text-align: center;">
                        <p style="margin: 5px 0;">Pagamento aprovado</p>
                        <p style="margin: 5px 0;">Obrigado!</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            Log.d("PRINT_DEBUG", "printTestReceipt -> HTML size=${html.length}")

            printerInstance.printHtml(context, html)
            waitUntilPrinterReady(printerInstance, "apos_print_test")

            printerInstance.scrollPaper(4)
            waitUntilPrinterReady(printerInstance, "apos_scroll_test")

            printerInstance.cutPaper(CutType.PAPER_PARTIAL_CUT)
            waitUntilPrinterReady(printerInstance, "apos_cut_test")

            onSuccess()
        } catch (e: Exception) {
            Log.e("PrinterManager", "Falha ao imprimir teste", e)
            onError("Falha ao imprimir teste: ${e.message}")
        }
    }

    fun printReceiptJob(
        job: PrintJob,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val payload = job.payload
            val jobType = normalizeNullable(job.type)?.lowercase()
            val topLevelPrintMode = normalizeNullable(job.print_mode)?.lowercase()
            val payloadPrintMode = normalizeNullable(payload?.print_mode)?.lowercase()
            val effectivePrintMode = payloadPrintMode ?: topLevelPrintMode
            val forceConsolidatedReceipt =
                job.force_consolidated_receipt ?: payload?.force_consolidated_receipt ?: false

            val tickets = payload?.unit_tickets.orEmpty()
            val hasTickets = tickets.isNotEmpty()
            val hasItems = payload?.items?.isNotEmpty() == true
            val shouldPrintCustomerReceipt = payload?.print_customer_receipt ?: true

            Log.d(
                "PRINT_DEBUG",
                "printReceiptJob chamado | jobId=${job.id} | type=$jobType | topLevelPrintMode=$topLevelPrintMode | payloadPrintMode=$payloadPrintMode | effectivePrintMode=$effectivePrintMode | forceConsolidatedReceipt=$forceConsolidatedReceipt | hasTickets=$hasTickets | hasItems=$hasItems | shouldPrintCustomerReceipt=$shouldPrintCustomerReceipt | ticketCount=${tickets.size} | items=${payload?.items?.size ?: 0}"
            )

            when {
                jobType == "receipt" -> {
                    Log.d("PRINT_DEBUG", "Roteando para printSummaryReceipt() por type=receipt")
                    printSummaryReceipt(job, onSuccess, onError)
                }

                jobType == "ticket" -> {
                    Log.d("PRINT_DEBUG", "Roteando para printStyledTicket()")
                    printStyledTicket(job, onSuccess, onError)
                }

                hasTickets && shouldPrintCustomerReceipt -> {
                    Log.d("PRINT_DEBUG", "Roteando para printSummaryPlusTickets()")
                    printSummaryPlusTickets(job, onSuccess, onError)
                }

                hasTickets && !shouldPrintCustomerReceipt -> {
                    Log.d("PRINT_DEBUG", "Roteando para printVoucherTickets()")
                    printVoucherTickets(job, onSuccess, onError)
                }

                forceConsolidatedReceipt || effectivePrintMode == "consolidated" || hasItems -> {
                    Log.d("PRINT_DEBUG", "Roteando para printSummaryReceipt() por modo/items")
                    printSummaryReceipt(job, onSuccess, onError)
                }

                else -> {
                    Log.d("PRINT_DEBUG", "Fallback para printSummaryReceipt()")
                    printSummaryReceipt(job, onSuccess, onError)
                }
            }
        } catch (e: Exception) {
            Log.e("STTotem", "Falha ao rotear job ${job.id}", e)
            onError("Falha ao processar job: ${e.message}")
        }
    }

    fun printVoucherTickets(
        job: PrintJob,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val printerInstance = requirePrinterInitialized(onError) ?: return
            val payload = job.payload
            val tickets = payload?.unit_tickets.orEmpty()

            if (tickets.isEmpty()) {
                Log.d("PRINT_DEBUG", "printVoucherTickets() sem unit_tickets -> sucesso sem impressao")
                onSuccess()
                return
            }

            val brandName = cleanNullableText(payload?.brand_name, "ST Totem")
            val consumerDoc = cleanNullableText(payload?.consumer_doc, "-")
            val shortCode = normalizeNullable(payload?.short_order_code)
                ?: PrinterUtils.formatShortCode(normalizeNullable(payload?.order_id) ?: job.order_id)
            val date = PrinterUtils.formatDate(
                normalizeNullable(payload?.created_at) ?: job.created_at
            )
            // MUDANÇA: baixar logo uma vez antes do loop
            val logoDataUri = normalizeNullable(payload?.brand_logo_url)?.let { loadLogoAsDataUri(it) }
            Log.d("PRINT_DEBUG", "printVoucherTickets() -> quantidade=${tickets.size} | hasLogo=${logoDataUri != null}")

            tickets.forEachIndexed { index, ticket ->
                val ticketNumber = index + 1

                waitUntilPrinterReady(printerInstance, "antes_do_voucher_$ticketNumber")

                val html = buildStyledVoucherHtml(
                    title = "VALE COMPRA",
                    shortCode = shortCode,
                    badge = "VALE UM",
                    itemName = cleanNullableText(ticket.item_name, "Item"),
                    unitLabel = "UNIDADE ${ticket.unit_number ?: 1}/${ticket.total_units ?: 1}",
                    consumerDoc = consumerDoc,
                    brandName = brandName,
                    logoDataUri = logoDataUri,
                    date = date
                )

                Log.d(
                    "PRINT_DEBUG",
                    "Imprimindo voucher $ticketNumber/${tickets.size} | item=${ticket.item_name} | unidade=${ticket.unit_number}/${ticket.total_units} | htmlSize=${html.length}"
                )

                printerInstance.printHtml(context, html)
                waitUntilPrinterReady(printerInstance, "apos_printHtml_voucher_$ticketNumber")

                printerInstance.scrollPaper(voucherPreCutFeedLines)
                Thread.sleep(voucherFlushDelayMs)
                waitUntilPrinterReady(printerInstance, "apos_scroll_pre_cut_voucher_$ticketNumber")

                printerInstance.cutPaper(CutType.PAPER_PARTIAL_CUT)
                Thread.sleep(voucherAfterCutDelayMs)
                waitUntilPrinterReady(printerInstance, "apos_cut_voucher_$ticketNumber")

                if (index < tickets.lastIndex) {
                    printerInstance.scrollPaper(voucherPostCutFeedLines)
                    Thread.sleep(voucherAfterPostCutFeedDelayMs)
                    waitUntilPrinterReady(printerInstance, "apos_scroll_post_cut_voucher_$ticketNumber")

                    Thread.sleep(voucherInterPrintDelayMs)
                }
            }

            onSuccess()
        } catch (e: Exception) {
            Log.e("STTotem", "Falha ao imprimir vouchers", e)
            onError("Falha ao imprimir vouchers: ${e.message}")
        }
    }

    fun printSummaryReceipt(
        job: PrintJob,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val printerInstance = requirePrinterInitialized(onError) ?: return
            val payload = job.payload

            waitUntilPrinterReady(printerInstance, "antes_do_resumo")

            val html = buildSummaryOnlyHtml(
                payload = payload,
                fallbackOrderId = job.order_id,
                fallbackCreatedAt = job.created_at
            )

            Log.d("PRINT_DEBUG", "printSummaryReceipt() -> HTML size=${html.length}")

            printerInstance.scrollPaper(summaryPreFeedLines)
            Thread.sleep(summaryBeforePrintDelayMs)
            waitUntilPrinterReady(printerInstance, "antes_do_printHtml_resumo")

            printerInstance.printHtml(context, html)
            Thread.sleep(summaryAfterPrintDelayMs)
            waitUntilPrinterReady(printerInstance, "apos_printHtml_resumo")

            printerInstance.scrollPaper(summaryPostFeedLines)
            waitUntilPrinterReady(printerInstance, "apos_scroll_resumo")

            printerInstance.cutPaper(CutType.PAPER_PARTIAL_CUT)
            Thread.sleep(summaryAfterCutDelayMs)
            waitUntilPrinterReady(printerInstance, "apos_cut_resumo")

            onSuccess()
        } catch (e: Exception) {
            Log.e("STTotem", "Falha ao imprimir resumo", e)
            onError("Falha ao imprimir resumo: ${e.message}")
        }
    }

    fun printSummaryPlusTickets(
        job: PrintJob,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        printVoucherTickets(
            job = job,
            onSuccess = {
                try {
                    Thread.sleep(summaryAfterTicketsDelayMs)
                } catch (_: Exception) {
                }

                printSummaryReceipt(
                    job = job,
                    onSuccess = onSuccess,
                    onError = onError
                )
            },
            onError = onError
        )
    }

    private fun printStyledTicket(
        job: PrintJob,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val printerInstance = requirePrinterInitialized(onError) ?: return
            val payload = job.payload

            waitUntilPrinterReady(printerInstance, "antes_ticket_estilizado")

            val brandName = cleanNullableText(payload?.brand_name, "ST Totem")
            val consumerDoc = cleanNullableText(payload?.consumer_doc, "-")
            // MUDANÇA: baixar logo
            val logoDataUri = normalizeNullable(payload?.brand_logo_url)?.let { loadLogoAsDataUri(it) }

            val shortCode = normalizeNullable(payload?.short_order_code)
                ?: PrinterUtils.formatShortCode(normalizeNullable(payload?.order_id) ?: job.order_id)

            val itemName = cleanNullableText(payload?.item_name, "Item")
            val unitNumber = payload?.unit_number ?: 1
            val totalUnits = payload?.total_units ?: 1

            val date = PrinterUtils.formatDate(
                normalizeNullable(payload?.created_at) ?: job.created_at
            )

            val html = buildStyledVoucherHtml(
                title = "VALE COMPRA",
                shortCode = shortCode,
                badge = "VALE UM",
                itemName = itemName,
                unitLabel = "UNIDADE $unitNumber/$totalUnits",
                consumerDoc = consumerDoc,
                brandName = brandName,
                logoDataUri = logoDataUri,
                date = date
            )

            Log.d("PRINT_DEBUG", "printStyledTicket() -> HTML size=${html.length} | hasLogo=${logoDataUri != null}")

            printerInstance.printHtml(context, html)
            waitUntilPrinterReady(printerInstance, "apos_print_ticket_estilizado")

            printerInstance.scrollPaper(3)
            waitUntilPrinterReady(printerInstance, "apos_scroll_ticket_estilizado")

            printerInstance.cutPaper(CutType.PAPER_PARTIAL_CUT)
            Thread.sleep(voucherAfterCutDelayMs)
            waitUntilPrinterReady(printerInstance, "apos_cut_ticket_estilizado")

            onSuccess()
        } catch (e: Exception) {
            Log.e("STTotem", "Falha ao imprimir ticket estilizado", e)
            onError("Falha ao imprimir ticket: ${e.message}")
        }
    }

    private fun buildStyledVoucherHtml(
        title: String,
        shortCode: String,
        badge: String,
        itemName: String,
        unitLabel: String,
        consumerDoc: String,
        brandName: String,
        logoDataUri: String?,
        date: String
    ): String {
        val safeTitle = escapeHtml(PrinterUtils.truncate(title, 24))
        val safeShortCode = escapeHtml(shortCode)
        val safeBadge = escapeHtml(PrinterUtils.truncate(badge, 16))
        val safeItemName = escapeHtml(PrinterUtils.truncate(itemName, 24))
        val safeUnitLabel = escapeHtml(unitLabel)
        val safeBrandName = escapeHtml(PrinterUtils.truncate(brandName, 28))
        val safeDate = escapeHtml(date)

        // Logo se disponível, senão nome da marca como fallback
        val logoOrBrand = if (!logoDataUri.isNullOrBlank()) {
            """<img src="$logoDataUri" style="max-width:300px; max-height:130px; width:80%;" />"""
        } else {
            """<span style="font-weight:bold; font-size:20px;">$safeBrandName</span>"""
        }

        return """
            <html>
            <body style="font-family: monospace; margin:0; padding:0; text-align:center;">
                <div style="border:2px solid #000; padding:8px; margin:0;">

                    <div style="background:#000; color:#fff; font-weight:bold; font-size:22px; padding:6px 0; margin-bottom:8px;">
                        $safeTitle
                    </div>

                    <div style="font-size:20px; font-weight:bold; margin:4px 0 6px 0; letter-spacing:1px;">
                        #$safeShortCode
                    </div>

                    <div style="display:inline-block; background:#000; color:#fff; font-weight:bold; font-size:16px; padding:3px 14px; margin-bottom:8px;">
                        $safeBadge
                    </div>

                    <div style="font-size:28px; font-weight:bold; margin:6px 0 4px 0;">
                        $safeItemName
                    </div>

                    <div style="font-size:20px; font-weight:bold; margin:4px 0 14px 0;">
                        $safeUnitLabel
                    </div>

                    <hr style="border:none; border-top:1px dashed #000; margin:6px 0 10px 0;">

                    <div style="margin:6px 0 6px 0; text-align:center;">
                        $logoOrBrand
                    </div>

                    <div style="font-size:13px; margin-top:8px; margin-bottom:2px;">
                        $safeDate
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    // Download + conversão para JPEG antes do base64.
    // O html2bitmap.jar da Gertec nao renderiza WebP em data URI —
    // converter para JPEG garante que qualquer formato (webp/png/jpg)
    // seja exibido corretamente no printHtml.
    private fun loadLogoAsDataUri(url: String): String? {
        return try {
            Log.d("PRINT_DEBUG", "Baixando logo: $url")
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("PRINT_DEBUG", "Logo HTTP=${response.code}")
                    return null
                }
                val bytes = response.body?.bytes() ?: return null

                // Decodifica qualquer formato (webp, png, jpg) para Bitmap
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Log.w("PRINT_DEBUG", "Logo nao decodificavel como bitmap")
                    return null
                }

                // Re-comprime como JPEG — html2bitmap suporta JPEG em data URI
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                Log.d("PRINT_DEBUG", "Logo OK | original=${bitmap.width}x${bitmap.height} | jpeg b64=${b64.length} chars")
                "data:image/jpeg;base64,$b64"
            }
        } catch (e: Exception) {
            Log.e("PRINT_DEBUG", "Falha ao carregar logo: ${e.message}")
            null
        }
    }

    private fun buildSummaryOnlyHtml(
        payload: PrintPayload?,
        fallbackOrderId: String?,
        fallbackCreatedAt: String?
    ): String {
        val brandName = cleanNullableText(payload?.brand_name, "ST Totem")
        val receiptHeader = cleanNullableText(payload?.receipt_header, "Comprovante de Pedido")
        val receiptSubheader = cleanNullableText(payload?.receipt_subheader, "")
        val receiptFooter = cleanNullableText(payload?.receipt_footer, "")
        val pickupMessage = cleanNullableText(payload?.pickup_message, "Retire no balcao")
        val locationName = cleanNullableText(payload?.location_name, "")
        val consumerDoc = cleanNullableText(payload?.consumer_doc, "")

        val shortCode = normalizeNullable(payload?.short_order_code)
            ?: PrinterUtils.formatShortCode(normalizeNullable(payload?.order_id) ?: fallbackOrderId)

        val date = PrinterUtils.formatDate(
            normalizeNullable(payload?.created_at) ?: fallbackCreatedAt
        )

        val items = payload?.items ?: emptyList()
        val total = payload?.total ?: 0.0
        val discount = payload?.discount ?: 0.0
        val cashback = payload?.cashback ?: 0.0

        val htmlBuilder = StringBuilder()
        htmlBuilder.append("<html><body style=\"font-family: monospace; width: 100%; margin: 0; padding: 0;\">")

        htmlBuilder.append("<div style=\"text-align: center;\">")
        htmlBuilder.append("<h2 style=\"margin: 5px 0;\">${escapeHtml(brandName)}</h2>")
        if (!brandName.equals(receiptHeader, ignoreCase = true)) {
            htmlBuilder.append("<h3 style=\"margin: 5px 0;\">${escapeHtml(receiptHeader)}</h3>")
        }
        if (receiptSubheader.isNotBlank()) {
            htmlBuilder.append("<p style=\"margin: 5px 0;\">${escapeHtml(receiptSubheader)}</p>")
        }
        htmlBuilder.append("</div>")

        htmlBuilder.append("<hr style=\"border: none; border-top: 1px dashed #000; margin: 10px 0;\">")

        htmlBuilder.append("<p style=\"margin: 5px 0;\">Pedido: ${escapeHtml(shortCode)}</p>")
        htmlBuilder.append("<p style=\"margin: 5px 0;\">Data: ${escapeHtml(date)}</p>")
        if (locationName.isNotBlank()) {
            htmlBuilder.append("<p style=\"margin: 5px 0;\">Local: ${escapeHtml(locationName)}</p>")
        }
        if (consumerDoc.isNotBlank()) {
            htmlBuilder.append("<p style=\"margin: 5px 0;\">Consumidor: ${escapeHtml(consumerDoc)}</p>")
        }

        htmlBuilder.append("<hr style=\"border: none; border-top: 1px dashed #000; margin: 10px 0;\">")

        if (items.isEmpty()) {
            htmlBuilder.append("<p style=\"margin: 5px 0;\">Sem itens</p>")
        } else {
            items.forEach { item ->
                val itemName = PrinterUtils.truncate(item.name, 22)
                htmlBuilder.append("<div style=\"display: flex; justify-content: space-between; margin: 3px 0;\">")
                htmlBuilder.append("<span>${escapeHtml("${item.quantity}x $itemName")}</span>")
                htmlBuilder.append("<span>${escapeHtml(PrinterUtils.formatCurrency(item.subtotal))}</span>")
                htmlBuilder.append("</div>")
            }
        }

        htmlBuilder.append("<hr style=\"border: none; border-top: 1px dashed #000; margin: 10px 0;\">")

        if (discount > 0.0) {
            htmlBuilder.append("<div style=\"display: flex; justify-content: space-between; margin: 3px 0;\">")
            htmlBuilder.append("<span>DESCONTO</span>")
            htmlBuilder.append("<span>${escapeHtml(PrinterUtils.formatCurrency(discount))}</span>")
            htmlBuilder.append("</div>")
        }

        if (cashback > 0.0) {
            htmlBuilder.append("<div style=\"display: flex; justify-content: space-between; margin: 3px 0;\">")
            htmlBuilder.append("<span>CASHBACK</span>")
            htmlBuilder.append("<span>${escapeHtml(PrinterUtils.formatCurrency(cashback))}</span>")
            htmlBuilder.append("</div>")
        }

        htmlBuilder.append("<div style=\"display: flex; justify-content: space-between; font-weight: bold; font-size: 1.1em; margin-top: 8px;\">")
        htmlBuilder.append("<span>TOTAL</span>")
        htmlBuilder.append("<span>${escapeHtml(PrinterUtils.formatCurrency(total))}</span>")
        htmlBuilder.append("</div>")

        htmlBuilder.append("<br>")
        htmlBuilder.append("<div style=\"text-align: center;\">")
        htmlBuilder.append("<p style=\"margin: 5px 0;\">Pagamento aprovado</p>")
        htmlBuilder.append("<p style=\"margin: 5px 0; font-weight: bold;\">${escapeHtml(pickupMessage)}</p>")
        if (receiptFooter.isNotBlank()) {
            htmlBuilder.append("<p style=\"margin: 5px 0; font-style: italic;\">${escapeHtml(receiptFooter)}</p>")
        }
        htmlBuilder.append("</div>")

        htmlBuilder.append("</body></html>")

        return htmlBuilder.toString()
    }

    private fun waitUntilPrinterReady(
        printerInstance: Printer,
        stage: String
    ) {
        val startedAt = System.currentTimeMillis()

        while (true) {
            val status = printerInstance.getStatus()
            if (status == Status.OK) {
                Log.d("PRINT_DEBUG", "Impressora OK em '$stage'")
                return
            }

            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= readyPollTimeoutMs) {
                throw IllegalStateException(
                    "Impressora nao voltou para OK em '$stage'. Status=$status"
                )
            }

            Log.d(
                "PRINT_DEBUG",
                "Aguardando impressora ficar OK em '$stage' | status=$status | elapsed=${elapsed}ms"
            )

            Thread.sleep(readyPollIntervalMs)
        }
    }

    private fun requirePrinterInitialized(onError: (String) -> Unit): Printer? {
        val printerInstance = printer
        if (printerInstance == null) {
            onError("Impressora nao inicializada")
            return null
        }
        return printerInstance
    }

    private fun normalizeNullable(value: String?): String? {
        val trimmed = value?.trim()
        return if (trimmed.isNullOrEmpty() || trimmed.equals("null", ignoreCase = true)) {
            null
        } else {
            trimmed
        }
    }

    private fun cleanNullableText(value: String?, fallback: String = ""): String {
        return PrinterUtils.sanitizeText(normalizeNullable(value) ?: fallback)
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun mapPrinterError(error: PrinterError): String {
        return when (error.code) {
            PrinterErrorCode.PRINTER_OUT_OF_PAPER.ordinal -> "Impressora sem papel"
            PrinterErrorCode.PRINTER_NOT_READY.ordinal -> "Impressora nao pronta"
            else -> "Erro na impressora"
        }
    }
}
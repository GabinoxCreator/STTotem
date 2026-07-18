package br.com.st.totem

data class PrintQueueResponse(
    val success: Boolean,
    val jobs: List<PrintJob> = emptyList(),
    val error: String? = null
)

data class PrintJob(
    val id: String,
    val type: String? = null,
    val status: String? = null,
    val order_id: String? = null,
    val created_at: String? = null,
    val print_mode: String? = null,
    val force_consolidated_receipt: Boolean? = null,
    val payload: PrintPayload? = null
)

data class PrintPayload(
    val brand_name: String? = null,
    val brand_logo_url: String? = null,
    val receipt_header: String? = null,
    val receipt_subheader: String? = null,
    val receipt_footer: String? = null,
    val pickup_message: String? = null,
    val short_order_code: String? = null,
    // SENHA sequencial por empresa (orders.pickup_code). Null/ausente (empresa sem
    // flag, backend antigo) → ficha e recibo saem exatamente como hoje.
    val pickup_code: String? = null,
    val location_name: String? = null,
    val order_id: String? = null,
    val created_at: String? = null,

    val consumer_doc: String? = null,
    val discount: Double? = null,
    val cashback: Double? = null,
    val print_customer_receipt: Boolean? = null,

    val print_mode: String? = null,
    val force_consolidated_receipt: Boolean? = null,

    val items: List<PrintItem> = emptyList(),
    val unit_tickets: List<UnitTicket> = emptyList(),
    val total: Double? = 0.0,

    val item_name: String? = null,
    val item_quantity: Int? = null,
    val unit_number: Int? = null,
    val total_units: Int? = null,
    val unit_price: Double? = null,
    val subtotal: Double? = null,

    // Foodtruck: linhas de adicional ("+ 2x Bacon") e rodapé por parceiro
    // ("Válido somente para <food truck>"). Ausentes em bar/ingresso → null
    // (ficha atual sai idêntica).
    val addon_lines: List<String>? = null,
    val partner_footer: String? = null,

    // Bingo (job type "bingo_card"): 5 números da cartela + rodada + nº da cartela.
    // Ausentes em qualquer outro job → cartela nunca é impressa por engano.
    val round_number: Int? = null,
    val card_number: Int? = null,
    val numbers: List<Int>? = null,
    val reprint: Boolean? = null,

    // Ingresso (Bloco 2): print_job tipo "ingresso".
    // Caso de 1 ingresso por job → campos no topo do payload.
    // Caso de vários ingressos no mesmo job → lista `ingressos`.
    val event_name: String? = null,
    val lot_name: String? = null,
    val ticket_code: String? = null,
    val qr_payload: String? = null,
    // event_date: string JÁ FORMATADA no totem-web ("dd/mm/yyyy HH:mm") — imprimir
    // VERBATIM (não reparsear). event_location: "venue - city". Ambos podem vir null.
    val event_date: String? = null,
    val event_location: String? = null,
    val ingressos: List<IngressoTicket> = emptyList()
)

// Um ingresso emitido. qr_payload = string CRUA validada na portaria (NUNCA reformatar).
data class IngressoTicket(
    val event_name: String? = null,
    val lot_name: String? = null,
    val ticket_code: String? = null,
    val qr_payload: String? = null,
    val event_date: String? = null,
    val event_location: String? = null
)

data class PrintItem(
    val name: String = "Produto",
    val quantity: Int = 0,
    val unit_price: Double = 0.0,
    val subtotal: Double = 0.0
)

data class UnitTicket(
    val item_name: String? = null,
    val unit_number: Int? = null,
    val total_units: Int? = null,
    // Foodtruck (podem faltar — bar/ingresso → null).
    val addon_lines: List<String>? = null,
    val partner_footer: String? = null
)
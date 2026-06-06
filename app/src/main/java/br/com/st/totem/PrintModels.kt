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
    val subtotal: Double? = null
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
    val total_units: Int? = null
)
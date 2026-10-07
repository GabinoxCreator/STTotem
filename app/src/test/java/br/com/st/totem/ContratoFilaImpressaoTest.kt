package br.com.st.totem

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrato da fila de impressão: o que a `totem-print-queue` manda no GET (totem-web
 * `supabase/functions/totem-print-queue/index.ts`, `enrichedJobs`) e os payloads que
 * as edges gravam em `print_jobs`:
 *  - ficha e recibo: `supabase/functions/_shared/print-jobs.ts` (`createPrintJobs`);
 *  - recibo pedido no fim da compra: `totem-print-receipt`; reimpressão: `totem-reprint-order`;
 *  - ingresso: `src/lib/collaborator.ts` (`enqueueIngressoPrintJobs`);
 *  - cartela: `_shared/print-jobs.ts` (`buildBingoCardJobs`).
 * Valores fictícios. Mudou um desses? Atualize o exemplo e decida cada campo novo (OS-161).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContratoFilaImpressaoTest {

    // Campos que toda ficha/recibo carrega (basePayload de _shared/print-jobs.ts).
    private val base = """
        "brand_name": "Bem-vindo!",
        "brand_logo_url": "https://x/logo.jpg",
        "brand_primary_color": "#0a5700",
        "brand_secondary_color": "#fbff00",
        "receipt_header": "Cabeçalho",
        "receipt_subheader": "Sub",
        "receipt_footer": "Rodapé",
        "pickup_message": "Aguarde seu pedido ser chamado",
        "short_order_code": "2F22",
        "pickup_code": "0042",
        "location_name": "Salão",
        "order_id": "bbbbbbbb-0000-4000-8000-000000000001",
        "created_at": "2026-10-05T22:39:53.000Z",
        "print_customer_receipt": true
    """.trimIndent()

    private val baseLidas = setOf(
        "brand_name", "brand_logo_url", "receipt_header", "receipt_subheader", "receipt_footer",
        "pickup_message", "short_order_code", "pickup_code", "location_name", "order_id",
        "created_at", "print_customer_receipt"
    )
    // O papel é preto e branco: as cores da marca não têm onde ir.
    private val baseIgnoradas = setOf("brand_primary_color", "brand_secondary_color")
    // O app ainda lê (com padrão escrito), nenhuma edge de hoje manda.
    private val legado = setOf("consumer_doc", "discount", "cashback")

    private fun envelope(tipo: String, modo: String, payload: String, orderId: String? = "bbbbbbbb-0000-4000-8000-000000000001") = """
        {
          "id": "aaaaaaaa-0000-4000-8000-0000000000${tipo.length}",
          "type": "$tipo",
          "status": "printing",
          "order_id": ${if (orderId == null) "null" else "\"$orderId\""},
          "created_at": "2026-10-05T22:39:54.000Z",
          "print_mode": "$modo",
          "payload": { $payload },
          "receipt": null
        }
    """.trimIndent()

    private val ficha = envelope("ticket", "ticket_per_unit", """
        $base,
        "item_name": "Heineken LN",
        "item_quantity": 1,
        "unit_number": 2,
        "total_units": 3,
        "unit_price": 14.99,
        "subtotal": 14.99,
        "total": 44.97,
        "addon_lines": ["+ 2x Bacon"],
        "partner_footer": "Válido somente para Truck X"
    """.trimIndent())

    private val recibo = envelope("receipt", "consolidated", """
        $base,
        "print_mode": "consolidated",
        "force_consolidated_receipt": true,
        "items": [{"name": "Heineken LN", "quantity": 3, "unit_price": 14.99, "subtotal": 44.97}],
        "total": 44.97,
        "is_reprint": true,
        "reprinted_at": "2026-10-05T22:45:00.000Z"
    """.trimIndent())

    private val reciboComFichas = envelope("receipt", "summary_plus_unit_lines", """
        $base,
        "print_mode": "summary_plus_unit_lines",
        "items": [{"name": "Heineken LN", "quantity": 2, "unit_price": 14.99, "subtotal": 29.98}],
        "unit_tickets": [
          {"item_name": "Heineken LN", "unit_number": 2, "total_units": 2, "addon_lines": ["+ Limão"], "partner_footer": "Válido somente para Truck X"},
          {"item_name": "Heineken LN", "unit_number": 1, "total_units": 2}
        ],
        "total": 29.98
    """.trimIndent())

    private val ingresso = envelope("ingresso", "consolidated", """
        "kind": "ingresso",
        "brand_name": "Deixa Rolar",
        "brand_logo_url": "https://x/logo.jpg",
        "brand_primary_color": "#000000",
        "brand_secondary_color": "#ffffff",
        "event_name": "Deixa Rolar",
        "event_date": "03/10/2026 16:00",
        "event_location": "NH Garden - S. J. do Rio Preto",
        "lot_name": "1º lote",
        "ticket_code": "AB12CD34EF",
        "qr_payload": " AB12CD34EF-RAW ",
        "site_order_id": "cccccccc-0000-4000-8000-000000000001",
        "total": 60.0,
        "created_at": "2026-10-03T19:00:00.000Z"
    """.trimIndent(), orderId = null)

    private val cartela = envelope("bingo_card", "consolidated", """
        "brand_name": "Bingo da Festa",
        "brand_logo_url": "https://x/logo.jpg",
        "round_number": 3,
        "card_number": 17,
        "numbers": [4, 18, 33, 47, 71],
        "short_order_code": "9C8E",
        "order_id": "bbbbbbbb-0000-4000-8000-000000000001",
        "reprint": true
    """.trimIndent())

    private val envelopeLidas = setOf("id", "type", "status", "order_id", "created_at", "print_mode", "payload")
    // Bloco antigo montado de order_items: todo recibo atual já leva payload.items
    // (conferido no banco em 07/10: 523 recibos em 30 dias, todos com itens).
    private val envelopeIgnoradas = setOf("receipt")

    private fun ler(json: JSONObject) = lerJobDeImpressao(json)

    // ---------- o que o app lê ----------

    @Test
    fun `a SENHA da ficha chega na impressora`() {
        // Antes da OS-161 o pickup_code era jogado fora na leitura (desde 18/07).
        assertEquals("0042", ler(JSONObject(ficha)).payload?.pickup_code)
        assertEquals("0042", ler(JSONObject(recibo)).payload?.pickup_code)
    }

    @Test
    fun `a cartela reimpressa sabe que e reimpressao`() {
        // Antes da OS-161 o reprint era jogado fora e "(REIMPRESSÃO)" nunca saía.
        assertEquals(true, ler(JSONObject(cartela)).payload?.reprint)
    }

    @Test
    fun `ficha le item, unidade e foodtruck`() {
        val p = ler(JSONObject(ficha)).payload!!
        assertEquals("Heineken LN", p.item_name)
        assertEquals(2, p.unit_number)
        assertEquals(3, p.total_units)
        assertEquals(listOf("+ 2x Bacon"), p.addon_lines)
        assertEquals("Válido somente para Truck X", p.partner_footer)
        assertEquals(44.97, p.total!!, 0.0001)
    }

    @Test
    fun `fichas do recibo saem em ordem e sem perder o adicional`() {
        val fichas = ler(JSONObject(reciboComFichas)).payload!!.unit_tickets
        assertEquals(listOf(1, 2), fichas.map { it.unit_number })
        assertEquals(listOf("+ Limão"), fichas[1].addon_lines)
    }

    @Test
    fun `qr do ingresso vem cru, sem trim`() {
        assertEquals(" AB12CD34EF-RAW ", ler(JSONObject(ingresso)).payload?.qr_payload)
    }

    @Test
    fun `fila inteira vem ordenada pela hora`() {
        val maisNovo = JSONObject(ficha).put("created_at", "2026-10-05T23:00:00.000Z")
        val corpo = JSONObject().put("success", true)
            .put("jobs", org.json.JSONArray().put(maisNovo).put(JSONObject(recibo)))
        val jobs = lerFilaDeImpressao(corpo.toString())
        assertEquals(listOf("receipt", "ticket"), jobs.map { it.type })
    }

    // ---------- inventário: toda chave decidida ----------

    @Test
    fun `toda chave do servidor tem decisao`() {
        for ((nome, ex) in listOf("ficha" to ficha, "recibo" to recibo, "recibo_com_fichas" to reciboComFichas,
            "ingresso" to ingresso, "cartela" to cartela)) {
            ContratoKit.inventario(nome, JSONObject(ex), "", envelopeLidas, envelopeIgnoradas)
        }
        ContratoKit.inventario(
            "ficha.payload", JSONObject(ficha), "payload",
            lidas = baseLidas + setOf("item_name", "item_quantity", "unit_number", "total_units",
                "unit_price", "subtotal", "total", "addon_lines", "partner_footer"),
            ignoradas = baseIgnoradas, legado = legado
        )
        ContratoKit.inventario(
            "recibo.payload", JSONObject(recibo), "payload",
            lidas = baseLidas + setOf("print_mode", "force_consolidated_receipt", "items", "total"),
            // A ficha/recibo reimpressos saem iguais ao original (só a cartela marca).
            ignoradas = baseIgnoradas + setOf("is_reprint", "reprinted_at"), legado = legado
        )
        ContratoKit.inventario(
            "recibo.items[0]", JSONObject(recibo), "payload.items.0",
            lidas = setOf("name", "quantity", "unit_price", "subtotal"), ignoradas = emptySet()
        )
        ContratoKit.inventario(
            "recibo_com_fichas.payload", JSONObject(reciboComFichas), "payload",
            lidas = baseLidas + setOf("print_mode", "items", "unit_tickets", "total"),
            ignoradas = baseIgnoradas, legado = legado
        )
        ContratoKit.inventario(
            "recibo_com_fichas.unit_tickets[0]", JSONObject(reciboComFichas), "payload.unit_tickets.0",
            lidas = setOf("item_name", "unit_number", "total_units", "addon_lines", "partner_footer"),
            ignoradas = emptySet(),
            // payload antigo chamava o produto de "name"; a leitura ainda aceita.
            legado = setOf("name")
        )
        ContratoKit.inventario(
            "ingresso.payload", JSONObject(ingresso), "payload",
            lidas = setOf("brand_name", "brand_logo_url", "event_name", "event_date", "event_location",
                "lot_name", "ticket_code", "qr_payload", "total", "created_at"),
            // kind repete o type; site_order_id é do site (a ficha não mostra).
            ignoradas = setOf("kind", "brand_primary_color", "brand_secondary_color", "site_order_id")
        )
        ContratoKit.inventario(
            "cartela.payload", JSONObject(cartela), "payload",
            lidas = setOf("brand_name", "brand_logo_url", "round_number", "card_number", "numbers",
                "short_order_code", "order_id", "reprint"),
            ignoradas = emptySet()
        )
    }

    // ---------- cada campo lido é lido de verdade ----------

    @Test
    fun `cada campo lido e lido de verdade`() {
        val envelopeCampos = listOf("id", "type", "status", "order_id", "created_at", "print_mode")
        ContratoKit.leDeVerdade("ficha", JSONObject(ficha),
            envelopeCampos + (baseLidas + setOf("item_name", "item_quantity", "unit_number", "total_units",
                "unit_price", "subtotal", "total", "addon_lines", "partner_footer")).map { "payload.$it" }, ::ler)
        ContratoKit.leDeVerdade("recibo", JSONObject(recibo),
            listOf("payload.print_mode", "payload.force_consolidated_receipt", "payload.total",
                "payload.items.0.name", "payload.items.0.quantity", "payload.items.0.unit_price",
                "payload.items.0.subtotal"), ::ler)
        ContratoKit.leDeVerdade("recibo_com_fichas", JSONObject(reciboComFichas),
            listOf("payload.unit_tickets.0.item_name", "payload.unit_tickets.0.unit_number",
                "payload.unit_tickets.0.total_units", "payload.unit_tickets.0.addon_lines",
                "payload.unit_tickets.0.partner_footer"), ::ler)
        ContratoKit.leDeVerdade("ingresso", JSONObject(ingresso),
            listOf("brand_name", "brand_logo_url", "event_name", "event_date", "event_location",
                "lot_name", "ticket_code", "qr_payload", "total", "created_at").map { "payload.$it" }, ::ler)
        ContratoKit.leDeVerdade("cartela", JSONObject(cartela),
            listOf("brand_name", "brand_logo_url", "round_number", "card_number", "numbers",
                "short_order_code", "order_id", "reprint").map { "payload.$it" }, ::ler)
    }

    // ---------- falhar alto / padrão explícito ----------

    @Test
    fun `job sem id falha alto`() {
        // Sem id o app não tem como dizer ao servidor o que aconteceu com a ficha.
        ContratoKit.obrigatorios("ficha", JSONObject(ficha), listOf("id"), ::ler)
    }

    @Test
    fun `servidor recusando vira erro com a mensagem dele`() {
        try {
            lerFilaDeImpressao("""{"success": false, "error": "Totem not found"}""")
            fail("era para falhar")
        } catch (e: RespostaInvalida) {
            assertEquals("Totem not found", e.message)
        }
    }

    @Test
    fun `campos opcionais nulos nao viram a palavra null`() {
        ContratoKit.nulosNaoViramTexto("ficha", JSONObject(ficha),
            listOf("type", "status", "order_id", "created_at", "print_mode") +
                (baseLidas + setOf("item_name", "partner_footer")).map { "payload.$it" }, ::ler)
        ContratoKit.nulosNaoViramTexto("recibo_com_fichas", JSONObject(reciboComFichas),
            listOf("payload.unit_tickets.0.item_name", "payload.unit_tickets.0.partner_footer",
                "payload.items.0.name"), ::ler)
        ContratoKit.nulosNaoViramTexto("ingresso", JSONObject(ingresso),
            listOf("event_name", "event_date", "event_location", "lot_name", "ticket_code", "qr_payload")
                .map { "payload.$it" }, ::ler)
    }

    @Test
    fun `padroes escritos do recibo continuam os de sempre`() {
        val j = JSONObject(recibo)
        val item = j.getJSONObject("payload").getJSONArray("items").getJSONObject(0)
        listOf("name", "quantity", "unit_price", "subtotal").forEach { item.put(it, JSONObject.NULL) }
        val lido = ler(j).payload!!.items.single()
        assertEquals("Produto", lido.name)
        assertEquals(0, lido.quantity)
        assertEquals(0.0, lido.unit_price, 0.0)
        assertEquals(0.0, lido.subtotal, 0.0)
    }

    @Test
    fun `ficha sem item_name usa o name antigo, inclusive quando item_name vem nulo`() {
        val j = JSONObject(reciboComFichas)
        val ut = j.getJSONObject("payload").getJSONArray("unit_tickets").getJSONObject(0)
        ut.put("item_name", JSONObject.NULL)
        ut.put("name", "Nome antigo")
        val fichas = ler(j).payload!!.unit_tickets
        assertTrue(fichas.any { it.item_name == "Nome antigo" })
    }

    @Test
    fun `sem SENHA a ficha segue igual`() {
        val j = JSONObject(ficha)
        j.getJSONObject("payload").put("pickup_code", JSONObject.NULL)
        assertNull(ler(j).payload?.pickup_code)
    }
}

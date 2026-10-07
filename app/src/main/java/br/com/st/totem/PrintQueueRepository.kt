package br.com.st.totem

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class PrintQueueRepository {

    private val httpClient = OkHttpClient()
    private val functionsBaseUrl = "https://buviakhfibcsamucnjwu.supabase.co/functions/v1"

    fun fetchPendingJobs(
        activationToken: String,
        onSuccess: (List<PrintJob>) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = Request.Builder()
            .url("$functionsBaseUrl/totem-print-queue")
            .get()
            .addHeader("x-activation-token", activationToken)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Falha ao buscar fila de impressao: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyString = response.body?.string()

                    if (!response.isSuccessful || bodyString.isNullOrBlank()) {
                        onError("Resposta invalida da fila de impressao")
                        return
                    }

                    try {
                        val jobs = lerFilaDeImpressao(bodyString)
                        jobs.forEach { job ->
                            Log.d(
                                "REPO_DEBUG",
                                "Job recebido | id=${job.id} | type=${job.type} | topPrintMode=${job.print_mode} | payloadPrintMode=${job.payload?.print_mode} | forceReceipt=${job.force_consolidated_receipt ?: job.payload?.force_consolidated_receipt} | items=${job.payload?.items?.size ?: 0} | unitTickets=${job.payload?.unit_tickets?.size ?: 0} | hasLogoUrl=${!job.payload?.brand_logo_url.isNullOrBlank()}"
                            )
                        }
                        Log.d("REPO_DEBUG", "Jobs ordenados por created_at: ${jobs.map { it.id }}")
                        onSuccess(jobs)
                    } catch (e: RespostaInvalida) {
                        Log.e("REPO_DEBUG", "Resposta inválida da fila: ${e.message}")
                        onError("Resposta inválida da fila de impressao: ${e.message}")
                    } catch (e: Exception) {
                        Log.e("REPO_DEBUG", "Erro ao processar JSON: ${e.message}", e)
                        onError("Erro ao processar fila de impressao: ${e.message}")
                    }
                }
            }
        })
    }

    fun updateJobStatus(
        activationToken: String,
        jobId: String,
        status: String,
        errorMessage: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val bodyJson = JSONObject()
                .put("job_id", jobId)
                .put("status", status)

            if (!errorMessage.isNullOrBlank()) {
                bodyJson.put("error_message", errorMessage)
            }

            val requestBody = bodyJson.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$functionsBaseUrl/totem-print-queue")
                .post(requestBody)
                .addHeader("x-activation-token", activationToken)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onError("Falha ao atualizar status de impressao: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            onError("Falha ao atualizar status do print job")
                            return
                        }
                        onSuccess()
                    }
                }
            })
        } catch (e: Exception) {
            onError("Erro ao montar atualizacao do print job: ${e.message}")
        }
    }
}

/**
 * Lê a resposta do GET da `totem-print-queue` (totem-web): `{ success, jobs: [...] }`.
 * Regra da OS-161: todo campo falha alto (`textoObrigatorio`) ou tem padrão escrito
 * aqui. Ver `ContratoFilaImpressaoTest`, que compara com o que o servidor manda.
 *
 * ⚠️ Achado da OS-161: o `pickup_code` (SENHA sequencial, 18/07) e o `reprint` da
 * cartela de bingo chegavam do servidor e o app jogava fora calado: o modelo tinha o
 * campo, a impressora usava, mas esta leitura nunca preenchia.
 */
internal fun lerFilaDeImpressao(corpo: String): List<PrintJob> {
    val root = JSONObject(corpo)
    if (root.logico("success") != true) {
        throw RespostaInvalida(root.texto("error") ?: "Falha ao buscar jobs")
    }
    val jobsArray = root.optJSONArray("jobs") ?: JSONArray()
    val jobs = (0 until jobsArray.length()).map { i ->
        val jobJson = jobsArray.optJSONObject(i)
            ?: throw RespostaInvalida("job $i da fila não é um objeto")
        lerJobDeImpressao(jobJson)
    }
    // Garante que jobs mais antigos sejam impressos primeiro
    return jobs.sortedBy { it.created_at ?: "" }
}

internal fun lerJobDeImpressao(jobJson: JSONObject): PrintJob {
    val payloadJson = jobJson.optJSONObject("payload")
    val jobType = jobJson.texto("type")
    val items = lerItens(payloadJson)
    val unitTickets = lerFichas(jobType, jobJson, payloadJson, items)

    val payload = PrintPayload(
        brand_name = payloadJson?.texto("brand_name"),
        brand_logo_url = payloadJson?.texto("brand_logo_url"),
        receipt_header = payloadJson?.texto("receipt_header"),
        receipt_subheader = payloadJson?.texto("receipt_subheader"),
        receipt_footer = payloadJson?.texto("receipt_footer"),
        pickup_message = payloadJson?.texto("pickup_message"),
        short_order_code = payloadJson?.texto("short_order_code"),
        // SENHA sequencial (orders.pickup_code). Null = empresa sem senha: ficha igual a sempre.
        pickup_code = payloadJson?.texto("pickup_code"),
        location_name = payloadJson?.texto("location_name"),
        order_id = payloadJson?.texto("order_id"),
        created_at = payloadJson?.texto("created_at"),

        consumer_doc = payloadJson?.texto("consumer_doc"),
        discount = payloadJson?.decimal("discount"),
        cashback = payloadJson?.decimal("cashback"),
        print_customer_receipt = payloadJson?.logico("print_customer_receipt"),

        print_mode = payloadJson?.texto("print_mode"),
        force_consolidated_receipt = payloadJson?.logico("force_consolidated_receipt"),

        items = items,
        unit_tickets = unitTickets,
        // Só vai no papel do resumo; sem total, o resumo sai com 0,00 (padrão de sempre).
        total = payloadJson?.decimal("total") ?: 0.0,

        item_name = payloadJson?.texto("item_name"),
        item_quantity = payloadJson?.inteiro("item_quantity"),
        unit_number = payloadJson?.inteiro("unit_number"),
        total_units = payloadJson?.inteiro("total_units"),
        unit_price = payloadJson?.decimal("unit_price"),
        subtotal = payloadJson?.decimal("subtotal"),

        // Foodtruck: podem faltar (bar/ingresso) → null, ficha sai igual.
        addon_lines = payloadJson?.listaDeTextos("addon_lines"),
        partner_footer = payloadJson?.texto("partner_footer"),

        // Bingo (job type "bingo_card"): rodada, nº da cartela e as 5 dezenas.
        round_number = payloadJson?.inteiro("round_number"),
        card_number = payloadJson?.inteiro("card_number"),
        numbers = payloadJson?.listaDeInteiros("numbers"),
        // "(REIMPRESSÃO)" na cartela reimpressa.
        reprint = payloadJson?.logico("reprint"),

        // Ingresso (Bloco 2). qr_payload lido CRU (sem trim/normalize): é o que a portaria valida.
        event_name = payloadJson?.texto("event_name"),
        lot_name = payloadJson?.texto("lot_name"),
        ticket_code = payloadJson?.texto("ticket_code"),
        qr_payload = payloadJson?.textoCru("qr_payload"),
        // Data já formatada + local, vindos do totem-web. Podem faltar.
        event_date = payloadJson?.texto("event_date"),
        event_location = payloadJson?.texto("event_location"),
        ingressos = lerIngressos(payloadJson)
    )

    return PrintJob(
        // Sem id não dá para avisar o servidor do que aconteceu com a ficha.
        id = jobJson.textoObrigatorio("id"),
        type = jobType,
        status = jobJson.texto("status"),
        order_id = jobJson.texto("order_id"),
        created_at = jobJson.texto("created_at"),
        print_mode = jobJson.texto("print_mode"),
        force_consolidated_receipt = jobJson.logico("force_consolidated_receipt"),
        payload = payload
    )
}

private fun lerItens(payloadJson: JSONObject?): List<PrintItem> {
    val itemsArray = payloadJson?.optJSONArray("items") ?: return emptyList()
    return (0 until itemsArray.length()).map { j ->
        val itemJson = itemsArray.optJSONObject(j)
            ?: throw RespostaInvalida("item $j do recibo não é um objeto")
        PrintItem(
            // Padrões de sempre do recibo, agora escritos (antes: optString/optInt).
            name = itemJson.texto("name") ?: "Produto",
            quantity = itemJson.inteiro("quantity") ?: 0,
            unit_price = itemJson.decimal("unit_price") ?: 0.0,
            subtotal = itemJson.decimal("subtotal") ?: 0.0
        )
    }
}

private fun lerFichas(
    jobType: String?,
    jobJson: JSONObject,
    payloadJson: JSONObject?,
    items: List<PrintItem>
): List<UnitTicket> {
    val normalizedJobType = jobType?.trim()?.lowercase()

    val unitTicketsArray = payloadJson?.optJSONArray("unit_tickets")
        ?: payloadJson?.optJSONArray("unitTickets")
        ?: jobJson.optJSONArray("unit_tickets")
        ?: jobJson.optJSONArray("unitTickets")

    if (unitTicketsArray != null && unitTicketsArray.length() > 0) {
        val unitTickets = (0 until unitTicketsArray.length()).map { j ->
            val utJson = unitTicketsArray.optJSONObject(j)
                ?: throw RespostaInvalida("ficha $j não é um objeto")
            UnitTicket(
                // Antes: item_name nulo virava a palavra "null" e pulava o "name".
                item_name = utJson.texto("item_name") ?: utJson.texto("name"),
                unit_number = utJson.inteiro("unit_number"),
                total_units = utJson.inteiro("total_units"),
                // Foodtruck: podem faltar (bar) → null.
                addon_lines = utJson.listaDeTextos("addon_lines"),
                partner_footer = utJson.texto("partner_footer")
            )
        }
        Log.d("REPO_DEBUG", "Fichas lidas do backend: ${unitTickets.size} | jobType=$normalizedJobType")

        // Ordena por unit_number para garantir impressão na sequência correta
        return unitTickets.sortedWith(compareBy(
            { it.item_name ?: "" },
            { it.unit_number ?: Int.MAX_VALUE }
        ))
    }

    if (normalizedJobType == "ticket" && items.isNotEmpty()) {
        Log.d("REPO_DEBUG", "Fichas vazias no JSON. Gerando localmente a partir dos itens para job ticket...")
        val geradas = items.filter { it.quantity > 0 }.flatMap { item ->
            (1..item.quantity).map { q ->
                UnitTicket(item_name = item.name, unit_number = q, total_units = item.quantity)
            }
        }
        Log.d("REPO_DEBUG", "Fichas geradas localmente: ${geradas.size}")
        return geradas.sortedWith(compareBy(
            { it.item_name ?: "" },
            { it.unit_number ?: Int.MAX_VALUE }
        ))
    }

    Log.d("REPO_DEBUG", "Sem fallback de unit_tickets | jobType=$normalizedJobType | items=${items.size}")
    return emptyList()
}

// Lista opcional de ingressos (caso de vários no mesmo job). qr_payload CRU.
private fun lerIngressos(payloadJson: JSONObject?): List<IngressoTicket> {
    val arr = payloadJson?.optJSONArray("ingressos") ?: return emptyList()
    return (0 until arr.length()).map { j ->
        val o = arr.optJSONObject(j) ?: throw RespostaInvalida("ingresso $j não é um objeto")
        IngressoTicket(
            event_name = o.texto("event_name"),
            lot_name = o.texto("lot_name"),
            ticket_code = o.texto("ticket_code"),
            qr_payload = o.textoCru("qr_payload"),
            event_date = o.texto("event_date"),
            event_location = o.texto("event_location")
        )
    }
}

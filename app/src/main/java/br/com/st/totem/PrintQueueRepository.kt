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
                        val root = JSONObject(bodyString)
                        val success = root.optBoolean("success", false)

                        if (!success) {
                            onError(root.optString("error", "Falha ao buscar jobs"))
                            return
                        }

                        val jobsArray = root.optJSONArray("jobs") ?: JSONArray()
                        val jobs = mutableListOf<PrintJob>()

                        for (i in 0 until jobsArray.length()) {
                            val jobJson = jobsArray.getJSONObject(i)
                            val payloadJson = jobJson.optJSONObject("payload")

                            val jobType = jobJson.optString("type").nullIfBlank()
                            val items = parseItems(payloadJson)
                            val unitTickets = parseUnitTickets(
                                jobType = jobType,
                                jobJson = jobJson,
                                payloadJson = payloadJson,
                                items = items
                            )

                            val payload = PrintPayload(
                                brand_name = payloadJson?.optString("brand_name").nullIfBlank(),
                                brand_logo_url = payloadJson?.optString("brand_logo_url").nullIfBlank(),
                                receipt_header = payloadJson?.optString("receipt_header").nullIfBlank(),
                                receipt_subheader = payloadJson?.optString("receipt_subheader").nullIfBlank(),
                                receipt_footer = payloadJson?.optString("receipt_footer").nullIfBlank(),
                                pickup_message = payloadJson?.optString("pickup_message").nullIfBlank(),
                                short_order_code = payloadJson?.optString("short_order_code").nullIfBlank(),
                                location_name = payloadJson?.optString("location_name").nullIfBlank(),
                                order_id = payloadJson?.optString("order_id").nullIfBlank(),
                                created_at = payloadJson?.optString("created_at").nullIfBlank(),

                                consumer_doc = payloadJson?.optString("consumer_doc").nullIfBlank(),
                                discount = payloadJson?.optNullableDouble("discount"),
                                cashback = payloadJson?.optNullableDouble("cashback"),
                                print_customer_receipt = payloadJson?.optNullableBoolean("print_customer_receipt"),

                                print_mode = payloadJson?.optString("print_mode").nullIfBlank(),
                                force_consolidated_receipt = payloadJson?.optNullableBoolean("force_consolidated_receipt"),

                                items = items,
                                unit_tickets = unitTickets,
                                total = payloadJson?.optNullableDouble("total") ?: 0.0,

                                item_name = payloadJson?.optString("item_name").nullIfBlank(),
                                item_quantity = payloadJson?.optNullableInt("item_quantity"),
                                unit_number = payloadJson?.optNullableInt("unit_number"),
                                total_units = payloadJson?.optNullableInt("total_units"),
                                unit_price = payloadJson?.optNullableDouble("unit_price"),
                                subtotal = payloadJson?.optNullableDouble("subtotal")
                            )

                            val job = PrintJob(
                                id = jobJson.optString("id"),
                                type = jobType,
                                status = jobJson.optString("status").nullIfBlank(),
                                order_id = jobJson.optString("order_id").nullIfBlank(),
                                created_at = jobJson.optString("created_at").nullIfBlank(),
                                print_mode = jobJson.optString("print_mode").nullIfBlank(),
                                force_consolidated_receipt = jobJson.optNullableBoolean("force_consolidated_receipt"),
                                payload = payload
                            )

                            Log.d(
                                "REPO_DEBUG",
                                "Job recebido | id=${job.id} | type=${job.type} | topPrintMode=${job.print_mode} | payloadPrintMode=${payload.print_mode} | forceReceipt=${job.force_consolidated_receipt ?: payload.force_consolidated_receipt} | items=${items.size} | unitTickets=${unitTickets.size} | hasLogoUrl=${!payload.brand_logo_url.isNullOrBlank()}"
                            )

                            jobs.add(job)
                        }

                        // ✅ Garante que jobs mais antigos sejam impressos primeiro
                        jobs.sortBy { it.created_at ?: "" }
                        Log.d("REPO_DEBUG", "Jobs ordenados por created_at: ${jobs.map { it.id }}")

                        onSuccess(jobs)
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

    private fun parseItems(payloadJson: JSONObject?): List<PrintItem> {
        val itemsArray = payloadJson?.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<PrintItem>()

        for (j in 0 until itemsArray.length()) {
            val itemJson = itemsArray.getJSONObject(j)
            items.add(
                PrintItem(
                    name = itemJson.optString("name", "Produto"),
                    quantity = itemJson.optInt("quantity", 0),
                    unit_price = itemJson.optDouble("unit_price", 0.0),
                    subtotal = itemJson.optDouble("subtotal", 0.0)
                )
            )
        }

        return items
    }

    private fun parseUnitTickets(
        jobType: String?,
        jobJson: JSONObject,
        payloadJson: JSONObject?,
        items: List<PrintItem>
    ): List<UnitTicket> {
        val normalizedJobType = jobType?.trim()?.lowercase()
        val unitTickets = mutableListOf<UnitTicket>()

        val unitTicketsArray = payloadJson?.optJSONArray("unit_tickets")
            ?: payloadJson?.optJSONArray("unitTickets")
            ?: jobJson.optJSONArray("unit_tickets")
            ?: jobJson.optJSONArray("unitTickets")

        if (unitTicketsArray != null && unitTicketsArray.length() > 0) {
            for (j in 0 until unitTicketsArray.length()) {
                val utJson = unitTicketsArray.getJSONObject(j)
                unitTickets.add(
                    UnitTicket(
                        item_name = utJson.optString("item_name")
                            .ifBlank { utJson.optString("name") }
                            .nullIfBlank(),
                        unit_number = utJson.optNullableInt("unit_number"),
                        total_units = utJson.optNullableInt("total_units")
                    )
                )
            }

            Log.d("REPO_DEBUG", "Fichas lidas do backend: ${unitTickets.size} | jobType=$normalizedJobType")

            // ✅ Ordena por unit_number para garantir impressão na sequência correta
            unitTickets.sortWith(compareBy(
                { it.item_name ?: "" },
                { it.unit_number ?: Int.MAX_VALUE }
            ))
            Log.d("REPO_DEBUG", "Fichas ordenadas: ${unitTickets.map { "${it.item_name}:${it.unit_number}" }}")

            return unitTickets
        }

        if (normalizedJobType == "ticket" && unitTickets.isEmpty() && items.isNotEmpty()) {
            Log.d("REPO_DEBUG", "Fichas vazias no JSON. Gerando localmente a partir dos itens para job ticket...")

            items.forEach { item ->
                if (item.quantity > 0) {
                    for (q in 1..item.quantity) {
                        unitTickets.add(
                            UnitTicket(
                                item_name = item.name,
                                unit_number = q,
                                total_units = item.quantity
                            )
                        )
                    }
                }
            }

            Log.d("REPO_DEBUG", "Fichas geradas localmente: ${unitTickets.size}")

            // ✅ Ordena fichas geradas localmente também
            unitTickets.sortWith(compareBy(
                { it.item_name ?: "" },
                { it.unit_number ?: Int.MAX_VALUE }
            ))

            return unitTickets
        } else {
            Log.d(
                "REPO_DEBUG",
                "Sem fallback de unit_tickets | jobType=$normalizedJobType | items=${items.size}"
            )
        }

        return unitTickets
    }

    private fun String?.nullIfBlank(): String? {
        val trimmed = this?.trim()
        return if (trimmed.isNullOrBlank() || trimmed.equals("null", ignoreCase = true)) {
            null
        } else {
            trimmed
        }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return try {
            optInt(name)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return try {
            optDouble(name)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return try {
            optBoolean(name)
        } catch (_: Exception) {
            null
        }
    }
}
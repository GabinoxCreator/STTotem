package br.com.st.totem.payment.sitef

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.com.softwareexpress.sitef.android.CliSiTef
import br.com.softwareexpress.sitef.android.ICliSiTefListener
import br.com.st.totem.LocalStorageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gerencia o ciclo de vida de pagamentos via CliSiTef (SoftwareExpress) com TLS GSURF.
 *
 * Padrões críticos seguidos:
 *  1. INSTÂNCIA ÚNICA — doc oficial: "only instantiate CliSiTef once, to avoid
 *     competition problems with the pinpad". A lib nativa libclisitef.so é única
 *     no processo e mantém estado iterativo. Recriar a instância Java não reseta
 *     esse estado e causa configure() -> -12 (iterative routine not finalized).
 *  2. ABORT NÃO-DESTRUTIVO — abortTransaction(-1) só SINALIZA o cancelamento. O
 *     SDK ainda precisa do onData responder e do onTransactionResult disparar
 *     para considerar o ciclo iterativo encerrado. NÃO anular cliSiTef nem fazer
 *     sleep cego — esperar o callback natural.
 *  3. ESTADO CONTROLADO — isTransactionActive impede startPayment concorrente
 *     enquanto uma transação anterior ainda não finalizou seu ciclo iterativo.
 *  4. GUARDA DE PROVA — nunca reportar "aprovado" sem NSU/autorização. Aprovação
 *     real sempre traz comprovante; sem ele, trata como NÃO aprovado (ver
 *     dispatchFinalResult).
 *  5. WATCHDOG DE INATIVIDADE (Fix #1) — se o pinpad ficar parado tempo demais
 *     (cliente desistiu/foi embora), encerra a venda sozinho e LIBERA o slot,
 *     evitando que a próxima venda fique presa em "Transação anterior em andamento".
 *     O watchdog chama abort() (cancelamento limpo, SEM matar o processo) e deixa
 *     o onTransactionResult fechar o ciclo; só libera na marra se o abort não
 *     finalizar dentro do prazo de carência.
 *  6. RESULTADO ÚNICO — finalResultDispatched garante que só o primeiro desfecho
 *     (resultado real OU watchdog) seja despachado; o segundo é ignorado.
 */
class CliSitefManager(
    private val context: Context,
    private val storage: LocalStorageManager,
    private val onStateChange: (PaymentState) -> Unit,
    private val onResult: (SitefPaymentResult) -> Unit,
    // 🆘 Acionado SÓ quando o ciclo nativo fica comprovadamente travado e não há
    // como resetá-lo em processo: (a) o abort não drenou dentro da carência, ou
    // (b) configure retornou -9/-12 (rotina iterativa anterior não finalizada).
    // Cabe à MainActivity reiniciar o processo para resetar a libclisitef.so.
    // NÃO é chamado no abort gracioso (que fecha o ciclo via onTransactionResult).
    private val onUnrecoverable: () -> Unit = {}
) {
    companion object {
        private const val TAG             = "CLISITEF_MANAGER"
        private const val EMPRESA_SITEF   = "THEO0167"
        private const val ENDERECO_SITEF  = "66.22.76.37"
        private const val CNPJ_CPF        = "43941698000152"
        private const val CNPJ_AUTOMACAO  = "61126523000173"
        private const val OPERADOR        = "0001"

        // --- Fix #1: tempos do watchdog de inatividade ---
        // Cartão: tempo do cliente ler, inserir e digitar a senha.
        // PIX: tempo de o cliente escanear e pagar (o countdown do SDK é ~300s).
        // PARA TESTAR: baixe WATCHDOG_CARD_TIMEOUT_MS para 15_000L temporariamente.
        private const val WATCHDOG_CARD_TIMEOUT_MS = 90_000L
        private const val WATCHDOG_PIX_TIMEOUT_MS  = 320_000L
        // Carência para o abort fechar o ciclo antes de liberarmos o slot na marra.
        private const val WATCHDOG_ABORT_GRACE_MS  = 10_000L
    }

    // ✅ Instância ÚNICA — criada uma vez, NUNCA destruída/recriada.
    private val cliSiTef: CliSiTef by lazy {
        Log.i(TAG, "Inicializando CliSiTef (instância única do processo)")
        CliSiTef(context)
    }

    private val executor    = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val resultFields = mutableMapOf<String, String>()
    private var receiptCustomer = ""
    private var receiptEstablishment = ""
    private var lastStage1Approved = false

    private val isTransactionActive   = AtomicBoolean(false)
    private val isAborting            = AtomicBoolean(false)
    private val finalResultDispatched = AtomicBoolean(false)

    var hadMidTransactionAbort: Boolean = false
        private set

    // --- Fix #1: relógio de segurança (watchdog de inatividade) ---
    private var watchdogTimeoutMs = WATCHDOG_CARD_TIMEOUT_MS

    // Rede de segurança: roda só se um abort (do watchdog OU do frontend) NÃO
    // produzir onTransactionResult dentro da carência. Significa que o ciclo
    // nativo está realmente travado: o onData parou de chegar, então a drenagem
    // não anda. Libera o slot para não travar a próxima venda E reinicia o
    // processo, pois a libclisitef.so segue com a rotina iterativa pendente
    // (deixá-la assim faria a próxima configure() retornar -12).
    private val watchdogForceReleaseRunnable = Runnable {
        if (isTransactionActive.get()) {
            Log.w(TAG, "WATCHDOG: abort não finalizou na carência — ciclo nativo travado, " +
                    "liberando slot e resetando processo (último recurso)")
            dispatchFinalResult(false, -97, "Tempo de inatividade esgotado")
            mainHandler.post { onUnrecoverable() }
        }
    }

    // Dispara quando o pinpad fica sem atividade por watchdogTimeoutMs.
    // Aciona um abort gracioso; a carência de força é armada dentro de abort().
    private val watchdogRunnable = Runnable {
        if (!isTransactionActive.get()) return@Runnable
        Log.w(TAG, "WATCHDOG: sem atividade do pinpad — abortando venda abandonada")
        try {
            abort()
        } catch (e: Exception) {
            Log.w(TAG, "watchdog abort: ${e.message}")
        }
    }

    private fun armWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, watchdogTimeoutMs)
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(watchdogForceReleaseRunnable)
    }

    fun startPayment(paymentMethod: String, amountCents: Int, referenceId: String): Boolean {
        if (isTransactionActive.get()) {
            Log.w(TAG, "startPayment recusado — transação anterior ainda ativa.")
            dispatchState(PaymentState.Finished(false, -98, "Transação anterior em andamento"))
            dispatchResult(SitefPaymentResult(success = false, errorMessage = "Transação anterior em andamento"))
            return false
        }

        val sitefOtp = storage.getSitefOtp()?.trim().orEmpty()
        if (sitefOtp.isBlank()) {
            Log.e(TAG, "OTP SiTef não configurado")
            return false
        }

        resetTransactionData()
        isAborting.set(false)
        finalResultDispatched.set(false)

        val modalidade = mapModalidade(paymentMethod)
        val valorStr   = amountCents.toString().padStart(12, '0')
        val cupom      = referenceId.replace("-", "").takeLast(20).ifBlank { "123456" }
        val restricoes = getRestricoes(paymentMethod)
        val now        = Date()
        val dataFiscal = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
        val horaFiscal = SimpleDateFormat("HHmmss",   Locale.getDefault()).format(now)

        val params = "[TipoComunicacaoExterna=GSURF.SSL;" +
                "GSurf.OTP=$sitefOtp;" +
                "TerminalUUID=$CNPJ_CPF;]"

        Log.i(TAG, "╔══════════════════════════════════════════════")
        Log.i(TAG, "║ startPayment method=$paymentMethod modal=$modalidade")
        Log.i(TAG, "║ valor=$valorStr (${amountCents}c) | terminalId=$CNPJ_AUTOMACAO")
        Log.i(TAG, "║ params=$params")
        Log.i(TAG, "╚══════════════════════════════════════════════")

        isTransactionActive.set(true)

        // --- Fix #1: arma o watchdog assim que a venda começa ---
        watchdogTimeoutMs = if (paymentMethod.trim().lowercase() == "pix")
            WATCHDOG_PIX_TIMEOUT_MS else WATCHDOG_CARD_TIMEOUT_MS
        armWatchdog()

        executor.execute {
            try {
                val configResult = cliSiTef.configure(
                    ENDERECO_SITEF, EMPRESA_SITEF, CNPJ_AUTOMACAO, params
                )

                if (configResult != 0) {
                    Log.e(TAG, "configure falhou: $configResult")
                    if (configResult == -9 || configResult == -12) {
                        // Rotina iterativa de uma venda anterior não foi finalizada na
                        // libclisitef.so. Não há como resetá-la em processo → finaliza
                        // esta tentativa e reinicia o processo para limpar o estado.
                        Log.e(TAG, "configure $configResult = ciclo iterativo anterior pendente — reset de processo")
                        dispatchFinalResult(false, configResult, mapResultMessage(configResult))
                        mainHandler.post { onUnrecoverable() }
                        return@execute
                    }
                    dispatchFinalResult(
                        success    = false,
                        resultCode = configResult,
                        message    = "Erro config: $configResult"
                    )
                    return@execute
                }

                Log.i(TAG, "configure OK — iniciando transação")
                dispatchState(PaymentState.Processing)

                cliSiTef.startTransaction(
                    listener, modalidade, valorStr, cupom,
                    dataFiscal, horaFiscal, OPERADOR, restricoes
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção durante transação", e)
                dispatchFinalResult(false, -99, e.message ?: "Erro desconhecido")
            }
        }

        return true
    }

    /**
     * Sinaliza ao SDK que a transação deve ser cancelada. Não destrói nem anula
     * nada — a finalização real vem via onTransactionResult.
     */
    fun abort() {
        if (!isTransactionActive.get()) {
            Log.d(TAG, "abort() ignorado — nenhuma transação ativa")
            return
        }
        if (isAborting.getAndSet(true)) {
            Log.d(TAG, "abort() ignorado — abort já em curso")
            return
        }
        Log.d(TAG, "abort() — chamando abortTransaction(-1), aguardando onTransactionResult")
        hadMidTransactionAbort = true

        // Carência: se o abort drenar e o onTransactionResult chegar, o
        // dispatchFinalResult cancela isto (cancelWatchdog). Se NÃO chegar dentro
        // da carência, o ciclo nativo está travado → força a liberação + reset.
        // Vale para qualquer abort (frontend OU watchdog).
        mainHandler.removeCallbacks(watchdogForceReleaseRunnable)
        mainHandler.postDelayed(watchdogForceReleaseRunnable, WATCHDOG_ABORT_GRACE_MS)

        try {
            cliSiTef.abortTransaction(-1)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao chamar abortTransaction: ${e.message}")
            dispatchFinalResult(false, -100, "Falha ao abortar: ${e.message}")
        }
    }

    fun clearMidTransactionAbortFlag() {
        hadMidTransactionAbort = false
    }

    /**
     * Libera dispositivos USB. Chamado APENAS pela MainActivity em pontos
     * específicos (re-permissão pós-abort), nunca durante uma transação ativa.
     */
    fun releaseUsbDevice() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList
            if (devices.isEmpty()) {
                Log.d(TAG, "releaseUsbDevice: nenhum device USB")
                return
            }
            devices.values.forEach { device ->
                if (!usbManager.hasPermission(device)) {
                    Log.d(TAG, "releaseUsbDevice: sem permissão para ${device.deviceName} vendor=${device.vendorId} — pulando")
                    return@forEach
                }
                try {
                    val connection = usbManager.openDevice(device)
                    if (connection != null) {
                        for (i in 0 until device.interfaceCount) {
                            try {
                                connection.releaseInterface(device.getInterface(i))
                            } catch (e: Exception) {
                                Log.w(TAG, "releaseInterface($i) falhou: ${e.message}")
                            }
                        }
                        connection.close()
                        Log.d(TAG, "releaseUsbDevice: liberado | device=${device.deviceName} vendor=${device.vendorId}")
                    } else {
                        Log.w(TAG, "releaseUsbDevice: openDevice retornou null para ${device.deviceName}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "releaseUsbDevice: falha para ${device.deviceName}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseUsbDevice: exceção geral: ${e.message}")
        }
    }

    private val listener = object : ICliSiTefListener {

        override fun onData(command: Int, fieldId: Int, minLen: Int, maxLen: Int, context: Int, data: ByteArray?) {
            val safeData = data?.decodeToString().orEmpty()
            val effectiveFieldId = if (fieldId == 0) minLen else fieldId

            Log.d(TAG, "onData | cmd=$command fieldId=$fieldId minLen=$minLen effectiveId=$effectiveFieldId " +
                    "aborting=${isAborting.get()} data=${safeData.take(80)}")

            // --- Fix #1: cada sinal do pinpad reinicia o relógio de inatividade ---
            armWatchdog()

            collectResponseField(effectiveFieldId, safeData)

            if (isAborting.get()) {
                Log.d(TAG, "onData durante abort — destravando ciclo com continueTransaction(\"\")")
                try {
                    cliSiTef.continueTransaction("")
                } catch (e: Exception) {
                    Log.w(TAG, "continueTransaction em abort: ${e.message}")
                }
                return
            }

            when (fieldId) {
                1, 3 -> {
                    if (safeData.isNotBlank()) dispatchState(PaymentState.ShowMessage(safeData))
                    safeContinue("")
                }
                4  -> { safeContinue("") }
                23 -> { safeContinue("") }
                21 -> {
                    Log.i(TAG, "MENU OPCOES: $safeData — respondendo 1 (À Vista)")
                    safeContinue("1")
                }
                14 -> { safeContinue("") }
                50 -> {
                    if (safeData.isNotBlank()) {
                        Log.i(TAG, "QR Code PIX recebido | tamanho=${safeData.length} | inicio=${safeData.take(30)}")
                        dispatchState(PaymentState.ShowQRCode(safeData))
                    }
                    safeContinue("")
                }
                51 -> {
                    Log.d(TAG, "PIX: remover QR Code")
                    dispatchState(PaymentState.HideQRCode)
                    safeContinue("")
                }
                52 -> {
                    if (safeData.isNotBlank()) {
                        Log.d(TAG, "PIX status: $safeData")
                        val countdown = Regex("\\((\\d+)\\)").find(safeData)?.groupValues?.get(1)?.toIntOrNull()
                        if (countdown != null) {
                            Log.d(TAG, "PIX countdown SDK: $countdown segundos")
                            dispatchState(PaymentState.PixCountdown(countdown))
                        } else {
                            dispatchState(PaymentState.ShowMessage(safeData))
                        }
                    }
                    safeContinue("")
                }
                else -> { safeContinue("") }
            }
        }

        override fun onTransactionResult(resultCode: Int, p1: Int) {
            val wasAborting = isAborting.getAndSet(false)
            Log.i(TAG, "onTransactionResult: resultCode=$resultCode p1=$p1 wasAborting=$wasAborting")
            Log.i(TAG, "campos coletados: $resultFields")
            Log.i(TAG, "nsuSitef=${resultFields["nsuSitef"]} codAutorizacao=${resultFields["codAutorizacao"]}")

            when {
                wasAborting -> {
                    Log.i(TAG, "Transação cancelada pelo abort do usuário: code=$resultCode")
                    dispatchFinalResult(false, resultCode, "Operação cancelada")
                }
                resultCode in 0..1 && p1 in setOf(0, 90, 255) && !lastStage1Approved -> {
                    lastStage1Approved = true
                    try {
                        Log.d(TAG, "finishTransaction(1) — confirmando... p1=$p1")
                        cliSiTef.finishTransaction(1)
                    } catch (e: Exception) {
                        Log.e(TAG, "finishTransaction error", e)
                        dispatchFinalResult(false, -100, "Erro ao confirmar: ${e.message}")
                    }
                }
                resultCode in 0..1 && p1 !in setOf(0, 90, 255) && !lastStage1Approved -> {
                    Log.e(TAG, "Stage 1 falhou: resultCode=$resultCode p1=$p1")
                    dispatchFinalResult(false, p1, mapResultMessage(p1))
                }
                lastStage1Approved -> {
                    val success = resultCode in 0..2
                    val message = if (success) "Pagamento aprovado" else mapResultMessage(resultCode)
                    Log.i(TAG, "Stage 2 finalizado: success=$success code=$resultCode")
                    dispatchFinalResult(success, resultCode, message)
                }
                else -> {
                    dispatchFinalResult(false, resultCode, mapResultMessage(resultCode))
                }
            }
        }
    }

    /**
     * Responde uma volta do laço iterativo. Se o SDK lançar aqui, o laço morreria
     * SEM chegar ao onTransactionResult e o ciclo ficaria preso (próxima venda =
     * started=false). Por isso finalizamos imediatamente em vez de esperar o
     * watchdog de 90s. Usado só no caminho normal; o caminho de abort tem o seu
     * próprio try/catch (e a carência do force-release).
     */
    private fun safeContinue(data: String) {
        try {
            cliSiTef.continueTransaction(data)
        } catch (e: Exception) {
            Log.e(TAG, "continueTransaction lançou — finalizando ciclo na hora: ${e.message}", e)
            dispatchFinalResult(false, -101, "Erro no ciclo iterativo: ${e.message}")
        }
    }

    private fun collectResponseField(effectiveFieldId: Int, buffer: String?) {
        if (buffer.isNullOrEmpty()) return
        when (effectiveFieldId) {
            100  -> resultFields["codTrans"]       = buffer
            121  -> receiptCustomer                = buffer
            122  -> receiptEstablishment           = buffer
            131  -> resultFields["bandeira"]       = buffer
            132  -> resultFields["nsuSitef"]       = buffer
            133  -> resultFields["nsuHost"]        = buffer
            134  -> resultFields["codAutorizacao"] = buffer
            135  -> resultFields["tipoCartao"]     = buffer
            2    -> resultFields["confirmacao"]    = buffer
        }
    }

    private fun dispatchFinalResult(success: Boolean, resultCode: Int, message: String) {
        // --- Fix #1: desliga o watchdog assim que houver desfecho ---
        cancelWatchdog()

        // --- Resultado único: só o primeiro desfecho (real OU watchdog) vence ---
        if (!finalResultDispatched.compareAndSet(false, true)) {
            Log.d(TAG, "dispatchFinalResult ignorado — resultado já despachado")
            return
        }

        // 🛡️ GUARDA DE PROVA DE PAGAMENTO
        // Aprovação real SEMPRE traz NSU SiTef, NSU host ou código de autorização.
        // Se o SDK reportar "sucesso" mas todos esses vierem vazios, NÃO houve
        // pagamento (ex.: PIX < R$1 recusado, recusa sem comprovante) — então
        // tratamos como NÃO aprovado, independentemente do que o código diga.
        val temProvaDePagamento =
            !resultFields["nsuSitef"].isNullOrBlank() ||
                    !resultFields["nsuHost"].isNullOrBlank() ||
                    !resultFields["codAutorizacao"].isNullOrBlank()

        val aprovado = success && temProvaDePagamento

        if (success && !temProvaDePagamento) {
            Log.w(TAG, "⚠️ SDK reportou sucesso SEM prova de pagamento (nsu/autorização vazios) — " +
                    "tratando como NÃO aprovado. codResp=$resultCode")
        }

        val mensagemFinal = if (aprovado) "Pagamento aprovado" else mensagemAmigavel(resultCode)

        Log.i(TAG, "dispatchFinalResult: aprovado=$aprovado code=$resultCode " +
                "nsuSitef=${resultFields["nsuSitef"]} codAutorizacao=${resultFields["codAutorizacao"]}")

        // Libera o slot ANTES de despachar
        isTransactionActive.set(false)
        isAborting.set(false)

        val result = SitefPaymentResult(
            success            = aprovado,
            codResp            = resultCode.toString(),
            codTrans           = resultFields["codTrans"],
            bandeira           = resultFields["bandeira"],
            nsuSitef           = resultFields["nsuSitef"],
            nsuHost            = resultFields["nsuHost"],
            codAutorizacao     = resultFields["codAutorizacao"],
            viaEstabelecimento = receiptEstablishment,
            viaCliente         = receiptCustomer,
            rawData            = resultFields.toMap(),
            errorMessage       = if (!aprovado) mensagemFinal else null
        )
        dispatchState(PaymentState.Finished(aprovado, resultCode, mensagemFinal))
        dispatchResult(result)

        Log.d(TAG, "dispatchFinalResult concluído")
    }

    private fun resetTransactionData() {
        resultFields.clear()
        receiptCustomer = ""
        receiptEstablishment = ""
        lastStage1Approved = false
    }

    private fun mapModalidade(method: String) = when (method.trim().lowercase()) {
        "pix"                          -> 122
        "débito", "debito", "debit"    -> 2
        else                           -> 3
    }

    private fun getRestricoes(method: String) = when (method.trim().lowercase()) {
        "débito", "debito", "debit"    -> "TransacoesHabilitadas=16;"
        "crédito", "credito", "credit" -> "TransacoesHabilitadas=26;"
        else                           -> ""
    }

    // Mensagens TÉCNICAS — só para logs internos.
    private fun mapResultMessage(code: Int) = when (code) {
        0, 1, 2 -> "Pagamento aprovado"
        -1      -> "Módulo não configurado"
        -2      -> "Operação cancelada pelo operador"
        -3      -> "Função inválida"
        -4      -> "Falta de memória"
        -5      -> "Falha de comunicação com o servidor SiTef"
        -6      -> "Operação cancelada pelo portador"
        -12     -> "Erro no ciclo iterativo — transação anterior incompleta"
        -15     -> "Operação cancelada pela automação"
        -40     -> "Transação negada pelo SiTef"
        -43     -> "Erro no PinPad"
        -9      -> "Ciclo iterativo anterior não finalizado"
        -97     -> "Tempo de inatividade esgotado"
        -100    -> "Erro interno"
        -101    -> "Exceção no ciclo iterativo"
        else    -> "Erro na transação (código $code)"
    }

    // Mensagens AMIGÁVEIS — exibidas ao cliente. Genéricas, sem código técnico.
    private fun mensagemAmigavel(code: Int) = when (code) {
        -2, -6, -15 -> "Pagamento cancelado."
        -5          -> "Não foi possível concluir o pagamento. Verifique a conexão e tente novamente."
        -97         -> "Tempo esgotado. Toque para tentar novamente."
        -1, -3, -4, -9, -12, -40, -43, -98, -99, -100, -101 ->
            "Não foi possível processar o pagamento. Tente novamente."
        else        -> "Pagamento não aprovado. Verifique seu cartão ou tente outra forma de pagamento."
    }

    private fun dispatchState(state: PaymentState) = mainHandler.post { onStateChange(state) }
    private fun dispatchResult(result: SitefPaymentResult) = mainHandler.post { onResult(result) }
}
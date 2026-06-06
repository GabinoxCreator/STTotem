package br.com.st.totem

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import br.com.st.totem.databinding.ActivityMainBinding
import br.com.st.totem.payment.sitef.PaymentProvider
import br.com.st.totem.payment.sitef.PaymentState
import br.com.st.totem.payment.sitef.PinPadStateMonitor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: LocalStorageManager
    private lateinit var printerManager: PrinterManager
    private lateinit var paymentProvider: PaymentProvider
    private lateinit var pinPadStateMonitor: PinPadStateMonitor

    private val httpClient = OkHttpClient()
    private val bootstrapRepository = BootstrapRepository()
    private val printQueueRepository = PrintQueueRepository()
    private val handler = Handler(Looper.getMainLooper())
    private val printQueueHandler = Handler(Looper.getMainLooper())
    private val printExecutor = Executors.newSingleThreadExecutor()
    private val printTimeoutHandler = Handler(Looper.getMainLooper())
    private var currentPrintTimeoutRunnable: Runnable? = null

    private val functionsBaseUrl = "https://buviakhfibcsamucnjwu.supabase.co/functions/v1"
    private val kioskWebUrl = "https://totemst.lovable.app/totem"

    private var appVersionName: String = "1.0.0"
    private var bootstrapLoaded = false
    private var isPrintingNow = false
    private var kioskModeEnabled = true
    private var kioskModeStartedOnce = false

    private var pendingPaymentMethod: String? = null
    private var pendingReferenceId: String? = null
    private var pendingAmountCents: Int? = null
    private var pendingFacePagReferenceId: String? = null
    private var pendingFacePagAmountCents: Int? = null
    private var pendingCameraPermissionRequest: PermissionRequest? = null
    private var appCameraPermissionReady = false

    private val printBaseTimeoutMs = 45_000L
    private val printPerVoucherTimeoutMs = 9_000L
    private val printSummaryBufferMs = 12_000L
    private val printMaxTimeoutMs = 240_000L

    private val ACTION_USB_PERMISSION = "br.com.st.totem.USB_PERMISSION"
    private var usbPermissionCallback: (() -> Unit)? = null
    private val PINPAD_VENDOR_ID = 5971

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d("USB", "Permissão USB: granted=$granted vendor=${device?.vendorId} device=${device?.deviceName}")
                // 🔎 LOG: resultado da permissão de USB (concedida ou negada)
                sendLog("pinpad", "usb_permissao",
                    JSONObject().put("granted", granted).put("vendor", device?.vendorId ?: -1),
                    if (granted) "info" else "warn")
                if (granted && device?.vendorId == PINPAD_VENDOR_ID) {
                    Log.i("PINPAD_MONITOR", "✅ Permissão USB concedida para pinpad vendor=$PINPAD_VENDOR_ID")
                }
                usbPermissionCallback?.invoke()
                usbPermissionCallback = null
            }
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() { sendHeartbeat(); handler.postDelayed(this, 30_000L) }
    }

    private val printQueueRunnable = object : Runnable {
        override fun run() { pollPrintQueue(); printQueueHandler.postDelayed(this, 2_000L) }
    }

    private val sitefLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val payload = JSONObject()
        try {
            payload.put("paymentMethod", pendingPaymentMethod ?: "")
            payload.put("referenceId", pendingReferenceId ?: "")
            payload.put("amountCents", pendingAmountCents ?: 0)
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val extras = result.data?.extras
                val codResp = extras?.getString("CODRESP")
                val success = codResp == "0"
                payload.put("success", success)
                payload.put("codResp", codResp ?: "")
                payload.put("codTrans", extras?.getString("CODTRANS") ?: "")
                payload.put("bandeira", extras?.getString("BANDEIRA") ?: "")
                payload.put("nsuSitef", extras?.getString("NSU_SITEF") ?: "")
                payload.put("nsuHost", extras?.getString("NSU_HOST") ?: "")
                payload.put("codAutorizacao", extras?.getString("COD_AUTORIZACAO") ?: "")
                payload.put("viaEstabelecimento", extras?.getString("VIA_ESTABELECIMENTO") ?: "")
                payload.put("viaCliente", extras?.getString("VIA_CLIENTE") ?: "")
                if (!success) payload.put("message", "Pagamento não aprovado. CODRESP=${codResp ?: "sem retorno"}")
                Toast.makeText(this, if (success) "Pagamento aprovado" else "Pagamento não aprovado", Toast.LENGTH_LONG).show()
            } else {
                payload.put("success", false)
                payload.put("cancelled", true)
                payload.put("message", "Pagamento cancelado ou sem retorno")
            }
        } catch (e: Exception) {
            payload.put("success", false)
            payload.put("message", "Erro: ${e.message}")
        }
        dispatchSitefResultToWeb(payload)
        pendingPaymentMethod = null; pendingReferenceId = null; pendingAmountCents = null
    }

    private val facePagLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val payload = JSONObject()
        try {
            val data = result.data
            val success = result.resultCode == Activity.RESULT_OK && data?.getBooleanExtra("success", false) == true
            val returnedSessionId = data?.getStringExtra("livenessSessionId") ?: data?.getStringExtra("sessionId") ?: ""
            payload.put("success", success)
            payload.put("referenceId", data?.getStringExtra("referenceId") ?: (pendingFacePagReferenceId ?: ""))
            payload.put("amountCents", data?.getIntExtra("amountCents", pendingFacePagAmountCents ?: 0) ?: 0)
            if (success) {
                payload.put("sessionId", returnedSessionId)
                payload.put("livenessSessionId", returnedSessionId)
                payload.put("capturedAt", data?.getLongExtra("capturedAt", System.currentTimeMillis()) ?: System.currentTimeMillis())
                payload.put("sdkVersion", data?.getStringExtra("sdkVersion") ?: "android_amplify_liveness_v1")
                payload.put("captureMode", data?.getStringExtra("captureMode") ?: "aws_rekognition_liveness")
                payload.put("sdkMode", data?.getStringExtra("sdkMode") ?: "android_amplify")
            } else {
                payload.put("errorCode", data?.getStringExtra("errorCode") ?: "facepag_cancelled")
                payload.put("message", data?.getStringExtra("message") ?: "Captura FacePag cancelada")
            }
        } catch (e: Exception) {
            payload.put("success", false)
            payload.put("errorCode", "facepag_result_parse_error")
            payload.put("message", "Erro: ${e.message}")
        }
        dispatchFacePagResultToWeb(payload)
        pendingFacePagReferenceId = null; pendingFacePagAmountCents = null
    }

    private val appCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        appCameraPermissionReady = granted
        if (!granted) {
            Toast.makeText(this, "Permissão de câmera necessária.", Toast.LENGTH_LONG).show()
            notifyWebCameraError("camera_permission_denied_on_startup")
            return@registerForActivityResult
        }
        continueAppStartup()
    }

    private val webCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingCameraPermissionRequest
        pendingCameraPermissionRequest = null
        if (request == null) return@registerForActivityResult
        if (!granted) {
            request.deny()
            notifyWebCameraError("camera_permission_denied_for_webview")
            return@registerForActivityResult
        }
        val allowedResources = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }.toTypedArray()
        if (allowedResources.isEmpty()) { request.deny(); notifyWebCameraError("camera_resource_missing"); return@registerForActivityResult }
        try { request.grant(allowedResources) } catch (e: Exception) { request.deny(); notifyWebCameraError("camera_grant_failed") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WebView.setWebContentsDebuggingEnabled(true)

        storage = LocalStorageManager(this)
        printerManager = PrinterManager(this)

        // ✅ Registra receiver USB ANTES de tudo
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // ✅ Monitor do pinpad — solicita permissão automaticamente ao detectar pinpad
        pinPadStateMonitor = PinPadStateMonitor(
            context = this,
            onPinPadConnected = { device ->
                Log.i("PINPAD_MONITOR", "Pinpad conectado vendor=${device.vendorId} — solicitando permissão USB")
                // 🔎 LOG: pinpad detectado/conectado
                sendLog("pinpad", "pinpad_conectado", JSONObject().put("vendor", device.vendorId))
                handler.post {
                    requestUsbPermissionsIfNeeded(force = true) {
                        Log.i("PINPAD_MONITOR", "✅ Permissão USB OK para pinpad vendor=${device.vendorId}")
                    }
                }
            },
            onPinPadDisconnected = { device ->
                Log.w("PINPAD_MONITOR", "⚠️ Pinpad desconectado vendor=${device.vendorId}")
                // 🔎 LOG: pinpad desconectado
                sendLog("pinpad", "pinpad_desconectado", JSONObject().put("vendor", device.vendorId), "warn")
            }
        )
        pinPadStateMonitor.start()

        // ✅ Solicita permissão USB para pinpad já conectado na inicialização
        handler.postDelayed({
            requestUsbPermissionsIfNeeded(force = false) {
                Log.i("PINPAD_MONITOR", "Permissão USB inicial OK")
            }
        }, 500L)

        paymentProvider = PaymentProvider(
            context = this,
            storage = storage,
            launcher = sitefLauncher,
            onStateChange = { state -> handlePaymentState(state) },
            onResult = { result ->
                val payload = JSONObject().apply {
                    put("success", result.success)
                    put("paymentMethod", pendingPaymentMethod ?: "")
                    put("referenceId", pendingReferenceId ?: "")
                    put("amountCents", pendingAmountCents ?: 0)
                    put("codResp", result.codResp ?: "")
                    put("codTrans", result.codTrans ?: "")
                    put("bandeira", result.bandeira ?: "")
                    put("nsuSitef", result.nsuSitef ?: "")
                    put("nsuHost", result.nsuHost ?: "")
                    put("codAutorizacao", result.codAutorizacao ?: "")
                    put("viaEstabelecimento", result.viaEstabelecimento ?: "")
                    put("viaCliente", result.viaCliente ?: "")
                    if (!result.success) put("message", result.errorMessage ?: "Pagamento não aprovado")
                }
                dispatchSitefResultToWeb(payload)
                pendingPaymentMethod = null; pendingReferenceId = null; pendingAmountCents = null
            },
            // Só dispara quando o ciclo nativo ficou comprovadamente travado
            // (abort não drenou na carência, ou configure -9/-12). Reinicia o
            // processo para resetar a libclisitef.so. NÃO é o caminho do abort comum.
            onUnrecoverable = { restartForNativeReset() }
        )

        printerManager.initialize(
            onReady = { Log.d("STTotem", "Impressora OK") },
            onError = { error -> Log.e("STTotem", "Erro impressora: $error") }
        )

        appVersionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" } catch (_: Exception) { "1.0.0" }

        keepScreenAwake()
        setupFullscreen()
        setupRetry()
        setupBackPress()
        ensureCameraPermissionAndStart()

        // 🔎 LOG: app aberto (boot). Confirma que o logging está vivo no totem.
        sendLog("init", "app_aberto", JSONObject().put("versao", appVersionName))
    }

    private fun requestUsbPermissionsIfNeeded(force: Boolean = false, onDone: () -> Unit) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList

        if (devices.isEmpty()) {
            Log.d("USB", "Nenhum device USB")
            // 🔎 LOG: nenhum device USB presente
            sendLog("pinpad", "usb_lista", JSONObject().put("qtd", 0).put("temPinpad", false), "warn")
            onDone()
            return
        }

        Log.d("USB", "Devices USB: ${devices.values.joinToString { "vendor=${it.vendorId} path=${it.deviceName} hasPerm=${usbManager.hasPermission(it)}" }}")
        // 🔎 LOG: lista de USB presente (com/sem pinpad)
        sendLog("pinpad", "usb_lista",
            JSONObject()
                .put("qtd", devices.size)
                .put("temPinpad", devices.values.any { it.vendorId == PINPAD_VENDOR_ID })
                .put("devices", devices.values.joinToString { "v=${it.vendorId}/perm=${usbManager.hasPermission(it)}" }))

        val devicesNeedingPermission = if (force) {
            devices.values.toList()
        } else {
            devices.values.filter { !usbManager.hasPermission(it) }
        }

        if (devicesNeedingPermission.isEmpty()) {
            Log.d("USB", "Todos os devices têm permissão")
            onDone()
            return
        }

        val device = devicesNeedingPermission
            .sortedByDescending { it.vendorId == PINPAD_VENDOR_ID }
            .first()

        Log.d("USB", "Solicitando permissão para vendor=${device.vendorId} path=${device.deviceName}")

        usbPermissionCallback = onDone

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, permissionIntent)

        handler.postDelayed({
            if (usbPermissionCallback != null) {
                Log.w("USB", "Timeout USB — seguindo mesmo assim")
                // 🔎 LOG: permissão de USB não respondida (timeout) — sintoma de "pinpad não abriu"
                sendLog("pinpad", "usb_timeout",
                    JSONObject().put("aviso", "permissao USB nao respondida em 10s"), "warn")
                usbPermissionCallback?.invoke()
                usbPermissionCallback = null
            }
        }, 10_000L)
    }

    // ✅ Abort chamado pelo frontend (botão cancelar PIX / cliente desiste)
    // ABORT GRACIOSO: apenas sinaliza o cancelamento. O CliSitefManager drena o
    // laço (continueTransaction durante o abort) e o onTransactionResult fecha o
    // ciclo nativo limpo, liberando o slot e devolvendo o resultado ao frontend.
    // NÃO reinicia o processo aqui — fazer isso matava o app no meio da drenagem,
    // deixando a libclisitef.so com a rotina iterativa pendente (causa do -12 na
    // venda seguinte). O restart agora só ocorre se a drenagem comprovadamente
    // travar, via callback onUnrecoverable -> restartForNativeReset().
    private fun abortSitefPayment() {
        Log.d("CLISITEF", "abortSitefPayment chamado pelo frontend — abort gracioso (drena + finaliza)")
        paymentProvider.abort()
    }

    // 🆘 Último recurso: o ciclo nativo travou de verdade (abort não drenou na
    // carência, ou configure retornou -9/-12). Como a libclisitef.so é única no
    // processo e mantém estado iterativo, a forma confiável de resetá-la é
    // reiniciar o processo — equivalente a fechar e reabrir o app.
    private fun restartForNativeReset() {
        runOnUiThread {
            Log.w("CLISITEF", "Ciclo nativo irrecuperável — reiniciando processo para resetar a libclisitef.so")
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finishAffinity()
            } catch (e: Exception) {
                Log.e("CLISITEF", "Falha ao reiniciar app: ${e.message}")
            }
        }
    }

    private fun handlePaymentState(state: PaymentState) {
        when (state) {
            is PaymentState.Processing -> Log.d("CLISITEF", "Processando...")
            is PaymentState.ShowMessage -> {
                Log.d("CLISITEF", "Mensagem: ${state.message}")
                val script = "(function(){try{window.dispatchEvent(new CustomEvent('sitef-message',{detail:{message:${JSONObject.quote(state.message)}}}));}catch(e){}})();"
                runOnUiThread { binding.webView.evaluateJavascript(script, null) }
            }
            is PaymentState.ShowQRCode -> {
                Log.d("CLISITEF", "QR Code PIX recebido")
                val script = "(function(){try{window.dispatchEvent(new CustomEvent('sitef-qrcode',{detail:{qrData:${JSONObject.quote(state.qrData)}}}));}catch(e){}})();"
                runOnUiThread { binding.webView.evaluateJavascript(script, null) }
            }
            is PaymentState.HideQRCode -> {
                Log.d("CLISITEF", "Esconder QR Code")
                val script = "(function(){try{window.dispatchEvent(new CustomEvent('sitef-hide-qrcode',{}));}catch(e){}})();"
                runOnUiThread { binding.webView.evaluateJavascript(script, null) }
            }
            is PaymentState.PixCountdown -> Log.d("CLISITEF", "PIX countdown: ${state.seconds}s")
            is PaymentState.Finished -> Log.d("CLISITEF", "Finalizado: success=${state.success} code=${state.resultCode}")
            else -> {}
        }
    }

    override fun onResume() {
        super.onResume()
        setupFullscreen()
        if (bootstrapLoaded) { startHeartbeatLoop(); startPrintQueueLoop() }
    }

    override fun onPause() {
        super.onPause()
        stopHeartbeatLoop(); stopPrintQueueLoop(); cancelPrintTimeout()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeatLoop(); stopPrintQueueLoop(); cancelPrintTimeout()
        printExecutor.shutdownNow()
        pendingCameraPermissionRequest = null
        pinPadStateMonitor.stop()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    private fun ensureCameraPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) { appCameraPermissionReady = true; continueAppStartup(); return }
        appCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun continueAppStartup() {
        setupWebView()
        if (!storage.isActivated()) { goToActivation(); return }
        bootstrapWithToken()
    }

    private fun goToActivation() {
        startActivity(Intent(this, ActivationActivity::class.java))
        finish()
    }

    private fun bootstrapWithToken() {
        val token = storage.getActivationToken()
        if (token.isNullOrBlank()) { goToActivation(); return }
        if (!isOnline()) { showError("Sem conexão", "Conecte o dispositivo à internet."); return }
        showLoadingState()
        bootstrapRepository.bootstrap(
            activationToken = token,
            onSuccess = { result ->
                runOnUiThread {
                    storage.saveTotemId(result.totemId)
                    storage.saveCompanyId(result.companyId)
                    storage.saveLocationId(result.locationId)
                    storage.saveIdentifier(result.identifier)
                    storage.saveSitefOtp(result.sitefOtp)
                    bootstrapLoaded = true
                    sendEvent("app_opened", JSONObject().put("mode", "token_bootstrap"))
                    startHeartbeatLoop(); startPrintQueueLoop()
                    loadKioskWithToken(token)
                }
            },
            onError = { message ->
                runOnUiThread {
                    bootstrapLoaded = false
                    stopHeartbeatLoop(); stopPrintQueueLoop()
                    storage.clearActivation()
                    showError("Ativação inválida", if (message.isBlank()) "Ative o totem novamente." else message)
                    handler.postDelayed({ goToActivation() }, 1200L)
                }
            }
        )
    }

    private fun pollPrintQueue() {
        if (isPrintingNow) return
        val token = storage.getActivationToken() ?: return
        printQueueRepository.fetchPendingJobs(
            activationToken = token,
            onSuccess = { jobs ->
                if (jobs.isEmpty()) return@fetchPendingJobs
                val job = jobs.first()
                isPrintingNow = true
                printQueueRepository.updateJobStatus(
                    activationToken = token, jobId = job.id, status = "printing",
                    onSuccess = {
                        val timeoutMs = computePrintTimeoutMs(job)
                        dispatchPrintStatusToWeb(status = "started", job = job, message = "Imprimindo")
                        val timeoutRunnable = Runnable {
                            dispatchPrintStatusToWeb(status = "failed", job = job, message = "Timeout", error = "timeout")
                            printQueueRepository.updateJobStatus(activationToken = token, jobId = job.id, status = "failed", errorMessage = "timeout", onSuccess = { isPrintingNow = false }, onError = { isPrintingNow = false })
                        }
                        currentPrintTimeoutRunnable = timeoutRunnable
                        printTimeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
                        printExecutor.execute {
                            printerManager.printReceiptJob(
                                job = job,
                                onSuccess = {
                                    cancelPrintTimeout()
                                    printQueueRepository.updateJobStatus(activationToken = token, jobId = job.id, status = "printed",
                                        onSuccess = { isPrintingNow = false; dispatchPrintStatusToWeb(status = "completed", job = job, message = "Concluído") },
                                        onError = { isPrintingNow = false; dispatchPrintStatusToWeb(status = "failed", job = job, message = "Falha", error = it) })
                                },
                                onError = { error ->
                                    cancelPrintTimeout()
                                    dispatchPrintStatusToWeb(status = "failed", job = job, message = "Falha", error = error)
                                    printQueueRepository.updateJobStatus(activationToken = token, jobId = job.id, status = "failed", errorMessage = error, onSuccess = { isPrintingNow = false }, onError = { isPrintingNow = false })
                                }
                            )
                        }
                    },
                    onError = { isPrintingNow = false; dispatchPrintStatusToWeb(status = "failed", job = job, message = "Falha ao iniciar", error = "update_status_printing_failed") }
                )
            },
            onError = { error -> Log.e("STTotem", "Erro fila impressão: $error") }
        )
    }

    private fun computePrintTimeoutMs(job: PrintJob): Long {
        val ticketsCount = job.payload?.unit_tickets?.size ?: 0
        return (printBaseTimeoutMs + (ticketsCount * printPerVoucherTimeoutMs) + printSummaryBufferMs).coerceAtMost(printMaxTimeoutMs)
    }

    private fun dispatchPrintStatusToWeb(status: String, job: PrintJob, message: String? = null, error: String? = null) {
        try {
            val payload = JSONObject().apply {
                put("status", status); put("jobId", job.id); put("type", job.type ?: "")
                put("printMode", job.print_mode ?: ""); put("orderId", job.order_id ?: "")
                put("ticketCount", job.payload?.unit_tickets?.size ?: 0)
                put("itemsCount", job.payload?.items?.size ?: 0)
                put("message", message ?: ""); put("error", error ?: "")
            }
            val escapedJson = JSONObject.quote(payload.toString())
            val script = "(function(){try{const d=JSON.parse($escapedJson);window.dispatchEvent(new CustomEvent('totem-print-status',{detail:d}));}catch(e){}})();"
            runOnUiThread { binding.webView.evaluateJavascript(script, null) }
        } catch (e: Exception) { Log.e("PRINT_DEBUG", "Erro despachar status", e) }
    }

    private fun startPrintQueueLoop() { printQueueHandler.removeCallbacks(printQueueRunnable); printQueueHandler.post(printQueueRunnable) }
    private fun stopPrintQueueLoop() { printQueueHandler.removeCallbacks(printQueueRunnable) }
    private fun cancelPrintTimeout() { currentPrintTimeoutRunnable?.let { printTimeoutHandler.removeCallbacks(it) }; currentPrintTimeoutRunnable = null }
    private fun keepScreenAwake() { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun setupRetry() { binding.retryButton.setOnClickListener { if (!storage.isActivated()) goToActivation() else bootstrapWithToken() } }
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (binding.webView.canGoBack()) binding.webView.goBack() }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            // Cache desligado intencionalmente (decisão pós bundle-stale em produção):
            // a WebView do kiosk estava servindo bundle JS antigo em cache, fazendo o
            // totem alternar entre o fluxo novo (prepare-order) e o legacy. clearCache +
            // LOAD_NO_CACHE forçam a WebView a sempre carregar o build publicado mais recente.
            clearCache(true)
            webViewClient = kioskWebViewClient()
            webChromeClient = TotemWebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
    }

    private inner class TotemWebChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null) Log.d("STTotem-WEB-CONSOLE", "${consoleMessage.message()} -- line ${consoleMessage.lineNumber()} @ ${consoleMessage.sourceId()}")
            return super.onConsoleMessage(consoleMessage)
        }
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            runOnUiThread {
                val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!wantsVideo) { request.deny(); return@runOnUiThread }
                val cameraGranted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (cameraGranted) {
                    try { request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) }
                    catch (e: Exception) { request.deny(); notifyWebCameraError("camera_grant_exception") }
                    return@runOnUiThread
                }
                pendingCameraPermissionRequest?.deny()
                pendingCameraPermissionRequest = request
                webCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun kioskWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                showWebView(); setupFullscreen(); enterKioskModeOnce()
                sendEvent("webview_loaded", JSONObject().put("url", url ?: ""))
                val permissionsPolyfill = "(function(){try{if(!navigator.permissions||typeof navigator.permissions.query!=='function'){navigator.permissions={query:function(desc){return Promise.resolve({state:'granted',name:desc&&desc.name});}};}else{var _orig=navigator.permissions.query.bind(navigator.permissions);navigator.permissions.query=function(desc){if(desc&&(desc.name==='camera'||desc.name==='microphone')){return Promise.resolve({state:'granted',name:desc.name});}return _orig(desc).catch(function(){return Promise.resolve({state:'granted',name:desc&&desc.name});});};}}catch(e){}})();"
                view?.evaluateJavascript(permissionsPolyfill, null)
                val getUserMediaPolyfill = "(function(){try{if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia)return;var _orig=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=function(constraints){return _orig(constraints).catch(function(err){var errName=err&&(err.name||(err.constructor&&err.constructor.name)||String(err));if(errName==='OverconstrainedError'||errName==='ConstraintNotSatisfiedError'){return _orig({video:true,audio:false});}if(errName==='NotAllowedError'||errName==='PermissionDeniedError'){return new Promise(function(resolve,reject){setTimeout(function(){_orig({video:true,audio:false}).then(resolve).catch(reject);},800);});}throw err;});};}catch(e){}})();"
                view?.evaluateJavascript(getUserMediaPolyfill, null)
                super.onPageFinished(view, url)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                showError("Erro ao carregar", "Não foi possível abrir o totem.")
                super.onReceivedError(view, request, error)
            }
        }
    }

    private fun loadKioskWithToken(token: String) {
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        // Cache-bust por versão do APK: cada release muda o &v=, forçando a WebView
        // a tratar como URL nova e descartar qualquer cache residual. Usa packageManager
        // (já usado para appVersionName) para não depender de import de BuildConfig.
        val versionCode = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (_: Exception) { 0 }
        binding.webView.loadUrl("$kioskWebUrl?token=$encodedToken&v=$versionCode")
    }

    private fun dispatchSitefResultToWeb(payload: JSONObject) {
        val escapedJson = JSONObject.quote(payload.toString())
        val script = "(function(){try{const d=JSON.parse($escapedJson);window.dispatchEvent(new CustomEvent('sitef-payment-result',{detail:d}));}catch(e){}})();"
        runOnUiThread { binding.webView.evaluateJavascript(script, null) }
    }

    private fun dispatchFacePagResultToWeb(payload: JSONObject) {
        val escapedJson = JSONObject.quote(payload.toString())
        val script = "(function(){try{const d=JSON.parse($escapedJson);window.dispatchEvent(new CustomEvent('facepag-liveness-result',{detail:d}));}catch(e){}})();"
        runOnUiThread { binding.webView.evaluateJavascript(script, null) }
    }

    private fun notifyWebCameraError(reason: String) {
        val escapedReason = JSONObject.quote(reason)
        val script = "(function(){try{window.dispatchEvent(new CustomEvent('android-camera-error',{detail:{reason:JSON.parse($escapedReason)}}));}catch(e){}})();"
        runOnUiThread { binding.webView.evaluateJavascript(script, null) }
    }

    private fun startHeartbeatLoop() { handler.removeCallbacks(heartbeatRunnable); handler.post(heartbeatRunnable) }
    private fun stopHeartbeatLoop() { handler.removeCallbacks(heartbeatRunnable) }

    private fun sendHeartbeat() {
        val token = storage.getActivationToken()
        val identifier = storage.getIdentifier()
        val bodyJson = JSONObject().put("status", "online").put("network_type", getNetworkType()).put("app_version", appVersionName)
        if (!identifier.isNullOrBlank()) bodyJson.put("identifier", identifier)
        val req = Request.Builder().url("$functionsBaseUrl/totem-heartbeat").post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!token.isNullOrBlank()) req.addHeader("x-activation-token", token)
        httpClient.newCall(req.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun sendEvent(type: String, payload: JSONObject = JSONObject()) {
        val token = storage.getActivationToken()
        val identifier = storage.getIdentifier()
        val bodyJson = JSONObject().put("type", type).put("payload", payload)
        if (!identifier.isNullOrBlank()) bodyJson.put("identifier", identifier)
        val req = Request.Builder().url("$functionsBaseUrl/totem-events").post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!token.isNullOrBlank()) req.addHeader("x-activation-token", token)
        httpClient.newCall(req.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    /**
     * Envia um log para o backend (Supabase /totem-log).
     * Assíncrono e fire-and-forget: nunca bloqueia o fluxo, e qualquer erro é
     * engolido em silêncio. NUNCA passar dado sensível (PAN, senha, CVV, trilha).
     */
    private fun sendLog(
        stage: String,
        event: String,
        detail: JSONObject = JSONObject(),
        severity: String = "info"
    ) {
        try {
            val identifier = storage.getIdentifier()
            val bodyJson = JSONObject()
                .put("stage", stage)
                .put("event", event)
                .put("detail", detail)
                .put("severity", severity)
            if (!identifier.isNullOrBlank()) bodyJson.put("totem_id", identifier)

            val req = Request.Builder()
                .url("$functionsBaseUrl/totem-log")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            httpClient.newCall(req.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}          // ignora em silêncio
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        } catch (_: Exception) { /* log nunca trava o app */ }
    }

    private fun showLoadingState() { binding.webView.visibility = View.VISIBLE; binding.errorContainer.visibility = View.GONE }
    private fun showWebView() { binding.webView.visibility = View.VISIBLE; binding.errorContainer.visibility = View.GONE }
    private fun showError(title: String, message: String) { binding.webView.visibility = View.GONE; binding.errorContainer.visibility = View.VISIBLE; binding.errorTitle.text = title; binding.errorMessage.text = message }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "unknown") ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun isCurrentlyInLockTaskMode(): Boolean {
        return try { val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE } catch (_: Exception) { false }
    }

    private fun enterKioskModeOnce() {
        if (!kioskModeEnabled || kioskModeStartedOnce) return
        if (isCurrentlyInLockTaskMode()) { kioskModeStartedOnce = true; return }
        try { startLockTask(); kioskModeStartedOnce = true } catch (e: Exception) { Log.e("STTotem", "Falha kiosk", e) }
    }

    private fun enterKioskMode() {
        if (!kioskModeEnabled) return
        try { if (!isCurrentlyInLockTaskMode()) startLockTask(); kioskModeStartedOnce = true } catch (e: Exception) { Log.e("STTotem", "Falha kiosk", e) }
    }

    private fun exitKioskMode() {
        try { stopLockTask(); kioskModeStartedOnce = false } catch (e: Exception) { Log.e("STTotem", "Falha sair kiosk", e) }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun getActivationToken(): String = storage.getActivationToken() ?: ""

        @JavascriptInterface
        fun getDeviceSerial(): String {
            @Suppress("DEPRECATION")
            return try { Build.SERIAL ?: "unknown" } catch (_: Exception) { "unknown" }
        }

        @JavascriptInterface
        fun clearActivation() {
            runOnUiThread {
                storage.clearActivation(); bootstrapLoaded = false
                stopHeartbeatLoop(); stopPrintQueueLoop(); cancelPrintTimeout()
                goToActivation()
            }
        }

        @JavascriptInterface
        fun isSitefAvailable(): Boolean = paymentProvider.isSitefAvailable()

        @JavascriptInterface
        fun isFacePagAvailable(): Boolean = false

        @JavascriptInterface
        fun startFacePagLiveness(referenceId: String, amountCents: Int, livenessSessionId: String, region: String, identityPoolId: String): Boolean {
            val hasPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                dispatchFacePagResultToWeb(JSONObject().put("success", false).put("referenceId", referenceId).put("amountCents", amountCents).put("errorCode", "camera_permission_missing").put("message", "Permissão de câmera não concedida"))
                return false
            }
            pendingFacePagReferenceId = referenceId; pendingFacePagAmountCents = amountCents
            val intent = Intent(this@MainActivity, FacePagCameraActivity::class.java).apply {
                putExtra("referenceId", referenceId); putExtra("amountCents", amountCents)
                putExtra("livenessSessionId", livenessSessionId)
                putExtra("region", region.ifBlank { "us-east-1" })
                putExtra("identityPoolId", identityPoolId)
            }
            facePagLauncher.launch(intent)
            return true
        }

        @JavascriptInterface
        fun cancelFacePagLiveness() { FacePagCameraActivity.requestCancel() }

        @JavascriptInterface
        fun startSitefPayment(paymentMethod: String, amountCents: Int, referenceId: String): Boolean {
            pendingPaymentMethod = paymentMethod
            pendingReferenceId = referenceId
            pendingAmountCents = amountCents
            runOnUiThread {
                val forceUsbRefresh = paymentProvider.hadMidTransactionAbort()
                if (forceUsbRefresh) {
                    Log.d("USB", "hadMidTransactionAbort=true — forçando re-permissão USB")
                    paymentProvider.clearMidTransactionAbortFlag()
                }
                requestUsbPermissionsIfNeeded(force = forceUsbRefresh) {
                    val started = paymentProvider.startPayment(paymentMethod, amountCents, referenceId)
                    if (!started) {
                        val message = paymentProvider.getPaymentReadinessError() ?: "Não foi possível iniciar o pagamento"
                        dispatchSitefResultToWeb(JSONObject().put("success", false).put("paymentMethod", paymentMethod).put("referenceId", referenceId).put("amountCents", amountCents).put("message", message))
                    }
                }
            }
            return true
        }

        @JavascriptInterface
        fun abortSitefPayment() {
            Log.d("CLISITEF", "abortSitefPayment chamado pelo frontend")
            this@MainActivity.abortSitefPayment()
        }

        @JavascriptInterface
        fun enableKioskMode() { runOnUiThread { kioskModeEnabled = true; kioskModeStartedOnce = false; enterKioskMode() } }

        @JavascriptInterface
        fun disableKioskMode() { runOnUiThread { kioskModeEnabled = false; exitKioskMode() } }

        @JavascriptInterface
        fun isKioskModeEnabled(): Boolean = kioskModeEnabled
    }
}
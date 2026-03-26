package br.com.st.totem

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import br.com.st.totem.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: LocalStorageManager

    private val httpClient = OkHttpClient()
    private val bootstrapRepository = BootstrapRepository()
    private val handler = Handler(Looper.getMainLooper())

    private val functionsBaseUrl = "https://buviakhfibcsamucnjwu.supabase.co/functions/v1"
    private val kioskWebUrl = "https://totemst.lovable.app/totem"

    private var timeoutSeconds: Int = 60
    private var appVersionName: String = "1.0.0"
    private var bootstrapLoaded = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = LocalStorageManager(this)

        appVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        keepScreenAwake()
        setupFullscreen()
        setupWebView()
        setupRetry()
        setupBackPress()

        if (!storage.isActivated()) {
            goToActivation()
            return
        }

        bootstrapWithToken()
    }

    override fun onResume() {
        super.onResume()
        setupFullscreen()
        if (bootstrapLoaded) {
            startHeartbeatLoop()
        }
    }

    override fun onPause() {
        super.onPause()
        stopHeartbeatLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeatLoop()
    }

    private fun goToActivation() {
        val intent = Intent(this, ActivationActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun bootstrapWithToken() {
        val token = storage.getActivationToken()

        if (token.isNullOrBlank()) {
            goToActivation()
            return
        }

        if (!isOnline()) {
            showError(
                title = "Sem conexão",
                message = "Conecte o dispositivo à internet e tente novamente."
            )
            return
        }

        showLoadingState()

        bootstrapRepository.bootstrap(
            activationToken = token,
            onSuccess = { result ->
                runOnUiThread {
                    storage.saveTotemId(result.totemId)
                    storage.saveCompanyId(result.companyId)
                    storage.saveLocationId(result.locationId)
                    storage.saveIdentifier(result.identifier)

                    bootstrapLoaded = true

                    sendEvent("app_opened", JSONObject().put("mode", "token_bootstrap"))
                    startHeartbeatLoop()
                    loadKiosk()
                }
            },
            onError = { message ->
                runOnUiThread {
                    bootstrapLoaded = false
                    stopHeartbeatLoop()
                    storage.clearActivation()
                    showError(
                        title = "Ativação inválida",
                        message = if (message.isBlank()) {
                            "Não foi possível validar a ativação. Ative o totem novamente."
                        } else {
                            message
                        }
                    )

                    handler.postDelayed({
                        goToActivation()
                    }, 1200L)
                }
            }
        )
    }

    private fun keepScreenAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupRetry() {
        binding.retryButton.setOnClickListener {
            if (!storage.isActivated()) {
                goToActivation()
            } else {
                bootstrapWithToken()
            }
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            webViewClient = kioskWebViewClient()
            webChromeClient = WebChromeClient()

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.safeBrowsingEnabled = true

            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
    }

    private fun kioskWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                showWebView()
                setupFullscreen()
                sendEvent("webview_loaded", JSONObject().put("url", url ?: ""))
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                showError(
                    title = "Erro ao carregar",
                    message = "Não foi possível abrir a tela do totem."
                )

                sendEvent(
                    "webview_error",
                    JSONObject()
                        .put("code", error?.errorCode ?: -1)
                        .put("description", error?.description?.toString() ?: "")
                )

                super.onReceivedError(view, request, error)
            }
        }
    }

    private fun loadKiosk() {
        val identifier = storage.getIdentifier()

        if (identifier.isNullOrBlank()) {
            storage.clearActivation()
            goToActivation()
            return
        }

        val finalUrl = "$kioskWebUrl?identifier=$identifier"
        binding.webView.loadUrl(finalUrl)
    }

    private fun startHeartbeatLoop() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
    }

    private fun stopHeartbeatLoop() {
        handler.removeCallbacks(heartbeatRunnable)
    }

    private fun sendHeartbeat() {
        val token = storage.getActivationToken()
        val identifier = storage.getIdentifier()

        val bodyJson = JSONObject()
            .put("status", "online")
            .put("network_type", getNetworkType())
            .put("app_version", appVersionName)

        if (!identifier.isNullOrBlank()) {
            bodyJson.put("identifier", identifier)
        }

        val requestBody = bodyJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url("$functionsBaseUrl/totem-heartbeat")
            .post(requestBody)

        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("x-activation-token", token)
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Não trava UX
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun sendEvent(type: String, payload: JSONObject = JSONObject()) {
        val token = storage.getActivationToken()
        val identifier = storage.getIdentifier()

        val bodyJson = JSONObject()
            .put("type", type)
            .put("payload", payload)

        if (!identifier.isNullOrBlank()) {
            bodyJson.put("identifier", identifier)
        }

        val requestBody = bodyJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url("$functionsBaseUrl/totem-events")
            .post(requestBody)

        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("x-activation-token", token)
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Não trava UX
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun showLoadingState() {
        binding.webView.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showWebView() {
        binding.webView.visibility = View.VISIBLE
        binding.errorContainer.visibility = View.GONE
    }

    private fun showError(title: String, message: String) {
        binding.webView.visibility = View.GONE
        binding.errorContainer.visibility = View.VISIBLE
        binding.errorTitle.text = title
        binding.errorMessage.text = message
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "unknown"
        val capabilities = cm.getNetworkCapabilities(network) ?: return "unknown"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

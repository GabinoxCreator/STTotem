package br.com.st.totem

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.AmplifyConfiguration
import com.amplifyframework.ui.liveness.model.FaceLivenessDetectionException
import com.amplifyframework.ui.liveness.ui.FaceLivenessDetector
import org.json.JSONObject
import java.lang.ref.WeakReference

class FacePagCameraActivity : ComponentActivity() {

    private var livenessSessionId: String = ""
    private var referenceId: String = ""
    private var amountCents: Int = 0
    private var description: String = ""
    private var region: String = "us-east-1"
    private var identityPoolId: String = ""

    private var isCompleting = false

    companion object {
        @Volatile private var amplifyConfigured = false
        private var activeRef: WeakReference<FacePagCameraActivity>? = null

        fun requestCancel() {
            activeRef?.get()?.cancelAndFinish(
                errorCode = "cancelled_by_web",
                message   = "Captura FacePag cancelada pelo usuario"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        livenessSessionId = intent.getStringExtra("livenessSessionId").orEmpty()
        referenceId       = intent.getStringExtra("referenceId").orEmpty()
        amountCents       = intent.getIntExtra("amountCents", 0)
        description       = intent.getStringExtra("description").orEmpty()
        region            = intent.getStringExtra("region").orEmpty().ifBlank { "us-east-1" }
        identityPoolId    = intent.getStringExtra("identityPoolId").orEmpty()

        activeRef = WeakReference(this)

        Log.d("FACEPAG_CAMERA", "FacePagCameraActivity iniciada timestamp=${System.currentTimeMillis()}")
        Log.d("FACEPAG_CAMERA", "livenessSessionId = ${if (livenessSessionId.isNotBlank()) livenessSessionId else "AUSENTE"}")
        Log.d("FACEPAG_CAMERA", "region            = $region")
        Log.d("FACEPAG_CAMERA", "identityPoolId    = ${if (identityPoolId.isNotBlank()) identityPoolId else "AUSENTE"}")

        if (livenessSessionId.isBlank()) {
            Log.e("FACEPAG_CAMERA", "ERRO: livenessSessionId ausente - abortando")
            cancelAndFinish("missing_session_id", "sessionId nao recebido")
            return
        }

        if (identityPoolId.isBlank()) {
            Log.e("FACEPAG_CAMERA", "ERRO: identityPoolId ausente - abortando")
            cancelAndFinish("missing_identity_pool", "identityPoolId nao recebido")
            return
        }

        configureAmplifyIfNeeded(identityPoolId, region)

        setContent {
            MaterialTheme {
                // cameraId = "1" force-seleciona a camera frontal no SK-210
                // O SK-210 nao reporta LENS_FACING corretamente, entao o SDK
                // nao consegue descobrir automaticamente qual camera usar.
                // Testar "1" primeiro. Se ainda falhar, trocar para "0".
                FaceLivenessDetector(
                    sessionId  = livenessSessionId,
                    region     = region,
                    onComplete = {
                        Log.d("FACEPAG_CAMERA", "Liveness AWS concluido timestamp=${System.currentTimeMillis()}")
                        Log.d("FACEPAG_CAMERA", "livenessSessionId retornado = $livenessSessionId")
                        completeSuccess()
                    },
                    onError = { error: FaceLivenessDetectionException ->
                        val throwable = error as? Throwable
                        Log.e("FACEPAG_CAMERA", "ERRO liveness classe: ${error.javaClass.name}")
                        Log.e("FACEPAG_CAMERA", "ERRO liveness message: ${error.message}")
                        Log.e("FACEPAG_CAMERA", "ERRO liveness cause: ${throwable?.cause?.toString() ?: "null"}")
                        Log.e("FACEPAG_CAMERA", "ERRO liveness cause2: ${throwable?.cause?.cause?.toString() ?: "null"}")
                        cancelAndFinish(
                            errorCode = "liveness_detection_error",
                            message   = error.message ?: "Erro na deteccao de liveness"
                        )
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRef = null
    }

    private fun configureAmplifyIfNeeded(poolId: String, awsRegion: String) {
        if (amplifyConfigured) {
            Log.d("FACEPAG_AMPLIFY", "Amplify ja configurado - pulando")
            return
        }

        try {
            val configJson = JSONObject().apply {
                put("UserAgent", "aws-amplify-cli/2.0")
                put("Version",   "1.0")
                put("auth", JSONObject().apply {
                    put("plugins", JSONObject().apply {
                        put("awsCognitoAuthPlugin", JSONObject().apply {
                            put("CredentialsProvider", JSONObject().apply {
                                put("CognitoIdentity", JSONObject().apply {
                                    put("Default", JSONObject().apply {
                                        put("PoolId", poolId)
                                        put("Region", awsRegion)
                                    })
                                })
                            })
                        })
                    })
                })
            }

            Amplify.addPlugin(AWSCognitoAuthPlugin())
            val config = AmplifyConfiguration.builder(configJson).build()
            Amplify.configure(config, applicationContext)
            amplifyConfigured = true

            Log.d("FACEPAG_AMPLIFY", "Amplify configurado identityPoolId=***${poolId.takeLast(6)}")
        } catch (e: Exception) {
            Log.e("FACEPAG_AMPLIFY", "ERRO ao configurar Amplify: ${e.message}")
        }
    }

    private fun completeSuccess() {
        if (isCompleting) return
        isCompleting = true

        val result = Intent().apply {
            putExtra("success",           true)
            putExtra("sessionId",         livenessSessionId)
            putExtra("livenessSessionId", livenessSessionId)
            putExtra("referenceId",       referenceId)
            putExtra("amountCents",       amountCents)
            putExtra("description",       description)
            putExtra("capturedAt",        System.currentTimeMillis())
            putExtra("sdkVersion",        "android_amplify_liveness_v1")
            putExtra("captureMode",       "aws_rekognition_liveness")
            putExtra("sdkMode",           "android_amplify")
        }

        setResult(RESULT_OK, result)
        finish()
    }

    private fun cancelAndFinish(errorCode: String, message: String) {
        if (isCompleting) return
        isCompleting = true

        Log.w("FACEPAG_CAMERA", "cancelAndFinish errorCode=$errorCode message=$message")

        val result = Intent().apply {
            putExtra("success",     false)
            putExtra("referenceId", referenceId)
            putExtra("amountCents", amountCents)
            putExtra("description", description)
            putExtra("errorCode",   errorCode)
            putExtra("message",     message)
        }

        setResult(RESULT_CANCELED, result)
        finish()
    }
}
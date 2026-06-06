package br.com.st.totem

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ActivationActivity : AppCompatActivity() {

    private lateinit var inputCode: EditText
    private lateinit var btnActivate: Button
    private lateinit var txtError: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var storage: LocalStorageManager
    private val repository = ActivationRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        storage = LocalStorageManager(this)

        inputCode = findViewById(R.id.inputActivationCode)
        btnActivate = findViewById(R.id.btnActivate)
        txtError = findViewById(R.id.txtActivationError)
        progressBar = findViewById(R.id.progressActivation)

        btnActivate.setOnClickListener {
            activateTotem()
        }
    }

    private fun activateTotem() {
        val code = inputCode.text.toString().trim().uppercase()

        if (!Regex("^[A-Z0-9]{6}$").matches(code)) {
            txtError.visibility = View.VISIBLE
            txtError.text = "Código inválido. Use 6 caracteres alfanuméricos."
            return
        }

        txtError.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        btnActivate.isEnabled = false

        repository.activate(
            requestData = ActivationRequest(
                activation_code = code,
                app_version = getAppVersionName()
            ),
            onSuccess = { result ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnActivate.isEnabled = true

                    val token = result.activationToken ?: ""

                    if (token.isBlank()) {
                        txtError.visibility = View.VISIBLE
                        txtError.text = "Token de ativação não retornado."
                        return@runOnUiThread
                    }

                    storage.clearActivation()
                    storage.saveActivationToken(token)
                    storage.saveTotemId(result.totemId)
                    storage.saveCompanyId(result.companyId)
                    storage.saveLocationId(result.locationId)
                    storage.saveIdentifier(result.identifier)

                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            },
            onError = { message ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnActivate.isEnabled = true
                    txtError.visibility = View.VISIBLE
                    txtError.text = message.ifBlank { "Falha ao ativar o totem." }
                }
            }
        )
    }

    private fun getAppVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
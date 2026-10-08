package br.com.st.totem

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

/**
 * Ativação do totem (OS-203, 08/10/2026), desenho aprovado pelo Gabriel em 08/10
 * (`_docs/totem-ativacao-desenho/`, telas 1 a 12). Duas etapas numa tela só,
 * igual à maquininha:
 *
 *  • APARELHO (passo 1): sem credencial de aparelho. O código de 8 números do /st
 *    vira a device_key. Uma vez na vida do totem.
 *  • CONTA (passo 2): com credencial, sem conta. O código do painel do cliente
 *    põe o totem para vender naquela conta. A cada troca de cliente, sem mexer
 *    em OTP, terminal ou loja do SiTef.
 *
 * Todo erro numa FAIXA sob as casinhas, no mesmo lugar (o teclado não pula):
 * vermelho = o código tem problema; âmbar = espere, o totem tenta sozinho.
 * Funciona sem a página do quiosque carregar (é nativa).
 */
class ActivationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AVISO = "aviso"
        const val EXTRA_CONTA = "conta"
        const val AVISO_DESLIGADO = "desligado" // tirado da conta pelo painel
        const val AVISO_SAIU = "saiu"           // saiu da conta na área técnica

        fun abrir(context: Context, aviso: String? = null, conta: String? = null): Intent =
            Intent(context, ActivationActivity::class.java).apply {
                if (aviso != null) putExtra(EXTRA_AVISO, aviso)
                if (conta != null) putExtra(EXTRA_CONTA, conta)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

        private const val ESPERA_SEM_INTERNET_MS = 5_000L
        private const val ESPERA_SERVIDOR_MS = 8_000L
        private const val BLOQUEIO_MS = 10 * 60_000L
        private const val PRONTO_MS = 2_000L
    }

    private enum class Modo { APARELHO, CONTA }
    private enum class EstadoCodigo { NORMAL, ERRO, MORTO, ESPERA }

    private lateinit var storage: LocalStorageManager
    private val handler = Handler(Looper.getMainLooper())
    private var modo = Modo.CONTA
    private val codigo = StringBuilder()
    private var estadoCodigo = EstadoCodigo.NORMAL
    private var modoLetras = false
    private var conferindo = false
    private var bloqueadoAte = 0L
    private var retentativa: Runnable? = null
    /** Entrar na conta sem credencial de aparelho (totem ainda não cadastrado no /st). */
    private var semAparelho = false

    private lateinit var chip: TextView
    private lateinit var passos: LinearLayout
    private lateinit var icone: View
    private lateinit var titulo: TextView
    private lateinit var subtitulo: TextView
    private lateinit var casas: LinearLayout
    private lateinit var campoLetras: EditText
    private lateinit var faixa: TotemUi.Faixa
    private lateinit var link: TextView
    private lateinit var teclado: View
    private lateinit var botao: TextView
    private lateinit var girando: ProgressBar
    private lateinit var rodape: TextView

    private val relogio = object : Runnable {
        override fun run() {
            val falta = bloqueadoAte - System.currentTimeMillis()
            if (falta <= 0) {
                bloqueadoAte = 0
                faixa.esconder()
                estadoCodigo = EstadoCodigo.NORMAL
                pintar()
                return
            }
            faixa.relogio(mmss(falta))
            pintarBotao()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = LocalStorageManager(this)
        if (storage.isActivated()) { irParaOTotem(); return }

        montarTela()
        modo = if (storage.hasDeviceKey()) Modo.CONTA else Modo.APARELHO
        aplicarModo()

        val conta = intent.getStringExtra(EXTRA_CONTA)
        when (intent.getStringExtra(EXTRA_AVISO)) {
            AVISO_DESLIGADO -> faixa.mostrar(TotemUi.Tom.AMBAR,
                if (conta != null) "Este totem foi desligado da conta $conta pelo painel." else "Este totem foi desligado da conta pelo painel.",
                "As vendas feitas continuam no relatório da conta.", R.drawable.ic_tt_sair)
            AVISO_SAIU -> faixa.mostrar(TotemUi.Tom.VERDE,
                if (conta != null) "Totem fora da conta $conta" else "Totem fora da conta",
                "Para usar em outra conta, digite o código do próximo cliente.")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Number) = TotemUi.dp(this, v)

    // ── tela ────────────────────────────────────────────────────────────────

    private fun montarTela() {
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(TotemUi.FUNDO) }

        // Cabeçalho branco 100 dp: marca e chip.
        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(TotemUi.CARD)
            setPadding(dp(32), 0, dp(32), 0)
        }
        topo.addView(marca())
        topo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chip = TotemUi.texto(this, "", 19f, TotemUi.AZUL, TotemUi.Peso.NEGRITO).apply {
            setPadding(dp(19), dp(11), dp(19), dp(11))
            maxLines = 1
        }
        topo.addView(chip)
        raiz.addView(topo, LinearLayout.LayoutParams(MATCH, dp(100)))
        raiz.addView(View(this).apply { setBackgroundColor(TotemUi.LINHA) }, LinearLayout.LayoutParams(MATCH, dp(1.3f)))

        val miolo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(29), dp(32), dp(21))
        }
        raiz.addView(miolo, LinearLayout.LayoutParams(MATCH, 0, 1f))

        passos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        miolo.addView(passos, LinearLayout.LayoutParams(MATCH, WRAP))

        icone = FrameLayout(this).apply {
            background = TotemUi.caixa(this@ActivationActivity, TotemUi.LILAS, 23f)
            addView(ImageView(this@ActivationActivity).apply {
                setImageResource(R.drawable.ic_tt_totem)
                TotemUi.tingir(this, TotemUi.AZUL)
            }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        miolo.addView(icone, LinearLayout.LayoutParams(dp(60), dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })

        titulo = TotemUi.texto(this, "", 48f, TotemUi.TINTA, TotemUi.Peso.TITULO, centro = true)
        miolo.addView(titulo, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        subtitulo = TotemUi.texto(this, "", 23f, TotemUi.CINZA2, centro = true).apply { setLineSpacing(0f, 1.2f) }
        miolo.addView(subtitulo, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); marginStart = dp(48); marginEnd = dp(48) })

        casas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        miolo.addView(casas, LinearLayout.LayoutParams(MATCH, dp(93)).apply { topMargin = dp(16) })

        // Só para o código antigo, com letras (transição). Teclado do aparelho.
        campoLetras = EditText(this).apply {
            hint = "ABC123"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 43f)
            setTextColor(TotemUi.TINTA)
            setHintTextColor(TotemUi.CINZA)
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            background = TotemUi.caixa(this@ActivationActivity, TotemUi.CARD, 17f, TotemUi.CASA_CHEIA, 2f)
            visibility = View.GONE
            doAfterTextChanged { if (!conferindo) { faixa.esconder(); pintarBotao() } }
        }
        miolo.addView(campoLetras, LinearLayout.LayoutParams(MATCH, dp(93)).apply { topMargin = dp(27) })

        faixa = TotemUi.faixa(this)
        miolo.addView(faixa.vaga, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        link = TotemUi.texto(this, "", 20f, TotemUi.CINZA2, TotemUi.Peso.FORTE, centro = true).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { tocarLink() }
            TotemUi.toque(this)
        }
        miolo.addView(link, LinearLayout.LayoutParams(MATCH, dp(48)))

        miolo.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        teclado = TotemUi.teclado(this, aoDigito = { digito(it) }, aoApagar = { apagar() }, aoLimpar = { limpar() })
        miolo.addView(teclado, LinearLayout.LayoutParams(MATCH, WRAP))

        // Rodapé branco: botão de 107 dp e a identidade do aparelho.
        raiz.addView(View(this).apply { setBackgroundColor(TotemUi.LINHA) }, LinearLayout.LayoutParams(MATCH, dp(1.3f)))
        val pe = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TotemUi.CARD)
            setPadding(dp(32), dp(20), dp(32), dp(17))
        }
        val caixaBotao = FrameLayout(this)
        botao = TotemUi.texto(this, "", 31f, Color.WHITE, TotemUi.Peso.NEGRITO, centro = true).apply {
            setOnClickListener { confirmar() }
            TotemUi.toque(this)
        }
        caixaBotao.addView(botao, FrameLayout.LayoutParams(MATCH, MATCH))
        girando = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        caixaBotao.addView(girando, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(40) })
        pe.addView(caixaBotao, LinearLayout.LayoutParams(MATCH, dp(107)))
        rodape = TotemUi.texto(this, "", 17f, TotemUi.CINZA, TotemUi.Peso.MEDIO, centro = true).apply { maxLines = 1 }
        pe.addView(rodape, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(11) })
        raiz.addView(pe, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(raiz)
    }

    private fun marca() = TotemUi.texto(this, "", 36f, TotemUi.TINTA, TotemUi.Peso.TITULO).apply {
        text = SpannableString("FestPag").apply { setSpan(ForegroundColorSpan(TotemUi.AZUL), 4, 7, 0) }
    }

    private enum class EstadoPasso { FEITO, AGORA, DEPOIS }

    private fun pintarPassos() {
        passos.removeAllViews()
        val feito = modo == Modo.CONTA
        passos.addView(passo("1", "Aparelho", if (feito) EstadoPasso.FEITO else EstadoPasso.AGORA))
        passos.addView(View(this).apply { setBackgroundColor(if (feito) TotemUi.VERDE else 0xFFDCDBE6.toInt()) },
            LinearLayout.LayoutParams(dp(43), dp(2.7f)).apply { marginStart = dp(12); marginEnd = dp(12) })
        passos.addView(passo("2", "Conta do cliente", if (feito) EstadoPasso.AGORA else EstadoPasso.DEPOIS))
    }

    private fun passo(numero: String, rotulo: String, estado: EstadoPasso): View {
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val (corBola, corTexto) = when (estado) {
            EstadoPasso.FEITO -> TotemUi.VERDE to TotemUi.VERDE_TEXTO
            EstadoPasso.AGORA -> TotemUi.AZUL to TotemUi.AZUL
            EstadoPasso.DEPOIS -> 0xFFDCDBE6.toInt() to TotemUi.CINZA
        }
        val bola = FrameLayout(this).apply { background = TotemUi.circulo(corBola) }
        if (estado == EstadoPasso.FEITO) {
            bola.addView(ImageView(this).apply { setImageResource(R.drawable.ic_tt_check); TotemUi.tingir(this, Color.WHITE) },
                FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        } else {
            bola.addView(TotemUi.texto(this, numero, 19f, Color.WHITE, TotemUi.Peso.TITULO, centro = true), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        linha.addView(bola, LinearLayout.LayoutParams(dp(37), dp(37)))
        linha.addView(TotemUi.texto(this, rotulo, 20f, corTexto, TotemUi.Peso.NEGRITO), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        return linha
    }

    // ── modo ────────────────────────────────────────────────────────────────

    private fun aplicarModo() {
        cancelarRetentativa()
        codigo.clear()
        estadoCodigo = EstadoCodigo.NORMAL
        modoLetras = false
        campoLetras.setText("")
        faixa.esconder()
        pintarPassos()
        val tag = storage.getAssetTag()
        when (modo) {
            Modo.APARELHO -> {
                pintarChip("Aparelho novo", TotemUi.LILAS, TotemUi.AZUL, false)
                TotemUi.mostrar(icone, true)
                titulo.text = "Ativar este totem"
                subtitulo.text = "Digite o código de 8 números gerado no painel da FestPag. É feito uma vez só."
                link.text = "Ainda sem código do aparelho? Entrar direto na conta"
                rodape.text = "Aparelho ${DeviceIdentity.curto(DeviceIdentity.hardwareId(this, storage))} · app ${DeviceIdentity.appVersion(this)}"
            }
            Modo.CONTA -> {
                if (tag != null) pintarChip("Totem $tag", TotemUi.VERDE_FUNDO, TotemUi.VERDE_TEXTO, true)
                else pintarChip("Sem conta", TotemUi.FUNDO, TotemUi.CINZA2, false)
                TotemUi.mostrar(icone, false)
                titulo.text = "Entrar na conta"
                subtitulo.text = "Peça ao responsável o código de 8 números no painel do cliente."
                link.text = "Tenho um código com letras"
                val terminal = storage.getSitefTerminalId()
                rodape.text = listOfNotNull(tag?.let { "Totem $it" }, terminal?.let { "terminal $it" }, "pronto para vender").joinToString(" · ")
            }
        }
        mostrarLetras(false)
        pintar()
    }

    private fun pintarChip(texto: String, fundo: Int, cor: Int, comBolinha: Boolean) {
        chip.text = texto
        chip.setTextColor(cor)
        chip.background = TotemUi.caixa(this, fundo, 99f)
        if (comBolinha) {
            val b = TotemUi.circulo(TotemUi.VERDE).apply { val t = dp(12); setSize(t, t); setBounds(0, 0, t, t) }
            chip.setCompoundDrawablesRelative(b, null, null, null)
            chip.compoundDrawablePadding = dp(9)
        } else chip.setCompoundDrawablesRelative(null, null, null, null)
    }

    private fun tocarLink() {
        if (conferindo) return
        if (modo == Modo.APARELHO) {
            // Plano B: o totem entra na conta do jeito antigo e, na próxima
            // abertura, se registra sozinho como aparelho (adoção).
            semAparelho = true
            modo = Modo.CONTA
            aplicarModo()
            faixa.mostrar(TotemUi.Tom.AMBAR, "Entrando sem código do aparelho",
                "O totem se registra sozinho depois. Peça o código do painel do cliente.")
            return
        }
        mostrarLetras(!modoLetras)
        faixa.esconder()
        pintar()
    }

    private fun mostrarLetras(sim: Boolean) {
        modoLetras = sim
        TotemUi.mostrar(campoLetras, sim)
        TotemUi.mostrar(casas, !sim)
        TotemUi.mostrar(teclado, !sim)
        if (modo == Modo.CONTA) link.text = if (sim) "Voltar para o código de números" else "Tenho um código com letras"
        subtitulo.text = when {
            modo == Modo.APARELHO -> subtitulo.text
            sim -> "Digite o código de 6 letras e números do painel do cliente."
            else -> "Peça ao responsável o código de 8 números no painel do cliente."
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (sim) { campoLetras.requestFocus(); imm.showSoftInput(campoLetras, InputMethodManager.SHOW_IMPLICIT) }
        else imm.hideSoftInputFromWindow(campoLetras.windowToken, 0)
    }

    // ── teclado ─────────────────────────────────────────────────────────────

    private fun travado() = conferindo || bloqueadoAte > System.currentTimeMillis()

    private fun digito(d: Char) {
        if (travado()) return
        cancelarRetentativa()
        if (estadoCodigo == EstadoCodigo.MORTO) codigo.clear()
        if (codigo.length >= 8) return
        codigo.append(d)
        estadoCodigo = EstadoCodigo.NORMAL
        faixa.esconder()
        pintar()
    }

    private fun apagar() {
        if (travado() || codigo.isEmpty()) return
        cancelarRetentativa()
        if (estadoCodigo == EstadoCodigo.MORTO) codigo.clear() else codigo.deleteCharAt(codigo.length - 1)
        estadoCodigo = EstadoCodigo.NORMAL
        faixa.esconder()
        pintar()
    }

    private fun limpar() {
        if (travado()) return
        cancelarRetentativa()
        codigo.clear()
        estadoCodigo = EstadoCodigo.NORMAL
        faixa.esconder()
        pintar()
    }

    private fun pintar() {
        pintarCasas()
        val off = travado()
        teclado.alpha = if (off) 0.45f else 1f
        pintarBotao()
    }

    /** As 8 casinhas, 4 + 4 (67x93 dp). Cursor na da vez; cor conforme o estado. */
    private fun pintarCasas() {
        casas.removeAllViews()
        val daVez = if (estadoCodigo == EstadoCodigo.NORMAL && !travado()) codigo.length else -1
        for (i in 0 until 8) {
            if (i == 4) casas.addView(View(this), LinearLayout.LayoutParams(dp(19), 1))
            val d = codigo.getOrNull(i)?.toString().orEmpty()
            val (fundo, borda, cor) = when (estadoCodigo) {
                EstadoCodigo.ERRO -> Triple(0xFFFFFBFB.toInt(), TotemUi.CASA_ERRO, TotemUi.TINTA)
                EstadoCodigo.MORTO -> Triple(TotemUi.CARD, TotemUi.LINHA, TotemUi.CASA_MORTA)
                EstadoCodigo.ESPERA -> Triple(TotemUi.CARD, TotemUi.CASA_CHEIA, TotemUi.TEXTO)
                EstadoCodigo.NORMAL -> Triple(TotemUi.CARD, if (i == daVez) TotemUi.AZUL else if (d.isNotEmpty()) TotemUi.CASA_CHEIA else TotemUi.LINHA, TotemUi.TINTA)
            }
            casas.addView(TotemUi.texto(this, if (i == daVez) "|" else d, 51f, if (i == daVez) TotemUi.AZUL else cor, TotemUi.Peso.TITULO, centro = true).apply {
                background = TotemUi.caixa(this@ActivationActivity, fundo, 17f, borda, if (i == daVez) 2.7f else 2f)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(67), dp(93)).apply { marginStart = if (i == 0 || i == 4) 0 else dp(9) })
        }
    }

    private fun digitado(): String =
        if (modoLetras) campoLetras.text?.toString()?.trim()?.uppercase().orEmpty() else codigo.toString()

    private fun completo(): Boolean =
        if (modoLetras) digitado().matches(Regex("^[A-Z0-9]{6}$")) else digitado().matches(Regex("^[0-9]{8}$"))

    private fun pintarBotao() {
        if (!::botao.isInitialized) return
        val falta = bloqueadoAte - System.currentTimeMillis()
        val esperando = retentativa != null
        botao.text = when {
            falta > 0 -> "Aguarde ${mmss(falta)}"
            conferindo -> "Conferindo o código…"
            esperando -> "Tentar agora"
            modo == Modo.APARELHO -> "Ativar"
            else -> "Entrar na conta"
        }
        TotemUi.mostrar(girando, conferindo)
        val ligado = when {
            falta > 0 -> false
            conferindo -> true
            esperando -> true
            else -> completo() && estadoCodigo != EstadoCodigo.MORTO
        }
        TotemUi.cta(botao, ligado)
        if (conferindo) botao.isEnabled = false
    }

    // ── confirmar ───────────────────────────────────────────────────────────

    private fun confirmar() {
        if (travado()) return
        if (retentativa != null) { cancelarRetentativa() }
        if (!completo()) return
        val valor = digitado()
        conferindo = true
        estadoCodigo = EstadoCodigo.ESPERA
        pintar()
        if (modo == Modo.APARELHO) ativarAparelho(valor) else entrarNaConta(valor)
    }

    private fun ativarAparelho(valor: String) {
        DeviceActivateRepository().activate(
            code = valor,
            hardwareId = DeviceIdentity.hardwareId(this, storage),
            appVersion = DeviceIdentity.appVersion(this),
            onSuccess = { cred ->
                runOnUiThread {
                    conferindo = false
                    storage.saveDeviceKey(cred.deviceKey)
                    storage.saveAssetTag(cred.info?.assetTag)
                    cred.info?.terminal?.let { storage.saveSitefTerminalId(it) }
                    modo = Modo.CONTA
                    aplicarModo()
                    faixa.mostrar(TotemUi.Tom.VERDE, "${cred.info?.assetTag ?: "O totem"} está ativado",
                        "Agora, o código de 8 números da conta do cliente.")
                }
            },
            onError = { erro -> runOnUiThread { tratarErro(erro, valor) } }
        )
    }

    private fun entrarNaConta(valor: String) {
        val chave = if (semAparelho) null else storage.getDeviceKey()
        ActivationRepository().activate(
            requestData = ActivationRequest(activation_code = valor, app_version = DeviceIdentity.appVersion(this)),
            hardwareId = DeviceIdentity.hardwareId(this, storage),
            deviceKey = chave,
            onSuccess = { r ->
                runOnUiThread {
                    conferindo = false
                    val token = r.activationToken
                    if (token.isNullOrBlank()) {
                        tratarErro(ErroPorta("Token de ativação não retornado."), valor)
                        return@runOnUiThread
                    }
                    // Com aparelho: sai só a conta antiga (OTP, terminal e loja ficam).
                    // Sem aparelho: o "zera tudo" de sempre.
                    if (storage.hasDeviceKey()) storage.clearAccountLink() else storage.clearActivation()
                    storage.saveActivationToken(token)
                    storage.saveTotemId(r.totemId)
                    storage.saveCompanyId(r.companyId)
                    storage.saveLocationId(r.locationId)
                    storage.saveIdentifier(r.identifier)
                    storage.saveCompanyName(r.companyName)
                    storage.saveAssetTag(r.aparelho?.assetTag)
                    mostrarPronto(r.companyName, r.aparelho?.assetTag ?: storage.getAssetTag(), r.aparelho?.terminal ?: storage.getSitefTerminalId())
                }
            },
            onError = { erro -> runOnUiThread { tratarErro(erro, valor) } }
        )
    }

    /** Escolhe a faixa (vermelha ou âmbar) e o estado do código para cada erro. */
    private fun tratarErro(erro: ErroPorta, valor: String) {
        conferindo = false
        val msg = erro.mensagem.lowercase()
        val painel = if (modo == Modo.APARELHO) "no painel da FestPag" else "no painel do cliente"
        when {
            erro.semRede -> {
                estadoCodigo = EstadoCodigo.ESPERA
                faixa.mostrar(TotemUi.Tom.AMBAR, "Sem internet. Tentando de novo…",
                    "Confira o cabo ou o Wi-Fi. O código fica aqui, não precisa digitar de novo.", R.drawable.ic_tt_setas)
                agendarRetentativa(valor, ESPERA_SEM_INTERNET_MS)
            }
            erro.servidorInstavel -> {
                estadoCodigo = EstadoCodigo.ESPERA
                faixa.mostrar(TotemUi.Tom.AMBAR, "Não conseguimos falar com a FestPag agora",
                    "Tentando de novo… O problema é do nosso lado, o código está certo.", R.drawable.ic_tt_setas)
                agendarRetentativa(valor, ESPERA_SERVIDOR_MS)
            }
            erro.muitasTentativas -> {
                estadoCodigo = EstadoCodigo.MORTO
                codigo.clear()
                bloqueadoAte = System.currentTimeMillis() + BLOQUEIO_MS
                faixa.mostrar(TotemUi.Tom.AMBAR, "Muitas tentativas erradas", "Por segurança, o totem espera 10 minutos.",
                    R.drawable.ic_tt_relogio, mmss(BLOQUEIO_MS))
                handler.removeCallbacks(relogio)
                handler.postDelayed(relogio, 1000)
            }
            erro.codigo == "expired" || msg.contains("expirado") || msg.contains("venceu") -> morto("Este código venceu",
                "Gere outro $painel. Ao tocar no primeiro número, as casinhas se limpam.", R.drawable.ic_tt_relogio)
            erro.codigo == "already_used" || msg.contains("já foi usado") || msg.contains("já utilizado") -> morto("Este código já foi usado",
                "Cada código serve para um totem. Gere outro $painel.")
            erro.codigo == "revoked" || msg.contains("cancelado") || msg.contains("revogado") -> morto("Este código foi cancelado",
                "Gere outro $painel.")
            msg.contains("outro aparelho") -> morto("Este código é de outro aparelho",
                "No painel de aparelhos, gere o código para este totem: ${DeviceIdentity.curto(DeviceIdentity.hardwareId(this, storage))}.", R.drawable.ic_tt_troca)
            msg.contains("maquininha") -> morto("Este código é de uma maquininha", "Gere um código de totem $painel.")
            msg.contains("baixado") || msg.contains("não existe mais") -> morto("Este totem está fora do cadastro", "Chame o suporte da FestPag.")
            else -> {
                estadoCodigo = EstadoCodigo.ERRO
                faixa.mostrar(TotemUi.Tom.VERMELHO, "Código não encontrado",
                    "Confira os ${if (modoLetras) "6 caracteres" else "8 números"} $painel. Para corrigir, toque em apagar.")
            }
        }
        pintar()
    }

    private fun morto(titulo: String, texto: String, icone: Int? = null) {
        estadoCodigo = EstadoCodigo.MORTO
        faixa.mostrar(TotemUi.Tom.VERMELHO, titulo, texto, icone)
    }

    private fun agendarRetentativa(valor: String, ms: Long) {
        cancelarRetentativa()
        val r = Runnable {
            retentativa = null
            if (isFinishing) return@Runnable
            conferindo = true
            pintar()
            if (modo == Modo.APARELHO) ativarAparelho(valor) else entrarNaConta(valor)
        }
        retentativa = r
        handler.postDelayed(r, ms)
    }

    private fun cancelarRetentativa() {
        retentativa?.let { handler.removeCallbacks(it) }
        retentativa = null
    }

    // ── "Tudo pronto" (2 s) e o cardápio ────────────────────────────────────

    private fun mostrarPronto(conta: String?, tag: String?, terminal: String?) {
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(TotemUi.FUNDO) }
        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(TotemUi.CARD); setPadding(dp(32), 0, dp(32), 0)
        }
        topo.addView(marca())
        raiz.addView(topo, LinearLayout.LayoutParams(MATCH, dp(100)))

        val meio = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(43), 0, dp(43), 0)
        }
        val bola = FrameLayout(this).apply {
            background = TotemUi.circulo(TotemUi.VERDE)
            addView(ImageView(this@ActivationActivity).apply { setImageResource(R.drawable.ic_tt_check); TotemUi.tingir(this, Color.WHITE) },
                FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        }
        val aro = FrameLayout(this).apply {
            background = TotemUi.circulo(TotemUi.VERDE_FUNDO)
            addView(bola, FrameLayout.LayoutParams(dp(187), dp(187), Gravity.CENTER))
        }
        meio.addView(aro, LinearLayout.LayoutParams(dp(240), dp(240)))
        meio.addView(TotemUi.texto(this, "Tudo pronto", 64f, TotemUi.TINTA, TotemUi.Peso.TITULO, centro = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(48) })
        meio.addView(TotemUi.texto(this, "Este totem agora vende para", 21f, TotemUi.CINZA2, TotemUi.Peso.MEDIO, centro = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(15) })
        meio.addView(TotemUi.texto(this, conta ?: "a conta do cliente", 36f, TotemUi.AZUL, TotemUi.Peso.TITULO, centro = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        if (tag != null || terminal != null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = TotemUi.caixa(this@ActivationActivity, TotemUi.CARD, 24f, TotemUi.LINHA, 1.3f)
                setPadding(dp(27), dp(5), dp(27), dp(5))
            }
            if (tag != null) card.addView(TotemUi.linhaInfo(this, "Totem", tag, terminal != null))
            if (terminal != null) card.addView(TotemUi.linhaInfo(this, "Terminal de cartão", terminal, false))
            meio.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(40) })
        }
        raiz.addView(meio, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val pe = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(TotemUi.CARD)
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        pe.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(TotemUi.AZUL)
        }, LinearLayout.LayoutParams(MATCH, dp(11)))
        pe.addView(TotemUi.texto(this, "Abrindo o cardápio…", 17f, TotemUi.CINZA, TotemUi.Peso.MEDIO, centro = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(11) })
        raiz.addView(pe, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(raiz)
        handler.postDelayed({ if (!isFinishing) irParaOTotem() }, PRONTO_MS)
    }

    private fun irParaOTotem() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun mmss(ms: Long): String {
        val s = ((ms + 999) / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}

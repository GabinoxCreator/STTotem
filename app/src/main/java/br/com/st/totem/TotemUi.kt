package br.com.st.totem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Peças das telas de ativação do totem (OS-203, desenho aprovado pelo Gabriel em
 * 08/10/2026, `_docs/totem-ativacao-desenho/`). Mesma família da maquininha
 * (`PosUi`/`PosPecas` do STSmartPOS, OS-128/OS-134), com as medidas do SK-210:
 * 1080x1920 px a 1,5 px por dp, então os números aqui são os dp do LEIA-ME.
 *
 * Feito em código pelo mesmo motivo da maquininha: a tela muda de estado o tempo
 * todo (casinhas, faixa, botão) e assim nenhum tint do Material3 aparece.
 */
object TotemUi {

    const val FUNDO = 0xFFF4F4F8.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val AZUL = 0xFF5F6EF9.toInt()
    const val AZUL2 = 0xFF8B63EE.toInt()
    const val LILAS = 0xFFE9EAFE.toInt()
    const val TINTA = 0xFF12111C.toInt()
    const val TEXTO = 0xFF3A394B.toInt()
    const val CINZA = 0xFF8C8AA0.toInt()
    const val CINZA2 = 0xFF6B697F.toInt()
    const val LINHA = 0xFFE8E7EF.toInt()
    const val DESLIGADO = 0xFFD9D8E3.toInt()
    const val VERDE = 0xFF12B981.toInt()
    const val VERDE_FUNDO = 0xFFE5F8F1.toInt()
    const val VERDE_TEXTO = 0xFF0B7A55.toInt()
    const val VERMELHO = 0xFFE5484D.toInt()
    const val VERMELHO_FUNDO = 0xFFFDECEC.toInt()
    const val VERMELHO_TEXTO = 0xFFB42318.toInt()
    const val AMBAR_FUNDO = 0xFFFFF5E3.toInt()
    const val AMBAR_TEXTO = 0xFF6B4400.toInt()
    const val CASA_CHEIA = 0xFFC9CCFB.toInt()
    const val CASA_ERRO = 0xFFF3A6A9.toInt()
    const val CASA_MORTA = 0xFFB9B7C8.toInt()

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    // ---------- letras: Space Grotesk nos títulos e números, Inter no resto ----------

    private val fontes = mutableMapOf<Int, Typeface>()
    private fun fonte(ctx: Context, res: Int): Typeface =
        fontes.getOrPut(res) { ResourcesCompat.getFont(ctx, res) ?: Typeface.DEFAULT }

    enum class Peso { NORMAL, MEDIO, FORTE, NEGRITO, TITULO, TITULO_MEDIO }

    fun texto(ctx: Context, s: CharSequence, sp: Float, cor: Int, peso: Peso = Peso.NORMAL, centro: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(cor)
            if (centro) gravity = Gravity.CENTER
            typeface = fonte(ctx, when (peso) {
                Peso.NORMAL -> R.font.inter_regular
                Peso.MEDIO -> R.font.inter_medium
                Peso.FORTE -> R.font.inter_semibold
                Peso.NEGRITO -> R.font.inter_bold
                Peso.TITULO -> R.font.space_grotesk_bold
                Peso.TITULO_MEDIO -> R.font.space_grotesk_semibold
            })
            if (peso == Peso.TITULO) letterSpacing = -0.02f
        }

    // ---------- fundos ----------

    fun caixa(ctx: Context, cor: Int, raioDp: Float, borda: Int = 0, larguraBordaDp: Float = 0f) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, raioDp).toFloat()
            setColor(cor)
            if (larguraBordaDp > 0f) setStroke(dp(ctx, larguraBordaDp), borda)
        }

    fun circulo(cor: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cor) }

    /** Gradiente azul→roxo: SÓ no botão principal. */
    fun gradiente(ctx: Context, raioDp: Float) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(AZUL, AZUL2)).apply {
            cornerRadius = dp(ctx, raioDp).toFloat()
        }

    fun tingir(icone: ImageView, cor: Int) { icone.imageTintList = ColorStateList.valueOf(cor) }

    fun toque(view: View) {
        val attrs = view.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        view.foreground = attrs.getDrawable(0)
        attrs.recycle()
        view.isClickable = true
        view.isFocusable = true
    }

    fun mostrar(view: View, sim: Boolean) { view.visibility = if (sim) View.VISIBLE else View.GONE }

    /** Botão principal: 107 dp, raio 27, gradiente; desligado = cinza chapado. */
    fun cta(botao: TextView, ligado: Boolean) {
        botao.background = if (ligado) gradiente(botao.context, 27f) else caixa(botao.context, DESLIGADO, 27f)
        botao.setTextColor(Color.WHITE)
        botao.isEnabled = ligado
        botao.elevation = if (ligado) dp(botao.context, 6).toFloat() else 0f
    }

    // ---------- teclado de calculadora: tecla 88 dp, raio 23, vão 13 ----------

    fun teclado(ctx: Context, aoDigito: (Char) -> Unit, aoApagar: () -> Unit, aoLimpar: () -> Unit): GridLayout {
        val grade = GridLayout(ctx).apply { columnCount = 3; rowCount = 4; useDefaultMargins = false }
        val vao = dp(ctx, 13)
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Limpar", "0", "apagar").forEachIndexed { i, r ->
            val tecla: View = when (r) {
                "apagar" -> FrameLayout(ctx).apply {
                    addView(ImageView(ctx).apply {
                        setImageResource(R.drawable.ic_tt_apagar)
                        tingir(this, TEXTO)
                        contentDescription = "Apagar"
                    }, FrameLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40), Gravity.CENTER))
                    setOnClickListener { aoApagar() }
                    setOnLongClickListener { aoLimpar(); true }
                }
                "Limpar" -> texto(ctx, r, 23f, TEXTO, Peso.FORTE, centro = true).apply { setOnClickListener { aoLimpar() } }
                else -> texto(ctx, r, 43f, TINTA, Peso.TITULO_MEDIO, centro = true).apply { setOnClickListener { aoDigito(r[0]) } }
            }
            tecla.background = caixa(ctx, CARD, 23f, LINHA, 1.3f)
            toque(tecla)
            // Linha em FILL: sem isso a GridLayout alinha pela linha de base do texto e a tecla "Limpar" desce.
            grade.addView(tecla, GridLayout.LayoutParams(GridLayout.spec(i / 3, GridLayout.FILL), GridLayout.spec(i % 3, 1f)).apply {
                width = 0
                height = dp(ctx, 88)
                setMargins(if (i % 3 == 0) 0 else vao / 2, if (i < 3) 0 else vao / 2, if (i % 3 == 2) 0 else vao / 2, if (i >= 9) 0 else vao / 2)
            })
        }
        return grade
    }

    // ---------- faixa de aviso (espaço reservado de 112 dp: o teclado não pula) ----------

    enum class Tom { AMBAR, VERMELHO, VERDE }

    class Faixa(val vaga: FrameLayout, val raiz: LinearLayout, val titulo: TextView, val texto: TextView, val icone: ImageView, val relogio: TextView) {
        fun mostrar(tom: Tom, titulo: String, texto: String? = null, icone: Int? = null, relogio: String? = null) {
            pintar(this, tom, icone)
            this.titulo.text = titulo
            this.texto.text = texto.orEmpty()
            mostrar(this.texto, !texto.isNullOrBlank())
            this.relogio.text = relogio.orEmpty()
            mostrar(this.relogio, !relogio.isNullOrBlank())
            raiz.visibility = View.VISIBLE
        }
        fun relogio(texto: String) { relogio.text = texto; mostrar(relogio, true) }
        fun esconder() { raiz.visibility = View.INVISIBLE }
        val visivel get() = raiz.visibility == View.VISIBLE
    }

    fun faixa(ctx: Context): Faixa {
        val vaga = FrameLayout(ctx).apply { minimumHeight = dp(ctx, 112) }
        val raiz = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 17), dp(ctx, 20), dp(ctx, 17))
            visibility = View.INVISIBLE
        }
        val bola = FrameLayout(ctx)
        val icone = ImageView(ctx)
        bola.addView(icone, FrameLayout.LayoutParams(dp(ctx, 31), dp(ctx, 31), Gravity.CENTER))
        raiz.addView(bola, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56)).apply { marginEnd = dp(ctx, 16) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val t = texto(ctx, "", 24f, TINTA, Peso.NEGRITO)
        val s = texto(ctx, "", 19f, TINTA).apply { setLineSpacing(0f, 1.25f) }
        col.addView(t)
        col.addView(s, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 5) })
        raiz.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val rel = texto(ctx, "", 43f, AMBAR_TEXTO, Peso.TITULO).apply { visibility = View.GONE; fontFeatureSettings = "tnum" }
        raiz.addView(rel, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 12) })
        vaga.addView(raiz, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        return Faixa(vaga, raiz, t, s, icone, rel)
    }

    private fun pintar(f: Faixa, tom: Tom, icone: Int?) {
        val ctx = f.raiz.context
        val (fundo, borda, cor, fundoIcone) = when (tom) {
            Tom.AMBAR -> listOf(AMBAR_FUNDO, 0xFFF5D08A.toInt(), AMBAR_TEXTO, 0xFFFFE7B8.toInt())
            Tom.VERMELHO -> listOf(VERMELHO_FUNDO, 0xFFF6B8BA.toInt(), VERMELHO_TEXTO, 0xFFFBD5D6.toInt())
            Tom.VERDE -> listOf(VERDE_FUNDO, 0xFFA8E6CF.toInt(), VERDE_TEXTO, 0xFFC7F0E0.toInt())
        }
        f.raiz.background = caixa(ctx, fundo, 21f, borda, 2f)
        (f.icone.parent as View).background = caixa(ctx, fundoIcone, 16f)
        f.icone.setImageResource(icone ?: when (tom) {
            Tom.VERMELHO -> R.drawable.ic_tt_x
            Tom.VERDE -> R.drawable.ic_tt_check
            Tom.AMBAR -> R.drawable.ic_tt_relogio
        })
        tingir(f.icone, cor)
        f.titulo.setTextColor(cor)
        f.texto.setTextColor(cor)
        f.relogio.setTextColor(cor)
    }

    // ---------- linha de informação (tela "Tudo pronto") ----------

    fun linhaInfo(ctx: Context, rotulo: String, valor: String, comLinha: Boolean): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val l = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 19), 0, dp(ctx, 19))
            }
            l.addView(texto(ctx, rotulo, 21f, CINZA2, Peso.MEDIO), LinearLayout.LayoutParams(WRAP, WRAP))
            l.addView(texto(ctx, valor, 21f, TINTA, Peso.NEGRITO).apply {
                gravity = Gravity.END; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(ctx, 16) })
            addView(l)
            if (comLinha) addView(View(ctx).apply { setBackgroundColor(LINHA) }, LinearLayout.LayoutParams(MATCH, dp(ctx, 1.3f)))
        }
}

package br.com.st.totem

/**
 * Espera a impressora CONFIRMAR o que foi mandado para ela.
 *
 * ## Por que existe (OS-161, 07/10/2026)
 * A EasyLayer (SDK da impressora do SK-210) trabalha por FILA: `printHtml`,
 * `printImage`, `scrollPaper` e `cutPaper` só enfileiram um pedido e devolvem o
 * número dele na hora. Quem diz se o pedido saiu no papel é o `Printer.Listener`,
 * depois: `onPrinterSuccessful(número)` ou `onPrinterError(erro com o número)`
 * (conferido no SDK decompilado: `Printer.processRequest` → `AidlPrinterListener`).
 *
 * O app conferia só `getStatus()`, que fica OK enquanto a ficha ainda nem foi
 * desenhada (o `printHtml` desenha em segundo plano), e respondia "impresso" ao
 * servidor antes de a impressora terminar. O erro que a impressora mandava ia só
 * para o logcat. Foi assim que o Totem 0019 do Made registrou 13 fichas como
 * impressas em 28/09 sem sair nenhuma, e duas fichas do Deixa Rolar (03/10)
 * ficaram "impressas" sem chegar ao cliente.
 *
 * ## Como usar
 * `abrir()` no começo de cada trabalho; depois de mandar o corte de uma ficha,
 * `esperar(númeroDoÚltimoPedido, prazo)`. A fila da EasyLayer anda em ordem: a
 * confirmação do último pedido garante que os de antes já passaram. Um erro de
 * QUALQUER pedido desde o `abrir()` derruba o trabalho, porque o `cutPaper`
 * enfileira um avanço de papel cujo número não volta para nós.
 *
 * Esperar o corte antes de mandar a próxima ficha também protege de um defeito
 * do próprio SDK: ele reaproveita números de pedido enquanto a fila ainda tem
 * coisa, e um número repetido SUBSTITUI o pedido que estava na fila (um corte
 * pode sumir). Com a fila vazia a cada ficha, isso não acontece.
 *
 * Sem dependência de Android: testado em `ConfirmacaoImpressoraTest`.
 */
class ConfirmacaoImpressora(
    private val relogio: () -> Long = { System.currentTimeMillis() }
) {

    sealed class Resultado {
        object Confirmado : Resultado()
        data class Falhou(val causa: String) : Resultado()
        data class SemResposta(val prazoMs: Long) : Resultado()
    }

    private val trava = Object()
    private val confirmados = HashSet<Int>()
    private var primeiroErro: String? = null
    private var aberto = false

    /** Começa um trabalho: esquece tudo o que chegou antes. */
    fun abrir() {
        synchronized(trava) {
            confirmados.clear()
            primeiroErro = null
            aberto = true
        }
    }

    /** Fecha o trabalho: o que chegar depois (atrasado) é ignorado. */
    fun fechar() {
        synchronized(trava) {
            aberto = false
            confirmados.clear()
            primeiroErro = null
        }
    }

    /** `Printer.Listener.onPrinterSuccessful`. */
    fun aoConfirmar(numero: Int) {
        synchronized(trava) {
            if (!aberto) return
            confirmados.add(numero)
            trava.notifyAll()
        }
    }

    /** `Printer.Listener.onPrinterError`. Guarda o PRIMEIRO erro do trabalho. */
    fun aoFalhar(numero: Int, causa: String?) {
        synchronized(trava) {
            if (!aberto) return
            if (primeiroErro == null) {
                val texto = causa?.trim().takeUnless { it.isNullOrEmpty() } ?: "sem descrição"
                primeiroErro = "pedido $numero: $texto"
            }
            trava.notifyAll()
        }
    }

    /**
     * Espera a confirmação do pedido [numero] por até [prazoMs].
     * Erro de qualquer pedido do trabalho ganha da confirmação.
     */
    fun esperar(numero: Int, prazoMs: Long): Resultado {
        synchronized(trava) {
            val limite = relogio() + prazoMs
            while (true) {
                primeiroErro?.let { return Resultado.Falhou(it) }
                if (numero in confirmados) return Resultado.Confirmado
                val falta = limite - relogio()
                if (falta <= 0) return Resultado.SemResposta(prazoMs)
                trava.wait(falta)
            }
        }
    }
}

package br.com.st.totem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A ficha só conta como impressa quando a impressora confirma (OS-161).
 * Estes testes fingem o `Printer.Listener` da EasyLayer chamando aoConfirmar /
 * aoFalhar de outra thread, como o SDK faz (pelo binder da impressora).
 */
class ConfirmacaoImpressoraTest {

    @Test
    fun `confirmacao do corte libera a ficha`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        thread { Thread.sleep(50); c.aoConfirmar(7) }
        assertEquals(ConfirmacaoImpressora.Resultado.Confirmado, c.esperar(7, 2_000))
    }

    @Test
    fun `confirmacao que chegou antes de comecar a esperar tambem vale`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        c.aoConfirmar(3)
        assertEquals(ConfirmacaoImpressora.Resultado.Confirmado, c.esperar(3, 10))
    }

    @Test
    fun `erro da impressora derruba a ficha mesmo vindo de outro pedido do trabalho`() {
        // O cutPaper enfileira um avanço de papel cujo número não volta para nós:
        // erro de qualquer pedido do trabalho tem de valer.
        val c = ConfirmacaoImpressora()
        c.abrir()
        thread { Thread.sleep(50); c.aoFalhar(5, "Erro durante a impressão: 240") }
        val r = c.esperar(6, 2_000)
        assertTrue(r is ConfirmacaoImpressora.Resultado.Falhou)
        assertEquals("pedido 5: Erro durante a impressão: 240", (r as ConfirmacaoImpressora.Resultado.Falhou).causa)
    }

    @Test
    fun `erro ganha da confirmacao`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        c.aoFalhar(1, "Printer out of paper")
        c.aoConfirmar(2)
        assertTrue(c.esperar(2, 10) is ConfirmacaoImpressora.Resultado.Falhou)
    }

    @Test
    fun `fica so o primeiro erro`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        c.aoFalhar(1, "primeiro")
        c.aoFalhar(2, "segundo")
        assertEquals(
            ConfirmacaoImpressora.Resultado.Falhou("pedido 1: primeiro"),
            c.esperar(2, 10)
        )
    }

    @Test
    fun `erro sem descricao nao vira texto vazio`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        c.aoFalhar(4, null)
        assertEquals(ConfirmacaoImpressora.Resultado.Falhou("pedido 4: sem descrição"), c.esperar(4, 10))
    }

    @Test
    fun `silencio da impressora vira sem resposta no prazo`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        val inicio = System.currentTimeMillis()
        val r = c.esperar(9, 150)
        assertEquals(ConfirmacaoImpressora.Resultado.SemResposta(150), r)
        assertTrue("devolveu antes do prazo", System.currentTimeMillis() - inicio >= 140)
    }

    @Test
    fun `confirmacao de outro numero nao libera a ficha`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        c.aoConfirmar(1)
        assertTrue(c.esperar(2, 50) is ConfirmacaoImpressora.Resultado.SemResposta)
    }

    @Test
    fun `o que chega fora de um trabalho e ignorado`() {
        val c = ConfirmacaoImpressora()
        c.aoConfirmar(1)          // antes de abrir
        c.aoFalhar(1, "velho")    // antes de abrir
        c.abrir()
        assertTrue(c.esperar(1, 50) is ConfirmacaoImpressora.Resultado.SemResposta)
        c.fechar()
        c.aoFalhar(2, "atrasado") // depois de fechar
        c.abrir()
        c.aoConfirmar(2)
        assertEquals(ConfirmacaoImpressora.Resultado.Confirmado, c.esperar(2, 10))
    }

    @Test
    fun `abrir de novo esquece o trabalho anterior`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        c.aoFalhar(1, "do trabalho anterior")
        c.abrir()
        c.aoConfirmar(5)
        assertEquals(ConfirmacaoImpressora.Resultado.Confirmado, c.esperar(5, 10))
    }

    @Test
    fun `quem espera acorda quando o erro chega`() {
        val c = ConfirmacaoImpressora()
        c.abrir()
        val acordou = CountDownLatch(1)
        var resultado: ConfirmacaoImpressora.Resultado? = null
        thread {
            resultado = c.esperar(10, 60_000)
            acordou.countDown()
        }
        Thread.sleep(50)
        c.aoFalhar(10, "Erro durante a impressão: 1")
        assertTrue("a espera não acordou com o erro", acordou.await(2, TimeUnit.SECONDS))
        assertTrue(resultado is ConfirmacaoImpressora.Resultado.Falhou)
    }
}

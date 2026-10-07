package br.com.st.totem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** O motivo viaja no texto técnico e a tela escolhe a frase por ele (OS-161). */
class FalhaImpressaoTest {

    @Test
    fun `o motivo marcado volta inteiro mesmo dentro de outra frase`() {
        for (m in MotivoFalhaImpressao.values()) {
            val tecnico = "Falha ao imprimir vouchers: " + FalhaImpressao(m, "detalhe").message
            assertEquals(m, MotivoFalhaImpressao.de(tecnico))
        }
    }

    @Test
    fun `texto sem motivo conhecido e outro`() {
        assertEquals(MotivoFalhaImpressao.OUTRO, MotivoFalhaImpressao.de("Ingresso sem qr_payload"))
        assertEquals(MotivoFalhaImpressao.OUTRO, MotivoFalhaImpressao.de(null))
        assertEquals(MotivoFalhaImpressao.OUTRO, MotivoFalhaImpressao.de("   "))
        assertEquals(MotivoFalhaImpressao.OUTRO, MotivoFalhaImpressao.de("timeout"))
    }

    @Test
    fun `todo motivo tem frase para a tela`() {
        for (m in MotivoFalhaImpressao.values()) {
            assertTrue(m.name, TextosImpressao.tela(m).isNotBlank())
        }
    }

    @Test
    fun `o servidor recebe o codigo entre colchetes no comeco`() {
        assertEquals("[sem_confirmacao] x", MotivoFalhaImpressao.SEM_CONFIRMACAO.marcar("x"))
        assertEquals("[sem_papel] y", FalhaImpressao(MotivoFalhaImpressao.SEM_PAPEL, "y").message)
    }
}

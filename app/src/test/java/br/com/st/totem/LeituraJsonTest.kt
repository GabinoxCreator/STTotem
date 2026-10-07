package br.com.st.totem

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** As leituras de `LeituraJson.kt`, com o org.json do Android (OS-161). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeituraJsonTest {

    private val j = JSONObject(
        """
        {"nulo": null, "vazio": "", "espaco": "   ", "palavra": "null", "Palavra": "NULL",
         "ok": "  THEO0167 ", "num": 5, "numTexto": "7", "numQuebrado": "5.9", "decimal": 14.99,
         "decimalTexto": "2.5", "lixo": "abc", "sim": true, "naoTexto": "false", "talvez": "sim",
         "um": 1, "textos": ["a", null, "", "null", " b "], "inteiros": [1, "2", null, "x", 3.0]}
        """.trimIndent()
    )

    @Test
    fun `texto so devolve valor de verdade`() {
        assertNull(j.texto("falta"))
        assertNull(j.texto("nulo"))
        assertNull(j.texto("vazio"))
        assertNull(j.texto("espaco"))
        assertNull(j.texto("palavra"))
        assertNull(j.texto("Palavra"))
        assertEquals("THEO0167", j.texto("ok"))
        assertEquals("5", j.texto("num"))
    }

    @Test
    fun `texto cru nao mexe no valor`() {
        assertEquals("  THEO0167 ", j.textoCru("ok"))
        assertNull(j.textoCru("nulo"))
        assertNull(j.textoCru("vazio"))
        assertNull(j.textoCru("falta"))
    }

    @Test
    fun `obrigatorio falha alto dizendo o campo`() {
        for (campo in listOf("falta", "nulo", "vazio", "palavra")) {
            try {
                j.textoObrigatorio(campo)
                fail("'$campo' passou")
            } catch (e: RespostaInvalida) {
                assertTrue(e.message!!.contains("'$campo'"))
            }
        }
        assertEquals("THEO0167", j.textoObrigatorio("ok"))
    }

    @Test
    fun `numeros aceitam texto numerico e recusam lixo`() {
        assertEquals(5, j.inteiro("num"))
        assertEquals(7, j.inteiro("numTexto"))
        assertEquals(5, j.inteiro("numQuebrado"))
        assertNull(j.inteiro("lixo"))
        assertNull(j.inteiro("nulo"))
        assertNull(j.inteiro("falta"))
        assertEquals(14.99, j.decimal("decimal")!!, 0.0)
        assertEquals(2.5, j.decimal("decimalTexto")!!, 0.0)
        assertNull(j.decimal("lixo"))
        assertNull(j.decimal("nulo"))
    }

    @Test
    fun `logico so aceita verdadeiro e falso`() {
        assertEquals(true, j.logico("sim"))
        assertEquals(false, j.logico("naoTexto"))
        assertNull(j.logico("talvez"))
        assertNull(j.logico("um"))
        assertNull(j.logico("nulo"))
        assertNull(j.logico("falta"))
    }

    @Test
    fun `listas descartam o que nao e valor`() {
        assertEquals(listOf("a", "b"), j.listaDeTextos("textos"))
        assertEquals(listOf(1, 2, 3), j.listaDeInteiros("inteiros"))
        assertNull(j.listaDeTextos("falta"))
        assertNull(JSONObject("""{"v": [null, ""]}""").listaDeTextos("v"))
    }
}

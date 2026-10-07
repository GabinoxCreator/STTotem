package br.com.st.totem

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prova que os testes rodam com o org.json DO ANDROID (via Robolectric), o mesmo
 * do aparelho. É ele que devolve a palavra "null" no optString de campo nulo, a
 * armadilha que derrubou o cartão do totem da Porcada em 11/09 (OS-161).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrgJsonDoAndroidTest {

    @Test
    fun `optString de campo nulo devolve a palavra null no Android`() {
        val o = JSONObject("""{"loja": null}""")
        assertEquals("null", o.optString("loja"))
        assertEquals("null", o.optString("loja", "padrao"))
    }
}

package br.com.st.totem

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrato da `totem-activate` (totem-web `supabase/functions/totem-activate/index.ts`:
 * as duas respostas de sucesso, a normal e a idempotente) com o que o APP lê. OS-161.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContratoAtivacaoTest {

    private val exemplo = """
        {
          "success": true,
          "activation_token": "tok_ficticio_123",
          "idempotent": true,
          "totem": {"id": "11111111-1111-4111-8111-111111111111", "name": "Totem 0015",
                    "identifier": "3564834281", "status": "online",
                    "company_id": "22222222-2222-4222-8222-222222222222", "location_id": null},
          "company": {"id": "22222222-2222-4222-8222-222222222222", "name": "Bar", "logo": null},
          "location": {"id": "33333333-3333-4333-8333-333333333333", "name": "Salão", "address": "Rua"},
          "config": {"totem_id": "11111111-1111-4111-8111-111111111111"}
        }
    """.trimIndent()

    private fun ler(json: org.json.JSONObject) = lerRespostaAtivacao(json.toString()).copy(rawJson = null)

    @Test
    fun `le o token e os ids`() {
        val r = lerRespostaAtivacao(exemplo)
        assertEquals("tok_ficticio_123", r.activationToken)
        assertEquals("11111111-1111-4111-8111-111111111111", r.totemId)
        assertEquals("3564834281", r.identifier)
        assertEquals("22222222-2222-4222-8222-222222222222", r.companyId)
        assertEquals("33333333-3333-4333-8333-333333333333", r.locationId)
    }

    @Test
    fun `toda chave do servidor tem decisao`() {
        val j = ContratoKit.json(exemplo)
        ContratoKit.inventario(
            "ativacao", j, "",
            lidas = setOf("success", "activation_token", "totem", "company", "location"),
            ignoradas = setOf("idempotent", "config")
        )
        ContratoKit.inventario(
            "ativacao.totem", j, "totem",
            lidas = setOf("id", "identifier"),
            ignoradas = setOf("name", "status", "company_id", "location_id")
        )
    }

    @Test
    fun `cada campo lido e lido de verdade`() {
        ContratoKit.leDeVerdade(
            "ativacao", ContratoKit.json(exemplo),
            listOf("activation_token", "totem.id", "totem.identifier", "company.id", "location.id"),
            ::ler
        )
    }

    @Test
    fun `sem token falha alto, inclusive com a palavra null`() {
        ContratoKit.obrigatorios("ativacao", ContratoKit.json(exemplo), listOf("activation_token"), ::ler)
        val j = ContratoKit.json(exemplo)
        j.put("activation_token", "null")
        try {
            ler(j)
            fail("token \"null\" passou")
        } catch (e: RespostaInvalida) {
            // mesma frase que a tela de ativação já mostrava
            assertEquals("Token de ativação não retornado.", e.message)
        }
    }

    @Test
    fun `nulos nao viram texto`() {
        ContratoKit.nulosNaoViramTexto(
            "ativacao", ContratoKit.json(exemplo),
            listOf("totem.id", "totem.identifier", "company.id", "location.id"), ::ler
        )
    }
}

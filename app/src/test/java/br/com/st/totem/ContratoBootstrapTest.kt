package br.com.st.totem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrato da `totem-bootstrap` (totem-web `supabase/functions/totem-bootstrap/index.ts`,
 * resposta de sucesso no fim do arquivo) com o que o APP lê. Valores fictícios.
 * Mudou a edge? Atualize o exemplo e decida cada campo novo (OS-161).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContratoBootstrapTest {

    private val exemplo = """
        {
          "success": true,
          "form": null,
          "home_config": {"title": "Bem-vindo!", "logo_url": null, "background_url": "https://x/bg.jpeg",
                          "subtitle": null, "cta_text": null, "top_image_url": null, "bottom_image_url": null},
          "totem": {
            "id": "11111111-1111-4111-8111-111111111111",
            "name": "Totem 0015",
            "identifier": "3564834281",
            "status": "online",
            "company_id": "22222222-2222-4222-8222-222222222222",
            "location_id": "33333333-3333-4333-8333-333333333333",
            "sitef_otp": "9999999999",
            "sitef_terminal_id": "00000060",
            "sitef_loja": "THEO0167",
            "totem_type": "event_bar",
            "totem_label": null,
            "require_cpf": true
          },
          "company": {"id": "22222222-2222-4222-8222-222222222222", "name": "Bar", "logo": null},
          "location": {"id": "33333333-3333-4333-8333-333333333333", "name": "Salão", "address": "Rua"},
          "config": {"totem_id": "11111111-1111-4111-8111-111111111111", "payment_debit_enabled": true},
          "branding": {"brand_name": "Bem-vindo!", "brand_primary_color": "#0a5700"},
          "activation_status": "activated"
        }
    """.trimIndent()

    private fun ler(json: org.json.JSONObject) = lerRespostaBootstrap(json.toString()).copy(rawJson = null)

    @Test
    fun `le o que o servidor manda`() {
        val r = lerRespostaBootstrap(exemplo)
        assertEquals("11111111-1111-4111-8111-111111111111", r.totemId)
        assertEquals("3564834281", r.identifier)
        assertEquals("22222222-2222-4222-8222-222222222222", r.companyId)
        assertEquals("33333333-3333-4333-8333-333333333333", r.locationId)
        assertEquals("9999999999", r.sitefOtp)
        assertEquals("00000060", r.sitefTerminalId)
        assertEquals("THEO0167", r.sitefLoja)
    }

    @Test
    fun `toda chave do servidor tem decisao`() {
        val j = ContratoKit.json(exemplo)
        ContratoKit.inventario(
            "bootstrap", j, "",
            lidas = setOf("success", "totem", "company", "location"),
            // da página (WebView), não do app
            ignoradas = setOf("form", "home_config", "config", "branding", "activation_status")
        )
        ContratoKit.inventario(
            "bootstrap.totem", j, "totem",
            lidas = setOf("id", "identifier", "sitef_otp", "sitef_terminal_id", "sitef_loja"),
            ignoradas = setOf("name", "status", "company_id", "location_id", "totem_type", "totem_label", "require_cpf")
        )
        ContratoKit.inventario("bootstrap.company", j, "company", lidas = setOf("id"), ignoradas = setOf("name", "logo"))
        ContratoKit.inventario("bootstrap.location", j, "location", lidas = setOf("id"), ignoradas = setOf("name", "address"))
    }

    @Test
    fun `cada campo lido e lido de verdade`() {
        ContratoKit.leDeVerdade(
            "bootstrap", ContratoKit.json(exemplo),
            listOf("totem.id", "totem.identifier", "totem.sitef_otp", "totem.sitef_terminal_id",
                "totem.sitef_loja", "company.id", "location.id"),
            ::ler
        )
    }

    @Test
    fun `sem o totem ou sem o id falha alto`() {
        ContratoKit.obrigatorios("bootstrap", ContratoKit.json(exemplo), listOf("totem", "totem.id"), ::ler)
    }

    @Test
    fun `loja, OTP e terminal nulos ficam nulos e o app usa o padrao dele`() {
        val campos = listOf("totem.identifier", "totem.sitef_otp", "totem.sitef_terminal_id", "totem.sitef_loja")
        ContratoKit.nulosNaoViramTexto("bootstrap", ContratoKit.json(exemplo), campos, ::ler)
        val j = ContratoKit.json(exemplo)
        campos.forEach { ContratoKit.definir(j, it, org.json.JSONObject.NULL) }
        val r = ler(j)
        assertNull(r.sitefLoja)
        assertNull(r.sitefOtp)
        assertNull(r.sitefTerminalId)
        assertNull(r.identifier)
    }

    @Test
    fun `a palavra null que vier do servidor tambem vira nulo`() {
        val j = ContratoKit.json(exemplo)
        ContratoKit.definir(j, "totem.sitef_loja", "null")
        assertNull(ler(j).sitefLoja)
    }

    @Test
    fun `totem sem local manda location nulo`() {
        val j = ContratoKit.json(exemplo)
        j.put("location", org.json.JSONObject.NULL)
        assertNull(ler(j).locationId)
    }

    @Test
    fun `servidor recusando vira erro com a mensagem dele`() {
        try {
            lerRespostaBootstrap("""{"success": false, "error": "Token inválido ou ativação revogada."}""")
            fail("era para falhar")
        } catch (e: RespostaInvalida) {
            assertEquals("Token inválido ou ativação revogada.", e.message)
        }
    }
}

package br.com.st.totem

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrato das portas da "ativação uma vez só" do totem (OS-203, 08/10/2026),
 * escrito a partir do totem-web `supabase/functions/`: `device-activate`,
 * `device-adopt`, `totem-activate` e `totem-bootstrap` com `x-device-key`.
 * E a REGRA DE OURO no aparelho: com credencial, vazio nunca apaga o OTP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContratoAparelhoTest {

    // device-activate/index.ts e device-adopt/index.ts (deviceIdentityPayload)
    private val credencial = """
        {
          "success": true,
          "adopted": true,
          "device_key": "chave-ficticia-1",
          "link_id": "44444444-4444-4444-8444-444444444444",
          "device": {"id": "55555555-5555-4555-8555-555555555555", "kind": "totem", "asset_tag": "TT-012",
                     "serial_number": null, "model": "sk210", "sitef_terminal_id": "00000042",
                     "has_otp": true, "sitef_variant": "clisitef", "sitef_loja": "THEO0167", "status": "vinculado"}
        }
    """.trimIndent()

    @Test
    fun `credencial do aparelho e lida`() {
        val c = lerCredencialAparelho(credencial)
        assertEquals("chave-ficticia-1", c.deviceKey)
        assertEquals("TT-012", c.info?.assetTag)
        assertEquals("00000042", c.info?.terminal)
        assertTrue(c.info!!.temOtp)
        assertTrue(c.adotado)
    }

    @Test
    fun `toda chave da credencial tem decisao`() {
        val j = ContratoKit.json(credencial)
        ContratoKit.inventario("credencial", j, "",
            lidas = setOf("success", "adopted", "device_key", "device"),
            ignoradas = setOf("link_id"))
        ContratoKit.inventario("credencial.device", j, "device",
            lidas = setOf("asset_tag", "sitef_terminal_id", "has_otp"),
            ignoradas = setOf("id", "kind", "serial_number", "model", "sitef_variant", "sitef_loja", "status"))
    }

    @Test
    fun `sem credencial falha alto, inclusive com a palavra null`() {
        val j = ContratoKit.json(credencial)
        j.put("device_key", "null")
        try {
            lerCredencialAparelho(j.toString())
            fail("device_key \"null\" passou")
        } catch (_: RespostaInvalida) {
        }
    }

    @Test
    fun `servidor recusando vira erro com a mensagem dele`() {
        try {
            lerCredencialAparelho("""{"success":false,"error":"Código do aparelho expirado. Gere outro no painel."}""")
            fail("recusa passou")
        } catch (e: RespostaInvalida) {
            assertEquals("Código do aparelho expirado. Gere outro no painel.", e.message)
        }
    }

    // totem-bootstrap/index.ts: revogado com x-device-key válida
    @Test
    fun `revogado com todas as letras sai da conta`() {
        val corpo = """{"success":false,"revoked":true,"reason":"revoked","company_name":"Made in Brazil Bar","error":"Este totem foi desligado da conta.","device_identity":{"asset_tag":"TT-012"}}"""
        assertEquals("Made in Brazil Bar", lerSaidaDaConta(401, corpo)?.nomeDaConta)
        val semNome = """{"success":false,"revoked":true,"reason":"not_found","company_name":null}"""
        val s = lerSaidaDaConta(401, semNome)
        assertTrue(s != null)
        assertNull(s!!.nomeDaConta)
    }

    @Test
    fun `qualquer outra falha NUNCA tira da conta`() {
        // 401 do app velho (sem revoked), soluço do banco (503), lixo, vazio.
        assertNull(lerSaidaDaConta(401, """{"success":false,"error":"Token inválido ou ativação revogada."}"""))
        assertNull(lerSaidaDaConta(503, """{"success":false,"revoked":true}"""))
        assertNull(lerSaidaDaConta(401, "<html>gateway</html>"))
        assertNull(lerSaidaDaConta(401, ""))
        assertNull(lerSaidaDaConta(401, """{"revoked":"null"}"""))
    }

    @Test
    fun `bootstrap com aparelho le o rodape e o nome da conta`() {
        val corpo = """
            {"success": true,
             "totem": {"id": "11111111-1111-4111-8111-111111111111", "identifier": "3564834281",
                       "sitef_otp": "OTP-AP", "sitef_terminal_id": "00000042", "sitef_loja": "THEO0167"},
             "company": {"id": "22222222-2222-4222-8222-222222222222", "name": "Made in Brazil Bar", "logo": null},
             "location": null,
             "device_identity": {"asset_tag": "TT-012", "sitef_terminal_id": "00000042", "has_otp": true}}
        """.trimIndent()
        val r = lerRespostaBootstrap(corpo)
        assertEquals("Made in Brazil Bar", r.companyName)
        assertEquals("TT-012", r.aparelho?.assetTag)
        assertEquals("OTP-AP", r.sitefOtp)
    }

    @Test
    fun `entrar na conta le o nome da conta e o aparelho`() {
        val corpo = """
            {"success": true, "activation_token": "tok", "link_id": "x",
             "totem": {"id": "11111111-1111-4111-8111-111111111111", "identifier": "3564834281"},
             "company": {"id": "22222222-2222-4222-8222-222222222222", "name": "Bar do Teste"},
             "device_identity": {"asset_tag": "TT-012", "sitef_terminal_id": "00000042", "has_otp": true}}
        """.trimIndent()
        val r = lerRespostaAtivacao(corpo)
        assertEquals("Bar do Teste", r.companyName)
        assertEquals("00000042", r.aparelho?.terminal)
    }

    @Test
    fun `erro da porta traz status e codigo`() {
        val e = lerErroPorta(404, """{"success":false,"stage":"activation_lookup","code":"expired","error":"Este código venceu. Gere outro no painel."}""")
        assertEquals("expired", e.codigo)
        assertEquals("Este código venceu. Gere outro no painel.", e.mensagem)
        assertFalse(e.servidorInstavel)
        assertTrue(lerErroPorta(503, "{}").servidorInstavel)
        assertTrue(lerErroPorta(429, """{"code":"too_many_attempts"}""").muitasTentativas)
        assertEquals("Falha no servidor (HTTP 502)", lerErroPorta(502, "<html>").mensagem)
    }

    // ── a regra de ouro no aparelho ─────────────────────────────────────────

    private fun armazenamento(): LocalStorageManager {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("sttotem_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        return LocalStorageManager(ctx)
    }

    @Test
    fun `com aparelho, vazio nunca apaga OTP nem terminal`() {
        val s = armazenamento()
        s.saveDeviceKey("chave")
        s.saveSitefDoBootstrap("OTP-1", "00000042", "THEO0167")
        s.saveSitefDoBootstrap(null, "", null)
        assertEquals("OTP-1", s.getSitefOtp())
        assertEquals("00000042", s.getSitefTerminalId())
        assertNull(s.getSitefLoja()) // loja vazia = loja padrão, segue o servidor
    }

    @Test
    fun `sem aparelho grava como sempre gravou`() {
        val s = armazenamento()
        s.saveSitefDoBootstrap("OTP-1", "00000042", "THEO0167")
        s.saveSitefDoBootstrap(null, null, null)
        assertNull(s.getSitefOtp())
    }

    @Test
    fun `sair da conta apaga so a conta`() {
        val s = armazenamento()
        s.saveDeviceKey("chave")
        s.saveAssetTag("TT-012")
        s.saveSitefOtp("OTP-1"); s.saveSitefTerminalId("00000042"); s.saveSitefLoja("THEO0167")
        s.saveActivationToken("tok"); s.saveTotemId("t"); s.saveCompanyId("c"); s.saveCompanyName("Bar")
        s.clearAccountLink()
        assertFalse(s.isActivated())
        assertNull(s.getTotemId()); assertNull(s.getCompanyId()); assertNull(s.getCompanyName())
        assertEquals("chave", s.getDeviceKey())
        assertEquals("TT-012", s.getAssetTag())
        assertEquals("OTP-1", s.getSitefOtp())
        assertEquals("00000042", s.getSitefTerminalId())
        assertEquals("THEO0167", s.getSitefLoja())
    }
}

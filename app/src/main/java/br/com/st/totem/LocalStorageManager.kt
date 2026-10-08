package br.com.st.totem

import android.content.Context

class LocalStorageManager(context: Context) {

    private val prefs = context.getSharedPreferences("sttotem_prefs", Context.MODE_PRIVATE)

    fun saveActivationToken(token: String) {
        prefs.edit().putString("activation_token", token).apply()
    }

    fun getActivationToken(): String? {
        return prefs.getString("activation_token", null)
    }

    fun saveTotemId(value: String?) {
        prefs.edit().putString("totem_id", value).apply()
    }

    fun getTotemId(): String? {
        return prefs.getString("totem_id", null)
    }

    fun saveCompanyId(value: String?) {
        prefs.edit().putString("company_id", value).apply()
    }

    fun getCompanyId(): String? {
        return prefs.getString("company_id", null)
    }

    fun saveLocationId(value: String?) {
        prefs.edit().putString("location_id", value).apply()
    }

    fun getLocationId(): String? {
        return prefs.getString("location_id", null)
    }

    fun saveIdentifier(value: String?) {
        prefs.edit().putString("identifier", value).apply()
    }

    fun getIdentifier(): String? {
        return prefs.getString("identifier", null)
    }

    fun saveSitefOtp(value: String?) {
        prefs.edit().putString("sitef_otp", value).apply()
    }

    fun getSitefOtp(): String? {
        return prefs.getString("sitef_otp", null)
    }

    /** Qual "tipo de câmera" da DecodeLibrary é o leitor de código DESTE aparelho.
     *  Descoberto pelo QrScannerManager na primeira leitura e guardado para não
     *  repetir a procura na frente de quem está na fila da portaria. */
    fun saveScannerCameraType(value: String?) { prefs.edit().putString("scanner_camera_type", value).apply() }

    fun getScannerCameraType(): String? { return prefs.getString("scanner_camera_type", null) }

    fun saveSitefTerminalId(value: String?) { prefs.edit().putString("sitef_terminal_id", value).apply() }

    fun getSitefTerminalId(): String? { return prefs.getString("sitef_terminal_id", null) }

    /** Codigo da LOJA no SiTef deste aparelho, vindo do cadastro do totem.
     *  NULO/vazio = o app usa a loja padrao compilada (CODIGO_LOJA_PADRAO).
     *  NAO confundir com o terminal, que e o numero do equipamento. */
    fun saveSitefLoja(value: String?) { prefs.edit().putString("sitef_loja", value).apply() }

    fun getSitefLoja(): String? { return prefs.getString("sitef_loja", null) }

    fun isActivated(): Boolean {
        return !getActivationToken().isNullOrBlank()
    }

    // ── Grupo APARELHO (OS-203, 08/10/2026) ──────────────────────────────────
    // A identidade do totem físico: nasce na "Ativação do aparelho" (código do
    // /st) ou na adoção (app novo por cima do antigo) e NUNCA sai na troca de
    // conta. OTP, terminal e loja do SiTef também são do aparelho: continuam nas
    // chaves de sempre (sitef_*), só deixam de ser apagados ao sair da conta.

    fun saveDeviceKey(value: String?) { prefs.edit().putString("device_key", value).apply() }

    fun getDeviceKey(): String? = prefs.getString("device_key", null)?.takeIf { it.isNotBlank() }

    fun hasDeviceKey(): Boolean = getDeviceKey() != null

    fun saveHardwareId(value: String) { prefs.edit().putString("hardware_id", value).apply() }

    fun getHardwareId(): String? = prefs.getString("hardware_id", null)?.takeIf { it.isNotBlank() }

    /** Etiqueta da gestão (TT-012). Vazio nunca apaga a que já existe. */
    fun saveAssetTag(value: String?) { if (!value.isNullOrBlank()) prefs.edit().putString("device_asset_tag", value).apply() }

    fun getAssetTag(): String? = prefs.getString("device_asset_tag", null)?.takeIf { it.isNotBlank() }

    /** Nome da conta em que o totem está (para a tela de sair/entrar mostrar). */
    fun saveCompanyName(value: String?) { prefs.edit().putString("company_name", value).apply() }

    fun getCompanyName(): String? = prefs.getString("company_name", null)?.takeIf { it.isNotBlank() }

    /**
     * Grava OTP, terminal e loja vindos do bootstrap, com a REGRA DE OURO do app
     * novo: com credencial de aparelho, vazio NUNCA apaga o que já está guardado
     * (o servidor também garante isso, aqui é a segunda trava). A loja vazia é
     * legítima (= loja padrão), por isso só a loja segue o servidor sempre.
     * Sem credencial de aparelho (antes da adoção) grava como sempre gravou.
     */
    fun saveSitefDoBootstrap(otp: String?, terminal: String?, loja: String?) {
        if (hasDeviceKey()) {
            if (!otp.isNullOrBlank()) saveSitefOtp(otp)
            if (!terminal.isNullOrBlank()) saveSitefTerminalId(terminal)
            saveSitefLoja(loja)
        } else {
            saveSitefOtp(otp)
            saveSitefTerminalId(terminal)
            saveSitefLoja(loja)
        }
    }

    /**
     * "Sair da conta" (OS-203): apaga SÓ o grupo da conta. O aparelho (device_key,
     * identidade) e o SiTef (OTP, terminal, loja) ficam; por isso trocar de
     * cliente não pede reset de OTP. O `clearActivation()` abaixo continua sendo
     * o "zera tudo" do jeito antigo (app sem credencial de aparelho).
     */
    fun clearAccountLink() {
        prefs.edit()
            .remove("activation_token")
            .remove("totem_id")
            .remove("company_id")
            .remove("location_id")
            .remove("identifier")
            .remove("company_name")
            .apply()
    }

    fun clearActivation() {
        prefs.edit()
            .remove("activation_token")
            .remove("totem_id")
            .remove("company_id")
            .remove("location_id")
            .remove("identifier")
            .remove("sitef_otp")
            .remove("sitef_terminal_id")
            .remove("sitef_loja")
            .apply()
    }
}
package br.com.st.totem.payment.sitef

/**
 * Nome da bandeira a partir do que o SiTef devolve — quando dá para saber.
 *
 * O SiTef NÃO manda "VISA"/"MASTERCARD" num campo próprio: manda um CÓDIGO de
 * 5 posições (campo 132 do CliSiTef, tabela "Bandeira Padrão SiTef"), e a
 * tradução completa desse código mora no servidor (`_shared/sitef-bandeiras.ts`
 * do totem-web). Aqui no aparelho só fazemos o que dá para fazer sem tabela:
 *  1. se veio texto com letra, é o nome;
 *  2. senão, procuramos um nome conhecido dentro do comprovante (a via traz a
 *     bandeira escrita, ex.: "MASTERCARD DEBITO");
 *  3. senão, null — quem chama manda o código cru e o servidor traduz.
 *
 * Nasceu em 08/09/2026, no pedido do financeiro pela bandeira de cada venda.
 */
object SitefBandeiraNome {

    // Ordem importa: o composto antes do simples ("VISA ELECTRON" antes de "VISA").
    private val NOMES_NO_COMPROVANTE = listOf(
        "VISA ELECTRON", "MASTERCARD", "MAESTRO", "HIPERCARD", "AMERICAN EXPRESS",
        "SOROCRED", "BANESCARD", "CREDSYSTEM", "AGIPLAN", "CABAL", "ELO",
        "HIPER", "AMEX", "VISA", "DINERS", "DISCOVER", "AURA", "ALELO",
        "SODEXO", "TICKET", "VR ", "BEN "
    )

    private val CODIGO = Regex("^\\d{5}$")

    /** true quando o valor é um código de 5 dígitos da tabela padrão SiTef. */
    fun ehCodigo(valor: String?): Boolean = valor != null && CODIGO.matches(valor.trim())

    /**
     * Nome da bandeira, ou null. [bruto] é o que o SiTef chamou de bandeira;
     * [vias] são os textos de comprovante disponíveis.
     */
    fun resolver(bruto: String?, vararg vias: String?): String? {
        val b = bruto?.trim().orEmpty()
        if (b.any { it.isLetter() }) return b.uppercase()

        val texto = vias.filterNotNull().joinToString(" ").uppercase()
        if (texto.isBlank()) return null
        return NOMES_NO_COMPROVANTE.firstOrNull { texto.contains(it) }?.trim()
    }
}

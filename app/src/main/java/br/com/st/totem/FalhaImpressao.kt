package br.com.st.totem

/**
 * Por que uma ficha não saiu, em três motivos que a tela e o servidor entendem.
 *
 * O texto técnico que vai para o servidor (`print_jobs.error_message` e
 * `totem_logs`) começa com o código entre colchetes, ex.:
 * `[sem_confirmacao] Falha ao imprimir vouchers: ...`. Assim dá para contar no
 * banco quantas fichas falharam por motivo, e a tela escolhe o texto certo
 * sem depender da frase. Ver OS-161.
 */
enum class MotivoFalhaImpressao(val codigo: String) {
    /** A impressora disse que está sem papel. */
    SEM_PAPEL("sem_papel"),

    /** A impressora avisou erro (pelo `Printer.Listener` ou pelo status). */
    ERRO_DA_IMPRESSORA("erro_impressora"),

    /** Mandamos a ficha e a impressora não confirmou no prazo. */
    SEM_CONFIRMACAO("sem_confirmacao"),

    /** Qualquer outra coisa (impressora não inicializou, ficha sem dado...). */
    OUTRO("outro");

    /** `[codigo] texto`: o formato que [de] sabe ler de volta. */
    fun marcar(texto: String): String = "[$codigo] $texto"

    companion object {
        /** Lê o motivo de um texto técnico; sem código conhecido → [OUTRO]. */
        fun de(texto: String?): MotivoFalhaImpressao {
            if (texto.isNullOrBlank()) return OUTRO
            return values().firstOrNull { it != OUTRO && texto.contains("[${it.codigo}]") } ?: OUTRO
        }
    }
}

/** Exceção com motivo: os blocos `catch` do `PrinterManager` já a transformam em texto. */
class FalhaImpressao(
    val motivo: MotivoFalhaImpressao,
    detalhe: String
) : Exception(motivo.marcar(detalhe))

/**
 * O que a pessoa na frente do totem lê no quadro "Detalhes" da tela "Falha na
 * impressão" (`TotemSuccess.tsx`, totem-web). O título e a frase "Procure um
 * atendente para finalizar seu pedido." já existem lá; aqui vai só a causa.
 * ⚠️ Texto que cliente lê: aprovado pelo Gabriel em 07/10/2026 (OS-161); muda só
 * com o OK dele.
 */
object TextosImpressao {
    fun tela(motivo: MotivoFalhaImpressao): String = when (motivo) {
        MotivoFalhaImpressao.SEM_PAPEL -> "A impressora está sem papel."
        MotivoFalhaImpressao.ERRO_DA_IMPRESSORA -> "A impressora avisou um erro."
        MotivoFalhaImpressao.SEM_CONFIRMACAO -> "A impressora não confirmou a impressão."
        MotivoFalhaImpressao.OUTRO -> "Não foi possível imprimir."
    }
}

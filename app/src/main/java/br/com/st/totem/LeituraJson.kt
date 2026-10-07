package br.com.st.totem

import org.json.JSONArray
import org.json.JSONObject

/**
 * Leitura do que o SERVIDOR manda para o totem (OS-161, 07/10/2026).
 *
 * A regra: todo campo ou FALHA ALTO (`...Obrigatorio`, lança [RespostaInvalida]
 * com o nome do campo) ou tem PADRÃO EXPLÍCITO escrito no lugar onde é lido
 * (`json.texto("x") ?: "padrão"`). O que não pode é falhar calado.
 *
 * Por que não usar `optString` direto: no Android ele devolve a PALAVRA "null"
 * quando o campo vem nulo (até com padrão: `optString("x", "p")` dá "null") e ""
 * quando falta, e as duas passam como valor de verdade. Foi isso que derrubou o
 * cartão do totem da Porcada em 11/09 (a loja do SiTef virou "null"). Provado em
 * `OrgJsonDoAndroidTest`, que roda com o org.json do Android.
 */
class RespostaInvalida(mensagem: String) : Exception(mensagem)

/**
 * Texto do campo, sem espaços nas pontas. Null quando o campo falta, vem nulo,
 * vem vazio ou vem com a palavra "null".
 */
fun JSONObject.texto(campo: String): String? {
    if (!has(campo) || isNull(campo)) return null
    val valor = opt(campo) ?: return null
    val texto = (valor as? String ?: valor.toString()).trim()
    return texto.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
}

/**
 * Texto EXATAMENTE como veio (sem trim nem nada): para o que é validado byte a
 * byte depois, como o `qr_payload` do ingresso. Null só se falta, vem nulo ou vazio.
 */
fun JSONObject.textoCru(campo: String): String? {
    if (!has(campo) || isNull(campo)) return null
    val valor = opt(campo) ?: return null
    val texto = valor as? String ?: valor.toString()
    return texto.takeUnless { it.isEmpty() }
}

fun JSONObject.textoObrigatorio(campo: String): String =
    texto(campo) ?: throw RespostaInvalida("campo '$campo' ausente ou vazio na resposta do servidor")

/** Número inteiro; aceita número ou texto numérico. Null se falta, nulo ou não é número. */
fun JSONObject.inteiro(campo: String): Int? {
    if (!has(campo) || isNull(campo)) return null
    return when (val valor = opt(campo)) {
        is Number -> valor.toInt()
        is String -> valor.trim().toIntOrNull() ?: valor.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
}

/** Número com casas; aceita número ou texto numérico. Null se falta, nulo ou não é número. */
fun JSONObject.decimal(campo: String): Double? {
    if (!has(campo) || isNull(campo)) return null
    return when (val valor = opt(campo)) {
        is Number -> valor.toDouble().takeIf { it.isFinite() }
        is String -> valor.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}

/** Verdadeiro/falso; aceita booleano ou o texto "true"/"false". Null em qualquer outro caso. */
fun JSONObject.logico(campo: String): Boolean? {
    if (!has(campo) || isNull(campo)) return null
    return when (val valor = opt(campo)) {
        is Boolean -> valor
        is String -> when (valor.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
}

/** Lista de textos sem itens vazios/nulos. Null se o campo falta ou não sobra nenhum. */
fun JSONObject.listaDeTextos(campo: String): List<String>? {
    val lista = optJSONArray(campo) ?: return null
    val saida = (0 until lista.length()).mapNotNull { i -> lista.textoNa(i) }
    return saida.takeUnless { it.isEmpty() }
}

/** Lista de inteiros, pulando o que não é número. Null se o campo falta ou fica vazia. */
fun JSONObject.listaDeInteiros(campo: String): List<Int>? {
    val lista = optJSONArray(campo) ?: return null
    val saida = (0 until lista.length()).mapNotNull { i ->
        if (lista.isNull(i)) null
        else when (val v = lista.opt(i)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }
    return saida.takeUnless { it.isEmpty() }
}

/** Texto da posição [i] de uma lista, com a mesma regra de [texto]. */
fun JSONArray.textoNa(i: Int): String? {
    if (i < 0 || i >= length() || isNull(i)) return null
    val valor = opt(i) ?: return null
    val texto = (valor as? String ?: valor.toString()).trim()
    return texto.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
}

package br.com.st.totem

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Ferramentas dos testes de CONTRATO servidor → app (OS-161).
 *
 * A ideia: cada resposta do servidor tem um exemplo escrito a partir do código da
 * edge (o arquivo vem citado no teste). O teste confere quatro coisas:
 *  1. [inventario]: toda chave que o servidor manda foi DECIDIDA, lida ou ignorada de
 *     propósito. Campo novo no servidor quebra o teste até alguém decidir. Era o que
 *     faltava quando a cortesia (96-D) e a SENHA chegavam e o app jogava fora.
 *  2. [leDeVerdade]: cada campo dito "lido" muda o resultado quando muda no JSON
 *     (prova que a leitura usa o campo, e não um padrão escondido).
 *  3. [obrigatorios]: tirar um campo obrigatório falha ALTO ([RespostaInvalida]).
 *  4. [semPalavraNull]: campo opcional que vem nulo não vira a palavra "null".
 *
 * Os testes rodam com o org.json do Android (Robolectric), o mesmo do aparelho.
 */
object ContratoKit {

    fun json(texto: String) = JSONObject(texto)

    fun copia(o: JSONObject) = JSONObject(o.toString())

    /** Toda chave do objeto em [caminho] está em [lidas] ou [ignoradas], e toda lida existe. */
    fun inventario(
        nome: String,
        resposta: JSONObject,
        caminho: String,
        lidas: Set<String>,
        ignoradas: Set<String>,
        // O app lê (com padrão escrito) mas o servidor de hoje não manda: herança de
        // formato antigo. Fica listado para ninguém achar que é campo vivo.
        legado: Set<String> = emptySet()
    ) {
        val alvo = (if (caminho.isEmpty()) resposta else valorEm(resposta, caminho)) as? JSONObject
            ?: return fail("$nome: '$caminho' não é um objeto no exemplo")
        val chaves = alvo.keys().asSequence().toSet()
        val semDecisao = chaves - lidas - ignoradas - legado
        assertTrue(
            "$nome: o servidor manda ${semDecisao.sorted()} em '${caminho.ifEmpty { "raiz" }}' " +
                "e ninguém decidiu se o app lê ou ignora. Decida e anote no teste.",
            semDecisao.isEmpty()
        )
        val lidasQueNaoVem = lidas - chaves
        assertTrue(
            "$nome: o app lê ${lidasQueNaoVem.sorted()} em '${caminho.ifEmpty { "raiz" }}', " +
                "mas o servidor não manda: o app vai usar o padrão dele calado.",
            lidasQueNaoVem.isEmpty()
        )
        val dosDois = lidas intersect ignoradas
        assertTrue("$nome: $dosDois está como lido e ignorado ao mesmo tempo", dosDois.isEmpty())
        val legadoQueVoltou = legado intersect chaves
        assertTrue(
            "$nome: $legadoQueVoltou estava como legado (servidor não mandava) e voltou a vir: decida",
            legadoQueVoltou.isEmpty()
        )
    }

    /** Muda cada campo de [caminhos] e confere que a leitura muda junto. */
    fun <T> leDeVerdade(nome: String, base: JSONObject, caminhos: Collection<String>, ler: (JSONObject) -> T) {
        val original = ler(copia(base))
        for (caminho in caminhos) {
            val mexido = copia(base)
            trocarPorSentinela(mexido, caminho)
            val depois = try {
                ler(mexido)
            } catch (e: RespostaInvalida) {
                // A leitura percebeu a mudança e recusou: também prova que lê o campo.
                continue
            } catch (e: Exception) {
                fail("$nome: mudar '$caminho' quebrou a leitura: ${e.message}")
                return
            }
            assertNotEquals(
                "$nome: mudar '$caminho' não mudou nada no que o app leu: o campo não é lido de verdade",
                original, depois
            )
        }
    }

    /** Tirar cada campo de [caminhos] tem de falhar alto com [RespostaInvalida]. */
    fun <T> obrigatorios(nome: String, base: JSONObject, caminhos: Collection<String>, ler: (JSONObject) -> T) {
        for (caminho in caminhos) {
            for (modo in listOf("sem o campo", "campo nulo", "campo vazio")) {
                val mexido = copia(base)
                when (modo) {
                    "sem o campo" -> remover(mexido, caminho)
                    "campo nulo" -> definir(mexido, caminho, JSONObject.NULL)
                    else -> definir(mexido, caminho, "")
                }
                try {
                    ler(mexido)
                    fail("$nome: '$caminho' ($modo) passou calado; era para falhar alto")
                } catch (e: RespostaInvalida) {
                    // é isso que se espera
                }
            }
        }
    }

    /** Põe nulo em cada campo de [caminhos] e confere que a palavra "null" não aparece no resultado. */
    fun <T> nulosNaoViramTexto(nome: String, base: JSONObject, caminhos: Collection<String>, ler: (JSONObject) -> T) {
        val mexido = copia(base)
        caminhos.forEach { definir(mexido, it, JSONObject.NULL) }
        semPalavraNull(nome, ler(mexido))
    }

    /** Varre o objeto lido (data classes, listas, mapas) atrás da palavra "null" como texto. */
    fun semPalavraNull(nome: String, valor: Any?, caminho: String = "", vistos: MutableSet<Int> = HashSet()) {
        when (valor) {
            null, is Number, is Boolean, is Enum<*> -> return
            is String -> assertTrue(
                "$nome: '$caminho' virou a palavra \"null\"",
                !valor.trim().equals("null", ignoreCase = true)
            )
            is Collection<*> -> valor.forEachIndexed { i, v -> semPalavraNull(nome, v, "$caminho[$i]", vistos) }
            is Map<*, *> -> valor.forEach { (k, v) -> semPalavraNull(nome, v, "$caminho.$k", vistos) }
            else -> {
                if (!vistos.add(System.identityHashCode(valor))) return
                if (!valor.javaClass.name.startsWith("br.com.st.")) return
                var classe: Class<*>? = valor.javaClass
                while (classe != null && classe != Any::class.java) {
                    for (campo in classe.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(campo.modifiers)) continue
                        campo.isAccessible = true
                        semPalavraNull(nome, campo.get(valor), "$caminho.${campo.name}", vistos)
                    }
                    classe = classe.superclass
                }
            }
        }
    }

    // ---------- caminhos "a.b.0.c" dentro do JSON ----------

    fun valorEm(raiz: JSONObject, caminho: String): Any? {
        var atual: Any? = raiz
        for (parte in caminho.split('.')) {
            atual = when (atual) {
                is JSONObject -> atual.opt(parte)
                is JSONArray -> atual.opt(parte.toInt())
                else -> return null
            }
        }
        return atual
    }

    private fun paiEChave(raiz: JSONObject, caminho: String): Pair<Any, String> {
        val partes = caminho.split('.')
        var atual: Any = raiz
        for (parte in partes.dropLast(1)) {
            atual = when (atual) {
                is JSONObject -> atual.get(parte)
                is JSONArray -> atual.get(parte.toInt())
                else -> error("caminho '$caminho' não existe")
            }
        }
        return atual to partes.last()
    }

    fun definir(raiz: JSONObject, caminho: String, valor: Any) {
        val (pai, chave) = paiEChave(raiz, caminho)
        when (pai) {
            is JSONObject -> pai.put(chave, valor)
            is JSONArray -> pai.put(chave.toInt(), valor)
        }
    }

    fun remover(raiz: JSONObject, caminho: String) {
        val (pai, chave) = paiEChave(raiz, caminho)
        when (pai) {
            is JSONObject -> pai.remove(chave)
            is JSONArray -> pai.remove(chave.toInt())
        }
    }

    private fun trocarPorSentinela(raiz: JSONObject, caminho: String) {
        val novo: Any = when (val atual = valorEm(raiz, caminho)) {
            is String -> "SENTINELA ${caminho.uppercase()}"
            is Int -> atual + 7
            is Long -> atual + 7
            is Double -> atual + 7.25
            is Number -> atual.toDouble() + 7.25
            is Boolean -> !atual
            is JSONArray -> JSONArray()
            is JSONObject -> JSONObject()
            else -> "SENTINELA ${caminho.uppercase()}"
        }
        definir(raiz, caminho, novo)
    }

    fun <T> assertLeIgual(esperado: T, obtido: T, oQue: String) = assertEquals(oQue, esperado, obtido)
}

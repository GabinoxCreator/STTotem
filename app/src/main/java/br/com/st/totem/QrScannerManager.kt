package br.com.st.totem

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import com.imagealgorithmlab.barcode.DecodeMode
import com.imagealgorithmlab.barcode.SymbologyData
import com.imagealgorithmlab.barcode.camera.DecoderLibrary

/**
 * QrScannerManager — acende o leitor de código do SK-210.
 *
 * POR QUE ISTO EXISTE (10/09/2026): o SK-210 tem um módulo de leitura embaixo
 * da tela — o mesmo que a portaria do Marcel já usava. A `DecodeLibrary` da
 * Gertec vinha declarada no build deste app desde o começo, mas NENHUMA linha
 * chamava ela. Resultado no equipamento: passar o ingresso no leitor não fazia
 * absolutamente nada, porque não havia nada ligado para ler.
 *
 * COMO A GENTE DESCOBRE QUAL CÂMERA É O LEITOR: a biblioteca fala em "tipos de
 * câmera" (BackFacing, Camera2, Camera3…) e qual deles é o módulo de baixo
 * depende do aparelho. O totem de teste fica longe desta máquina, então não dá
 * para espiar pelo cabo: o app **procura sozinho**. Liga o primeiro tipo, e se
 * ninguém decodificar nada em alguns segundos, passa para o próximo, até que um
 * leia. O que leu é gravado e, da próxima vez, é o primeiro a ser tentado —
 * quem estiver na portaria não passa mais por essa procura.
 *
 * REGRA DE OURO: este totem VENDE. Falha do leitor não pode derrubar o app nem
 * travar a tela. Tudo aqui é try/catch e, no pior caso, o leitor simplesmente
 * não acende — a pessoa digita o código de 8 letras, que continua na tela.
 */
class QrScannerManager(
    private val activity: Activity,
    private val holder: ViewGroup,
    private val storage: LocalStorageManager,
    /** Código lido. Vem na UI thread. */
    private val onCodigo: (String) -> Unit,
    /** Log para o Supabase — o único jeito de saber o que aconteceu no aparelho. */
    private val onLog: (event: String, detail: JSONObject, severity: String) -> Unit,
) {
    companion object {
        private const val TAG = "SCANNER"

        /**
         * Ordem da procura. O módulo de leitura costuma se apresentar como a
         * câmera "de trás" — a frontal fica por último de propósito: ela é a do
         * rosto, aponta para a cara da pessoa e não é o leitor que queremos.
         */
        private val ORDEM = listOf(
            DecoderLibrary.CameraType.BackFacing,
            DecoderLibrary.CameraType.Camera2,
            DecoderLibrary.CameraType.Camera3,
            DecoderLibrary.CameraType.Camera4,
            DecoderLibrary.CameraType.FrontFacing,
        )

        /** Quanto esperar numa câmera antes de tentar a próxima, na procura. */
        private const val PROCURA_MS = 7000L

        /** Chave onde fica gravado o tipo que já leu neste aparelho. */
        private const val PREF_TIPO = "scanner_camera_type"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var decoder: DecoderLibrary? = null
    private var preview: View? = null
    private var ligado = false
    private var indice = 0
    /** Só procura quando ainda não sabemos qual é o leitor deste aparelho. */
    private var procurando = false

    private val trocarDeCamera = Runnable {
        if (!ligado || !procurando) return@Runnable
        indice += 1
        if (indice >= ORDEM.size) {
            onLog(
                "scanner_nao_encontrado",
                JSONObject().put("tipos_tentados", ORDEM.size),
                "warning",
            )
            desligar()
            return@Runnable
        }
        Log.i(TAG, "nada lido — tentando ${ORDEM[indice]}")
        abrir(ORDEM[indice])
    }

    /** O aparelho já sabe qual é o leitor dele? */
    fun tipoConhecido(): String? = storage.getScannerCameraType()

    /**
     * Liga o leitor. Chamado quando a tela de check-in entra no caminho do QR —
     * nunca antes: luz acesa o tempo todo em quiosque é lâmpada queimando à toa.
     */
    fun ligar() {
        if (ligado) return
        ligado = true

        val salvo = storage.getScannerCameraType()
        val tipo = ORDEM.firstOrNull { it.name == salvo }
        if (tipo != null) {
            procurando = false
            indice = ORDEM.indexOf(tipo)
            abrir(tipo)
        } else {
            procurando = true
            indice = 0
            abrir(ORDEM[0])
        }
    }

    /** Desliga e devolve a câmera. Sem isto, a facial não abre depois. */
    fun desligar() {
        ligado = false
        procurando = false
        handler.removeCallbacks(trocarDeCamera)
        fecharCamera()
    }

    private fun abrir(tipo: DecoderLibrary.CameraType) {
        fecharCamera()
        try {
            val dec = DecoderLibrary.sharedObject(activity)
            decoder = dec

            dec.setCallback { lista -> aoDecodificar(lista) }
            try { dec.setDecodeMode(DecodeMode.DECODERF) } catch (_: Throwable) {}
            try { dec.setFocusMode(DecoderLibrary.Focus.Focus_Normal) } catch (_: Throwable) {}
            try { dec.setDisplayOrientation(DecoderLibrary.Rotation.ROTATION_0) } catch (_: Throwable) {}

            val aceitou = try { dec.setCameraType(tipo) } catch (_: Throwable) { false }
            if (!aceitou) {
                Log.w(TAG, "aparelho não tem $tipo")
                proximaOuDesistir(tipo, "tipo_recusado")
                return
            }

            // A biblioteca é de Camera1/Camera2: ela precisa de uma superfície de
            // preview existindo, mesmo que ninguém veja. O holder tem 1dp e fica
            // atrás da WebView.
            try {
                val v = dec.cameraPreview
                if (v != null) {
                    preview = v
                    holder.removeAllViews()
                    holder.addView(v)
                }
            } catch (_: Throwable) { /* alguns aparelhos dispensam a preview */ }

            dec.setPreviewOn(false)
            dec.startCameraPreview()
            dec.startDecoding()
            // A luz do leitor: além de iluminar o ingresso, é ela que deixa
            // VER qual módulo acendeu — a nossa única pista sem cabo.
            try { dec.setTorch(true) } catch (_: Throwable) {}

            Log.i(TAG, "leitor aberto em $tipo")
            onLog(
                "scanner_aberto",
                JSONObject().put("camera_type", tipo.name).put("procurando", procurando),
                "info",
            )

            if (procurando) {
                handler.removeCallbacks(trocarDeCamera)
                handler.postDelayed(trocarDeCamera, PROCURA_MS)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "falha ao abrir $tipo: ${e.message}")
            proximaOuDesistir(tipo, e.message ?: "erro")
        }
    }

    private fun proximaOuDesistir(tipo: DecoderLibrary.CameraType, motivo: String) {
        onLog(
            "scanner_falhou",
            JSONObject().put("camera_type", tipo.name).put("motivo", motivo),
            "warning",
        )
        if (!ligado || !procurando) {
            // Era o tipo gravado e ele falhou: o aparelho mudou (ou a gravação
            // estava errada). Apaga e procura de novo na próxima vez.
            storage.saveScannerCameraType(null)
            desligar()
            return
        }
        handler.removeCallbacks(trocarDeCamera)
        handler.post(trocarDeCamera)
    }

    private fun aoDecodificar(lista: ArrayList<SymbologyData>?) {
        val dado = lista?.firstOrNull()?.data?.trim().orEmpty()
        if (dado.isEmpty()) return

        // Achamos o leitor deste aparelho: grava para nunca mais procurar.
        val tipo = ORDEM.getOrNull(indice)
        if (procurando && tipo != null) {
            procurando = false
            handler.removeCallbacks(trocarDeCamera)
            storage.saveScannerCameraType(tipo.name)
            onLog("scanner_descoberto", JSONObject().put("camera_type", tipo.name), "info")
            Log.i(TAG, "leitor deste aparelho é $tipo — gravado")
        }

        onLog(
            "scanner_leu",
            JSONObject().put("tamanho", dado.length).put("simbologia", lista?.firstOrNull()?.name ?: ""),
            "info",
        )
        // NUNCA logar o conteúdo: é o código do ingresso de alguém.
        handler.post { onCodigo(dado) }
    }

    private fun fecharCamera() {
        val dec = decoder
        decoder = null
        if (dec != null) {
            try { dec.setTorch(false) } catch (_: Throwable) {}
            try { dec.stopDecoding() } catch (_: Throwable) {}
            try { dec.stopCameraPreview() } catch (_: Throwable) {}
            try { dec.closeCamera() } catch (_: Throwable) {}
            try { dec.closeSharedObject() } catch (_: Throwable) {}
        }
        preview = null
        try { holder.removeAllViews() } catch (_: Throwable) {}
    }
}

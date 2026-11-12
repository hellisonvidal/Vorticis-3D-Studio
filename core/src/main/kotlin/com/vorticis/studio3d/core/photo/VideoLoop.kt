package com.vorticis.studio3d.core.photo

import kotlin.math.max
import kotlin.math.min

/**
 * Vídeo curto → vistas para o casco visual.
 * Descobre em qual quadro o vídeo completa uma volta de 360° ao redor do objeto
 * (o quadro que mais se parece com o primeiro, depois da metade do vídeo) e
 * escolhe quadros igualmente espaçados dentro dessa volta.
 */
object VideoLoop {

    class Plan(val frames: List<Int>, val loopLength: Int, val detected: Boolean, val score: Float) {
        fun explanation(total: Int): String =
            if (detected) "Detectei que o vídeo completa uma volta no quadro $loopLength de $total (semelhança ${(score * 100).toInt()}%) e usei ${frames.size} quadros dessa volta."
            else "Não encontrei o fim exato da volta; considerei que o vídeo inteiro é uma volta de 360° e usei ${frames.size} quadros."
    }

    private class Signature(val shape: BooleanArray, val hist: FloatArray)

    private const val GW = 24
    private const val GH = 32

    private fun signature(v: PhotoView): Signature? {
        val bb = v.mask.bbox() ?: return null
        val bw = bb[2] - bb[0] + 1; val bh = bb[3] - bb[1] + 1
        val shape = BooleanArray(GW * GH)
        for (j in 0 until GH) for (i in 0 until GW) {
            shape[j * GW + i] = v.mask[bb[0] + i * bw / GW, bb[1] + j * bh / GH]
        }
        val hist = FloatArray(64)
        var n = 0
        val step = max(1, (bw * bh) / 4000)
        var k = 0
        for (y in bb[1]..bb[3]) for (x in bb[0]..bb[2]) {
            if (k++ % step != 0 || !v.mask[x, y]) continue
            val c = v.pixels[y * v.width + x]
            val bin = (((c shr 22) and 3) shl 4) or (((c shr 14) and 3) shl 2) or ((c shr 6) and 3)
            hist[bin]++; n++
        }
        if (n > 0) for (i in hist.indices) hist[i] /= n
        return Signature(shape, hist)
    }

    private fun similarity(a: Signature, b: Signature): Float {
        var inter = 0; var uni = 0
        for (i in a.shape.indices) { if (a.shape[i] && b.shape[i]) inter++; if (a.shape[i] || b.shape[i]) uni++ }
        val iou = if (uni > 0) inter.toFloat() / uni else 0f
        var h = 0f
        for (i in a.hist.indices) h += min(a.hist[i], b.hist[i])
        return 0.5f * iou + 0.5f * h
    }

    fun plan(frames: List<PhotoView>, maxViews: Int = 12): Plan {
        val n = frames.size
        val sig = frames.map { signature(it) }
        var bestK = -1; var bestScore = 0f
        if (n >= 8 && sig[0] != null) {
            // nota de cada candidato: parecido com o início, e o quadro seguinte parecido com o 2º quadro
            val start = (n * 0.55f).toInt()
            val scores = FloatArray(n)
            for (k in start until n) {
                val s0 = sig[k]?.let { similarity(sig[0]!!, it) } ?: 0f
                val s1 = if (k + 1 < n && sig[1] != null && sig[k + 1] != null) similarity(sig[1]!!, sig[k + 1]!!) else s0
                scores[k] = (s0 * 2 + s1) / 3
            }
            // referência: quão diferente é o meio do vídeo (lado oposto do objeto)
            val mid = sig[n / 2]?.let { similarity(sig[0]!!, it) } ?: 0f
            for (k in start until n) if (scores[k] > bestScore) { bestScore = scores[k]; bestK = k }
            if (bestScore < 0.78f || bestScore < mid + 0.06f) bestK = -1
        }
        // fim da volta colado no fim do vídeo: o vídeo inteiro é (quase exatamente) uma volta
        if (bestK > 0 && bestK >= n - max(3, n / 8)) bestK = n
        val detected = bestK > 0
        val loop = if (detected) bestK else n
        val count = min(maxViews, loop)
        val chosen = (0 until count).map { it * loop / count }.filter { sig[it] != null }
        return Plan(chosen, loop, detected, bestScore)
    }
}

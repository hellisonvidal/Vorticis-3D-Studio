package com.vorticis.studio3d.core.photo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Nuvem de pontos usada na animação de processamento:
 * 1) os pontos formam a foto num plano; 2) depois do recorte, o fundo se desfaz
 * e os pontos do objeto avançam para uma forma 3D aproximada (na mesma escala
 * e posição do modelo final, para a transição ser contínua).
 * Dados intercalados por ponto: plano(xyz), alvo(xyz), cor(rgb), objeto(0/1), semente(0..1).
 */
class Cloud(val count: Int, val data: FloatArray, val masked: Boolean) {
    companion object { const val STRIDE = 11 }
}

object PreviewCloud {

    fun build(pixels: IntArray, w: Int, h: Int, mask: Mask? = null, depth: DepthMap? = null, maxPoints: Int = 22000): Cloud {
        val step = max(1, sqrt(w.toFloat() * h / maxPoints).toInt() + 1)
        val gw = (w + step - 1) / step; val gh = (h + step - 1) / step
        val n = gw * gh
        val d = FloatArray(n * Cloud.STRIDE)
        val ps = 1.2f / max(w, h)
        // escala do modelo final (mesma regra do PhotoTo3D: maior lado do recorte = 1,2)
        val bb = mask?.bbox()
        val k = if (bb != null) 1.2f / max(bb[2] - bb[0] + 1, bb[3] - bb[1] + 1) else ps
        val cxObj = if (bb != null) (bb[0] + bb[2]) / 2f else w / 2f
        val bottom = bb?.get(3)?.toFloat() ?: h.toFloat()
        // distância à borda do objeto na grade de pontos -> volume aproximado
        val dist = FloatArray(n)
        if (mask != null) {
            val inside = BooleanArray(n) { i -> mask[(i % gw) * step, (i / gw) * step] }
            for (i in 0 until n) dist[i] = if (inside[i]) 1e9f else 0f
            for (j in 0 until gh) for (i in 0 until gw) { val q = j * gw + i; if (dist[q] == 0f) continue
                var v = if (i == 0 || j == 0) 1f else dist[q]
                if (i > 0) v = min(v, dist[q - 1] + 1); if (j > 0) v = min(v, dist[q - gw] + 1); dist[q] = v }
            for (j in gh - 1 downTo 0) for (i in gw - 1 downTo 0) { val q = j * gw + i; if (dist[q] == 0f) continue
                var v = if (i == gw - 1 || j == gh - 1) 1f else dist[q]
                if (i < gw - 1) v = min(v, dist[q + 1] + 1); if (j < gh - 1) v = min(v, dist[q + gw] + 1); dist[q] = v }
        }
        val dmax = max(1f, dist.maxOrNull() ?: 1f)
        var rnd = 12345
        for (q in 0 until n) {
            val x = (q % gw) * step; val y = (q / gw) * step
            val c = pixels[min(y, h - 1) * w + min(x, w - 1)]
            val o = q * Cloud.STRIDE
            d[o] = (x - w / 2f) * ps; d[o + 1] = (h - y) * ps; d[o + 2] = 0f
            val fg = mask != null && dist[q] > 0f
            if (fg) {
                var z = sqrt(dist[q] * (2 * dmax - dist[q])) / dmax * 0.18f
                if (depth != null) z = z * 0.5f + depth.sample(x.toFloat() / w, y.toFloat() / h).coerceIn(0f, 1f) * 0.15f
                val side = if (q % 2 == 0) 1f else -0.8f
                d[o + 3] = (x - cxObj) * k; d[o + 4] = (bottom - y) * k; d[o + 5] = z * side * 1.2f
            } else { d[o + 3] = d[o]; d[o + 4] = d[o + 1]; d[o + 5] = 0f }
            d[o + 6] = ((c shr 16) and 255) / 255f; d[o + 7] = ((c shr 8) and 255) / 255f; d[o + 8] = (c and 255) / 255f
            d[o + 9] = if (fg || mask == null) 1f else 0f
            rnd = rnd * 1103515245 + 12345
            d[o + 10] = ((rnd ushr 8) and 0xFFFF) / 65535f
        }
        return Cloud(n, d, mask != null)
    }

    /** Normaliza um mapa de profundidade para 0..1 (para a prévia). */
    fun normalized(dm: DepthMap): DepthMap {
        val s = dm.data.sorted()
        val lo = s[s.size / 50]; val hi = s[s.size - 1 - s.size / 50]
        val r = max(1e-6f, hi - lo)
        return DepthMap(dm.width, dm.height, FloatArray(dm.data.size) { ((dm.data[it] - lo) / r).coerceIn(0f, 1f) })
    }
}

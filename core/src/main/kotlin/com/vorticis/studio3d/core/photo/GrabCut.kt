package com.vorticis.studio3d.core.photo

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Recorte do objeto principal sem rede neural, inspirado no GrabCut:
 * alterna entre (1) modelar as cores do objeto e do fundo com misturas de
 * gaussianas e (2) reclassificar cada pixel pela razão de verossimilhança,
 * somada a uma prioridade espacial para o centro e a uma suavização regional.
 * Funciona bem melhor que o modelo só de borda em fundos movimentados.
 */
object GrabCut {

    private class Gmm(val mean: Array<FloatArray>, val varc: FloatArray, val weight: FloatArray) {
        fun logLik(r: Float, g: Float, b: Float): Float {
            var p = 0.0
            for (k in mean.indices) {
                val dr = r - mean[k][0]; val dg = g - mean[k][1]; val db = b - mean[k][2]
                val d2 = dr * dr + dg * dg + db * db
                p += weight[k] * exp(-d2 / (2.0 * varc[k])) / (varc[k] * sqrt(varc[k].toDouble()))
            }
            return ln(p + 1e-30).toFloat()
        }
    }

    private fun fit(samples: List<FloatArray>, k: Int): Gmm {
        if (samples.isEmpty()) return Gmm(arrayOf(floatArrayOf(128f, 128f, 128f)), floatArrayOf(4000f), floatArrayOf(1f))
        val kk = min(k, samples.size)
        val centers = Array(kk) { samples[(it * samples.size) / kk].copyOf() }
        val assign = IntArray(samples.size)
        repeat(8) {
            for ((i, s) in samples.withIndex()) {
                var best = 0; var bd = Float.MAX_VALUE
                for (c in 0 until kk) {
                    val d = (s[0] - centers[c][0]).let { it * it } + (s[1] - centers[c][1]).let { it * it } + (s[2] - centers[c][2]).let { it * it }
                    if (d < bd) { bd = d; best = c }
                }
                assign[i] = best
            }
            for (c in 0 until kk) {
                var n = 0; var r = 0f; var g = 0f; var b = 0f
                for (i in samples.indices) if (assign[i] == c) { n++; r += samples[i][0]; g += samples[i][1]; b += samples[i][2] }
                if (n > 0) centers[c] = floatArrayOf(r / n, g / n, b / n)
            }
        }
        val varc = FloatArray(kk); val w = FloatArray(kk); val cnt = IntArray(kk)
        for (i in samples.indices) {
            val c = assign[i]; cnt[c]++
            val s = samples[i]
            varc[c] += ((s[0] - centers[c][0]).let { it * it } + (s[1] - centers[c][1]).let { it * it } + (s[2] - centers[c][2]).let { it * it }) / 3f
        }
        for (c in 0 until kk) {
            varc[c] = (if (cnt[c] > 0) varc[c] / cnt[c] else 400f).coerceIn(60f, 3000f)
            w[c] = (cnt[c] + 1f) / (samples.size + kk)
        }
        return Gmm(centers, varc, w)
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat())

    fun segment(pixels: IntArray, w: Int, h: Int, iterations: Int = 5): Mask {
        // 1) trabalha numa versão reduzida
        val scale = min(1f, 180f / max(w, h))
        val sw = max(8, (w * scale).toInt()); val sh = max(8, (h * scale).toInt())
        val small = IntArray(sw * sh) { i -> val x = (i % sw) * w / sw; val y = (i / sw) * h / sh; pixels[y * w + x] }
        // fundo garantido: faixas de cima/esquerda/direita (a base costuma cortar o objeto)
        val band = max(2, (min(sw, sh) * 0.025f).toInt())
        val hardBg = BooleanArray(sw * sh) { i -> val x = i % sw; val y = i / sw; y < band || x < band || x >= sw - band }

        // ponto de partida: modelo de borda, se plausível; senão, elipse central
        var mask = Segmentation.borderModel(small, sw, sh)
        val f0 = mask.area.toFloat() / (sw * sh)
        if (f0 < 0.04f || f0 > 0.8f) mask = Segmentation.centerEllipse(sw, sh)
        else {
            // une com o miolo central para não perder partes de cor parecida com o fundo
            val e = Segmentation.centerEllipse(sw, sh)
            val core = Segmentation.erode(e, max(1, sw / 8))
            for (i in mask.data.indices) mask.data[i] = mask.data[i] || core.data[i]
        }

        var fg: Gmm; var bg: Gmm
        val lr = FloatArray(sw * sh)
        repeat(iterations) {
            val fgS = ArrayList<FloatArray>(); val bgS = ArrayList<FloatArray>()
            val step = max(1, (sw * sh) / 6000)
            for (i in 0 until sw * sh step step) (if (mask.data[i] && !hardBg[i]) fgS else bgS).add(rgb(small[i]))
            fg = fit(fgS, 6); bg = fit(bgS, 8)
            val bb = mask.bbox() ?: intArrayOf(sw / 4, sh / 4, 3 * sw / 4, 3 * sh / 4)
            val cx = (bb[0] + bb[2]) / 2f; val cy = (bb[1] + bb[3]) / 2f
            val rx = max(4f, (bb[2] - bb[0]) / 2f + sw * 0.08f); val ry = max(4f, (bb[3] - bb[1]) / 2f + sh * 0.08f)
            for (i in 0 until sw * sh) {
                val c = rgb(small[i])
                val x = i % sw; val y = i / sw
                val dx = (x - cx) / rx; val dy = (y - cy) / ry
                val prior = 1.2f * (0.6f - (dx * dx + dy * dy)).coerceIn(-2.5f, 0.6f)
                lr[i] = (fg.logLik(c[0], c[1], c[2]) - bg.logLik(c[0], c[1], c[2])).coerceIn(-12f, 12f) + prior
            }
            val sm = boxBlur(boxBlur(lr, sw, sh, 1), sw, sh, 1)
            val next = Mask(sw, sh)
            for (i in 0 until sw * sh) next.data[i] = sm[i] > 0f && !hardBg[i]
            mask = clean(next)
        }
        // 2) resultado final na resolução original (bordas melhores)
        val big = mask.resize(w, h)
        val r = max(1, min(w, h) / 150)
        var m = Segmentation.dilate(Segmentation.erode(big, r), r)
        m = Segmentation.erode(Segmentation.dilate(m, r), r)
        m = Segmentation.largestComponent(Segmentation.fillHoles(m))
        val frac = m.area.toFloat() / (w * h)
        return if (frac < 0.01f || frac > 0.97f) Segmentation.centerEllipse(w, h) else m
    }

    private fun clean(m: Mask): Mask {
        var x = Segmentation.erode(Segmentation.dilate(m, 1), 1)
        x = Segmentation.dilate(Segmentation.erode(x, 1), 1)
        return Segmentation.largestComponent(Segmentation.fillHoles(x))
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val o = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += a[yy * w + xx]; n++ }
            }
            o[y * w + x] = s / n
        }
        return o
    }
}

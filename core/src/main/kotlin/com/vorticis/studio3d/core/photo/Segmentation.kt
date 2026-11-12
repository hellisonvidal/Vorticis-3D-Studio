package com.vorticis.studio3d.core.photo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Máscara binária de largura w e altura h. */
class Mask(val w: Int, val h: Int, val data: BooleanArray = BooleanArray(w * h)) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && data[y * w + x]
    operator fun set(x: Int, y: Int, v: Boolean) { data[y * w + x] = v }
    val area: Int get() = data.count { it }

    fun bbox(): IntArray? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (data[y * w + x]) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
    }

    /** Redimensiona por vizinho mais próximo. */
    fun resize(nw: Int, nh: Int): Mask {
        val m = Mask(nw, nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            m.data[y * nw + x] = data[(y * h / nh) * w + (x * w / nw)]
        }
        return m
    }

    companion object {
        fun fromAlpha(w: Int, h: Int, conf: FloatArray, threshold: Float = 0.5f): Mask {
            val m = Mask(w, h)
            for (i in conf.indices) m.data[i] = conf[i] >= threshold
            return m
        }
    }
}

/**
 * Segmentação automática do objeto principal sem IA externa
 * (usada quando o ML Kit não está disponível no aparelho):
 * modela o fundo pelas cores da borda, limiariza por Otsu,
 * aplica morfologia, preenche buracos e mantém o maior componente.
 */
object Segmentation {

    fun auto(pixels: IntArray, w: Int, h: Int): Mask {
        // 1) cores de fundo: k-means (k=4) sobre os pixels da borda
        val border = ArrayList<FloatArray>()
        val step = max(1, (w + h) / 400)
        for (x in 0 until w step step) { border += rgb(pixels[x]); border += rgb(pixels[(h - 1) * w + x]) }
        for (y in 0 until h step step) { border += rgb(pixels[y * w]); border += rgb(pixels[y * w + w - 1]) }
        val centers = kmeans(border, 4)

        // 2) distância de cada pixel à cor de fundo mais próxima
        val dist = FloatArray(w * h)
        for (i in 0 until w * h) {
            val c = rgb(pixels[i])
            var best = Float.MAX_VALUE
            for (k in centers) {
                val dr = c[0] - k[0]; val dg = c[1] - k[1]; val db = c[2] - k[2]
                best = min(best, dr * dr + dg * dg + db * db)
            }
            dist[i] = sqrt(best)
        }
        // suaviza a distância (box blur 3x3) para reduzir ruído
        val sm = blur(dist, w, h)
        val t = otsu(sm).coerceAtLeast(18f)
        var mask = Mask(w, h)
        for (i in sm.indices) mask.data[i] = sm[i] > t

        // 3) morfologia: fecha pequenas falhas e remove ruído
        val r = max(1, min(w, h) / 120)
        mask = erode(dilate(mask, r), r)
        mask = dilate(erode(mask, r), r)
        // 4) preenche buracos e mantém o componente principal
        mask = fillHoles(mask)
        mask = largestComponent(mask)
        // se falhou (quase vazio ou quase tudo), usa elipse central como fallback
        val frac = mask.area.toFloat() / (w * h)
        if (frac < 0.01f || frac > 0.97f) mask = centerEllipse(w, h)
        return mask
    }

    fun centerEllipse(w: Int, h: Int): Mask {
        val m = Mask(w, h)
        val cx = w / 2f; val cy = h / 2f; val rx = w * 0.38f; val ry = h * 0.42f
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / rx; val dy = (y - cy) / ry
            m.data[y * w + x] = dx * dx + dy * dy <= 1f
        }
        return m
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat())

    private fun kmeans(pts: List<FloatArray>, k: Int): List<FloatArray> {
        if (pts.isEmpty()) return listOf(floatArrayOf(255f, 255f, 255f))
        val centers = (0 until k).map { pts[it * pts.size / k].copyOf() }.toMutableList()
        val assign = IntArray(pts.size)
        repeat(12) {
            for ((i, p) in pts.withIndex()) {
                var best = 0; var bd = Float.MAX_VALUE
                for ((j, c) in centers.withIndex()) {
                    val d = (p[0] - c[0]) * (p[0] - c[0]) + (p[1] - c[1]) * (p[1] - c[1]) + (p[2] - c[2]) * (p[2] - c[2])
                    if (d < bd) { bd = d; best = j }
                }
                assign[i] = best
            }
            for (j in centers.indices) {
                var n = 0; val s = FloatArray(3)
                for (i in pts.indices) if (assign[i] == j) { n++; s[0] += pts[i][0]; s[1] += pts[i][1]; s[2] += pts[i][2] }
                if (n > 0) centers[j] = floatArrayOf(s[0] / n, s[1] / n, s[2] / n)
            }
        }
        // descarta centros pouco representativos (podem ser o próprio objeto tocando a borda)
        val counts = centers.indices.map { j -> assign.count { it == j } }
        return centers.filterIndexed { j, _ -> counts[j] >= pts.size * 0.08f }.ifEmpty { centers }
    }

    private fun blur(a: FloatArray, w: Int, h: Int): FloatArray {
        val o = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += a[yy * w + xx]; n++ }
            }
            o[y * w + x] = s / n
        }
        return o
    }

    private fun otsu(v: FloatArray): Float {
        val maxV = v.maxOrNull() ?: return 0f
        if (maxV <= 0f) return 0f
        val bins = 256
        val hist = IntArray(bins)
        for (x in v) hist[((x / maxV) * (bins - 1)).toInt()]++
        val total = v.size
        var sum = 0.0
        for (i in 0 until bins) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var thr = 0
        for (i in 0 until bins) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = i }
        }
        return thr.toFloat() / (bins - 1) * maxV
    }

    fun dilate(m: Mask, r: Int): Mask = morph(m, r, true)
    fun erode(m: Mask, r: Int): Mask = morph(m, r, false)

    /** Morfologia separável (quadrado 2r+1). */
    private fun morph(m: Mask, r: Int, dilate: Boolean): Mask {
        val w = m.w; val h = m.h
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = !dilate
            for (d in -r..r) {
                val xx = x + d
                val s = if (xx in 0 until w) m.data[y * w + xx] else false
                if (dilate && s) { v = true; break }
                if (!dilate && !s) { v = false; break }
            }
            tmp[y * w + x] = v
        }
        val out = Mask(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = !dilate
            for (d in -r..r) {
                val yy = y + d
                val s = if (yy in 0 until h) tmp[yy * w + x] else false
                if (dilate && s) { v = true; break }
                if (!dilate && !s) { v = false; break }
            }
            out.data[y * w + x] = v
        }
        return out
    }

    /** Preenche regiões de fundo que não tocam a borda. */
    fun fillHoles(m: Mask): Mask {
        val w = m.w; val h = m.h
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun push(x: Int, y: Int) {
            val i = y * w + x
            if (!m.data[i] && !outside[i]) { outside[i] = true; stack[sp++] = i }
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) push(x - 1, y); if (x < w - 1) push(x + 1, y)
            if (y > 0) push(x, y - 1); if (y < h - 1) push(x, y + 1)
        }
        val out = Mask(w, h)
        for (i in 0 until w * h) out.data[i] = !outside[i]
        return out
    }

    /** Mantém o maior componente conectado (com bônus para o que cobre o centro). */
    fun largestComponent(m: Mask): Mask {
        val w = m.w; val h = m.h
        val label = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        var bestLabel = -1; var bestScore = -1.0
        var next = 0
        for (start in 0 until w * h) {
            if (!m.data[start] || label[start] >= 0) continue
            var sp = 0; var size = 0; var central = 0
            label[start] = next; stack[sp++] = start
            while (sp > 0) {
                val i = stack[--sp]; size++
                val x = i % w; val y = i / w
                if (x > w / 4 && x < 3 * w / 4 && y > h / 4 && y < 3 * h / 4) central++
                val nb = intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)
                for (j in nb) if (j >= 0 && m.data[j] && label[j] < 0) { label[j] = next; stack[sp++] = j }
            }
            val score = size + central * 2.0
            if (score > bestScore) { bestScore = score; bestLabel = next }
            next++
        }
        val out = Mask(w, h)
        for (i in 0 until w * h) out.data[i] = label[i] == bestLabel && bestLabel >= 0
        return out
    }
}

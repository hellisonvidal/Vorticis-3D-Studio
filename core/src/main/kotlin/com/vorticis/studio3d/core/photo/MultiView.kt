package com.vorticis.studio3d.core.photo

import com.vorticis.studio3d.core.geom.MeshBuilder
import com.vorticis.studio3d.core.geom.MeshOps
import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Mesh
import com.vorticis.studio3d.core.model.Texture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Uma foto de um ângulo do objeto. */
class PhotoView(val pixels: IntArray, val width: Int, val height: Int, val mask: Mask)

/** Como as fotos foram tiradas. */
enum class CaptureLayout(val label: String) {
    /** Fotos em volta do objeto, com ângulos iguais entre elas (andando para a direita). */
    AROUND("Ao redor (360°)"),
    /** Mesmo arranjo, mas andando para a esquerda. */
    AROUND_LEFT("Ao redor (sentido inverso)"),
    /** Vistas fixas: frente, lado direito, trás, lado esquerdo (90° entre elas). */
    ORTHO("Frente / lado / trás"),
}

data class MultiViewOptions(
    val layout: CaptureLayout = CaptureLayout.AROUND,
    /** células no maior eixo da grade 3D */
    val resolution: Int = 96,
    val targetSize: Float = 1.2f,
    val smoothing: Int = 3,
)

/**
 * Reconstrução 3D por várias fotos ("casco visual" / shape-from-silhouette):
 * cada foto, num ângulo conhecido ao redor do eixo vertical, esculpe o volume;
 * o que sobra é a interseção das silhuetas. A superfície é extraída com
 * Surface Nets sobre um campo de distância com sinal (resultado liso e fechado)
 * e a textura é montada projetando as fotos de volta no modelo.
 */
object MultiView {

    fun angles(n: Int, layout: CaptureLayout): List<Float> {
        val base = when {
            layout == CaptureLayout.ORTHO || n == 2 -> List(n) { it * 90f }
            else -> List(n) { it * 360f / n }
        }
        return if (layout == CaptureLayout.AROUND_LEFT) base.map { -it } else base
    }

    /** Dados preparados de cada vista: escala, centro e distância com sinal da silhueta. */
    private class Prepared(
        val v: PhotoView, val angle: Float,
        val scale: Float, val cx: Float, val bottom: Float,
        val sdf: FloatArray,
    ) {
        val rx = cos(angle); val rz = -sin(angle)      // eixo "direita" da imagem no mundo
        val dx = sin(angle); val dz = cos(angle)       // direção da câmera

        /** Distância com sinal (positiva dentro), em unidades do mundo. */
        fun sd(x: Float, y: Float, z: Float): Float {
            val px = cx + (x * rx + z * rz) * scale
            val py = bottom - y * scale
            val ix = px.toInt(); val iy = py.toInt()
            if (px < 0 || py < 0 || ix >= v.width || iy >= v.height) return -1f
            return sdf[iy * v.width + ix] / scale
        }

        fun color(x: Float, y: Float, z: Float): Int {
            val ix = (cx + (x * rx + z * rz) * scale).toInt().coerceIn(0, v.width - 1)
            val iy = (bottom - y * scale).toInt().coerceIn(0, v.height - 1)
            return v.pixels[iy * v.width + ix]
        }
    }

    fun build(views: List<PhotoView>, opt: MultiViewOptions = MultiViewOptions()): PhotoResult {
        require(views.isNotEmpty())
        if (views.size == 1) return PhotoTo3D.build(views[0].pixels, views[0].width, views[0].height, views[0].mask)
        val H = opt.targetSize
        val angs = angles(views.size, opt.layout)
        val prepared = views.mapIndexedNotNull { i, v ->
            val bb = v.mask.bbox() ?: return@mapIndexedNotNull null
            // descarta fotos cujo recorte falhou (objeto ocupando quase tudo ou quase nada)
            val frac = v.mask.area.toFloat() / (v.width * v.height)
            if (frac < 0.01f || frac > 0.9f) return@mapIndexedNotNull null
            // altura do objeto é a mesma em todas as vistas: normaliza a escala por ela
            val hPx = (bb[3] - bb[1] + 1).toFloat()
            val cx = centroidX(v.mask, bb)
            Prepared(v, (angs[i] * PI / 180).toFloat(), hPx / H, cx, bb[3] + 0.5f, signedDistance(v.mask))
        }
        if (prepared.size < 2) {
            val v = views.first { it.mask.bbox() != null }
            return PhotoTo3D.build(v.pixels, v.width, v.height, v.mask)
        }
        fun field(x: Float, y: Float, z: Float): Float {
            var m = Float.MAX_VALUE
            for (p in prepared) { val d = p.sd(x, y, z); if (d < m) m = d; if (m < -0.05f) return m }
            return m
        }

        // extensão horizontal: maior meia-largura entre as vistas
        var R = 0f
        for (p in prepared) {
            val bb = p.v.mask.bbox()!!
            R = max(R, max(p.cx - bb[0], bb[2] + 1 - p.cx) / p.scale)
        }
        R *= 1.05f
        val cell = max(2 * R, H) / opt.resolution
        val nx = ceil(2 * R / cell).toInt() + 3
        val ny = ceil(H / cell).toInt() + 3
        val nz = nx
        val ox = -R - cell; val oy = -cell; val oz = -R - cell
        val f = FloatArray(nx * ny * nz)
        fun id(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            val edge = i == 0 || j == 0 || k == 0 || i == nx - 1 || j == ny - 1 || k == nz - 1
            f[id(i, j, k)] = if (edge) -cell else field(ox + i * cell, oy + j * cell, oz + k * cell)
        }
        val raw = surfaceNets(f, nx, ny, nz, cell, ox, oy, oz)
        if (raw.triangleCount < 20) {
            val v = views[0]
            val r = PhotoTo3D.build(v.pixels, v.width, v.height, v.mask)
            return r.copy(explanation = "As silhuetas não combinaram entre si (confira a ordem das fotos e o sentido da volta). Usei só a primeira foto. " + r.explanation)
        }
        var mesh = if (opt.smoothing > 0) MeshOps.smooth(raw, opt.smoothing) else raw
        // apoia no chão
        val b = mesh.bounds()
        mesh = MeshOps.translate(mesh, 0f, -b.minY, 0f)
        val height = mesh.bounds().sizeY.coerceAtLeast(1e-4f)

        // textura cilíndrica projetada a partir das fotos
        val tex = bakeTexture(prepared, R, H, cell, -b.minY) { x, y, z -> field(x, y + b.minY, z) }
        mesh = cylindricalUv(mesh, height)
        mesh = MeshOps.autoNormals(mesh, 70f)
        val degs = angs.take(prepared.size).joinToString(", ") { "${((it % 360 + 360) % 360).toInt()}°" }
        return PhotoResult(
            mesh, Material("Foto", 0xFFFFFFFF.toInt(), 0f, 0.7f, tex), PhotoMode.MULTIVIEW,
            "Reconstruí pelo cruzamento das silhuetas de ${prepared.size} fotos (ângulos $degs).",
        )
    }

    private fun centroidX(m: Mask, bb: IntArray): Float {
        var s = 0.0; var n = 0
        for (y in bb[1]..bb[3]) for (x in bb[0]..bb[2]) if (m[x, y]) { s += x; n++ }
        return if (n > 0) (s / n).toFloat() + 0.5f else (bb[0] + bb[2]) / 2f
    }

    /** Distância com sinal (chanfro 3-4) em pixels: positiva dentro da silhueta. */
    fun signedDistance(m: Mask): FloatArray {
        val inside = distanceTo(m, false)
        val outside = distanceTo(m, true)
        return FloatArray(m.w * m.h) { i -> if (m.data[i]) inside[i] - 0.5f else -(outside[i] - 0.5f) }
    }

    /** Distância de cada pixel ao pixel mais próximo cujo valor é [target]. */
    private fun distanceTo(m: Mask, target: Boolean): FloatArray {
        val w = m.w; val h = m.h
        val inf = 1e9f
        val d = FloatArray(w * h) { if (m.data[it] == target) 0f else inf }
        for (y in 0 until h) for (x in 0 until w) {
            val k = y * w + x; if (d[k] == 0f) continue
            var v = d[k]
            if (x > 0) v = min(v, d[k - 1] + 1f)
            if (y > 0) { v = min(v, d[k - w] + 1f); if (x > 0) v = min(v, d[k - w - 1] + 1.414f); if (x < w - 1) v = min(v, d[k - w + 1] + 1.414f) }
            d[k] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val k = y * w + x; if (d[k] == 0f) continue
            var v = d[k]
            if (x < w - 1) v = min(v, d[k + 1] + 1f)
            if (y < h - 1) { v = min(v, d[k + w] + 1f); if (x < w - 1) v = min(v, d[k + w + 1] + 1.414f); if (x > 0) v = min(v, d[k + w - 1] + 1.414f) }
            d[k] = v
        }
        // fora da imagem conta como fundo: limita a distância interna pela borda
        if (!target) for (y in 0 until h) for (x in 0 until w) {
            val k = y * w + x
            d[k] = min(d[k], min(min(x + 1f, w - x.toFloat()), min(y + 1f, h - y.toFloat())))
        }
        return d
    }

    /**
     * Surface Nets: um vértice por célula cruzada pela superfície (média dos cruzamentos
     * nas arestas) e um quadrilátero por aresta da grade com troca de sinal.
     * Como a borda da grade é toda "fora", a malha resultante é fechada.
     */
    private fun surfaceNets(f: FloatArray, nx: Int, ny: Int, nz: Int, cell: Float, ox: Float, oy: Float, oz: Float): Mesh {
        fun id(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i
        val cx = nx - 1; val cy = ny - 1; val cz = nz - 1
        val cellVert = IntArray(cx * cy * cz) { -1 }
        fun cid(i: Int, j: Int, k: Int) = (k * cy + j) * cx + i
        val b = MeshBuilder(4096)
        val corners = arrayOf(intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(1, 1, 0), intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(0, 1, 1), intArrayOf(1, 1, 1))
        val edges = arrayOf(
            intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7),
            intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(4, 6), intArrayOf(5, 7),
            intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
        )
        val v = FloatArray(8)
        for (k in 0 until cz) for (j in 0 until cy) for (i in 0 until cx) {
            var mask = 0
            for (c in 0 until 8) {
                v[c] = f[id(i + corners[c][0], j + corners[c][1], k + corners[c][2])]
                if (v[c] > 0f) mask = mask or (1 shl c)
            }
            if (mask == 0 || mask == 255) continue
            var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
            for (e in edges) {
                val a = v[e[0]]; val c = v[e[1]]
                if ((a > 0f) == (c > 0f)) continue
                val t = (a / (a - c)).coerceIn(0f, 1f)
                val pa = corners[e[0]]; val pc = corners[e[1]]
                sx += pa[0] + (pc[0] - pa[0]) * t; sy += pa[1] + (pc[1] - pa[1]) * t; sz += pa[2] + (pc[2] - pa[2]) * t
                n++
            }
            cellVert[cid(i, j, k)] = b.vertex(ox + (i + sx / n) * cell, oy + (j + sy / n) * cell, oz + (k + sz / n) * cell)
        }
        val idxOut = ArrayList<IntArray>()
        // posições já calculadas, usadas para orientar os quadriláteros
        val tmp = b.build(computeNormals = false)
        val P = tmp.positions
        fun quad(a: Int, c: Int, d: Int, e: Int, nxDir: Float, nyDir: Float, nzDir: Float) {
            if (a < 0 || c < 0 || d < 0 || e < 0) return
            // diagonal mais curta
            fun dist2(p: Int, q: Int): Float { val x = P[p * 3] - P[q * 3]; val y = P[p * 3 + 1] - P[q * 3 + 1]; val z = P[p * 3 + 2] - P[q * 3 + 2]; return x * x + y * y + z * z }
            val tris = if (dist2(a, d) <= dist2(c, e)) listOf(intArrayOf(a, c, d), intArrayOf(a, d, e)) else listOf(intArrayOf(a, c, e), intArrayOf(c, d, e))
            for (t in tris) {
                val ux = P[t[1] * 3] - P[t[0] * 3]; val uy = P[t[1] * 3 + 1] - P[t[0] * 3 + 1]; val uz = P[t[1] * 3 + 2] - P[t[0] * 3 + 2]
                val wx = P[t[2] * 3] - P[t[0] * 3]; val wy = P[t[2] * 3 + 1] - P[t[0] * 3 + 1]; val wz = P[t[2] * 3 + 2] - P[t[0] * 3 + 2]
                val crx = uy * wz - uz * wy; val cry = uz * wx - ux * wz; val crz = ux * wy - uy * wx
                if (crx * nxDir + cry * nyDir + crz * nzDir >= 0f) idxOut.add(t) else idxOut.add(intArrayOf(t[0], t[2], t[1]))
            }
        }
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            val f0 = f[id(i, j, k)]
            val in0 = f0 > 0f
            val jOk = j in 1 until ny - 1; val kOk = k in 1 until nz - 1; val iOk = i in 1 until nx - 1
            // aresta em X: (i,j,k)-(i+1,j,k)
            if (i < nx - 1 && jOk && kOk && in0 != (f[id(i + 1, j, k)] > 0f)) {
                val s = if (in0) 1f else -1f
                quad(cellVert[cid(i, j - 1, k - 1)], cellVert[cid(i, j, k - 1)], cellVert[cid(i, j, k)], cellVert[cid(i, j - 1, k)], s, 0f, 0f)
            }
            if (j < ny - 1 && iOk && kOk && in0 != (f[id(i, j + 1, k)] > 0f)) {
                val s = if (in0) 1f else -1f
                quad(cellVert[cid(i - 1, j, k - 1)], cellVert[cid(i, j, k - 1)], cellVert[cid(i, j, k)], cellVert[cid(i - 1, j, k)], 0f, s, 0f)
            }
            if (k < nz - 1 && iOk && jOk && in0 != (f[id(i, j, k + 1)] > 0f)) {
                val s = if (in0) 1f else -1f
                quad(cellVert[cid(i - 1, j - 1, k)], cellVert[cid(i, j - 1, k)], cellVert[cid(i, j, k)], cellVert[cid(i - 1, j, k)], 0f, 0f, s)
            }
        }
        val idx = IntArray(idxOut.size * 3)
        idxOut.forEachIndexed { t, tri -> idx[t * 3] = tri[0]; idx[t * 3 + 1] = tri[1]; idx[t * 3 + 2] = tri[2] }
        return Mesh(tmp.positions, FloatArray(tmp.positions.size), FloatArray(tmp.vertexCount * 2), idx)
    }

    /** UV cilíndrica (u = ângulo, v = altura) com duplicação de vértices na costura. */
    private fun cylindricalUv(m: Mesh, height: Float): Mesh {
        val n = m.vertexCount
        val u = FloatArray(n); val vv = FloatArray(n)
        for (i in 0 until n) {
            val x = m.positions[i * 3]; val y = m.positions[i * 3 + 1]; val z = m.positions[i * 3 + 2]
            var a = (atan2(x, z) / (2 * PI)).toFloat()
            if (a < 0) a += 1f
            u[i] = a; vv[i] = 1f - (y / height).coerceIn(0f, 1f)
        }
        val b = MeshBuilder(n + n / 10)
        for (i in 0 until n) b.vertex(m.positions[i * 3], m.positions[i * 3 + 1], m.positions[i * 3 + 2], u[i], vv[i])
        val dup = HashMap<Int, Int>()
        for (t in 0 until m.triangleCount) {
            val tri = IntArray(3) { m.indices[t * 3 + it] }
            val us = tri.map { u[it] }
            if (us.max() - us.min() > 0.5f) {
                for (c in 0 until 3) if (u[tri[c]] < 0.5f) {
                    val o = tri[c]
                    tri[c] = dup.getOrPut(o) { b.vertex(m.positions[o * 3], m.positions[o * 3 + 1], m.positions[o * 3 + 2], u[o] + 1f, vv[o]) }
                }
            }
            b.tri(tri[0], tri[1], tri[2])
        }
        return b.build(computeNormals = false)
    }

    /** Para cada (ângulo, altura) acha a superfície e pinta com as fotos que olham para ela. */
    private fun bakeTexture(views: List<Prepared>, rMax: Float, h: Float, cell: Float, yOffset: Float, field: (Float, Float, Float) -> Float): Texture {
        val tw = 512; val th = 256
        val px = IntArray(tw * th)
        val step = cell * 0.75f
        var lastGood = 0xFF808080.toInt()
        for (ty in 0 until th) {
            val y = (1f - (ty + 0.5f) / th) * h   // altura no modelo apoiado no chão
            for (tx in 0 until tw) {
                val phi = ((tx + 0.5f) / tw * 2 * PI).toFloat()
                val dx = sin(phi); val dz = cos(phi)
                // marcha de fora para dentro até entrar no volume
                var r = rMax * 1.1f
                var hit = -1f
                var prevR = r
                while (r > 0f) {
                    if (field(dx * r, y, dz * r) > 0f) { hit = r; break }
                    prevR = r; r -= step
                }
                if (hit < 0f) { px[ty * tw + tx] = lastGood; continue }
                // refina por bisseção
                var lo = hit; var hi = prevR
                repeat(5) { val mid = (lo + hi) / 2; if (field(dx * mid, y, dz * mid) > 0f) lo = mid else hi = mid }
                val sr = lo - cell * 0.6f // um pouco para dentro, evita pegar o fundo na borda
                val wx = dx * sr; val wz = dz * sr; val wy = y - yOffset
                // mistura as duas vistas mais frontais à superfície
                var best1 = -1; var best2 = -1; var w1 = -2f; var w2 = -2f
                for ((vi, p) in views.withIndex()) {
                    val d = dx * p.dx + dz * p.dz
                    if (d > w1) { best2 = best1; w2 = w1; best1 = vi; w1 = d } else if (d > w2) { best2 = vi; w2 = d }
                }
                val c1 = views[best1].color(wx, wy, wz)
                val col = if (best2 >= 0 && w2 > 0f && w1 > 0f) {
                    val c2 = views[best2].color(wx, wy, wz)
                    val a1 = w1 * w1; val a2 = w2 * w2
                    mix(c1, c2, a2 / (a1 + a2))
                } else c1
                px[ty * tw + tx] = col or (0xFF shl 24)
                lastGood = px[ty * tw + tx]
            }
        }
        return Texture(tw, th, px)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (((a shr 16) and 255) * (1 - t) + ((b shr 16) and 255) * t).toInt()
        val g = (((a shr 8) and 255) * (1 - t) + ((b shr 8) and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

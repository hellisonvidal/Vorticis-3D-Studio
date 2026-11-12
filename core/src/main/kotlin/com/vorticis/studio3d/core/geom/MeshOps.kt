package com.vorticis.studio3d.core.geom

import com.vorticis.studio3d.core.model.Mesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Construtor incremental de malhas. */
class MeshBuilder(capacity: Int = 256) {
    private var pos = FloatArray(capacity * 3)
    private var nor = FloatArray(capacity * 3)
    private var uv = FloatArray(capacity * 2)
    private var idx = IntArray(capacity * 3)
    var vertexCount = 0
        private set
    private var indexCount = 0

    fun vertex(x: Float, y: Float, z: Float, u: Float = 0f, v: Float = 0f, nx: Float = 0f, ny: Float = 0f, nz: Float = 0f): Int {
        if ((vertexCount + 1) * 3 > pos.size) {
            val n = pos.size * 2 + 48
            pos = pos.copyOf(n); nor = nor.copyOf(n); uv = uv.copyOf(n / 3 * 2)
        }
        val i = vertexCount
        pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z
        nor[i * 3] = nx; nor[i * 3 + 1] = ny; nor[i * 3 + 2] = nz
        uv[i * 2] = u; uv[i * 2 + 1] = v
        vertexCount++
        return i
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2 + 48)
        idx[indexCount++] = a; idx[indexCount++] = b; idx[indexCount++] = c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c); tri(a, c, d)
    }

    /** Gera a malha. Se [computeNormals], recalcula normais com ângulo de vinco [creaseDeg]. */
    fun build(computeNormals: Boolean = true, creaseDeg: Float = 40f): Mesh {
        val m = Mesh(
            pos.copyOf(vertexCount * 3), nor.copyOf(vertexCount * 3),
            uv.copyOf(vertexCount * 2), idx.copyOf(indexCount),
        )
        return if (computeNormals) MeshOps.autoNormals(m, creaseDeg) else m
    }
}

object MeshOps {

    // ---------- transformações ----------

    fun translate(m: Mesh, dx: Float, dy: Float, dz: Float): Mesh {
        val p = m.positions.copyOf()
        for (i in 0 until m.vertexCount) {
            p[i * 3] += dx; p[i * 3 + 1] += dy; p[i * 3 + 2] += dz
        }
        return Mesh(p, m.normals, m.uvs, m.indices)
    }

    fun scale(m: Mesh, sx: Float, sy: Float, sz: Float, px: Float = 0f, py: Float = 0f, pz: Float = 0f): Mesh {
        val p = m.positions.copyOf()
        val n = m.normals.copyOf()
        for (i in 0 until m.vertexCount) {
            p[i * 3] = px + (p[i * 3] - px) * sx
            p[i * 3 + 1] = py + (p[i * 3 + 1] - py) * sy
            p[i * 3 + 2] = pz + (p[i * 3 + 2] - pz) * sz
            // normais transformam pela inversa-transposta
            var nx = n[i * 3] / sx; var ny = n[i * 3 + 1] / sy; var nz = n[i * 3 + 2] / sz
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-12f) { nx /= l; ny /= l; nz /= l }
            n[i * 3] = nx; n[i * 3 + 1] = ny; n[i * 3 + 2] = nz
        }
        val idx = if (sx * sy * sz < 0) flipWinding(m.indices) else m.indices
        return Mesh(p, n, m.uvs, idx)
    }

    /** Rotação em graus em torno de um eixo ('x','y','z') passando pelo pivô. */
    fun rotate(m: Mesh, axis: Char, degrees: Float, px: Float = 0f, py: Float = 0f, pz: Float = 0f): Mesh {
        val a = degrees * PI.toFloat() / 180f
        val c = cos(a); val s = sin(a)
        val p = m.positions.copyOf()
        val n = m.normals.copyOf()
        fun rot(arr: FloatArray, i: Int, ox: Float, oy: Float, oz: Float) {
            val x = arr[i * 3] - ox; val y = arr[i * 3 + 1] - oy; val z = arr[i * 3 + 2] - oz
            val (rx, ry, rz) = when (axis) {
                'x' -> Triple(x, y * c - z * s, y * s + z * c)
                'z' -> Triple(x * c - y * s, x * s + y * c, z)
                else -> Triple(x * c + z * s, y, -x * s + z * c)
            }
            arr[i * 3] = rx + ox; arr[i * 3 + 1] = ry + oy; arr[i * 3 + 2] = rz + oz
        }
        for (i in 0 until m.vertexCount) {
            rot(p, i, px, py, pz)
            rot(n, i, 0f, 0f, 0f)
        }
        return Mesh(p, n, m.uvs, m.indices)
    }

    fun flipWinding(idx: IntArray): IntArray {
        val r = idx.copyOf()
        var i = 0
        while (i < r.size) {
            val t = r[i + 1]; r[i + 1] = r[i + 2]; r[i + 2] = t
            i += 3
        }
        return r
    }

    fun merge(meshes: List<Mesh>): Mesh {
        val vc = meshes.sumOf { it.vertexCount }
        val ic = meshes.sumOf { it.indices.size }
        val p = FloatArray(vc * 3); val n = FloatArray(vc * 3); val uv = FloatArray(vc * 2); val idx = IntArray(ic)
        var vo = 0; var io = 0
        for (m in meshes) {
            m.positions.copyInto(p, vo * 3)
            m.normals.copyInto(n, vo * 3)
            m.uvs.copyInto(uv, vo * 2)
            for (k in m.indices.indices) idx[io + k] = m.indices[k] + vo
            vo += m.vertexCount; io += m.indices.size
        }
        return Mesh(p, n, uv, idx)
    }

    // ---------- normais ----------

    private fun faceNormals(m: Mesh): FloatArray {
        val fn = FloatArray(m.triangleCount * 3)
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val a = m.indices[t * 3] * 3; val b = m.indices[t * 3 + 1] * 3; val c = m.indices[t * 3 + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            // não normalizado => peso por área
            fn[t * 3] = uy * vz - uz * vy
            fn[t * 3 + 1] = uz * vx - ux * vz
            fn[t * 3 + 2] = ux * vy - uy * vx
        }
        return fn
    }

    private data class Q(val x: Int, val y: Int, val z: Int)

    /** Agrupa vértices com a mesma posição (tolerância [eps]). Retorna id de grupo por vértice. */
    fun weldIds(m: Mesh, eps: Float = 1e-4f): IntArray {
        val ids = IntArray(m.vertexCount)
        val map = HashMap<Q, Int>(m.vertexCount * 2)
        val inv = 1f / eps
        for (i in 0 until m.vertexCount) {
            val q = Q(
                floor(m.positions[i * 3] * inv + 0.5f).toInt(),
                floor(m.positions[i * 3 + 1] * inv + 0.5f).toInt(),
                floor(m.positions[i * 3 + 2] * inv + 0.5f).toInt(),
            )
            ids[i] = map.getOrPut(q) { map.size }
        }
        return ids
    }

    /**
     * Recalcula normais por canto de triângulo, suavizando entre faces cujo ângulo
     * é menor que [creaseDeg] (bordas mais vivas ficam facetadas). Vértices são
     * divididos/fundidos conforme necessário.
     */
    fun autoNormals(m: Mesh, creaseDeg: Float = 40f): Mesh {
        if (m.triangleCount == 0) return m
        val fn = faceNormals(m)
        val fnu = FloatArray(fn.size)
        for (t in 0 until m.triangleCount) {
            val x = fn[t * 3]; val y = fn[t * 3 + 1]; val z = fn[t * 3 + 2]
            val l = sqrt(x * x + y * y + z * z)
            if (l > 0f) { fnu[t * 3] = x / l; fnu[t * 3 + 1] = y / l; fnu[t * 3 + 2] = z / l }
        }
        val weld = weldIds(m)
        val groups = (weld.maxOrNull() ?: -1) + 1
        // lista de faces por grupo de posição
        val count = IntArray(groups + 1)
        for (k in m.indices.indices) count[weld[m.indices[k]] + 1]++
        for (g in 0 until groups) count[g + 1] += count[g]
        val faces = IntArray(m.indices.size)
        val fill = count.copyOf()
        for (k in m.indices.indices) faces[fill[weld[m.indices[k]]]++] = k / 3
        val cosT = cos(creaseDeg * PI.toFloat() / 180f)

        val b = MeshBuilder(m.vertexCount)
        val dedupe = HashMap<Long, Int>()
        val newIdx = IntArray(m.indices.size)
        for (k in m.indices.indices) {
            val v = m.indices[k]
            val t = k / 3
            val g = weld[v]
            var nx = 0f; var ny = 0f; var nz = 0f
            for (q in count[g] until count[g + 1]) {
                val f = faces[q]
                val d = fnu[f * 3] * fnu[t * 3] + fnu[f * 3 + 1] * fnu[t * 3 + 1] + fnu[f * 3 + 2] * fnu[t * 3 + 2]
                if (f == t || d >= cosT) {
                    nx += fn[f * 3]; ny += fn[f * 3 + 1]; nz += fn[f * 3 + 2]
                }
            }
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 0f) { nx /= l; ny /= l; nz /= l } else { nx = fnu[t * 3]; ny = fnu[t * 3 + 1]; nz = fnu[t * 3 + 2] }
            // quantiza normal para reaproveitar vértices iguais
            val key = (v.toLong() shl 30) or
                (((nx + 1f) * 511f).toLong() shl 20) or
                (((ny + 1f) * 511f).toLong() shl 10) or
                ((nz + 1f) * 511f).toLong()
            val id = dedupe.getOrPut(key) {
                b.vertex(
                    m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2],
                    m.uvs[v * 2], m.uvs[v * 2 + 1], nx, ny, nz,
                )
            }
            newIdx[k] = id
        }
        var i = 0
        while (i < newIdx.size) { b.tri(newIdx[i], newIdx[i + 1], newIdx[i + 2]); i += 3 }
        return b.build(computeNormals = false)
    }

    /** Normais totalmente facetadas (estilo low poly). */
    fun flatShade(m: Mesh): Mesh = autoNormals(m, 0.01f)

    // ---------- suavização e simplificação ----------

    /** Suavização Taubin (não encolhe a malha), mantendo costuras de UV. */
    fun smooth(m: Mesh, iterations: Int = 6): Mesh {
        if (m.triangleCount == 0) return m
        val weld = weldIds(m)
        val groups = (weld.maxOrNull() ?: -1) + 1
        val gp = FloatArray(groups * 3)
        val cnt = IntArray(groups)
        for (v in 0 until m.vertexCount) {
            val g = weld[v]
            if (cnt[g] == 0) { gp[g * 3] = m.positions[v * 3]; gp[g * 3 + 1] = m.positions[v * 3 + 1]; gp[g * 3 + 2] = m.positions[v * 3 + 2] }
            cnt[g] = 1
        }
        val neighbors = Array(groups) { HashSet<Int>(8) }
        for (t in 0 until m.triangleCount) {
            val a = weld[m.indices[t * 3]]; val b = weld[m.indices[t * 3 + 1]]; val c = weld[m.indices[t * 3 + 2]]
            neighbors[a].add(b); neighbors[a].add(c)
            neighbors[b].add(a); neighbors[b].add(c)
            neighbors[c].add(a); neighbors[c].add(b)
        }
        val nb = Array(groups) { neighbors[it].toIntArray() }
        val tmp = FloatArray(gp.size)
        fun pass(lambda: Float) {
            for (g in 0 until groups) {
                val list = nb[g]
                if (list.isEmpty()) { tmp[g * 3] = gp[g * 3]; tmp[g * 3 + 1] = gp[g * 3 + 1]; tmp[g * 3 + 2] = gp[g * 3 + 2]; continue }
                var sx = 0f; var sy = 0f; var sz = 0f
                for (o in list) { sx += gp[o * 3]; sy += gp[o * 3 + 1]; sz += gp[o * 3 + 2] }
                val inv = 1f / list.size
                tmp[g * 3] = gp[g * 3] + lambda * (sx * inv - gp[g * 3])
                tmp[g * 3 + 1] = gp[g * 3 + 1] + lambda * (sy * inv - gp[g * 3 + 1])
                tmp[g * 3 + 2] = gp[g * 3 + 2] + lambda * (sz * inv - gp[g * 3 + 2])
            }
            tmp.copyInto(gp)
        }
        repeat(iterations) { pass(0.5f); pass(-0.53f) }
        val p = FloatArray(m.positions.size)
        for (v in 0 until m.vertexCount) {
            val g = weld[v]
            p[v * 3] = gp[g * 3]; p[v * 3 + 1] = gp[g * 3 + 1]; p[v * 3 + 2] = gp[g * 3 + 2]
        }
        return autoNormals(Mesh(p, m.normals, m.uvs, m.indices), 70f)
    }

    /**
     * Simplificação por agrupamento de vértices numa grade (visual low poly).
     * [cells] = número de células no maior eixo.
     */
    fun lowPoly(m: Mesh, cells: Int = 14): Mesh {
        if (m.triangleCount == 0) return m
        val bb = m.bounds()
        val cell = (bb.maxSize / cells).coerceAtLeast(1e-4f)
        val map = HashMap<Long, Int>()
        val sum = ArrayList<FloatArray>() // x,y,z,u,v,count
        val vmap = IntArray(m.vertexCount)
        for (v in 0 until m.vertexCount) {
            val qx = floor((m.positions[v * 3] - bb.minX) / cell).toLong()
            val qy = floor((m.positions[v * 3 + 1] - bb.minY) / cell).toLong()
            val qz = floor((m.positions[v * 3 + 2] - bb.minZ) / cell).toLong()
            val key = (qx shl 42) or (qy shl 21) or qz
            val id = map.getOrPut(key) { sum.add(FloatArray(6)); sum.size - 1 }
            val s = sum[id]
            s[0] += m.positions[v * 3]; s[1] += m.positions[v * 3 + 1]; s[2] += m.positions[v * 3 + 2]
            s[3] += m.uvs[v * 2]; s[4] += m.uvs[v * 2 + 1]; s[5] += 1f
            vmap[v] = id
        }
        val b = MeshBuilder(sum.size)
        for (s in sum) b.vertex(s[0] / s[5], s[1] / s[5], s[2] / s[5], s[3] / s[5], s[4] / s[5])
        val seen = HashSet<Long>()
        for (t in 0 until m.triangleCount) {
            val a = vmap[m.indices[t * 3]]; val c1 = vmap[m.indices[t * 3 + 1]]; val c2 = vmap[m.indices[t * 3 + 2]]
            if (a == c1 || c1 == c2 || a == c2) continue
            // evita triângulos duplicados
            val arr = intArrayOf(a, c1, c2).sorted()
            val key = (arr[0].toLong() shl 42) or (arr[1].toLong() shl 21) or arr[2].toLong()
            if (!seen.add(key)) continue
            b.tri(a, c1, c2)
        }
        return flatShade(b.build(computeNormals = false))
    }

    /** Espelha no eixo X (inverte a orientação dos triângulos para manter as normais para fora). */
    fun mirrorX(m: Mesh, pivotX: Float): Mesh = scale(m, -1f, 1f, 1f, pivotX, 0f, 0f)
}

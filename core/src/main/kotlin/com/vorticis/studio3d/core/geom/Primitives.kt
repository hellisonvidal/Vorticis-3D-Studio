package com.vorticis.studio3d.core.geom

import com.vorticis.studio3d.core.model.Mesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Primitivas paramétricas. Todas centradas na origem (exceto quando dito),
 * Y para cima, com UVs e normais.
 */
object Primitives {
    private const val TAU = (PI * 2).toFloat()

    fun box(w: Float, h: Float, d: Float): Mesh {
        val b = MeshBuilder(24)
        val x = w / 2; val y = h / 2; val z = d / 2
        fun face(p: Array<FloatArray>) {
            val a = b.vertex(p[0][0], p[0][1], p[0][2], 0f, 1f)
            val c = b.vertex(p[1][0], p[1][1], p[1][2], 1f, 1f)
            val e = b.vertex(p[2][0], p[2][1], p[2][2], 1f, 0f)
            val f = b.vertex(p[3][0], p[3][1], p[3][2], 0f, 0f)
            b.quad(a, c, e, f)
        }
        // cada face: inferior-esq, inferior-dir, superior-dir, superior-esq (vista de fora)
        face(arrayOf(floatArrayOf(-x, -y, z), floatArrayOf(x, -y, z), floatArrayOf(x, y, z), floatArrayOf(-x, y, z)))     // +Z
        face(arrayOf(floatArrayOf(x, -y, -z), floatArrayOf(-x, -y, -z), floatArrayOf(-x, y, -z), floatArrayOf(x, y, -z))) // -Z
        face(arrayOf(floatArrayOf(x, -y, z), floatArrayOf(x, -y, -z), floatArrayOf(x, y, -z), floatArrayOf(x, y, z)))     // +X
        face(arrayOf(floatArrayOf(-x, -y, -z), floatArrayOf(-x, -y, z), floatArrayOf(-x, y, z), floatArrayOf(-x, y, -z))) // -X
        face(arrayOf(floatArrayOf(-x, y, z), floatArrayOf(x, y, z), floatArrayOf(x, y, -z), floatArrayOf(-x, y, -z)))     // +Y
        face(arrayOf(floatArrayOf(-x, -y, -z), floatArrayOf(x, -y, -z), floatArrayOf(x, -y, z), floatArrayOf(-x, -y, z))) // -Y
        return b.build(creaseDeg = 30f)
    }

    /**
     * Superfície de revolução em torno do eixo Y.
     * [profile] = pares (raio, y) de baixo para cima. Raio 0 nas pontas fecha a forma.
     * [capBottom]/[capTop] fecham com tampas planas quando o raio da ponta for > 0.
     */
    fun lathe(
        profile: List<Pair<Float, Float>>,
        segments: Int = 32,
        capBottom: Boolean = true,
        capTop: Boolean = true,
        creaseDeg: Float = 40f,
    ): Mesh {
        val b = MeshBuilder(profile.size * (segments + 1) + 2 * (segments + 2))
        val n = profile.size
        // comprimento acumulado do perfil para a coordenada v
        val len = FloatArray(n)
        for (i in 1 until n) {
            val dr = profile[i].first - profile[i - 1].first
            val dy = profile[i].second - profile[i - 1].second
            len[i] = len[i - 1] + sqrt(dr * dr + dy * dy)
        }
        val total = if (len[n - 1] > 0f) len[n - 1] else 1f
        val rows = Array(n) { IntArray(segments + 1) }
        for (i in 0 until n) {
            val (r, y) = profile[i]
            for (s in 0..segments) {
                val a = (s % segments).toFloat() / segments * TAU // costura com posição idêntica
                rows[i][s] = b.vertex(r * sin(a), y, r * cos(a), s.toFloat() / segments, 1f - len[i] / total)
            }
        }
        for (i in 0 until n - 1) {
            for (s in 0 until segments) {
                val a = rows[i][s]; val c = rows[i][s + 1]; val d = rows[i + 1][s + 1]; val e = rows[i + 1][s]
                val r0 = profile[i].first; val r1 = profile[i + 1].first
                if (r0 < 1e-6f) b.tri(a, d, e)
                else if (r1 < 1e-6f) b.tri(a, c, d)
                else b.quad(a, c, d, e)
            }
        }
        fun cap(i: Int, up: Boolean) {
            val (r, y) = profile[i]
            if (r < 1e-6f) return
            val center = b.vertex(0f, y, 0f, 0.5f, 0.5f)
            val ring = IntArray(segments + 1) { s ->
                val a = (s % segments).toFloat() / segments * TAU
                b.vertex(r * sin(a), y, r * cos(a), 0.5f + 0.5f * sin(a), 0.5f - 0.5f * cos(a))
            }
            for (s in 0 until segments) {
                if (up) b.tri(center, ring[s], ring[s + 1]) else b.tri(center, ring[s + 1], ring[s])
            }
        }
        if (capBottom) cap(0, false)
        if (capTop) cap(n - 1, true)
        return b.build(creaseDeg = creaseDeg)
    }

    fun sphere(r: Float, segments: Int = 36, rings: Int = 18): Mesh {
        val prof = (0..rings).map { i ->
            val t = PI.toFloat() * i / rings
            Pair(r * sin(t), -r * cos(t))
        }
        return lathe(prof, segments, capBottom = false, capTop = false, creaseDeg = 80f)
    }

    /** Elipsoide com semi-eixos rx, ry, rz. */
    fun ellipsoid(rx: Float, ry: Float, rz: Float, segments: Int = 32, rings: Int = 16): Mesh =
        MeshOps.scale(sphere(1f, segments, rings), rx, ry, rz)

    fun cylinder(rBottom: Float, rTop: Float, h: Float, segments: Int = 32): Mesh =
        lathe(listOf(Pair(rBottom, -h / 2), Pair(rTop, h / 2)), segments, creaseDeg = 50f)

    fun cone(r: Float, h: Float, segments: Int = 32): Mesh = cylinder(r, 0f, h, segments)

    fun pyramid(base: Float, h: Float, sides: Int = 4): Mesh {
        // pirâmide com faces planas e base alinhada aos eixos
        val m = lathe(listOf(Pair(base / sqrt(2f), -h / 2), Pair(0f, h / 2)), sides, creaseDeg = 10f)
        return MeshOps.rotate(m, 'y', 45f)
    }

    fun capsule(r: Float, h: Float, segments: Int = 32): Mesh {
        val prof = ArrayList<Pair<Float, Float>>()
        val half = (h / 2 - r).coerceAtLeast(0f)
        val q = 8
        for (i in 0..q) { val t = PI.toFloat() / 2 * i / q; prof.add(Pair(r * sin(t), -half - r * cos(t))) }
        for (i in 0..q) { val t = PI.toFloat() / 2 * i / q; prof.add(Pair(r * cos(t), half + r * sin(t))) }
        return lathe(prof, segments, capBottom = false, capTop = false, creaseDeg = 80f)
    }

    fun torus(major: Float, minor: Float, segments: Int = 40, tube: Int = 18): Mesh {
        val b = MeshBuilder((segments + 1) * (tube + 1))
        val grid = Array(segments + 1) { IntArray(tube + 1) }
        for (i in 0..segments) {
            val a = (i % segments).toFloat() / segments * TAU
            for (j in 0..tube) {
                val t = (j % tube).toFloat() / tube * TAU
                val rr = major + minor * cos(t)
                grid[i][j] = b.vertex(rr * sin(a), minor * sin(t), rr * cos(a), i.toFloat() / segments, j.toFloat() / tube)
            }
        }
        for (i in 0 until segments) for (j in 0 until tube) {
            b.quad(grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1])
        }
        return b.build(creaseDeg = 80f)
    }

    fun plane(w: Float, d: Float): Mesh {
        val b = MeshBuilder(4)
        val a = b.vertex(-w / 2, 0f, d / 2, 0f, 1f)
        val c = b.vertex(w / 2, 0f, d / 2, 1f, 1f)
        val e = b.vertex(w / 2, 0f, -d / 2, 1f, 0f)
        val f = b.vertex(-w / 2, 0f, -d / 2, 0f, 0f)
        b.quad(a, c, e, f)
        // verso para ficar visível dos dois lados e ser exportável como sólido fino
        b.quad(f, e, c, a)
        return b.build()
    }

    /**
     * Extrusão de um polígono 2D (no plano XY, anti-horário) com profundidade em Z,
     * centralizada em z = 0.
     */
    fun extrude(poly: List<Pair<Float, Float>>, depth: Float): Mesh {
        val pts = if (signedArea(poly) < 0) poly.reversed() else poly
        val tris = earcut(pts)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        pts.forEach { (x, y) -> minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        val sx = (maxX - minX).coerceAtLeast(1e-6f); val sy = (maxY - minY).coerceAtLeast(1e-6f)
        val b = MeshBuilder(pts.size * 4)
        val z = depth / 2
        val front = pts.map { (x, y) -> b.vertex(x, y, z, (x - minX) / sx, 1f - (y - minY) / sy) }
        val back = pts.map { (x, y) -> b.vertex(x, y, -z, 1f - (x - minX) / sx, 1f - (y - minY) / sy) }
        for (t in tris) {
            b.tri(front[t[0]], front[t[1]], front[t[2]])
            b.tri(back[t[0]], back[t[2]], back[t[1]])
        }
        var perim = 0f
        val acc = FloatArray(pts.size + 1)
        for (i in pts.indices) {
            val (x0, y0) = pts[i]; val (x1, y1) = pts[(i + 1) % pts.size]
            perim += sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            acc[i + 1] = perim
        }
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            val u0 = acc[i] / perim; val u1 = acc[i + 1] / perim
            val a = b.vertex(pts[i].first, pts[i].second, z, u0, 0f)
            val c = b.vertex(pts[j].first, pts[j].second, z, u1, 0f)
            val d = b.vertex(pts[j].first, pts[j].second, -z, u1, 1f)
            val e = b.vertex(pts[i].first, pts[i].second, -z, u0, 1f)
            b.quad(a, e, d, c)
        }
        return b.build(creaseDeg = 35f)
    }

    fun starPolygon(points: Int, rOuter: Float, rInner: Float): List<Pair<Float, Float>> =
        (0 until points * 2).map { i ->
            val r = if (i % 2 == 0) rOuter else rInner
            val a = PI.toFloat() / 2 + i * PI.toFloat() / points
            Pair(r * cos(a), r * sin(a))
        }

    fun heartPolygon(size: Float, n: Int = 64): List<Pair<Float, Float>> =
        (0 until n).map { i ->
            val t = i.toFloat() / n * TAU
            val x = 16 * sin(t).let { it * it * it }
            val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
            Pair(x / 34f * size, y / 34f * size)
        }

    fun regularPolygon(sides: Int, r: Float): List<Pair<Float, Float>> =
        (0 until sides).map { i ->
            val a = PI.toFloat() / 2 + i * TAU / sides
            Pair(r * cos(a), r * sin(a))
        }

    /** Prisma triangular (telhado de duas águas) com cumeeira ao longo de Z. */
    fun roof(w: Float, h: Float, d: Float): Mesh =
        extrude(listOf(Pair(-w / 2, 0f), Pair(w / 2, 0f), Pair(0f, h)), d)

    // ---------- triangulação ----------

    fun signedArea(p: List<Pair<Float, Float>>): Float {
        var a = 0f
        for (i in p.indices) {
            val (x0, y0) = p[i]; val (x1, y1) = p[(i + 1) % p.size]
            a += x0 * y1 - x1 * y0
        }
        return a / 2
    }

    /** Ear clipping simples para polígonos sem furos (anti-horário). */
    fun earcut(p: List<Pair<Float, Float>>): List<IntArray> {
        val idx = p.indices.toMutableList()
        val out = ArrayList<IntArray>()
        var guard = 0
        while (idx.size > 3 && guard < p.size * p.size) {
            guard++
            var clipped = false
            for (k in idx.indices) {
                val i0 = idx[(k + idx.size - 1) % idx.size]; val i1 = idx[k]; val i2 = idx[(k + 1) % idx.size]
                val (ax, ay) = p[i0]; val (bx, by) = p[i1]; val (cx, cy) = p[i2]
                val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
                if (cross <= 1e-9f) continue
                var inside = false
                for (o in idx) {
                    if (o == i0 || o == i1 || o == i2) continue
                    if (pointInTri(p[o].first, p[o].second, ax, ay, bx, by, cx, cy)) { inside = true; break }
                }
                if (inside) continue
                out.add(intArrayOf(i0, i1, i2))
                idx.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (idx.size == 3) out.add(intArrayOf(idx[0], idx[1], idx[2]))
        else if (idx.size > 3) { // fallback: leque
            for (k in 1 until idx.size - 1) out.add(intArrayOf(idx[0], idx[k], idx[k + 1]))
        }
        return out
    }

    private fun pointInTri(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos) && !(abs(d1) < 1e-12f && abs(d2) < 1e-12f)
    }
}

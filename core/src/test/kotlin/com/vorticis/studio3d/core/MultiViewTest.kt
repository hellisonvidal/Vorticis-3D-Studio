package com.vorticis.studio3d.core

import com.vorticis.studio3d.core.export.ExportFormat
import com.vorticis.studio3d.core.export.Exporters
import com.vorticis.studio3d.core.model.Node
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.photo.CaptureLayout
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.MultiView
import com.vorticis.studio3d.core.photo.MultiViewOptions
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.core.photo.PhotoView
import com.vorticis.studio3d.core.photo.Segmentation
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiViewTest {
    private val out = File(System.getProperty("vorticis.out") ?: "build/test-exports").apply { mkdirs() }

    /** Objeto de teste: elipsoide achatado (0.6 x 1.0 x 0.3) + "nariz" no lado +X. Frente vermelha, verso azul. */
    private fun inside(x: Float, y: Float, z: Float): Boolean {
        val e = (x / 0.3f) * (x / 0.3f) + ((y - 0.5f) / 0.5f) * ((y - 0.5f) / 0.5f) + (z / 0.15f) * (z / 0.15f) <= 1f
        val nose = (x - 0.32f) * (x - 0.32f) + (y - 0.6f) * (y - 0.6f) + z * z <= 0.1f * 0.1f
        return e || nose
    }

    /** "Fotografa" ortograficamente a partir do ângulo (graus) com fundo claro. */
    private fun shoot(deg: Float, w: Int = 260, h: Int = 300): PhotoView {
        val a = Math.toRadians(deg.toDouble()).toFloat()
        val rx = cos(a); val rz = -sin(a); val dx = sin(a); val dz = cos(a)
        val scale = 200f; val cx = w / 2f + 7f; val bottom = h - 40f // deslocado de propósito
        val px = IntArray(w * h) { 0xFFEDEDE8.toInt() }
        for (py in 0 until h) for (pxi in 0 until w) {
            val s = (pxi + 0.5f - cx) / scale; val y = (bottom - py - 0.5f) / scale
            var t = 0.6f
            while (t > -0.6f) {
                val x = s * rx + t * dx; val z = s * rz + t * dz
                if (inside(x, y, z)) {
                    px[py * w + pxi] = if (z >= 0f) 0xFFD03030.toInt() else 0xFF3040D0.toInt()
                    break
                }
                t -= 0.005f
            }
        }
        return PhotoView(px, w, h, Segmentation.auto(px, w, h))
    }

    private fun closed(n: Node): Boolean {
        val m = n.mesh
        val weld = com.vorticis.studio3d.core.geom.MeshOps.weldIds(m)
        val edges = HashMap<Long, Int>()
        for (t in 0 until m.triangleCount) for (k in 0 until 3) {
            val a = weld[m.indices[t * 3 + k]]; val b = weld[m.indices[t * 3 + (k + 1) % 3]]
            val key = a.toLong() shl 32 or b.toLong(); edges[key] = (edges[key] ?: 0) + 1
        }
        val bad = edges.filter { (k, v) -> !(v == 1 && edges[(k and 0xFFFFFFFFL) shl 32 or (k ushr 32)] == 1) }
        if (bad.isNotEmpty()) println("arestas ruins: ${bad.size} de ${edges.size}; multiplicidades=${bad.values.groupingBy { it }.eachCount()} sem-par=${bad.count { (k, _) -> edges[(k and 0xFFFFFFFFL) shl 32 or (k ushr 32)] == null }}")
        return bad.isEmpty()
    }

    private fun channel(c: Int, s: Int) = (c shr s) and 255

    @Test fun reconstructsFromPhotosAround() {
        for ((name, angles, layout) in listOf(
            Triple("8fotos", MultiView.angles(8, CaptureLayout.AROUND), CaptureLayout.AROUND),
            Triple("4fotos", MultiView.angles(4, CaptureLayout.ORTHO), CaptureLayout.ORTHO),
            Triple("2fotos", MultiView.angles(2, CaptureLayout.AROUND), CaptureLayout.AROUND),
            Triple("6fotos_esq", MultiView.angles(6, CaptureLayout.AROUND_LEFT), CaptureLayout.AROUND_LEFT),
        )) {
            val views = angles.map { shoot(it) }
            val t0 = System.currentTimeMillis()
            val r = MultiView.build(views, MultiViewOptions(layout = layout))
            val ms = System.currentTimeMillis() - t0
            val b = r.mesh.bounds()
            println("$name: ${r.explanation} tris=${r.mesh.triangleCount} ${ms}ms  tamanho=%.2f x %.2f x %.2f".format(b.sizeX, b.sizeY, b.sizeZ))
            assertEquals(PhotoMode.MULTIVIEW, r.mode)
            val node = Node("multi_$name", r.mesh, r.material, 1, "foto")
            assertTrue(closed(node), "malha fechada ($name)")
            // altura normalizada para 1.2; profundidade real (0.36) bem menor que a largura (~0.78)
            assertTrue(kotlin.math.abs(b.sizeY - 1.2f) < 0.08f, "altura ${b.sizeY}")
            // sem foto exatamente de lado (6 fotos a cada 60°) o casco visual fica mais grosso
            val maxRatio = if (angles.any { kotlin.math.abs(((it % 180) + 180) % 180 - 90f) < 1f }) 0.6f else 0.8f
            assertTrue(b.sizeZ / b.sizeX < maxRatio, "profundidade recuperada: ${b.sizeZ}/${b.sizeX}")
            assertTrue(b.maxX > -b.minX + 0.05f, "nariz do lado +X: ${b.minX}..${b.maxX}")
            // textura: frente (u=0, +Z) vermelha; verso (u=0.5, -Z) azul
            val tex = r.material.texture!!
            val front = tex.sample(0.01f, 0.5f); val back = tex.sample(0.5f, 0.5f)
            assertTrue(channel(front, 16) > channel(front, 0), "frente vermelha ($name)")
            // o verso só tem cor real se alguma foto o viu (com 2 fotos, frente + lado, não há)
            if (angles.any { kotlin.math.abs(((it % 360) + 360) % 360 - 180f) <= 60f }) assertTrue(channel(back, 0) > channel(back, 16), "verso azul ($name)")
            if (name == "8fotos") {
                val dir = File(out, "multivista").apply { mkdirs() }
                for (fmt in ExportFormat.entries) for (f in Exporters.exportFiles(Scene(listOf(node)), fmt, "multivista")) File(dir, f.name).writeBytes(f.bytes)
            }
        }
    }

    @Test fun videoFindsFullTurn() {
        // "vídeo" de 1 volta e 1/4 (0° a 450°), 1 quadro a cada 15°
        val frames = (0..30).map { shoot(it * 15f) }
        val plan = com.vorticis.studio3d.core.photo.VideoLoop.plan(frames)
        println("vídeo: " + plan.explanation(frames.size) + " quadros=" + plan.frames)
        assertTrue(plan.detected, "volta detectada")
        assertTrue(plan.loopLength in 23..25, "volta em ~24 quadros (360°): ${plan.loopLength}")
        val r = MultiView.build(plan.frames.map { frames[it] })
        val b = r.mesh.bounds()
        println("vídeo -> tamanho=%.2f x %.2f x %.2f".format(b.sizeX, b.sizeY, b.sizeZ))
        assertTrue(b.sizeZ / b.sizeX < 0.6f)
        // vídeo de exatamente uma volta sem repetição: usa o vídeo inteiro
        val oneTurn = (0 until 24).map { shoot(it * 15f) }
        val p2 = com.vorticis.studio3d.core.photo.VideoLoop.plan(oneTurn)
        println("vídeo 360 exato: " + p2.explanation(oneTurn.size))
        assertTrue(p2.loopLength >= 23)
    }
}

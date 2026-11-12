package com.vorticis.studio3d.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.vorticis.studio3d.core.photo.DepthMap
import com.vorticis.studio3d.core.photo.DepthPrep
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.core.photo.PhotoOptions
import com.vorticis.studio3d.core.photo.PhotoTo3D
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthTest {
    private val w = 240; private val h = 320

    private fun blob(): Pair<IntArray, Mask> {
        val px = IntArray(w * h) { 0xFFE0E0E0.toInt() }
        val m = Mask(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x - w / 2f) / (w * 0.35f); val ny = (y - h / 2f) / (h * 0.4f)
            if (nx * nx + ny * ny <= 1f) { m[x, y] = true; px[y * w + x] = 0xFF3050C0.toInt() }
        }
        return px to m
    }

    @Test fun depthShapesTheFront() {
        val (px, mask) = blob()
        // lado esquerdo da imagem mais perto da câmera
        val depth = DepthMap(60, 80, FloatArray(60 * 80) { i -> 1f - (i % 60) / 60f })
        val flat = PhotoTo3D.build(px, w, h, mask, PhotoOptions(mode = PhotoMode.VOLUME))
        val withDepth = PhotoTo3D.build(px, w, h, mask, PhotoOptions(mode = PhotoMode.VOLUME, depth = depth))
        fun frontZ(r: com.vorticis.studio3d.core.photo.PhotoResult, left: Boolean): Float {
            val m = r.mesh; var best = 0f
            for (i in 0 until m.vertexCount) {
                val x = m.positions[i * 3]; val z = m.positions[i * 3 + 2]
                if ((left && x < -0.15f) || (!left && x > 0.15f)) best = maxOf(best, z)
            }
            return best
        }
        println("sem IA: esq=%.3f dir=%.3f | com IA: esq=%.3f dir=%.3f".format(frontZ(flat, true), frontZ(flat, false), frontZ(withDepth, true), frontZ(withDepth, false)))
        assertTrue(frontZ(withDepth, true) > frontZ(withDepth, false) * 1.15f, "lado mais perto sai mais")
        assertTrue(withDepth.explanation.contains("profundidade"))
    }

    /** Roda o Depth Anything V2 de verdade (no GitHub Actions o modelo é baixado antes dos testes). */
    @Test fun realDepthModel() {
        val f = File(System.getProperty("vorticis.depthModel") ?: "")
        if (!f.exists()) { println("modelo de profundidade ausente (${f.path}): teste pulado"); return }
        // cena: parede ao fundo, chão em perspectiva e uma esfera sombreada à frente
        val W = 320; val H = 320
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            var c = if (y < H * 0.55) 0xFFB8C4D0.toInt() else {
                val t = (y - H * 0.55f) / (H * 0.45f); val v = (150 + 80 * t).toInt(); (0xFF shl 24) or (v shl 16) or ((v * 0.9).toInt() shl 8) or (v * 0.7).toInt()
            }
            val dx = (x - W / 2f) / 70f; val dy = (y - H * 0.6f) / 70f
            if (dx * dx + dy * dy < 1f) {
                val nz = sqrt(1 - dx * dx - dy * dy); val l = (0.25f + 0.75f * (nz * 0.8f - dx * 0.3f - dy * 0.4f)).coerceIn(0f, 1f)
                val v = (l * 230).toInt(); c = (0xFF shl 24) or (v shl 16) or ((v * 0.4).toInt() shl 8) or (v * 0.3).toInt()
            }
            px[y * W + x] = c
        }
        val env = OrtEnvironment.getEnvironment()
        env.createSession(f.absolutePath).use { session ->
            val inName = session.inputNames.first()
            println("entradas=${session.inputInfo} saídas=${session.outputInfo}")
            val (iw, ih) = DepthPrep.inputSize(W, H)
            val t0 = System.currentTimeMillis()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(DepthPrep.toTensor(px, W, H, iw, ih)), longArrayOf(1, 3, ih.toLong(), iw.toLong())).use { input ->
                session.run(mapOf(inName to input)).use { res ->
                    val out = res.get(0) as OnnxTensor
                    val shape = out.info.shape
                    val fb = out.floatBuffer; val arr = FloatArray(fb.remaining()); fb.get(arr)
                    val dm = DepthPrep.fromOutput(arr, shape)
                    val center = dm.sample(0.5f, 0.6f); val wall = dm.sample(0.5f, 0.1f); val floorNear = dm.sample(0.5f, 0.97f)
                    println("saída=${shape.toList()} ${System.currentTimeMillis() - t0}ms  esfera=%.3f parede=%.3f chão_perto=%.3f".format(center, wall, floorNear))
                    assertEquals(iw, dm.width); assertEquals(ih, dm.height)
                    assertTrue(arr.all { it.isFinite() })
                    assertTrue(center > wall, "esfera mais perto que a parede (maior = mais perto)")
                }
            }
        }
    }
}

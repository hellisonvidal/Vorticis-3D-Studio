package com.vorticis.studio3d.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.vorticis.studio3d.core.export.ExportFormat
import com.vorticis.studio3d.core.export.Exporters
import com.vorticis.studio3d.core.model.Node
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.photo.DepthMap
import com.vorticis.studio3d.core.photo.DepthPrep
import com.vorticis.studio3d.core.photo.GrabCut
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.core.photo.PhotoOptions
import com.vorticis.studio3d.core.photo.PhotoTo3D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Laboratório com foto real (só roda se -Dvorticis.lab.image apontar para uma imagem).
 * Gera máscara, profundidade e o GLB final para inspeção visual.
 */
class LabTest {
    @Test fun lab() {
        val img = File(System.getProperty("vorticis.lab.image") ?: "")
        if (!img.exists()) { println("lab: sem imagem"); return }
        val out = File(System.getProperty("vorticis.out") ?: "build/test-exports", "lab").apply { mkdirs() }
        val src = ImageIO.read(img)
        val s = 720f / maxOf(src.width, src.height)
        val w = (src.width * s).toInt(); val h = (src.height * s).toInt()
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(src, 0, 0, w, h, null); dispose() } }
        val px = IntArray(w * h) { bi.getRGB(it % w, it / w) or (0xFF shl 24) }
        // máscara: arquivo externo (preto/branco) ou GrabCut
        val maskFile = File(System.getProperty("vorticis.lab.mask") ?: "")
        val mask = if (maskFile.exists()) {
            val mi = ImageIO.read(maskFile); val m = Mask(w, h)
            for (y in 0 until h) for (x in 0 until w) m[x, y] = (mi.getRGB(x * mi.width / w, y * mi.height / h) and 0xFF) > 127
            m
        } else GrabCut.segment(px, w, h)
        val mo = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) mo.setRGB(x, y, if (mask[x, y]) px[y * w + x] else 0x202020)
        ImageIO.write(mo, "png", File(out, "mascara.png"))
        // profundidade
        var depth: DepthMap? = null
        val model = File(System.getProperty("vorticis.depthModel") ?: "")
        if (model.exists()) {
            val env = OrtEnvironment.getEnvironment()
            env.createSession(model.absolutePath).use { ses ->
                val (iw, ih) = DepthPrep.inputSize(w, h)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(DepthPrep.toTensor(px, w, h, iw, ih)), longArrayOf(1, 3, ih.toLong(), iw.toLong())).use { t ->
                    ses.run(mapOf(ses.inputNames.first() to t)).use { r ->
                        val o = r.get(0) as OnnxTensor; val fb = o.floatBuffer; val a = FloatArray(fb.remaining()); fb.get(a)
                        depth = DepthPrep.fromOutput(a, o.info.shape)
                    }
                }
            }
            val d = depth!!
            val mn = d.data.min(); val mx = d.data.max()
            val dimg = BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB)
            for (i in d.data.indices) { val v = ((d.data[i] - mn) / (mx - mn) * 255).toInt(); dimg.setRGB(i % d.width, i / d.width, (v shl 16) or (v shl 8) or v) }
            ImageIO.write(dimg, "png", File(out, "profundidade.png"))
        }
        for ((name, opt) in listOf("sem_ia" to PhotoOptions(mode = PhotoMode.VOLUME), "com_ia" to PhotoOptions(mode = PhotoMode.VOLUME, depth = depth))) {
            val t0 = System.currentTimeMillis()
            val r = PhotoTo3D.build(px, w, h, mask, opt)
            println("lab $name: ${r.mesh.triangleCount} tri ${System.currentTimeMillis() - t0}ms — ${r.explanation}")
            val f = Exporters.export(Scene(listOf(Node("lab", r.mesh, r.material, 1, "foto"))), ExportFormat.GLB, "lab_$name")
            File(out, "lab_$name.glb").writeBytes(f.bytes)
        }
    }
}

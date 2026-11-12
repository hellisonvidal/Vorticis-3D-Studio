package com.vorticis.studio3d.core

import com.vorticis.studio3d.core.cloud.CloudGenerator
import com.vorticis.studio3d.core.cloud.GlbImporter
import com.vorticis.studio3d.core.cloud.ImageDecoder
import com.vorticis.studio3d.core.cloud.Json
import com.vorticis.studio3d.core.export.ExportFormat
import com.vorticis.studio3d.core.export.Exporters
import com.vorticis.studio3d.core.export.GltfExporter
import com.vorticis.studio3d.core.mind.VorticisMind
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.model.Texture
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudTest {
    private val decoder = ImageDecoder { bytes ->
        ImageIO.read(bytes.inputStream())?.let { im -> Texture(im.width, im.height, IntArray(im.width * im.height) { im.getRGB(it % im.width, it / im.width) or (0xFF shl 24) }) }
    }

    @Test fun jsonRoundTrip() {
        val v = Json.parse("""{"a":[1,2.5,"x\"y",true,null],"b":{"c":"çã"}}""")
        assertEquals("""{"a":[1,2.5,"x\"y",true,null],"b":{"c":"çã"}}""", Json.stringify(v))
    }

    @Test fun importsOwnGlb() {
        val scene = VorticisMind().interpret("castelo", Scene()).scene
        val glb = GltfExporter.glb(scene)
        val back = GlbImporter.import(glb, decoder)
        assertEquals(scene.triangleCount, back.triangleCount)
        assertTrue(back.nodes.any { it.material.texture != null }, "textura preservada")
        assertTrue(abs(back.bounds().sizeY - 1.2f) < 0.01f && abs(back.bounds().minY) < 1e-4f)
        // o modelo importado pode ser reexportado em todos os formatos
        for (f in ExportFormat.entries) assertTrue(Exporters.export(back, f, "x").bytes.isNotEmpty())
    }

    /** Geração real na nuvem gratuita (só roda no GitHub Actions com VORTICIS_CLOUD_TEST=1). */
    @Test fun realCloudGeneration() {
        if (System.getenv("VORTICIS_CLOUD_TEST") != "1") { println("nuvem: teste pulado"); return }
        val img = File("../tools/probe/teste_foguete.png").readBytes()
        val t0 = System.currentTimeMillis()
        val glb = CloudGenerator.generate(img, "image/png", System.getenv("HF_TOKEN")) { m, f -> println("  progresso: $m ${f ?: ""}") }
        val scene = GlbImporter.import(glb, decoder)
        println("nuvem: GLB ${glb.size} bytes em ${(System.currentTimeMillis() - t0) / 1000}s; ${scene.nodes.size} malhas, ${scene.triangleCount} triângulos, textura=${scene.nodes.any { it.material.texture != null }}")
        assertTrue(scene.triangleCount > 1000)
        val out = File(System.getProperty("vorticis.out") ?: "build/test-exports").apply { mkdirs() }
        File(out, "nuvem_trellis.glb").writeBytes(glb)
        File(out, "nuvem_reimportado.glb").writeBytes(GltfExporter.glb(scene))
    }
}

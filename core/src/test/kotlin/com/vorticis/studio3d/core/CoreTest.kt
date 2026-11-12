package com.vorticis.studio3d.core

import com.vorticis.studio3d.core.export.ExportFormat
import com.vorticis.studio3d.core.export.Exporters
import com.vorticis.studio3d.core.mind.Special
import com.vorticis.studio3d.core.mind.TextRasterizer
import com.vorticis.studio3d.core.mind.VorticisMind
import com.vorticis.studio3d.core.model.Node
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.core.photo.PhotoOptions
import com.vorticis.studio3d.core.photo.PhotoTo3D
import com.vorticis.studio3d.core.photo.Segmentation
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    private val out = File(System.getProperty("vorticis.out") ?: "build/test-exports").apply { mkdirs() }

    private val awtText = TextRasterizer { text ->
        val font = Font(Font.SANS_SERIF, Font.BOLD, 120)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().apply { this.font = font }
        val fm = probe.fontMetrics
        val w = fm.stringWidth(text) + 40; val h = fm.height + 40
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK; g.fillRect(0, 0, w, h)
        g.color = Color.WHITE; g.font = font
        g.drawString(text, 20, 20 + fm.ascent)
        val m = Mask(w, h)
        for (y in 0 until h) for (x in 0 until w) m[x, y] = (img.getRGB(x, y) and 0xFF) > 127
        m
    }

    private val mind = VorticisMind(awtText)

    private fun run(vararg cmds: String): Scene {
        var s = Scene()
        for (c in cmds) {
            val r = mind.interpret(c, s)
            println("> $c\n  ${r.message}  [nós=${r.scene.nodes.size}, tris=${r.scene.triangleCount}]")
            s = r.scene
        }
        return s
    }

    private fun kinds(s: Scene) = s.nodes.groupBy { it.group }.map { it.value.first().kind }

    /** Cada aresta usada exatamente 2 vezes com orientações opostas = sólido fechado e orientado. */
    private fun isClosed(n: Node): Boolean {
        val m = n.mesh
        val weld = com.vorticis.studio3d.core.geom.MeshOps.weldIds(m)
        val edges = HashMap<Long, Int>()
        for (t in 0 until m.triangleCount) for (k in 0 until 3) {
            val a = weld[m.indices[t * 3 + k]]; val b = weld[m.indices[t * 3 + (k + 1) % 3]]
            val key = a.toLong() shl 32 or b.toLong()
            edges[key] = (edges[key] ?: 0) + 1
        }
        return edges.all { (k, v) -> val rev = (k and 0xFFFFFFFFL) shl 32 or (k ushr 32); v == 1 && edges[rev] == 1 }
    }

    @Test fun createsTableWithChairsAround() {
        val s = run("crie uma mesa redonda de madeira com 4 cadeiras pretas")
        val k = kinds(s)
        assertEquals(1, k.count { it == "mesa" })
        assertEquals(4, k.count { it == "cadeira" })
        val chairColors = s.nodes.filter { it.kind == "cadeira" }.map { it.material.color }.toSet()
        assertTrue(chairColors.all { it == 0xFF1E1E1E.toInt() }, "cadeiras pretas")
        // cadeiras ao redor: distâncias ao centro parecidas
        val tb = s.groupBounds(s.nodes.first { it.kind == "mesa" }.group)
        val d = s.nodes.filter { it.kind == "cadeira" }.map { it.group }.distinct().map {
            val b = s.groupBounds(it); kotlin.math.hypot((b.centerX - tb.centerX).toDouble(), (b.centerZ - tb.centerZ).toDouble())
        }
        assertTrue(d.max() - d.min() < 0.05, "cadeiras em círculo: $d")
    }

    @Test fun partsColorsAndCounts() {
        val s = run("uma casa azul de 2 andares com telhado vermelho")
        val walls = s.nodes.first { it.part == "paredes" }
        val roof = s.nodes.first { it.part == "telhado" }
        assertEquals(0xFF2F6FD8.toInt(), walls.material.color)
        assertEquals(0xFFD8322F.toInt(), roof.material.color)
        assertTrue(s.bounds().sizeY > 3f, "2 andares deixa a casa alta: ${s.bounds().sizeY}")
    }

    @Test fun stackingAndEditing() {
        var s = run("esfera dourada em cima de um cubo de marmore")
        val sphere = s.groupBounds(s.nodes.first { it.kind == "esfera" }.group)
        val cube = s.groupBounds(s.nodes.first { it.kind == "cubo" }.group)
        assertTrue(abs(sphere.minY - cube.maxY) < 0.02f || abs(cube.minY - sphere.maxY) < 0.02f, "empilhado")
        val before = s.bounds().sizeY
        s = mind.interpret("deixe tudo 2 vezes maior", s).scene
        assertTrue(abs(s.bounds().sizeY - before * 2) < 0.01f)
        s = mind.interpret("pinte a esfera de verde", s).scene
        assertEquals(0xFF3BA55C.toInt(), s.nodes.first { it.kind == "esfera" }.material.color)
        s = mind.interpret("remova o cubo", s).scene
        assertTrue(s.nodes.none { it.kind == "cubo" })
    }

    @Test fun manyCommands() {
        val cmds = listOf(
            "foguete vermelho com 3 aletas", "arvore low poly", "carro azul", "castelo com 6 torres", "robo", "texto 'VORTICIS' dourado",
            "estrela de 7 pontas amarela", "predio de 12 andares", "um vaso", "caneca branca", "trofeu", "flor rosa com 10 petalas",
            "cogumelo", "aviao", "barco", "espada", "planeta", "ovni", "dado", "escada com 5 degraus", "sofa vermelho", "cama",
            "luminaria", "garrafa verde", "estante com 6 prateleiras", "boneco", "banco", "coluna", "coracao", "piramide", "toro", "copo",
            "cilindro de 2 metros de altura", "mesa de 1,2 m de largura", "3 esferas grandes", "pinheiro",
        )
        for (c in cmds) {
            val s = run(c)
            assertTrue(s.nodes.isNotEmpty(), "nada criado para: $c")
        }
        val tall = run("cilindro de 2 metros de altura").bounds().sizeY
        assertTrue(abs(tall - 2f) < 0.01f, "altura $tall")
        assertEquals(3, kinds(run("3 esferas grandes")).size)
        assertEquals(Special.UNDO, mind.interpret("desfazer", Scene()).special)
        val photo = mind.interpret("transforme a foto em vaso", run("cubo"))
        assertEquals(PhotoMode.LATHE, photo.photoMode)
        val edits = run("cadeira", "gire 45 graus", "mais alto", "mova para direita", "duplique 2 vezes", "suavize", "espelhe", "de metal")
        assertEquals(3, edits.groups().size)
    }

    // ------------------------------------------------------------- foto
    private fun synthetic(kind: String, w: Int = 320, h: Int = 400): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x - w / 2f) / (w / 2f); val ny = (y - h / 2f) / (h / 2f)
            val inside = when (kind) {
                "vase" -> { val r = 0.35f + 0.25f * kotlin.math.sin((ny + 1) * 2.2f); abs(nx) < r && abs(ny) < 0.85f }
                "blob" -> (nx * nx) / 0.5f + (ny * ny) / 0.7f < 1f || ((nx - 0.5f) * (nx - 0.5f) + (ny + 0.5f) * (ny + 0.5f)) < 0.06f
                else -> abs(nx) < 0.6f && abs(ny) < 0.3f
            }
            val noise = ((x * 31 + y * 17) % 13) - 6
            px[y * w + x] = if (inside) (0xFF shl 24) or ((200 + noise) shl 16) or ((60 + noise) shl 8) or (40 + noise)
            else (0xFF shl 24) or ((235 + noise) shl 16) or ((235 + noise) shl 8) or (230 + noise)
        }
        return px
    }

    @Test fun photoPipelines() {
        val w = 320; val h = 400
        for (kind in listOf("blob", "vase", "rect")) {
            val px = synthetic(kind)
            val mask = Segmentation.auto(px, w, h)
            assertTrue(mask.area > w * h * 0.1, "máscara $kind = ${mask.area}")
            val auto = PhotoTo3D.build(px, w, h, mask)
            println("foto $kind -> ${auto.mode}: ${auto.explanation} tris=${auto.mesh.triangleCount}")
            if (kind == "vase") assertEquals(PhotoMode.LATHE, auto.mode)
            if (kind == "blob") assertEquals(PhotoMode.VOLUME, auto.mode)
            for (mode in PhotoMode.entries.filter { it != PhotoMode.AUTO }) {
                val r = PhotoTo3D.build(px, w, h, mask, PhotoOptions(mode = mode))
                val node = Node("foto_${kind}_$mode", r.mesh, r.material, 1, "foto")
                assertTrue(r.mesh.triangleCount > 100)
                assertTrue(isClosed(node), "malha fechada: $kind $mode")
                if (kind == "blob" && mode == PhotoMode.VOLUME) exportAll(Scene(listOf(node)), "foto_volume")
                if (kind == "vase" && mode == PhotoMode.LATHE) exportAll(Scene(listOf(node)), "foto_vaso")
            }
        }
    }

    @Test fun exportEverything() {
        val s = run("mesa redonda de madeira com 4 cadeiras", "adicione um vaso em cima")
        exportAll(s, "cena_mesa")
        exportAll(run("texto 'OLA' dourado"), "texto")
        exportAll(run("castelo"), "castelo")
        exportAll(run("casa azul de 2 andares com telhado vermelho", "adicione uma arvore ao lado"), "casa")
        exportAll(run("foguete vermelho com 3 aletas"), "foguete")
        exportAll(run("carro azul", "adicione um robo ao lado"), "carro_robo")
        exportAll(run("esfera dourada em cima de um cubo de marmore"), "esfera_cubo")
        val all = Exporters.exportAll(s, "cena")
        File(out, all.name).writeBytes(all.bytes)
        assertTrue(all.bytes.size > 1000)
    }

    private fun exportAll(s: Scene, base: String) {
        val dir = File(out, base).apply { mkdirs() }
        for (fmt in ExportFormat.entries) {
            for (f in Exporters.exportFiles(s, fmt, base)) File(dir, f.name).writeBytes(f.bytes)
            val packed = Exporters.export(s, fmt, base)
            assertTrue(packed.bytes.isNotEmpty(), "$fmt vazio")
        }
    }
}

package com.vorticis.studio3d.core.export

import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Node
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.model.Texture
import java.util.IdentityHashMap

/** Arquivo gerado por um exportador. */
class ExportFile(val name: String, val bytes: ByteArray, val mime: String)

enum class ExportFormat(
    val ext: String,
    val label: String,
    val description: String,
    val mime: String,
) {
    GLB("glb", "GLB (glTF binário)", "Padrão da web, AR, Android, Unity, Godot, Three.js. Com texturas.", "model/gltf-binary"),
    GLTF("gltf", "glTF (texto)", "glTF 2.0 em JSON com dados embutidos.", "model/gltf+json"),
    OBJ("obj", "OBJ + MTL", "Universal: Blender, Maya, 3ds Max, Cinema 4D. Vai em ZIP com materiais e texturas.", "model/obj"),
    FBX("fbx", "FBX (binário)", "Unity, Unreal, Maya, 3ds Max, Blender. Com materiais e textura embutida.", "application/octet-stream"),
    DAE("dae", "Collada (DAE)", "SketchUp, Blender, Godot e motores de jogo.", "model/vnd.collada+xml"),
    USDZ("usdz", "USDZ", "Realidade aumentada no iPhone/iPad (Quick Look) e Apple Vision.", "model/vnd.usdz+zip"),
    STL("stl", "STL (impressão 3D)", "Impressoras 3D e fatiadores (Cura, PrusaSlicer). Z para cima, 1 unidade = 100 mm.", "model/stl"),
    THREEMF("3mf", "3MF (impressão 3D)", "Impressão 3D moderna com cores (Bambu Studio, PrusaSlicer, Cura).", "model/3mf"),
    PLY("ply", "PLY (cores por vértice)", "Escaneamento 3D, MeshLab, CloudCompare. Inclui cores.", "application/octet-stream"),
    OFF("off", "OFF", "Formato geométrico simples para pesquisa e Geomview.", "text/plain"),
    X3D("x3d", "X3D", "Padrão web ISO, sucessor do VRML.", "model/x3d+xml"),
    WRL("wrl", "VRML (WRL)", "Impressão 3D colorida (serviços de impressão) e visualizadores antigos.", "model/vrml"),
    ;

    val fileExtension: String get() = ext
}

object Exporters {

    /** Exporta e empacota: formatos com vários arquivos são entregues em ZIP. */
    fun export(scene: Scene, format: ExportFormat, baseName: String = "modelo"): ExportFile {
        val base = safeName(baseName).trimStart('_').ifEmpty { "modelo" }
        val files = exportFiles(scene, format, base)
        if (files.size == 1) return files[0]
        val zip = ZipWriter()
        files.forEach { zip.add(it.name, it.bytes, compress = true) }
        return ExportFile("${base}_${format.ext}.zip", zip.finish(), "application/zip")
    }

    fun exportFiles(scene: Scene, format: ExportFormat, base: String): List<ExportFile> = when (format) {
        ExportFormat.GLB -> listOf(ExportFile("$base.glb", GltfExporter.glb(scene), format.mime))
        ExportFormat.GLTF -> listOf(ExportFile("$base.gltf", GltfExporter.gltf(scene), format.mime))
        ExportFormat.OBJ -> ObjExporter.export(scene, base)
        ExportFormat.FBX -> listOf(ExportFile("$base.fbx", FbxExporter.export(scene), format.mime))
        ExportFormat.DAE -> DaeExporter.export(scene, base)
        ExportFormat.USDZ -> listOf(ExportFile("$base.usdz", UsdzExporter.export(scene), format.mime))
        ExportFormat.STL -> listOf(ExportFile("$base.stl", StlExporter.export(scene), format.mime))
        ExportFormat.THREEMF -> listOf(ExportFile("$base.3mf", ThreeMfExporter.export(scene), format.mime))
        ExportFormat.PLY -> listOf(ExportFile("$base.ply", PlyExporter.export(scene), format.mime))
        ExportFormat.OFF -> listOf(ExportFile("$base.off", OffExporter.export(scene).toByteArray(), format.mime))
        ExportFormat.X3D -> X3dExporter.export(scene, base)
        ExportFormat.WRL -> listOf(ExportFile("$base.wrl", WrlExporter.export(scene).toByteArray(), format.mime))
    }

    /** Todos os formatos num único ZIP (uma pasta por formato). */
    fun exportAll(scene: Scene, baseName: String = "modelo"): ExportFile {
        val base = safeName(baseName).trimStart('_').ifEmpty { "modelo" }
        val zip = ZipWriter()
        for (fmt in ExportFormat.entries) {
            for (f in exportFiles(scene, fmt, base)) zip.add("${fmt.ext}/${f.name}", f.bytes, compress = true)
        }
        zip.add("LEIA-ME.txt", readme().toByteArray(), compress = true)
        return ExportFile("${base}_todos_formatos.zip", zip.finish(), "application/zip")
    }

    private fun readme() = buildString {
        appendLine("Gerado pelo Vorticis 3D Studio")
        appendLine()
        ExportFormat.entries.forEach { appendLine("${it.ext.uppercase()} - ${it.label}: ${it.description}") }
        appendLine()
        appendLine("Unidades: metros (glTF, FBX, DAE, USDZ, OBJ, X3D, PLY, OFF, WRL).")
        appendLine("STL e 3MF: milímetros com Z para cima, escala 1 m da cena = 100 mm (pronto para imprimir).")
    }
}

/** Utilidades compartilhadas entre exportadores. */
internal class TextureTable(scene: Scene, private val base: String) {
    private val map = IdentityHashMap<Texture, String>()
    val files = ArrayList<ExportFile>()

    init {
        scene.nodes.mapNotNull { it.material.texture }.forEach { t ->
            if (!map.containsKey(t)) {
                val name = "${base}_tex${map.size}.png"
                map[t] = name
                files += ExportFile(name, PngEncoder.encode(t), "image/png")
            }
        }
    }

    fun name(t: Texture?): String? = t?.let { map[it] }
}

internal class MaterialTable(scene: Scene) {
    val list = ArrayList<Material>()
    private val index = HashMap<Material, Int>()
    init { scene.nodes.forEach { n -> index.getOrPut(n.material) { list += n.material; list.size - 1 } } }
    fun indexOf(m: Material) = index[m]!!
    fun id(m: Material) = "M${indexOf(m)}_${safeName(m.name).trimStart('_')}"
}

internal fun nodeIds(scene: Scene): List<Pair<Node, String>> =
    scene.nodes.filter { it.mesh.triangleCount > 0 }.mapIndexed { i, n -> n to "N${i}_${safeName(n.name).trimStart('_')}" }

/** Cor final do vértice: cor do material x textura (para formatos com cor por vértice). */
internal fun vertexColor(m: Material, u: Float, v: Float): Int {
    val t = m.texture ?: return m.color
    val c = t.sample(u, v)
    val r = ((c shr 16) and 255) * ((m.color shr 16) and 255) / 255
    val g = ((c shr 8) and 255) * ((m.color shr 8) and 255) / 255
    val b = (c and 255) * (m.color and 255) / 255
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

// ======================= OBJ =======================
object ObjExporter {
    fun export(scene: Scene, base: String): List<ExportFile> {
        val tex = TextureTable(scene, base)
        val mats = MaterialTable(scene)
        val obj = StringBuilder()
        obj.appendLine("# Vorticis 3D Studio").appendLine("mtllib $base.mtl")
        var off = 1
        for ((n, id) in nodeIds(scene)) {
            val m = n.mesh
            obj.appendLine("o $id")
            for (i in 0 until m.vertexCount) obj.append("v ").append(f(m.positions[i * 3])).append(' ').append(f(m.positions[i * 3 + 1])).append(' ').append(f(m.positions[i * 3 + 2])).append('\n')
            for (i in 0 until m.vertexCount) obj.append("vt ").append(f(m.uvs[i * 2])).append(' ').append(f(1f - m.uvs[i * 2 + 1])).append('\n')
            for (i in 0 until m.vertexCount) obj.append("vn ").append(f(m.normals[i * 3])).append(' ').append(f(m.normals[i * 3 + 1])).append(' ').append(f(m.normals[i * 3 + 2])).append('\n')
            obj.appendLine("usemtl ${mats.id(n.material)}")
            for (t in 0 until m.triangleCount) {
                obj.append('f')
                for (k in 0 until 3) { val v = m.indices[t * 3 + k] + off; obj.append(' ').append(v).append('/').append(v).append('/').append(v) }
                obj.append('\n')
            }
            off += m.vertexCount
        }
        val mtl = StringBuilder("# Vorticis 3D Studio\n")
        for (mt in mats.list) {
            mtl.appendLine("newmtl ${mats.id(mt)}")
            mtl.appendLine("Ka 0 0 0")
            mtl.appendLine("Kd ${f(mt.r)} ${f(mt.g)} ${f(mt.b)}")
            mtl.appendLine("Ks ${f(0.5f * (1 - mt.roughness))} ${f(0.5f * (1 - mt.roughness))} ${f(0.5f * (1 - mt.roughness))}")
            mtl.appendLine("Ns ${f((1 - mt.roughness) * 500 + 4)}")
            mtl.appendLine("Pr ${f(mt.roughness)}").appendLine("Pm ${f(mt.metallic)}")
            mtl.appendLine("d 1").appendLine("illum 2")
            tex.name(mt.texture)?.let { mtl.appendLine("map_Kd $it") }
        }
        return listOf(
            ExportFile("$base.obj", obj.toString().toByteArray(), "model/obj"),
            ExportFile("$base.mtl", mtl.toString().toByteArray(), "text/plain"),
        ) + tex.files
    }
}

// ======================= STL =======================
object StlExporter {
    /** Converte Y-up (metros) para Z-up (mm, 1 m = 100 mm de impressão). */
    internal fun toPrint(x: Float, y: Float, z: Float) = Triple(x * 100f, -z * 100f, y * 100f)

    fun export(scene: Scene): ByteArray {
        val tris = scene.nodes.sumOf { it.mesh.triangleCount }
        val b = LeBuffer(84 + tris * 50)
        val header = "Vorticis 3D Studio - STL (mm, Z-up)".toByteArray().copyOf(80)
        b.bytes(header).i32(tris)
        for (n in scene.nodes) {
            val m = n.mesh; val p = m.positions
            for (t in 0 until m.triangleCount) {
                val v = IntArray(3) { m.indices[t * 3 + it] }
                val pts = v.map { toPrint(p[it * 3], p[it * 3 + 1], p[it * 3 + 2]) }
                val ux = pts[1].first - pts[0].first; val uy = pts[1].second - pts[0].second; val uz = pts[1].third - pts[0].third
                val wx = pts[2].first - pts[0].first; val wy = pts[2].second - pts[0].second; val wz = pts[2].third - pts[0].third
                var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
                val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (l > 0) { nx /= l; ny /= l; nz /= l }
                b.f32(nx).f32(ny).f32(nz)
                pts.forEach { b.f32(it.first).f32(it.second).f32(it.third) }
                b.i16(0)
            }
        }
        return b.toByteArray()
    }
}

// ======================= PLY =======================
object PlyExporter {
    fun export(scene: Scene): ByteArray {
        val vc = scene.vertexCount; val fc = scene.triangleCount
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\ncomment Vorticis 3D Studio\n")
            append("element vertex $vc\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property float nx\nproperty float ny\nproperty float nz\n")
            append("property float s\nproperty float t\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\nproperty uchar alpha\n")
            append("element face $fc\nproperty list uchar int vertex_indices\nend_header\n")
        }
        val b = LeBuffer(header.length + vc * 36 + fc * 13)
        b.bytes(header.toByteArray(Charsets.US_ASCII))
        for (n in scene.nodes) {
            val m = n.mesh
            for (i in 0 until m.vertexCount) {
                b.f32(m.positions[i * 3]).f32(m.positions[i * 3 + 1]).f32(m.positions[i * 3 + 2])
                b.f32(m.normals[i * 3]).f32(m.normals[i * 3 + 1]).f32(m.normals[i * 3 + 2])
                b.f32(m.uvs[i * 2]).f32(1f - m.uvs[i * 2 + 1])
                val c = vertexColor(n.material, m.uvs[i * 2], m.uvs[i * 2 + 1])
                b.u8((c shr 16) and 255).u8((c shr 8) and 255).u8(c and 255).u8(255)
            }
        }
        var off = 0
        for (n in scene.nodes) {
            val m = n.mesh
            for (t in 0 until m.triangleCount) {
                b.u8(3); for (k in 0 until 3) b.i32(m.indices[t * 3 + k] + off)
            }
            off += m.vertexCount
        }
        return b.toByteArray()
    }
}

// ======================= OFF =======================
object OffExporter {
    fun export(scene: Scene): String {
        val sb = StringBuilder("OFF\n# Vorticis 3D Studio\n${scene.vertexCount} ${scene.triangleCount} 0\n")
        for (n in scene.nodes) {
            val p = n.mesh.positions
            for (i in 0 until n.mesh.vertexCount) sb.append(f(p[i * 3])).append(' ').append(f(p[i * 3 + 1])).append(' ').append(f(p[i * 3 + 2])).append('\n')
        }
        var off = 0
        for (n in scene.nodes) {
            val m = n.mesh
            for (t in 0 until m.triangleCount) sb.append("3 ").append(m.indices[t * 3] + off).append(' ').append(m.indices[t * 3 + 1] + off).append(' ').append(m.indices[t * 3 + 2] + off).append('\n')
            off += m.vertexCount
        }
        return sb.toString()
    }
}

// ======================= VRML 2.0 =======================
object WrlExporter {
    fun export(scene: Scene): String {
        val sb = StringBuilder("#VRML V2.0 utf8\n# Vorticis 3D Studio\n")
        for ((n, id) in nodeIds(scene)) {
            val m = n.mesh
            sb.append("DEF $id Shape {\n  appearance Appearance { material Material { diffuseColor ${f(n.material.r)} ${f(n.material.g)} ${f(n.material.b)} shininess ${f(1 - n.material.roughness)} } }\n")
            sb.append("  geometry IndexedFaceSet {\n    solid TRUE\n    colorPerVertex TRUE\n    coord Coordinate { point [\n")
            for (i in 0 until m.vertexCount) sb.append("      ").append(f(m.positions[i * 3])).append(' ').append(f(m.positions[i * 3 + 1])).append(' ').append(f(m.positions[i * 3 + 2])).append(",\n")
            sb.append("    ] }\n    color Color { color [\n")
            for (i in 0 until m.vertexCount) {
                val c = vertexColor(n.material, m.uvs[i * 2], m.uvs[i * 2 + 1])
                sb.append("      ").append(f(((c shr 16) and 255) / 255f)).append(' ').append(f(((c shr 8) and 255) / 255f)).append(' ').append(f((c and 255) / 255f)).append(",\n")
            }
            sb.append("    ] }\n    coordIndex [\n")
            for (t in 0 until m.triangleCount) sb.append("      ").append(m.indices[t * 3]).append(", ").append(m.indices[t * 3 + 1]).append(", ").append(m.indices[t * 3 + 2]).append(", -1,\n")
            sb.append("    ]\n  }\n}\n")
        }
        return sb.toString()
    }
}

// ======================= X3D =======================
object X3dExporter {
    fun export(scene: Scene, base: String): List<ExportFile> {
        val tex = TextureTable(scene, base)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE X3D PUBLIC \"ISO//Web3D//DTD X3D 3.3//EN\" \"http://www.web3d.org/specifications/x3d-3.3.dtd\">\n")
        sb.append("<X3D profile=\"Interchange\" version=\"3.3\">\n<head><meta name=\"generator\" content=\"Vorticis 3D Studio\"/></head>\n<Scene>\n")
        for ((n, id) in nodeIds(scene)) {
            val m = n.mesh; val mt = n.material
            sb.append("<Shape DEF=\"$id\">\n<Appearance>\n")
            sb.append("<Material diffuseColor=\"${f(mt.r)} ${f(mt.g)} ${f(mt.b)}\" shininess=\"${f(1 - mt.roughness)}\" specularColor=\"${f(mt.metallic * 0.8f)} ${f(mt.metallic * 0.8f)} ${f(mt.metallic * 0.8f)}\"/>\n")
            tex.name(mt.texture)?.let { sb.append("<ImageTexture url=\"$it\"/>\n") }
            sb.append("</Appearance>\n<IndexedTriangleSet solid=\"true\" normalPerVertex=\"true\" index=\"")
            for (k in m.indices.indices) { if (k > 0) sb.append(' '); sb.append(m.indices[k]) }
            sb.append("\">\n<Coordinate point=\"")
            for (i in 0 until m.vertexCount) { if (i > 0) sb.append(' '); sb.append(f(m.positions[i * 3])).append(' ').append(f(m.positions[i * 3 + 1])).append(' ').append(f(m.positions[i * 3 + 2])) }
            sb.append("\"/>\n<Normal vector=\"")
            for (i in 0 until m.vertexCount) { if (i > 0) sb.append(' '); sb.append(f(m.normals[i * 3])).append(' ').append(f(m.normals[i * 3 + 1])).append(' ').append(f(m.normals[i * 3 + 2])) }
            sb.append("\"/>\n<TextureCoordinate point=\"")
            for (i in 0 until m.vertexCount) { if (i > 0) sb.append(' '); sb.append(f(m.uvs[i * 2])).append(' ').append(f(1f - m.uvs[i * 2 + 1])) }
            sb.append("\"/>\n</IndexedTriangleSet>\n</Shape>\n")
        }
        sb.append("</Scene>\n</X3D>\n")
        return listOf(ExportFile("$base.x3d", sb.toString().toByteArray(), "model/x3d+xml")) + tex.files
    }
}

// ======================= COLLADA =======================
object DaeExporter {
    fun export(scene: Scene, base: String): List<ExportFile> {
        val tex = TextureTable(scene, base)
        val mats = MaterialTable(scene)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">\n")
        sb.append("<asset><contributor><authoring_tool>Vorticis 3D Studio</authoring_tool></contributor>")
        sb.append("<created>2026-01-01T00:00:00Z</created><modified>2026-01-01T00:00:00Z</modified>")
        sb.append("<unit name=\"meter\" meter=\"1\"/><up_axis>Y_UP</up_axis></asset>\n")
        sb.append("<library_images>\n")
        tex.files.forEachIndexed { i, tf -> sb.append("<image id=\"IMG$i\" name=\"IMG$i\"><init_from>${tf.name}</init_from></image>\n") }
        sb.append("</library_images>\n<library_effects>\n")
        for (mt in mats.list) {
            val id = mats.id(mt)
            sb.append("<effect id=\"$id-fx\"><profile_COMMON>\n")
            val tn = tex.name(mt.texture)
            val imgIdx = tex.files.indexOfFirst { it.name == tn }
            if (imgIdx >= 0) {
                sb.append("<newparam sid=\"$id-surface\"><surface type=\"2D\"><init_from>IMG$imgIdx</init_from></surface></newparam>\n")
                sb.append("<newparam sid=\"$id-sampler\"><sampler2D><source>$id-surface</source></sampler2D></newparam>\n")
            }
            sb.append("<technique sid=\"common\"><phong>")
            sb.append("<emission><color>0 0 0 1</color></emission>")
            if (imgIdx >= 0) sb.append("<diffuse><texture texture=\"$id-sampler\" texcoord=\"UVSET0\"/></diffuse>")
            else sb.append("<diffuse><color>${f(mt.r)} ${f(mt.g)} ${f(mt.b)} 1</color></diffuse>")
            val s = 0.5f * (1 - mt.roughness)
            sb.append("<specular><color>${f(s)} ${f(s)} ${f(s)} 1</color></specular><shininess><float>${f((1 - mt.roughness) * 100 + 2)}</float></shininess>")
            sb.append("</phong></technique></profile_COMMON></effect>\n")
        }
        sb.append("</library_effects>\n<library_materials>\n")
        for (mt in mats.list) { val id = mats.id(mt); sb.append("<material id=\"$id\" name=\"${xmlEscape(mt.name)}\"><instance_effect url=\"#$id-fx\"/></material>\n") }
        sb.append("</library_materials>\n<library_geometries>\n")
        val nodes = nodeIds(scene)
        for ((n, id) in nodes) {
            val m = n.mesh
            sb.append("<geometry id=\"$id-mesh\" name=\"${xmlEscape(n.name)}\"><mesh>\n")
            fun source(suffix: String, data: FloatArray, stride: Int, names: List<String>, transform: (Int, Float) -> Float = { _, v -> v }) {
                sb.append("<source id=\"$id-$suffix\"><float_array id=\"$id-$suffix-array\" count=\"${data.size}\">")
                for (k in data.indices) { if (k > 0) sb.append(' '); sb.append(f(transform(k, data[k]))) }
                sb.append("</float_array><technique_common><accessor source=\"#$id-$suffix-array\" count=\"${data.size / stride}\" stride=\"$stride\">")
                names.forEach { sb.append("<param name=\"$it\" type=\"float\"/>") }
                sb.append("</accessor></technique_common></source>\n")
            }
            source("pos", m.positions, 3, listOf("X", "Y", "Z"))
            source("nor", m.normals, 3, listOf("X", "Y", "Z"))
            source("uv", m.uvs, 2, listOf("S", "T")) { k, v -> if (k % 2 == 1) 1f - v else v }
            sb.append("<vertices id=\"$id-vtx\"><input semantic=\"POSITION\" source=\"#$id-pos\"/></vertices>\n")
            sb.append("<triangles material=\"mat\" count=\"${m.triangleCount}\">")
            sb.append("<input semantic=\"VERTEX\" source=\"#$id-vtx\" offset=\"0\"/>")
            sb.append("<input semantic=\"NORMAL\" source=\"#$id-nor\" offset=\"0\"/>")
            sb.append("<input semantic=\"TEXCOORD\" source=\"#$id-uv\" offset=\"0\" set=\"0\"/><p>")
            for (k in m.indices.indices) { if (k > 0) sb.append(' '); sb.append(m.indices[k]) }
            sb.append("</p></triangles>\n</mesh></geometry>\n")
        }
        sb.append("</library_geometries>\n<library_visual_scenes><visual_scene id=\"Scene\" name=\"Scene\">\n")
        for ((n, id) in nodes) {
            sb.append("<node id=\"$id\" name=\"${xmlEscape(n.name)}\" type=\"NODE\"><instance_geometry url=\"#$id-mesh\"><bind_material><technique_common>")
            sb.append("<instance_material symbol=\"mat\" target=\"#${mats.id(n.material)}\"><bind_vertex_input semantic=\"UVSET0\" input_semantic=\"TEXCOORD\" input_set=\"0\"/></instance_material>")
            sb.append("</technique_common></bind_material></instance_geometry></node>\n")
        }
        sb.append("</visual_scene></library_visual_scenes>\n<scene><instance_visual_scene url=\"#Scene\"/></scene>\n</COLLADA>\n")
        return listOf(ExportFile("$base.dae", sb.toString().toByteArray(), "model/vnd.collada+xml")) + tex.files
    }
}

// ======================= 3MF =======================
object ThreeMfExporter {
    fun export(scene: Scene): ByteArray {
        val mats = MaterialTable(scene)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<model unit=\"millimeter\" xml:lang=\"pt-BR\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
        sb.append("<metadata name=\"Application\">Vorticis 3D Studio</metadata>\n<resources>\n<basematerials id=\"1\">\n")
        for (mt in mats.list) {
            val c = vertexAvg(mt)
            sb.append("<base name=\"${xmlEscape(mt.name)}\" displaycolor=\"${String.format("#%06XFF", c and 0xFFFFFF)}\"/>\n")
        }
        sb.append("</basematerials>\n")
        val nodes = nodeIds(scene)
        nodes.forEachIndexed { i, (n, _) ->
            val m = n.mesh
            sb.append("<object id=\"${i + 2}\" name=\"${xmlEscape(n.name)}\" type=\"model\" pid=\"1\" pindex=\"${mats.indexOf(n.material)}\">\n<mesh>\n<vertices>\n")
            for (v in 0 until m.vertexCount) {
                val (x, y, z) = StlExporter.toPrint(m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2])
                sb.append("<vertex x=\"${f(x)}\" y=\"${f(y)}\" z=\"${f(z)}\"/>\n")
            }
            sb.append("</vertices>\n<triangles>\n")
            for (t in 0 until m.triangleCount) {
                val a = m.indices[t * 3]; val b = m.indices[t * 3 + 1]; val c = m.indices[t * 3 + 2]
                if (a == b || b == c || a == c) continue
                sb.append("<triangle v1=\"$a\" v2=\"$b\" v3=\"$c\"/>\n")
            }
            sb.append("</triangles>\n</mesh>\n</object>\n")
        }
        sb.append("</resources>\n<build>\n")
        nodes.indices.forEach { sb.append("<item objectid=\"${it + 2}\"/>\n") }
        sb.append("</build>\n</model>\n")
        val zip = ZipWriter()
        zip.add("[Content_Types].xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>").toByteArray(), true)
        zip.add("_rels/.rels", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>").toByteArray(), true)
        zip.add("3D/3dmodel.model", sb.toString().toByteArray(), true)
        return zip.finish()
    }

    /** Cor média do material (considera a textura). */
    private fun vertexAvg(m: Material): Int {
        val t = m.texture ?: return m.color
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val step = maxOf(1, t.pixels.size / 4096)
        for (i in t.pixels.indices step step) { val c = t.pixels[i]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
        val avg = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        return vertexColorMul(avg, m.color)
    }

    private fun vertexColorMul(a: Int, b: Int): Int {
        val r = ((a shr 16) and 255) * ((b shr 16) and 255) / 255
        val g = ((a shr 8) and 255) * ((b shr 8) and 255) / 255
        val bl = (a and 255) * (b and 255) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

// ======================= USDZ =======================
object UsdzExporter {
    fun export(scene: Scene): ByteArray {
        val tex = TextureTable(scene, "tex")
        val mats = MaterialTable(scene)
        val sb = StringBuilder()
        sb.append("#usda 1.0\n(\n    defaultPrim = \"Root\"\n    metersPerUnit = 1\n    upAxis = \"Y\"\n    doc = \"Vorticis 3D Studio\"\n)\n\n")
        sb.append("def Xform \"Root\" (\n    kind = \"component\"\n)\n{\n")
        sb.append("    def Scope \"Materials\"\n    {\n")
        for (mt in mats.list) {
            val id = mats.id(mt)
            val path = "/Root/Materials/$id"
            sb.append("        def Material \"$id\"\n        {\n")
            sb.append("            token outputs:surface.connect = <$path/PBR.outputs:surface>\n")
            sb.append("            def Shader \"PBR\"\n            {\n                uniform token info:id = \"UsdPreviewSurface\"\n")
            val tn = tex.name(mt.texture)
            if (tn != null) sb.append("                color3f inputs:diffuseColor.connect = <$path/Tex.outputs:rgb>\n")
            else sb.append("                color3f inputs:diffuseColor = (${f(mt.r)}, ${f(mt.g)}, ${f(mt.b)})\n")
            sb.append("                float inputs:metallic = ${f(mt.metallic)}\n                float inputs:roughness = ${f(mt.roughness)}\n")
            sb.append("                token outputs:surface\n            }\n")
            if (tn != null) {
                sb.append("            def Shader \"St\"\n            {\n                uniform token info:id = \"UsdPrimvarReader_float2\"\n")
                sb.append("                string inputs:varname = \"st\"\n                float2 outputs:result\n            }\n")
                sb.append("            def Shader \"Tex\"\n            {\n                uniform token info:id = \"UsdUVTexture\"\n")
                sb.append("                asset inputs:file = @$tn@\n")
                sb.append("                float2 inputs:st.connect = <$path/St.outputs:result>\n")
                sb.append("                token inputs:wrapS = \"repeat\"\n                token inputs:wrapT = \"repeat\"\n")
                sb.append("                float3 outputs:rgb\n            }\n")
            }
            sb.append("        }\n")
        }
        sb.append("    }\n\n")
        for ((n, id) in nodeIds(scene)) {
            val m = n.mesh
            val b = m.bounds()
            sb.append("    def Mesh \"$id\" (\n        prepend apiSchemas = [\"MaterialBindingAPI\"]\n    )\n    {\n")
            sb.append("        float3[] extent = [(${f(b.minX)}, ${f(b.minY)}, ${f(b.minZ)}), (${f(b.maxX)}, ${f(b.maxY)}, ${f(b.maxZ)})]\n")
            sb.append("        int[] faceVertexCounts = [")
            for (t in 0 until m.triangleCount) { if (t > 0) sb.append(", "); sb.append('3') }
            sb.append("]\n        int[] faceVertexIndices = [")
            for (k in m.indices.indices) { if (k > 0) sb.append(", "); sb.append(m.indices[k]) }
            sb.append("]\n        point3f[] points = [")
            for (i in 0 until m.vertexCount) { if (i > 0) sb.append(", "); sb.append('(').append(f(m.positions[i * 3])).append(", ").append(f(m.positions[i * 3 + 1])).append(", ").append(f(m.positions[i * 3 + 2])).append(')') }
            sb.append("]\n        normal3f[] normals = [")
            for (i in 0 until m.vertexCount) { if (i > 0) sb.append(", "); sb.append('(').append(f(m.normals[i * 3])).append(", ").append(f(m.normals[i * 3 + 1])).append(", ").append(f(m.normals[i * 3 + 2])).append(')') }
            sb.append("] (\n            interpolation = \"vertex\"\n        )\n")
            sb.append("        texCoord2f[] primvars:st = [")
            for (i in 0 until m.vertexCount) { if (i > 0) sb.append(", "); sb.append('(').append(f(m.uvs[i * 2])).append(", ").append(f(1f - m.uvs[i * 2 + 1])).append(')') }
            sb.append("] (\n            interpolation = \"vertex\"\n        )\n")
            sb.append("        uniform token subdivisionScheme = \"none\"\n")
            sb.append("        rel material:binding = </Root/Materials/${mats.id(n.material)}>\n    }\n\n")
        }
        sb.append("}\n")
        val zip = ZipWriter(align = 64)
        zip.add("model.usda", sb.toString().toByteArray())
        tex.files.forEach { zip.add(it.name, it.bytes) }
        return zip.finish()
    }
}

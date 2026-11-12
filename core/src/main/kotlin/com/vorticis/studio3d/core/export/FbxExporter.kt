package com.vorticis.studio3d.core.export

import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.model.Texture
import java.util.IdentityHashMap

/**
 * FBX binário 7.4 (o mesmo usado por Blender/Unity/Unreal),
 * com malha, normais, UVs, materiais Phong e textura embutida.
 */
object FbxExporter {

    private class FNode(val name: String, vararg p: Any) {
        val props = p.toMutableList()
        val children = ArrayList<FNode>()
        fun add(name: String, vararg p: Any, block: FNode.() -> Unit = {}): FNode {
            val n = FNode(name, *p); n.block(); children += n; return n
        }
    }

    private fun FNode.p70(block: FNode.() -> Unit) = add("Properties70", block = block)
    private fun FNode.p(name: String, type: String, label: String, flags: String, vararg v: Any) = add("P", name, type, label, flags, *v)

    private const val SEP = "\u0000\u0001"

    fun export(scene: Scene): ByteArray {
        val mats = MaterialTable(scene)
        val nodes = nodeIds(scene)
        var nextId = 1_000_000L
        fun id() = nextId++

        val root = ArrayList<FNode>()
        root += FNode("FBXHeaderExtension").apply {
            add("FBXHeaderVersion", 1003)
            add("FBXVersion", 7400)
            add("EncryptionType", 0)
            add("CreationTimeStamp") {
                add("Version", 1000); add("Year", 1970); add("Month", 1); add("Day", 1)
                add("Hour", 10); add("Minute", 0); add("Second", 0); add("Millisecond", 0)
            }
            add("Creator", "Vorticis 3D Studio")
        }
        root += FNode("FileId", byteArrayOf(0x28, 0xb3.toByte(), 0x2a, 0xeb.toByte(), 0xb6.toByte(), 0x24, 0xcc.toByte(), 0xc2.toByte(), 0xbf.toByte(), 0xc8.toByte(), 0xb0.toByte(), 0x2a, 0xa9.toByte(), 0x2b, 0xfc.toByte(), 0xf1.toByte()))
        root += FNode("CreationTime", "1970-01-01 10:00:00:000")
        root += FNode("Creator", "Vorticis 3D Studio")
        root += FNode("GlobalSettings").apply {
            add("Version", 1000)
            p70 {
                p("UpAxis", "int", "Integer", "", 1); p("UpAxisSign", "int", "Integer", "", 1)
                p("FrontAxis", "int", "Integer", "", 2); p("FrontAxisSign", "int", "Integer", "", 1)
                p("CoordAxis", "int", "Integer", "", 0); p("CoordAxisSign", "int", "Integer", "", 1)
                p("OriginalUpAxis", "int", "Integer", "", 1); p("OriginalUpAxisSign", "int", "Integer", "", 1)
                p("UnitScaleFactor", "double", "Number", "", 100.0)
                p("OriginalUnitScaleFactor", "double", "Number", "", 100.0)
            }
        }
        val docId = id()
        root += FNode("Documents").apply {
            add("Count", 1)
            add("Document", docId, "Scene", "Scene") { p70 { }; add("RootNode", 0L) }
        }
        root += FNode("References")

        // texturas únicas
        val texIds = IdentityHashMap<Texture, Pair<Long, Long>>() // textura -> (textureId, videoId)
        val texNames = IdentityHashMap<Texture, String>()
        scene.nodes.mapNotNull { it.material.texture }.forEach { t ->
            if (!texIds.containsKey(t)) { texIds[t] = Pair(id(), id()); texNames[t] = "tex${texNames.size}" }
        }
        val matIds = mats.list.associateWith { id() }

        root += FNode("Definitions").apply {
            add("Version", 100)
            add("Count", 1 + nodes.size * 2 + mats.list.size + texIds.size * 2)
            add("ObjectType", "GlobalSettings") { add("Count", 1) }
            add("ObjectType", "Model") { add("Count", nodes.size) }
            add("ObjectType", "Geometry") { add("Count", nodes.size) }
            add("ObjectType", "Material") { add("Count", mats.list.size) }
            if (texIds.isNotEmpty()) {
                add("ObjectType", "Texture") { add("Count", texIds.size) }
                add("ObjectType", "Video") { add("Count", texIds.size) }
            }
        }

        val connections = FNode("Connections")
        val objects = FNode("Objects")
        for ((n, name) in nodes) {
            val m = n.mesh
            val gid = id(); val mid = id()
            val poly = IntArray(m.indices.size) { k -> if (k % 3 == 2) -(m.indices[k] + 1) else m.indices[k] }
            val normals = DoubleArray(m.indices.size * 3) { k -> m.normals[m.indices[k / 3] * 3 + k % 3].toDouble() }
            val uv = DoubleArray(m.vertexCount * 2) { k -> if (k % 2 == 0) m.uvs[k].toDouble() else (1.0 - m.uvs[k]) }
            objects.add("Geometry", gid, "$name${SEP}Geometry", "Mesh") {
                p70 { }
                add("GeometryVersion", 124)
                add("Vertices", DoubleArray(m.positions.size) { m.positions[it].toDouble() })
                add("PolygonVertexIndex", poly)
                add("LayerElementNormal", 0) {
                    add("Version", 101); add("Name", "")
                    add("MappingInformationType", "ByPolygonVertex"); add("ReferenceInformationType", "Direct")
                    add("Normals", normals)
                }
                add("LayerElementUV", 0) {
                    add("Version", 101); add("Name", "UVMap")
                    add("MappingInformationType", "ByPolygonVertex"); add("ReferenceInformationType", "IndexToDirect")
                    add("UV", uv); add("UVIndex", m.indices.copyOf())
                }
                add("LayerElementMaterial", 0) {
                    add("Version", 101); add("Name", "")
                    add("MappingInformationType", "AllSame"); add("ReferenceInformationType", "IndexToDirect")
                    add("Materials", intArrayOf(0))
                }
                add("Layer", 0) {
                    add("Version", 100)
                    add("LayerElement") { add("Type", "LayerElementNormal"); add("TypedIndex", 0) }
                    add("LayerElement") { add("Type", "LayerElementUV"); add("TypedIndex", 0) }
                    add("LayerElement") { add("Type", "LayerElementMaterial"); add("TypedIndex", 0) }
                }
            }
            objects.add("Model", mid, "$name${SEP}Model", "Mesh") {
                add("Version", 232)
                p70 {
                    p("Lcl Translation", "Lcl Translation", "", "A", 0.0, 0.0, 0.0)
                    p("Lcl Rotation", "Lcl Rotation", "", "A", 0.0, 0.0, 0.0)
                    p("Lcl Scaling", "Lcl Scaling", "", "A", 1.0, 1.0, 1.0)
                    p("DefaultAttributeIndex", "int", "Integer", "", 0)
                }
                add("MultiLayer", 0); add("MultiTake", 0)
                add("Shading", true)
                add("Culling", "CullingOff")
            }
            connections.add("C", "OO", mid, 0L)
            connections.add("C", "OO", gid, mid)
            connections.add("C", "OO", matIds.getValue(n.material), mid)
        }
        for (mt in mats.list) {
            val matName = mats.id(mt)
            val spec = (0.5 * (1 - mt.roughness))
            objects.add("Material", matIds.getValue(mt), "$matName${SEP}Material", "") {
                add("Version", 102); add("ShadingModel", "phong"); add("MultiLayer", 0)
                p70 {
                    p("DiffuseColor", "Color", "", "A", mt.r.toDouble(), mt.g.toDouble(), mt.b.toDouble())
                    p("DiffuseFactor", "Number", "", "A", 1.0)
                    p("SpecularColor", "Color", "", "A", spec, spec, spec)
                    p("Shininess", "Number", "", "A", ((1 - mt.roughness) * 100 + 2).toDouble())
                    p("ShininessExponent", "Number", "", "A", ((1 - mt.roughness) * 100 + 2).toDouble())
                    p("ReflectionFactor", "Number", "", "A", mt.metallic.toDouble())
                    p("Opacity", "Number", "", "A", 1.0)
                }
            }
            mt.texture?.let { t ->
                val (tid, _) = texIds.getValue(t)
                connections.add("C", "OP", tid, matIds.getValue(mt), "DiffuseColor")
            }
        }
        for ((t, ids) in texIds) {
            val (tid, vid) = ids
            val tn = texNames.getValue(t)
            val file = "$tn.png"
            objects.add("Video", vid, "$tn${SEP}Video", "Clip") {
                add("Type", "Clip")
                p70 { p("Path", "KString", "XRefUrl", "", file) }
                add("UseMipMap", 0)
                add("Filename", file); add("RelativeFilename", file)
                add("Content", PngEncoder.encode(t))
            }
            objects.add("Texture", tid, "$tn${SEP}Texture", "") {
                add("Type", "TextureVideoClip"); add("Version", 202)
                add("TextureName", "$tn${SEP}Texture")
                p70 { p("UseMaterial", "bool", "", "", 1) }
                add("Media", "$tn${SEP}Video")
                add("FileName", file); add("RelativeFilename", file)
                add("ModelUVTranslation", 0.0, 0.0); add("ModelUVScaling", 1.0, 1.0)
                add("Texture_Alpha_Source", "None")
                add("Cropping", 0, 0, 0, 0)
            }
            connections.add("C", "OO", vid, tid)
        }
        root += objects
        root += connections
        root += FNode("Takes").apply { add("Current", "") }

        // ---------- escrita binária ----------
        val b = LeBuffer(1 shl 20)
        b.bytes("Kaydara FBX Binary  ".toByteArray(Charsets.US_ASCII)).u8(0).u8(0x1A).u8(0).i32(7400)
        root.forEach { write(b, it) }
        b.bytes(ByteArray(13))
        // rodapé
        b.bytes(byteArrayOf(0xfa.toByte(), 0xbc.toByte(), 0xab.toByte(), 0x09, 0xd0.toByte(), 0xc8.toByte(), 0xd4.toByte(), 0x66, 0xb1.toByte(), 0x76, 0xfb.toByte(), 0x83.toByte(), 0x1c, 0xf7.toByte(), 0x26, 0x7e))
        b.bytes(ByteArray(4))
        var pad = ((b.size + 15) and 15.inv()) - b.size
        if (pad == 0) pad = 16
        b.bytes(ByteArray(pad))
        b.i32(7400)
        b.bytes(ByteArray(120))
        b.bytes(byteArrayOf(0xf8.toByte(), 0x5a, 0x8c.toByte(), 0x6a, 0xde.toByte(), 0xf5.toByte(), 0xd9.toByte(), 0x7e, 0xec.toByte(), 0xe9.toByte(), 0x0c, 0xe3.toByte(), 0x75, 0x8f.toByte(), 0x29, 0x0b))
        return b.toByteArray()
    }

    private fun write(b: LeBuffer, n: FNode) {
        val start = b.size
        val name = n.name.toByteArray(Charsets.UTF_8)
        b.i32(0).i32(n.props.size).i32(0).u8(name.size).bytes(name)
        val ps = b.size
        for (p in n.props) writeProp(b, p)
        b.setI32(start + 8, b.size - ps)
        if (n.children.isNotEmpty() || n.props.isEmpty()) {
            n.children.forEach { write(b, it) }
            b.bytes(ByteArray(13))
        }
        b.setI32(start, b.size)
    }

    private fun writeProp(b: LeBuffer, p: Any) {
        when (p) {
            is Boolean -> b.u8('C'.code).u8(if (p) 1 else 0)
            is Short -> b.u8('Y'.code).i16(p.toInt())
            is Int -> b.u8('I'.code).i32(p)
            is Long -> b.u8('L'.code).i64(p)
            is Float -> b.u8('F'.code).f32(p)
            is Double -> b.u8('D'.code).f64(p)
            is String -> { val s = p.toByteArray(Charsets.UTF_8); b.u8('S'.code).i32(s.size).bytes(s) }
            is ByteArray -> b.u8('R'.code).i32(p.size).bytes(p)
            is DoubleArray -> { b.u8('d'.code).i32(p.size).i32(0).i32(p.size * 8); p.forEach { b.f64(it) } }
            is IntArray -> { b.u8('i'.code).i32(p.size).i32(0).i32(p.size * 4); p.forEach { b.i32(it) } }
            is FloatArray -> { b.u8('f'.code).i32(p.size).i32(0).i32(p.size * 4); p.forEach { b.f32(it) } }
            else -> error("Tipo FBX não suportado: ${p::class}")
        }
    }
}

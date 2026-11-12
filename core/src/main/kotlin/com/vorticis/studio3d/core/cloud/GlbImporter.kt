package com.vorticis.studio3d.core.cloud

import com.vorticis.studio3d.core.geom.MeshOps
import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Mesh
import com.vorticis.studio3d.core.model.Node
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.model.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Decodifica PNG/JPEG em [Texture] (Android: BitmapFactory; JVM: ImageIO). */
fun interface ImageDecoder { fun decode(bytes: ByteArray): Texture? }

/**
 * Importa GLB (glTF 2.0 binário) para a cena do Vorticis: malhas, UVs, normais,
 * cor/textura base, metálico/rugosidade e transformações dos nós.
 * O resultado é normalizado (altura 1,2, apoiado no chão, centralizado).
 */
object GlbImporter {

    fun import(bytes: ByteArray, decoder: ImageDecoder, group: Int = 1, kind: String = "foto", targetHeight: Float = 1.2f): Scene {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "Não é um arquivo GLB" }
        var off = 12
        var json: Map<String, Any?> = emptyMap()
        var bin: ByteBuffer? = null
        while (off + 8 <= bytes.size) {
            val len = bb.getInt(off); val type = bb.getInt(off + 4)
            if (type == 0x4E4F534A) json = Json.parse(String(bytes, off + 8, len, Charsets.UTF_8)).obj()
            else if (type == 0x004E4942) bin = ByteBuffer.wrap(bytes, off + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN)
            off += 8 + len
        }
        val buf = bin ?: error("GLB sem dados binários")
        val views = json["bufferViews"].list(); val accessors = json["accessors"].list()

        fun viewBytes(vi: Int): ByteBuffer {
            val v = views[vi].obj()
            val o = (v["byteOffset"] as? Double ?: 0.0).toInt(); val l = (v["byteLength"] as Double).toInt()
            return (buf.duplicate().position(o).limit(o + l) as ByteBuffer).slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        fun floats(ai: Int, comps: Int): FloatArray {
            val a = accessors[ai].obj()
            val count = (a["count"] as Double).toInt()
            val ct = (a["componentType"] as Double).toInt()
            val normalized = a["normalized"] == true
            val v = views[(a["bufferView"] as Double).toInt()].obj()
            val base = viewBytes((a["bufferView"] as Double).toInt())
            val aoff = (a["byteOffset"] as? Double ?: 0.0).toInt()
            val csize = when (ct) { 5126 -> 4; 5123, 5122 -> 2; else -> 1 }
            val stride = (v["byteStride"] as? Double)?.toInt() ?: (csize * comps)
            val out = FloatArray(count * comps)
            for (i in 0 until count) for (c in 0 until comps) {
                val p = aoff + i * stride + c * csize
                out[i * comps + c] = when (ct) {
                    5126 -> base.getFloat(p)
                    5123 -> (base.getShort(p).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                    5122 -> base.getShort(p).toFloat().let { if (normalized) maxOf(it / 32767f, -1f) else it }
                    5121 -> (base.get(p).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                    else -> base.get(p).toFloat().let { if (normalized) maxOf(it / 127f, -1f) else it }
                }
            }
            return out
        }

        fun indices(ai: Int): IntArray {
            val a = accessors[ai].obj()
            val count = (a["count"] as Double).toInt()
            val ct = (a["componentType"] as Double).toInt()
            val base = viewBytes((a["bufferView"] as Double).toInt())
            val aoff = (a["byteOffset"] as? Double ?: 0.0).toInt()
            return IntArray(count) { i ->
                when (ct) {
                    5125 -> base.getInt(aoff + i * 4)
                    5123 -> base.getShort(aoff + i * 2).toInt() and 0xFFFF
                    else -> base.get(aoff + i).toInt() and 0xFF
                }
            }
        }

        // texturas (decodificadas uma vez)
        val texCache = HashMap<Int, Texture?>()
        fun texture(ti: Int): Texture? = texCache.getOrPut(ti) {
            val t = json["textures"].list().getOrNull(ti).obj()
            val src = (t["source"] as? Double)?.toInt() ?: return@getOrPut null
            val img = json["images"].list().getOrNull(src).obj()
            val vi = (img["bufferView"] as? Double)?.toInt() ?: return@getOrPut null
            val bv = viewBytes(vi); val arr = ByteArray(bv.remaining()); bv.get(arr)
            decoder.decode(arr)
        }

        fun material(mi: Int?): Material {
            if (mi == null) return Material("Nuvem", 0xFFCCCCCC.toInt(), 0f, 0.7f)
            val m = json["materials"].list().getOrNull(mi).obj()
            val pbr = m["pbrMetallicRoughness"].obj()
            val f = pbr["baseColorFactor"].list().map { (it as Double).toFloat() }.ifEmpty { listOf(1f, 1f, 1f, 1f) }
            val color = (0xFF shl 24) or ((f[0] * 255).toInt().coerceIn(0, 255) shl 16) or ((f[1] * 255).toInt().coerceIn(0, 255) shl 8) or (f[2] * 255).toInt().coerceIn(0, 255)
            val tex = (pbr["baseColorTexture"].obj()["index"] as? Double)?.toInt()?.let { texture(it) }
            // modelos vindos de foto: pouco metal, para não escurecer
            val metal = ((pbr["metallicFactor"] as? Double) ?: 1.0).toFloat().coerceIn(0f, 1f) * (if (tex != null) 0.2f else 1f)
            val rough = ((pbr["roughnessFactor"] as? Double) ?: 1.0).toFloat().coerceIn(0.05f, 1f)
            return Material(if (tex != null) "Foto" else "Nuvem", color, metal, rough, tex)
        }

        // percorre a hierarquia aplicando as transformações
        val nodes = json["nodes"].list()
        val result = ArrayList<Node>()
        fun visit(ni: Int, parent: FloatArray) {
            val n = nodes[ni].obj()
            val local = localMatrix(n)
            val world = mul(parent, local)
            (n["mesh"] as? Double)?.toInt()?.let { mi ->
                val mesh = json["meshes"].list()[mi].obj()
                for (prim in mesh["primitives"].list()) {
                    val p = prim.obj()
                    if ((p["mode"] as? Double ?: 4.0).toInt() != 4) continue
                    val at = p["attributes"].obj()
                    val pos = floats((at["POSITION"] as Double).toInt(), 3)
                    val vc = pos.size / 3
                    val nor = (at["NORMAL"] as? Double)?.let { floats(it.toInt(), 3) } ?: FloatArray(vc * 3)
                    val uv = (at["TEXCOORD_0"] as? Double)?.let { floats(it.toInt(), 2) } ?: FloatArray(vc * 2)
                    val idx = (p["indices"] as? Double)?.let { indices(it.toInt()) } ?: IntArray(vc) { it }
                    for (i in 0 until vc) {
                        val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
                        pos[i * 3] = world[0] * x + world[4] * y + world[8] * z + world[12]
                        pos[i * 3 + 1] = world[1] * x + world[5] * y + world[9] * z + world[13]
                        pos[i * 3 + 2] = world[2] * x + world[6] * y + world[10] * z + world[14]
                        val nx = nor[i * 3]; val ny = nor[i * 3 + 1]; val nz = nor[i * 3 + 2]
                        var tx = world[0] * nx + world[4] * ny + world[8] * nz
                        var ty = world[1] * nx + world[5] * ny + world[9] * nz
                        var tz = world[2] * nx + world[6] * ny + world[10] * nz
                        val l = sqrt(tx * tx + ty * ty + tz * tz); if (l > 0) { tx /= l; ty /= l; tz /= l }
                        nor[i * 3] = tx; nor[i * 3 + 1] = ty; nor[i * 3 + 2] = tz
                    }
                    var m = Mesh(pos, nor, uv, idx)
                    if (at["NORMAL"] == null) m = MeshOps.autoNormals(m, 60f)
                    result += Node("Nuvem_${result.size}", m, material((p["material"] as? Double)?.toInt()), group, kind, "corpo")
                }
            }
            n["children"].list().forEach { visit((it as Double).toInt(), world) }
        }
        val sceneIdx = (json["scene"] as? Double)?.toInt() ?: 0
        val roots = json["scenes"].list().getOrNull(sceneIdx).obj()["nodes"].list().map { (it as Double).toInt() }
            .ifEmpty { nodes.indices.toList() }
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        roots.forEach { visit(it, identity) }
        require(result.isNotEmpty()) { "O GLB não tem malhas" }

        // normaliza: altura alvo, apoiado no chão, centralizado
        var scene = Scene(result, group + 1)
        val b = scene.bounds()
        val s = targetHeight / max(b.sizeY, 1e-4f)
        scene = scene.copy(nodes = scene.nodes.map {
            it.copy(mesh = MeshOps.translate(MeshOps.scale(it.mesh, s, s, s, b.centerX, b.minY, b.centerZ), -b.centerX, -b.minY, -b.centerZ))
        })
        return scene
    }

    private fun localMatrix(n: Map<String, Any?>): FloatArray {
        n["matrix"].list().takeIf { it.size == 16 }?.let { l -> return FloatArray(16) { (l[it] as Double).toFloat() } }
        val t = n["translation"].list().map { (it as Double).toFloat() }.ifEmpty { listOf(0f, 0f, 0f) }
        val r = n["rotation"].list().map { (it as Double).toFloat() }.ifEmpty { listOf(0f, 0f, 0f, 1f) }
        val sc = n["scale"].list().map { (it as Double).toFloat() }.ifEmpty { listOf(1f, 1f, 1f) }
        val (x, y, z, w) = r
        // coluna-maior, como no glTF
        return floatArrayOf(
            (1 - 2 * (y * y + z * z)) * sc[0], (2 * (x * y + z * w)) * sc[0], (2 * (x * z - y * w)) * sc[0], 0f,
            (2 * (x * y - z * w)) * sc[1], (1 - 2 * (x * x + z * z)) * sc[1], (2 * (y * z + x * w)) * sc[1], 0f,
            (2 * (x * z + y * w)) * sc[2], (2 * (y * z - x * w)) * sc[2], (1 - 2 * (x * x + y * y)) * sc[2], 0f,
            t[0], t[1], t[2], 1f,
        )
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (rr in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + rr] * b[c * 4 + k]
            r[c * 4 + rr] = s
        }
        return r
    }
}

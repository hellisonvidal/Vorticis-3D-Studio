package com.vorticis.studio3d.core.export

import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.model.Texture
import java.util.Base64
import java.util.IdentityHashMap

/** glTF 2.0 — binário (.glb) ou texto com buffer embutido (.gltf). */
object GltfExporter {

    fun glb(scene: Scene): ByteArray {
        val (json, bin) = build(scene, embedUri = false)
        val jsonBytes = json.toByteArray(Charsets.UTF_8).let { b ->
            val pad = (4 - b.size % 4) % 4
            b + ByteArray(pad) { ' '.code.toByte() }
        }
        val binPadded = bin + ByteArray((4 - bin.size % 4) % 4)
        val total = 12 + 8 + jsonBytes.size + 8 + binPadded.size
        val out = LeBuffer(total)
        out.i32(0x46546C67).i32(2).i32(total)
        out.i32(jsonBytes.size).i32(0x4E4F534A).bytes(jsonBytes)
        out.i32(binPadded.size).i32(0x004E4942).bytes(binPadded)
        return out.toByteArray()
    }

    fun gltf(scene: Scene): ByteArray = build(scene, embedUri = true).first.toByteArray(Charsets.UTF_8)

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            else -> if (c.code < 0x20) append(String.format("\\u%04x", c.code)) else append(c)
        }
    }

    private fun build(scene: Scene, embedUri: Boolean): Pair<String, ByteArray> {
        val bin = LeBuffer(1 shl 16)
        val views = ArrayList<String>()
        val accessors = ArrayList<String>()
        val meshes = ArrayList<String>()
        val nodes = ArrayList<String>()
        val materials = ArrayList<Material>()
        val matIndex = HashMap<Material, Int>()
        val textures = IdentityHashMap<Texture, Int>()
        val texList = ArrayList<Texture>()

        fun view(bytes: ByteArray, target: Int?): Int {
            bin.pad(4)
            val off = bin.size
            bin.bytes(bytes)
            views += buildString {
                append("{\"buffer\":0,\"byteOffset\":$off,\"byteLength\":${bytes.size}")
                if (target != null) append(",\"target\":$target")
                append("}")
            }
            return views.size - 1
        }

        fun floats(a: FloatArray): ByteArray = LeBuffer(a.size * 4).apply { a.forEach { f32(it) } }.toByteArray()

        for (node in scene.nodes) {
            val m = node.mesh
            if (m.triangleCount == 0) continue
            val mi = matIndex.getOrPut(node.material) { materials += node.material; materials.size - 1 }
            node.material.texture?.let { t -> if (!textures.containsKey(t)) { textures[t] = texList.size; texList += t } }
            val b = m.bounds()
            val pv = view(floats(m.positions), 34962)
            accessors += "{\"bufferView\":$pv,\"componentType\":5126,\"count\":${m.vertexCount},\"type\":\"VEC3\"," +
                "\"min\":[${b.minX},${b.minY},${b.minZ}],\"max\":[${b.maxX},${b.maxY},${b.maxZ}]}"
            val pa = accessors.size - 1
            val nv = view(floats(m.normals), 34962)
            accessors += "{\"bufferView\":$nv,\"componentType\":5126,\"count\":${m.vertexCount},\"type\":\"VEC3\"}"
            val na = accessors.size - 1
            val tv = view(floats(m.uvs), 34962)
            accessors += "{\"bufferView\":$tv,\"componentType\":5126,\"count\":${m.vertexCount},\"type\":\"VEC2\"}"
            val ta = accessors.size - 1
            val iv = view(LeBuffer(m.indices.size * 4).apply { m.indices.forEach { i32(it) } }.toByteArray(), 34963)
            accessors += "{\"bufferView\":$iv,\"componentType\":5125,\"count\":${m.indices.size},\"type\":\"SCALAR\"}"
            val ia = accessors.size - 1
            meshes += "{\"name\":\"${esc(node.name)}\",\"primitives\":[{\"attributes\":{\"POSITION\":$pa,\"NORMAL\":$na,\"TEXCOORD_0\":$ta},\"indices\":$ia,\"material\":$mi,\"mode\":4}]}"
            nodes += "{\"name\":\"${esc(node.name)}\",\"mesh\":${meshes.size - 1}}"
        }

        val images = texList.map { t -> "{\"bufferView\":${view(PngEncoder.encode(t), null)},\"mimeType\":\"image/png\"}" }
        val matJson = materials.map { mt ->
            buildString {
                append("{\"name\":\"${esc(mt.name)}\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[${mt.r},${mt.g},${mt.b},1.0]")
                mt.texture?.let { append(",\"baseColorTexture\":{\"index\":${textures[it]}}") }
                append(",\"metallicFactor\":${mt.metallic},\"roughnessFactor\":${mt.roughness}}")
                append(",\"doubleSided\":false}")
            }
        }
        bin.pad(4)
        val binBytes = bin.toByteArray()
        val bufferJson = if (embedUri)
            "{\"byteLength\":${binBytes.size},\"uri\":\"data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(binBytes)}\"}"
        else "{\"byteLength\":${binBytes.size}}"

        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Vorticis 3D Studio\"},")
            append("\"scene\":0,\"scenes\":[{\"name\":\"Cena\",\"nodes\":[${nodes.indices.joinToString(",")}]}],")
            append("\"nodes\":[${nodes.joinToString(",")}],")
            append("\"meshes\":[${meshes.joinToString(",")}],")
            append("\"materials\":[${matJson.joinToString(",")}],")
            if (texList.isNotEmpty()) {
                append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":10497,\"wrapT\":10497}],")
                append("\"images\":[${images.joinToString(",")}],")
                append("\"textures\":[${texList.indices.joinToString(",") { "{\"sampler\":0,\"source\":$it}" }}],")
            }
            append("\"accessors\":[${accessors.joinToString(",")}],")
            append("\"bufferViews\":[${views.joinToString(",")}],")
            append("\"buffers\":[$bufferJson]}")
        }
        return Pair(json, binBytes)
    }
}

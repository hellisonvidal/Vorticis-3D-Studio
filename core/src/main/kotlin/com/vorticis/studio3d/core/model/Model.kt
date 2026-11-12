package com.vorticis.studio3d.core.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Imagem RGBA simples (pixels em ARGB), independente do Android. */
class Texture(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "Tamanho de textura inválido" }
    }

    /** Amostra com UV no padrão glTF (origem no canto superior esquerdo), com repetição. */
    fun sample(u: Float, v: Float): Int {
        val uu = u - floor(u)
        val vv = v - floor(v)
        val x = (uu * width).toInt().coerceIn(0, width - 1)
        val y = (vv * height).toInt().coerceIn(0, height - 1)
        return pixels[y * width + x]
    }
}

/** Material PBR simplificado (compatível com glTF metallic/roughness). */
data class Material(
    val name: String = "Material",
    /** Cor base em ARGB. */
    val color: Int = 0xFFCCCCCC.toInt(),
    val metallic: Float = 0f,
    val roughness: Float = 0.6f,
    val texture: Texture? = null,
) {
    val r: Float get() = ((color shr 16) and 0xFF) / 255f
    val g: Float get() = ((color shr 8) and 0xFF) / 255f
    val b: Float get() = (color and 0xFF) / 255f
    val a: Float get() = ((color ushr 24) and 0xFF) / 255f

    fun hex(): String = String.format("#%06X", color and 0xFFFFFF)
}

/**
 * Malha triangular indexada. Convenções:
 *  - Y para cima, unidades em metros;
 *  - triângulos no sentido anti-horário vistos de fora;
 *  - UV com origem no topo-esquerdo da imagem (como glTF).
 * Instâncias são tratadas como imutáveis: operações geram novas malhas.
 */
class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    init {
        require(normals.size == positions.size) { "normals != positions" }
        require(uvs.size / 2 == positions.size / 3) { "uvs != vertices" }
        require(indices.size % 3 == 0) { "índices não múltiplos de 3" }
    }

    fun bounds(): Bounds {
        val b = Bounds.empty()
        for (i in 0 until vertexCount) b.add(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
        return b
    }

    companion object {
        val EMPTY = Mesh(FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0))
    }
}

class Bounds(
    var minX: Float, var minY: Float, var minZ: Float,
    var maxX: Float, var maxY: Float, var maxZ: Float,
) {
    val isEmpty: Boolean get() = minX > maxX
    val sizeX: Float get() = if (isEmpty) 0f else maxX - minX
    val sizeY: Float get() = if (isEmpty) 0f else maxY - minY
    val sizeZ: Float get() = if (isEmpty) 0f else maxZ - minZ
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
    val centerZ: Float get() = (minZ + maxZ) / 2f
    val maxSize: Float get() = max(sizeX, max(sizeY, sizeZ))

    fun add(x: Float, y: Float, z: Float) {
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
    }

    fun add(o: Bounds) {
        if (o.isEmpty) return
        add(o.minX, o.minY, o.minZ); add(o.maxX, o.maxY, o.maxZ)
    }

    companion object {
        fun empty() = Bounds(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
        )
    }
}

/**
 * Uma peça da cena. A geometria já está em coordenadas do mundo.
 * @param group identifica o "objeto" ao qual a peça pertence (ex.: todas as peças de uma mesa).
 * @param kind tipo do objeto (ex.: "mesa"), usado pela IA para encontrar alvos de edição.
 * @param part nome da parte (ex.: "pernas"), usado para pintar partes específicas.
 */
data class Node(
    val name: String,
    val mesh: Mesh,
    val material: Material,
    val group: Int,
    val kind: String,
    val part: String = "",
)

/** Cena imutável: lista de peças. */
data class Scene(val nodes: List<Node> = emptyList(), val nextGroup: Int = 1) {
    val isEmpty: Boolean get() = nodes.isEmpty()
    val triangleCount: Int get() = nodes.sumOf { it.mesh.triangleCount }
    val vertexCount: Int get() = nodes.sumOf { it.mesh.vertexCount }
    val lastGroup: Int? get() = nodes.maxOfOrNull { it.group }

    fun bounds(): Bounds {
        val b = Bounds.empty()
        nodes.forEach { b.add(it.mesh.bounds()) }
        return b
    }

    fun groupBounds(group: Int): Bounds {
        val b = Bounds.empty()
        nodes.filter { it.group == group }.forEach { b.add(it.mesh.bounds()) }
        return b
    }

    fun groups(): List<Int> = nodes.map { it.group }.distinct()

    fun materials(): List<Material> = nodes.map { it.material }.distinct()
}

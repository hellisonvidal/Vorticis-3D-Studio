package com.vorticis.studio3d.core.gen

import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Texture
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Texturas procedurais geradas no próprio aparelho (sem baixar nada). */
object ProceduralTextures {
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val xf = x - xi; val yf = y - yi
        val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf)
        val a = hash(xi, yi, seed); val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed); val d = hash(xi + 1, yi + 1, seed)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    /** Ruído fractal periódico-ish em [0,1]. */
    fun fbm(x: Float, y: Float, octaves: Int = 5, seed: Int = 1): Float {
        var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (o in 0 until octaves) {
            sum += amp * valueNoise(x * f, y * f, seed + o * 17)
            norm += amp; amp *= 0.5f; f *= 2f
        }
        return sum / norm
    }

    private fun mix(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = (((c1 shr 16) and 255) * (1 - tt) + ((c2 shr 16) and 255) * tt).toInt()
        val g = (((c1 shr 8) and 255) * (1 - tt) + ((c2 shr 8) and 255) * tt).toInt()
        val b = ((c1 and 255) * (1 - tt) + (c2 and 255) * tt).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun shade(c: Int, f: Float): Int {
        val r = (((c shr 16) and 255) * f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 255) * f).toInt().coerceIn(0, 255)
        val b = ((c and 255) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun make(size: Int, f: (Float, Float) -> Int): Texture {
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = f(x.toFloat() / size, y.toFloat() / size)
        return Texture(size, size, px)
    }

    fun wood(base: Int, size: Int = 256): Texture {
        val dark = shade(base, 0.62f)
        return make(size) { u, v ->
            val n = fbm(u * 3f, v * 12f, 4, 7)
            val ring = sin((v * 18f + n * 6f) * 3.14159f) * 0.5f + 0.5f
            val grain = fbm(u * 40f, v * 3f, 3, 3)
            mix(base, dark, ring * 0.55f + grain * 0.25f)
        }
    }

    fun marble(base: Int, size: Int = 256): Texture {
        val vein = shade(base, 0.55f)
        return make(size) { u, v ->
            val n = fbm(u * 4f, v * 4f, 6, 11)
            val s = abs(sin((u * 6f + n * 8f) * 3.14159f))
            mix(vein, base, s.let { sqrt(it) })
        }
    }

    fun stone(base: Int, size: Int = 256): Texture = make(size) { u, v ->
        val n = fbm(u * 8f, v * 8f, 6, 5)
        val spots = if (fbm(u * 30f, v * 30f, 2, 9) > 0.72f) 0.8f else 1f
        shade(base, (0.7f + n * 0.5f) * spots)
    }

    fun brick(base: Int, size: Int = 256): Texture {
        val mortar = 0xFFD8D2C4.toInt()
        return make(size) { u, v ->
            val rows = 8f
            val row = floor(v * rows)
            val off = if (row.toInt() % 2 == 0) 0f else 0.5f / 4f
            val cu = (u + off) * 4f
            val fu = cu - floor(cu); val fv = v * rows - row
            if (fu < 0.05f || fv < 0.1f) mortar
            else shade(base, 0.8f + 0.35f * hash(floor(cu).toInt(), row.toInt(), 3) + 0.15f * fbm(u * 30, v * 30, 2, 4))
        }
    }

    fun fabric(base: Int, size: Int = 256): Texture = make(size) { u, v ->
        val w = (sin(u * 200f) * sin(v * 200f)) * 0.5f + 0.5f
        shade(base, 0.85f + 0.15f * w + 0.08f * fbm(u * 10, v * 10, 3, 2))
    }

    fun brushedMetal(base: Int, size: Int = 256): Texture = make(size) { u, v ->
        shade(base, 0.85f + 0.2f * fbm(u * 2f, v * 120f, 3, 13))
    }

    fun grass(base: Int, size: Int = 256): Texture = make(size) { u, v ->
        shade(base, 0.7f + 0.5f * fbm(u * 25f, v * 25f, 4, 21))
    }

    fun checker(c1: Int, c2: Int, n: Int = 8, size: Int = 256): Texture = make(size) { u, v ->
        if ((floor(u * n).toInt() + floor(v * n).toInt()) % 2 == 0) c1 else c2
    }
}

/** Biblioteca de materiais reconhecidos pela IA. */
object MaterialLibrary {
    class Preset(val key: String, val words: List<String>, private val factory: (Int?) -> Material) {
        private val cache = HashMap<Int?, Material>()
        /** Materiais e texturas são reaproveitados para a mesma cor (economiza memória e arquivo). */
        @Synchronized
        fun build(color: Int?): Material = cache.getOrPut(color) { factory(color) }
    }

    private fun c(hex: Long) = hex.toInt()

    val presets: List<Preset> = listOf(
        Preset("madeira", listOf("madeira", "madeiras", "wood", "wooden", "mogno", "carvalho", "pinho")) { col ->
            val base = col ?: c(0xFF9C6B3E); Material("Madeira", base, 0f, 0.75f, ProceduralTextures.wood(base))
        },
        Preset("metal", listOf("metal", "metalico", "metalica", "aco", "inox", "ferro", "steel", "metallic", "aluminio")) { col ->
            val base = col ?: c(0xFFB8BEC6); Material("Metal", base, 1f, 0.32f, ProceduralTextures.brushedMetal(base))
        },
        Preset("ouro", listOf("ouro", "dourado", "dourada", "gold", "golden")) { col -> Material("Ouro", col ?: c(0xFFE8B731), 1f, 0.25f) },
        Preset("prata", listOf("prata", "prateado", "prateada", "silver")) { col -> Material("Prata", col ?: c(0xFFD8D8DE), 1f, 0.2f) },
        Preset("cobre", listOf("cobre", "bronze", "copper")) { col -> Material("Cobre", col ?: c(0xFFC07444), 1f, 0.3f) },
        Preset("vidro", listOf("vidro", "cristal", "glass", "transparente")) { col -> Material("Vidro", col ?: c(0xFFBFE6F2), 0f, 0.05f) },
        Preset("pedra", listOf("pedra", "rocha", "stone", "rock", "granito")) { col ->
            val base = col ?: c(0xFF8D8A85); Material("Pedra", base, 0f, 0.9f, ProceduralTextures.stone(base))
        },
        Preset("marmore", listOf("marmore", "marble")) { col ->
            val base = col ?: c(0xFFF0EEEA); Material("Marmore", base, 0f, 0.3f, ProceduralTextures.marble(base))
        },
        Preset("tijolo", listOf("tijolo", "tijolos", "brick", "bricks")) { col ->
            val base = col ?: c(0xFFA5492F); Material("Tijolo", base, 0f, 0.85f, ProceduralTextures.brick(base))
        },
        Preset("concreto", listOf("concreto", "cimento", "concrete")) { col ->
            val base = col ?: c(0xFFA7A7A2); Material("Concreto", base, 0f, 0.95f, ProceduralTextures.stone(base))
        },
        Preset("tecido", listOf("tecido", "pano", "fabric", "veludo", "couro")) { col ->
            val base = col ?: c(0xFF5D6E8C); Material("Tecido", base, 0f, 0.95f, ProceduralTextures.fabric(base))
        },
        Preset("plastico", listOf("plastico", "plastic", "lego")) { col -> Material("Plastico", col ?: c(0xFFE04A3A), 0f, 0.35f) },
        Preset("borracha", listOf("borracha", "rubber")) { col -> Material("Borracha", col ?: c(0xFF2B2B2B), 0f, 0.95f) },
        Preset("ceramica", listOf("ceramica", "porcelana", "ceramic", "barro", "argila")) { col -> Material("Ceramica", col ?: c(0xFFF3EFE6), 0f, 0.2f) },
        Preset("grama", listOf("grama", "gramado", "grass")) { col ->
            val base = col ?: c(0xFF4E9A3A); Material("Grama", base, 0f, 0.95f, ProceduralTextures.grass(base))
        },
        Preset("xadrez", listOf("xadrez", "quadriculado", "checker")) { col ->
            Material("Xadrez", 0xFFFFFFFF.toInt(), 0f, 0.5f, ProceduralTextures.checker(col ?: c(0xFF202020), c(0xFFF0F0F0)))
        },
    )

    fun find(word: String): Preset? = presets.firstOrNull { word in it.words }
        ?: if (word.endsWith("s")) presets.firstOrNull { word.dropLast(1) in it.words } else null

    fun solid(color: Int, name: String = "Cor"): Material = Material(name, color, 0f, 0.55f)
}

/** Cores reconhecidas (português e inglês, sem acentos). */
object ColorLibrary {
    val colors: Map<String, Int> = mapOf(
        "vermelho" to 0xFFD8322F, "vermelha" to 0xFFD8322F, "red" to 0xFFD8322F,
        "azul" to 0xFF2F6FD8, "blue" to 0xFF2F6FD8,
        "verde" to 0xFF3BA55C, "green" to 0xFF3BA55C,
        "amarelo" to 0xFFF2C230, "amarela" to 0xFFF2C230, "yellow" to 0xFFF2C230,
        "laranja" to 0xFFF08A24, "orange" to 0xFFF08A24,
        "roxo" to 0xFF7E45C9, "roxa" to 0xFF7E45C9, "violeta" to 0xFF8A4FD6, "purple" to 0xFF7E45C9,
        "lilas" to 0xFFB99BE0, "lavanda" to 0xFFB9A7E6,
        "rosa" to 0xFFEC6FA6, "pink" to 0xFFEC6FA6, "magenta" to 0xFFD62E8A,
        "preto" to 0xFF1E1E1E, "preta" to 0xFF1E1E1E, "black" to 0xFF1E1E1E,
        "branco" to 0xFFF2F2F2, "branca" to 0xFFF2F2F2, "white" to 0xFFF2F2F2,
        "cinza" to 0xFF8A8D91, "gray" to 0xFF8A8D91, "grey" to 0xFF8A8D91,
        "marrom" to 0xFF7A4B2A, "brown" to 0xFF7A4B2A, "castanho" to 0xFF7A4B2A,
        "bege" to 0xFFD9C3A0, "beige" to 0xFFD9C3A0, "creme" to 0xFFF1E6C8,
        "ciano" to 0xFF2EC4D6, "cyan" to 0xFF2EC4D6, "turquesa" to 0xFF2EC4B6,
        "vinho" to 0xFF7A1F35, "bordo" to 0xFF7A1F35,
        "dourado" to 0xFFE8B731, "dourada" to 0xFFE8B731,
        "prateado" to 0xFFD8D8DE, "prateada" to 0xFFD8D8DE,
        "azul-marinho" to 0xFF1E2E5A, "marinho" to 0xFF1E2E5A,
        "salmao" to 0xFFF08C7A, "coral" to 0xFFFF7F6B, "oliva" to 0xFF7D8A3A,
        "neon" to 0xFF39FF14,
    ).mapValues { it.value.toInt() }

    /** Procura a cor aceitando plural ("pretas", "azuis", "marrons"). */
    fun find(word: String): Int? = colors[word]
        ?: (if (word.endsWith("s")) colors[word.dropLast(1)] else null)
        ?: when (word) { "azuis" -> colors["azul"]; "marrons" -> colors["marrom"]; else -> null }

    fun parseHex(word: String): Int? {
        val w = word.removePrefix("#")
        if (!word.startsWith("#") || (w.length != 6 && w.length != 3)) return null
        val full = if (w.length == 3) w.map { "$it$it" }.joinToString("") else w
        return full.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }

    fun adjust(color: Int, factor: Float): Int {
        val r = (((color shr 16) and 255) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 255) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 255) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun lighten(color: Int): Int {
        val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
        return (0xFF shl 24) or ((r + 255) / 2 shl 16) or ((g + 255) / 2 shl 8) or ((b + 255) / 2)
    }
}

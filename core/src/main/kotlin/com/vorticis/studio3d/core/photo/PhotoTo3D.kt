package com.vorticis.studio3d.core.photo

import com.vorticis.studio3d.core.geom.MeshBuilder
import com.vorticis.studio3d.core.geom.MeshOps
import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Mesh
import com.vorticis.studio3d.core.model.Texture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class PhotoMode(val label: String, val description: String) {
    AUTO("Automático", "A IA analisa a silhueta e escolhe a melhor técnica"),
    VOLUME("Volume", "Infla a silhueta como um objeto arredondado (bonecos, animais, frutas)"),
    LATHE("Revolução", "Gira o contorno em torno do eixo (vasos, garrafas, copos, luminárias)"),
    EXTRUDE("Extrusão", "Recorte com espessura e chanfro (logos, placas, chaveiros)"),
    RELIEF("Relevo", "Placa em alto-relevo pela luminosidade da foto (litofania/quadro)"),
    MULTIVIEW("Várias fotos", "Cruza as silhuetas de fotos tiradas ao redor do objeto (forma real, inclusive o verso)"),
}

data class PhotoOptions(
    val mode: PhotoMode = PhotoMode.AUTO,
    /** multiplicador de espessura (1 = natural) */
    val thickness: Float = 1f,
    /** detalhe de superfície vindo da luminosidade (0..1) */
    val detail: Float = 0.12f,
    /** resolução da grade no maior lado */
    val resolution: Int = 190,
    /** altura final do objeto em unidades (metros) */
    val targetSize: Float = 1.2f,
    /** profundidade estimada por IA (Depth Anything); quando presente, molda a frente do objeto */
    val depth: DepthMap? = null,
)

data class PhotoResult(val mesh: Mesh, val material: Material, val mode: PhotoMode, val explanation: String)

/**
 * Converte uma foto + máscara do objeto em um modelo 3D, 100% no aparelho.
 * Técnicas: inflagem por equação de Poisson (estilo Teddy/Monster Mash),
 * revolução do contorno, extrusão chanfrada e relevo por luminância.
 */
object PhotoTo3D {

    class Analysis(
        val fill: Float, val aspect: Float, val symmetry: Float,
        val singleRun: Float, val colorSimplicity: Float, val coverage: Float,
    )

    fun analyze(pixels: IntArray, w: Int, h: Int, mask: Mask): Analysis {
        val bb = mask.bbox() ?: return Analysis(0f, 1f, 0f, 0f, 0f, 0f)
        val bw = bb[2] - bb[0] + 1; val bh = bb[3] - bb[1] + 1
        val area = mask.area
        val axis = axisX(mask, bb)
        var symErr = 0.0; var symTot = 0.0; var single = 0; var rows = 0
        for (y in bb[1]..bb[3]) {
            var l = -1; var r = -1; var runs = 0; var prev = false
            for (x in bb[0]..bb[2]) {
                val v = mask[x, y]
                if (v && !prev) runs++
                if (v) { if (l < 0) l = x; r = x }
                prev = v
            }
            if (l < 0) continue
            rows++
            if (runs == 1) single++
            val dl = axis - l; val dr = r - axis
            symErr += abs(dl - dr); symTot += abs(dl) + abs(dr)
        }
        // simplicidade de cor: quantas cores (4 bits/canal) cobrem 90% do objeto
        val hist = HashMap<Int, Int>()
        for (y in bb[1]..bb[3] step 2) for (x in bb[0]..bb[2] step 2) if (mask[x, y]) {
            val c = pixels[y * w + x]
            val q = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
            hist[q] = (hist[q] ?: 0) + 1
        }
        val total = hist.values.sum().coerceAtLeast(1)
        var acc = 0; var n = 0
        for (v in hist.values.sortedDescending()) { acc += v; n++; if (acc >= total * 0.9) break }
        return Analysis(
            fill = area.toFloat() / (bw * bh),
            aspect = bh.toFloat() / bw,
            symmetry = if (symTot > 0) (1 - symErr / symTot).toFloat() else 0f,
            singleRun = if (rows > 0) single.toFloat() / rows else 0f,
            colorSimplicity = (1f - (n - 1) / 40f).coerceIn(0f, 1f),
            coverage = area.toFloat() / (w * h),
        )
    }

    fun chooseMode(a: Analysis): Pair<PhotoMode, String> {
        val pct = { v: Float -> "${(v * 100).toInt()}%" }
        return when {
            a.coverage > 0.93f -> PhotoMode.RELIEF to
                "Não encontrei um objeto separado do fundo (ocupa ${pct(a.coverage)} da foto), então criei um relevo da imagem inteira."
            a.symmetry > 0.965f && a.singleRun > 0.97f && a.aspect > 0.9f -> PhotoMode.LATHE to
                "A silhueta é simétrica (${pct(a.symmetry)}) e contínua: modelei por revolução, como um objeto torneado."
            a.colorSimplicity > 0.8f && a.fill < 0.75f -> PhotoMode.EXTRUDE to
                "Poucas cores e contorno recortado (parece logo/símbolo): fiz uma extrusão chanfrada."
            else -> PhotoMode.VOLUME to
                "Formato orgânico (simetria ${pct(a.symmetry)}, preenchimento ${pct(a.fill)}): inflei a silhueta em volume."
        }
    }

    fun build(pixels: IntArray, w: Int, h: Int, maskIn: Mask, opt: PhotoOptions = PhotoOptions()): PhotoResult {
        val mask = smoothOutline(if (maskIn.w != w || maskIn.h != h) maskIn.resize(w, h) else maskIn)
        var mode = opt.mode
        var why = ""
        if (mode == PhotoMode.AUTO) {
            val (m, e) = chooseMode(analyze(pixels, w, h, mask)); mode = m; why = e
        } else why = "Modo ${mode.label}: ${mode.description.lowercase()}."
        val bb = if (mode == PhotoMode.RELIEF) intArrayOf(0, 0, w - 1, h - 1) else (mask.bbox() ?: intArrayOf(0, 0, w - 1, h - 1))
        val solid = mode != PhotoMode.LATHE && mode != PhotoMode.RELIEF
        // frente/verso usam um atlas: metade esquerda = foto, metade direita = versão suavizada para o verso
        // (evita repetir rosto/estampa nas costas); as bordas recebem a cor de dentro (sem "franja" do fundo)
        val tex = if (solid) frontBackAtlas(bleed(crop(pixels, w, bb), mask, bb)) else crop(pixels, w, bb)
        val material = Material("Foto", 0xFFFFFFFF.toInt(), 0f, 0.7f, tex)
        val mesh = when (mode) {
            PhotoMode.LATHE -> lathe(mask, bb, opt)
            PhotoMode.EXTRUDE -> gridSolid(pixels, w, h, mask, bb, opt, extrude = true)
            PhotoMode.RELIEF -> relief(pixels, w, bb, opt)
            else -> gridSolid(pixels, w, h, mask, bb, opt, extrude = false)
        }
        if (opt.depth != null && mode == PhotoMode.VOLUME) why += " A IA de profundidade moldou o relevo da frente."
        return PhotoResult(mesh, material, mode, why)
    }

    /** Suaviza o contorno da máscara (remove serrilhado e pontas finas do recorte). */
    fun smoothOutline(m: Mask): Mask {
        val r = max(2, min(m.w, m.h) / 90)
        val f = FloatArray(m.w * m.h) { if (m.data[it]) 1f else 0f }
        val b = boxBlur(boxBlur(f, m.w, m.h, r), m.w, m.h, r)
        val out = Mask(m.w, m.h)
        for (i in b.indices) out.data[i] = b[i] > 0.5f
        return Segmentation.largestComponent(Segmentation.fillHoles(out)).takeIf { it.area > m.area / 3 } ?: m
    }

    /** Pixels na borda do recorte (e fora dele) recebem a cor do pixel interno mais próximo. */
    private fun bleed(t: Texture, mask: Mask, bb: IntArray): Texture {
        val w = t.width; val h = t.height
        val r = max(2, max(w, h) / 160)
        val known = BooleanArray(w * h) { i -> mask[bb[0] + i % w, bb[1] + i / w] }
        val core = Segmentation.erode(Mask(w, h, known), r)
        if (core.area < 16) return t
        val px = t.pixels.copyOf()
        val done = core.data.copyOf()
        val queue = IntArray(w * h); var head = 0; var tail = 0
        for (i in done.indices) if (done[i]) queue[tail++] = i
        while (head < tail) {
            val i = queue[head++]; val x = i % w; val y = i / w
            val nb = intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)
            for (j in nb) if (j >= 0 && !done[j]) { done[j] = true; px[j] = px[i]; queue[tail++] = j }
        }
        return Texture(w, h, px)
    }

    private fun frontBackAtlas(t: Texture): Texture {
        val w = t.width; val h = t.height
        // verso: média local grande (mantém as cores, some com rosto/letras) e um pouco mais escuro
        val r = max(3, max(w, h) / 25)
        val ch = Array(3) { c -> FloatArray(w * h) { i -> ((t.pixels[i] shr (16 - 8 * c)) and 255).toFloat() } }
        val blurred = ch.map { boxBlur(boxBlur(boxBlur(it, w, h, r), w, h, r), w, h, r) }
        val out = IntArray(2 * w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            out[y * 2 * w + x] = t.pixels[i] or (0xFF shl 24)
            val rr = (blurred[0][i] * 0.88f).toInt().coerceIn(0, 255)
            val gg = (blurred[1][i] * 0.88f).toInt().coerceIn(0, 255)
            val bb = (blurred[2][i] * 0.88f).toInt().coerceIn(0, 255)
            out[y * 2 * w + w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Texture(2 * w, h, out)
    }

    private fun crop(pixels: IntArray, w: Int, bb: IntArray): Texture {
        val cw = bb[2] - bb[0] + 1; val ch = bb[3] - bb[1] + 1
        val out = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) out[y * cw + x] = pixels[(bb[1] + y) * w + bb[0] + x] or (0xFF shl 24)
        return Texture(cw, ch, out)
    }

    private fun axisX(mask: Mask, bb: IntArray): Float {
        var s = 0.0; var n = 0.0
        for (y in bb[1]..bb[3]) {
            var l = -1; var r = -1
            for (x in bb[0]..bb[2]) if (mask[x, y]) { if (l < 0) l = x; r = x }
            if (l >= 0) { val wgt = (r - l + 1).toDouble(); s += (l + r) / 2.0 * wgt; n += wgt }
        }
        return if (n > 0) (s / n).toFloat() else (bb[0] + bb[2]) / 2f
    }

    private fun luminance(c: Int): Float =
        (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f

    /** Distância chanfrada 3-4 até o fundo, em unidades de célula. */
    private fun chamfer(inside: BooleanArray, gw: Int, gh: Int): FloatArray {
        val inf = 1e9f
        val d = FloatArray(gw * gh) { if (inside[it]) inf else 0f }
        for (j in 0 until gh) for (i in 0 until gw) {
            val k = j * gw + i; if (d[k] == 0f) continue
            var v = d[k]
            if (i == 0 || j == 0 || i == gw - 1 || j == gh - 1) v = 1f
            if (i > 0) v = min(v, d[k - 1] + 1f)
            if (j > 0) v = min(v, d[k - gw] + 1f)
            if (i > 0 && j > 0) v = min(v, d[k - gw - 1] + 1.4142f)
            if (i < gw - 1 && j > 0) v = min(v, d[k - gw + 1] + 1.4142f)
            d[k] = v
        }
        for (j in gh - 1 downTo 0) for (i in gw - 1 downTo 0) {
            val k = j * gw + i; if (d[k] == 0f) continue
            var v = d[k]
            if (i < gw - 1) v = min(v, d[k + 1] + 1f)
            if (j < gh - 1) v = min(v, d[k + gw] + 1f)
            if (i < gw - 1 && j < gh - 1) v = min(v, d[k + gw + 1] + 1.4142f)
            if (i > 0 && j < gh - 1) v = min(v, d[k + gw - 1] + 1.4142f)
            d[k] = v
        }
        return d
    }

    /**
     * Sólido fechado frente/verso sobre uma grade. No modo volume a altura vem de
     * h = sqrt(u), com Δu = -2 e u = 0 na borda (seção transversal circular).
     * No modo extrusão a altura é constante com chanfro.
     */
    private fun gridSolid(pixels: IntArray, w: Int, imgH: Int, mask: Mask, bb: IntArray, opt: PhotoOptions, extrude: Boolean): Mesh {
        val bw = bb[2] - bb[0] + 1; val bh = bb[3] - bb[1] + 1
        val cell = max(bw, bh).toFloat() / opt.resolution
        val gw = ceil(bw / cell).toInt() + 1; val gh = ceil(bh / cell).toInt() + 1
        val inside = BooleanArray(gw * gh)
        val lum = FloatArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val px = (bb[0] + i * cell).toInt().coerceAtMost(bb[2]); val py = (bb[1] + j * cell).toInt().coerceAtMost(bb[3])
            inside[j * gw + i] = mask[px, py]
            lum[j * gw + i] = luminance(pixels[py * w + px])
        }
        // remove pontos isolados (sem vizinhos) que gerariam espinhos
        for (j in 0 until gh) for (i in 0 until gw) {
            val k = j * gw + i
            if (!inside[k]) continue
            var nb = 0
            if (i > 0 && inside[k - 1]) nb++; if (i < gw - 1 && inside[k + 1]) nb++
            if (j > 0 && inside[k - gw]) nb++; if (j < gh - 1 && inside[k + gw]) nb++
            if (nb <= 1) inside[k] = false
        }
        val boundary = BooleanArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val k = j * gw + i
            if (!inside[k]) continue
            var b = i == 0 || j == 0 || i == gw - 1 || j == gh - 1
            if (!b) loop@ for (dj in -1..1) for (di in -1..1) if (!inside[(j + dj) * gw + i + di]) { b = true; break@loop }
            boundary[k] = b
        }
        val dist = chamfer(inside, gw, gh)
        val height = FloatArray(gw * gh)      // frente
        val heightBack = FloatArray(gw * gh)  // verso
        if (extrude) {
            val t = 0.06f * max(gw, gh) * opt.thickness
            val bevel = max(2f, t * 0.35f)
            for (k in height.indices) if (inside[k] && !boundary[k]) height[k] = t * min(1f, (dist[k] - 1f) / bevel).coerceAtLeast(0.2f)
            height.copyInto(heightBack)
        } else {
            // Poisson por SOR, inicializado com d²
            val u = FloatArray(gw * gh)
            for (k in u.indices) if (inside[k] && !boundary[k]) u[k] = dist[k] * dist[k] * 0.8f
            val omega = 1.85f
            val iters = (max(gw, gh) * 2.5f).toInt().coerceIn(150, 600)
            repeat(iters) {
                for (j in 1 until gh - 1) for (i in 1 until gw - 1) {
                    val k = j * gw + i
                    if (!inside[k] || boundary[k]) continue
                    val nv = (u[k - 1] + u[k + 1] + u[k - gw] + u[k + gw] + 2f) / 4f
                    u[k] += omega * (nv - u[k])
                }
            }
            // passa-alta da luminância para micro-relevo
            val blur = boxBlur(lum, gw, gh, 4)
            val depth = opt.depth
            // profundidade da IA normalizada dentro do objeto (percentis 5..95, robusto a ruído)
            var dn: FloatArray? = null
            if (depth != null) {
                val d = FloatArray(gw * gh)
                val vals = ArrayList<Float>()
                for (j in 0 until gh) for (i in 0 until gw) {
                    val q = j * gw + i
                    if (!inside[q]) continue
                    val px = bb[0] + i * cell; val py = bb[1] + j * cell
                    d[q] = depth.sample(px / w, py / imgH); vals += d[q]
                }
                vals.sort()
                if (vals.size > 20) {
                    val lo = vals[vals.size * 5 / 100]; val hi = vals[vals.size * 95 / 100]
                    if (hi - lo > 1e-6f) dn = FloatArray(gw * gh) { q -> ((d[q] - lo) / (hi - lo)).coerceIn(0f, 1f) }
                }
            }
            val relief = 0.22f * max(gw, gh)
            for (k in u.indices) if (inside[k] && !boundary[k]) {
                val base = sqrt(max(0f, u[k]))
                val hp = (lum[k] - blur[k])
                val micro = opt.detail * hp * min(1f, base / 4f) * max(gw, gh) * 0.05f
                if (dn != null) {
                    // frente: forma da IA (o que está mais perto "sai" mais) sobre uma base inflada;
                    // perto da borda a influência cai para fechar a malha sem degraus
                    val ramp = min(1f, (dist[k] - 1f) / 5f).coerceAtLeast(0f)
                    height[k] = (base * 0.5f + dn[k] * relief * ramp + micro * 0.5f) * opt.thickness
                    heightBack[k] = base * 0.75f * opt.thickness
                } else {
                    height[k] = (base * 0.85f + micro) * opt.thickness
                    heightBack[k] = height[k]
                }
            }
        }

        // escala para o mundo: maior lado = targetSize; base no chão (y=0)
        val k = opt.targetSize / (max(gw, gh) - 1)
        val zMax = (height.maxOrNull() ?: 0f)
        val b = MeshBuilder(gw * gh * 2)
        val front = IntArray(gw * gh) { -1 }
        val back = IntArray(gw * gh) { -1 }
        for (j in 0 until gh) for (i in 0 until gw) {
            val q = j * gw + i
            if (!inside[q]) continue
            val x = (i - (gw - 1) / 2f) * k; val y = (gh - 1 - j) * k
            val u = i / (gw - 1f); val v = j / (gh - 1f)
            // atlas: frente na metade esquerda da textura, verso na direita
            front[q] = b.vertex(x, y, height[q] * k, u * 0.5f, v)
            // na borda a posição é a mesma da frente (malha continua fechada), mas com a UV do verso
            back[q] = b.vertex(x, y, if (boundary[q]) height[q] * k else -heightBack[q] * k, 0.5f + u * 0.5f, v)
        }
        for (j in 0 until gh - 1) for (i in 0 until gw - 1) {
            // ordem anti-horária vista da frente: a(sup-esq), d(inf-esq), c(inf-dir), bq(sup-dir)
            val a = j * gw + i; val bq = a + 1; val d = a + gw; val c = d + 1
            val corners = intArrayOf(a, d, c, bq).filter { inside[it] }
            val tris: List<IntArray> = when (corners.size) {
                4 -> listOf(intArrayOf(a, d, c), intArrayOf(a, c, bq))
                3 -> listOf(corners.toIntArray())
                else -> emptyList()
            }
            for (t in tris) {
                if (boundary[t[0]] && boundary[t[1]] && boundary[t[2]]) continue
                b.tri(front[t[0]], front[t[1]], front[t[2]])
                b.tri(back[t[0]], back[t[2]], back[t[1]])
            }
        }
        val mesh = b.build(computeNormals = false)
        if (zMax <= 0f) return MeshOps.autoNormals(mesh, 60f)
        return MeshOps.autoNormals(mesh, if (extrude) 50f else 75f)
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size); val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (d in -r..r) { val xx = x + d; if (xx in 0 until w) { s += a[y * w + xx]; n++ } }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (d in -r..r) { val yy = y + d; if (yy in 0 until h) { s += tmp[yy * w + x]; n++ } }
            out[y * w + x] = s / n
        }
        return out
    }

    /** Revolução do contorno: raio por linha a partir do eixo de simetria. */
    private fun lathe(mask: Mask, bb: IntArray, opt: PhotoOptions): Mesh {
        val bw = bb[2] - bb[0] + 1; val bh = bb[3] - bb[1] + 1
        val axis = axisX(mask, bb)
        val rowsN = min(bh, opt.resolution)
        val radius = FloatArray(rowsN)
        val rowY = FloatArray(rowsN)
        for (r in 0 until rowsN) {
            val y = bb[1] + (r * (bh - 1f) / (rowsN - 1)).toInt()
            var l = -1; var rr = -1
            for (x in bb[0]..bb[2]) if (mask[x, y]) { if (l < 0) l = x; rr = x }
            radius[r] = if (l < 0) 0f else (rr - l + 1) / 2f
            rowY[r] = (y - bb[1]).toFloat()
        }
        // suaviza o perfil
        val sm = FloatArray(rowsN) { i ->
            var s = 0f; var n = 0
            for (d in -2..2) { val j = i + d; if (j in 0 until rowsN) { s += radius[j]; n++ } }
            s / n
        }
        val scale = opt.targetSize / max(bh, bw)
        val seg = 64
        val b = MeshBuilder(rowsN * (seg + 1))
        val grid = Array(rowsN) { IntArray(seg + 1) }
        for (r in 0 until rowsN) {
            val rad = sm[r] * scale * opt.thickness.coerceIn(0.3f, 3f).let { if (it == 1f) 1f else it }
            val y = (bh - 1 - rowY[r]) * scale
            for (s in 0..seg) {
                val a = (s % seg).toFloat() / seg * (2 * PI).toFloat()
                val px = sm[r] * sin(a)
                val u = ((axis + px - bb[0]) / bw).coerceIn(0f, 1f)
                grid[r][s] = b.vertex(rad * sin(a), y, rad * cos(a), u, rowY[r] / (bh - 1))
            }
        }
        // linhas de cima (r=0) para baixo; anti-horário visto de fora
        for (r in 0 until rowsN - 1) for (s in 0 until seg) {
            val top0 = grid[r][s]; val top1 = grid[r][s + 1]; val bot0 = grid[r + 1][s]; val bot1 = grid[r + 1][s + 1]
            b.tri(bot0, bot1, top1); b.tri(bot0, top1, top0)
        }
        fun cap(r: Int, up: Boolean) {
            if (sm[r] <= 0.5f) return
            val y = (bh - 1 - rowY[r]) * scale
            val center = b.vertex(0f, y, 0f, ((axis - bb[0]) / bw), rowY[r] / (bh - 1))
            for (s in 0 until seg) {
                if (up) b.tri(center, grid[r][s], grid[r][s + 1]) else b.tri(center, grid[r][s + 1], grid[r][s])
            }
        }
        cap(0, true); cap(rowsN - 1, false)
        return b.build(creaseDeg = 60f)
    }

    /** Placa em relevo (litofania invertida: claro = alto). */
    private fun relief(pixels: IntArray, w: Int, bb: IntArray, opt: PhotoOptions): Mesh {
        val bw = bb[2] - bb[0] + 1; val bh = bb[3] - bb[1] + 1
        val cell = max(bw, bh).toFloat() / opt.resolution
        val gw = ceil(bw / cell).toInt() + 1; val gh = ceil(bh / cell).toInt() + 1
        val k = opt.targetSize / (max(gw, gh) - 1)
        val base = 0.02f * opt.targetSize
        val amp = 0.06f * opt.targetSize * opt.thickness
        val lum = FloatArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val px = (bb[0] + i * cell).toInt().coerceAtMost(bb[2]); val py = (bb[1] + j * cell).toInt().coerceAtMost(bb[3])
            lum[j * gw + i] = luminance(pixels[py * w + px])
        }
        val l = boxBlur(lum, gw, gh, 1)
        val b = MeshBuilder(gw * gh * 2 + 4 * (gw + gh))
        val top = IntArray(gw * gh)
        val bot = IntArray(gw * gh)
        fun xy(i: Int, j: Int) = Pair((i - (gw - 1) / 2f) * k, (gh - 1 - j) * k)
        for (j in 0 until gh) for (i in 0 until gw) {
            val (x, y) = xy(i, j)
            val u = i / (gw - 1f); val v = j / (gh - 1f)
            top[j * gw + i] = b.vertex(x, y, base + amp * l[j * gw + i], u, v)
            bot[j * gw + i] = b.vertex(x, y, 0f, u, v)
        }
        for (j in 0 until gh - 1) for (i in 0 until gw - 1) {
            val a = j * gw + i; val bq = a + 1; val d = a + gw; val c = d + 1
            b.tri(top[a], top[d], top[c]); b.tri(top[a], top[c], top[bq])
            b.tri(bot[a], bot[c], bot[d]); b.tri(bot[a], bot[bq], bot[c])
        }
        // laterais
        fun wall(p: Int, q: Int) {
            // p->q ao longo da borda no sentido anti-horário visto da frente
            b.quad(bot[p], bot[q], top[q], top[p])
        }
        for (i in 0 until gw - 1) { wall(i + 1, i); wall((gh - 1) * gw + i, (gh - 1) * gw + i + 1) }
        for (j in 0 until gh - 1) { wall(j * gw, (j + 1) * gw); wall((j + 1) * gw + gw - 1, j * gw + gw - 1) }
        return b.build(creaseDeg = 50f)
    }
}

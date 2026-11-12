package com.vorticis.studio3d.core.mind

import com.vorticis.studio3d.core.gen.ColorLibrary
import com.vorticis.studio3d.core.gen.GenParams
import com.vorticis.studio3d.core.gen.MaterialLibrary
import com.vorticis.studio3d.core.gen.ObjectDef
import com.vorticis.studio3d.core.gen.ObjectLibrary
import com.vorticis.studio3d.core.geom.MeshOps
import com.vorticis.studio3d.core.model.Bounds
import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Mesh
import com.vorticis.studio3d.core.model.Node
import com.vorticis.studio3d.core.model.Scene
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.PhotoMode
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Rasteriza texto em máscara (implementado no Android com Canvas; na JVM com AWT). */
fun interface TextRasterizer {
    fun rasterize(text: String): Mask
}

/** Resultado de uma instrução. */
data class MindResult(
    val scene: Scene,
    val message: String,
    /** quando diferente de null, o app deve reconstruir o modelo da última foto neste modo */
    val photoMode: PhotoMode? = null,
    val photoThickness: Float? = null,
    val special: Special? = null,
    val changed: Boolean = true,
)

enum class Special { UNDO, REDO, HELP, EXPORT, PHOTO_REQUEST }

/**
 * "Vorticis Mind": interpretador de linguagem natural (português e inglês) que roda
 * 100% offline. Ele entende objetos, quantidades, cores, materiais, medidas, partes,
 * relações espaciais ("em cima", "ao redor") e comandos de edição, e transforma tudo
 * em geometria procedural.
 */
class VorticisMind(private val textRasterizer: TextRasterizer? = null) {

    // ------------------------------------------------------------ léxico
    private val createVerbs = listOf("cri", "faz", "fac", "ger", "model", "constru", "desenh", "quer", "mont", "imagin", "make", "create", "build", "generat", "draw", "fabric", "produz", "esculp", "invent")
    private val addVerbs = listOf("adicion", "acrescent", "coloc", "inclu", "insir", "insere", "bot", "poe", "ponh", "add", "put", "junt", "agreg")
    private val editVerbs = listOf(
        "pint", "color", "mud", "troc", "deix", "torn", "aument", "diminu", "reduz", "ampli", "encolh", "gir", "rotacion", "vir",
        "estic", "along", "alarg", "achat", "afin", "engross", "suaviz", "arredond", "alis", "remov", "apag", "delet", "exclu", "tir",
        "dupliq", "copi", "clon", "mov", "desloc", "empurr", "subi", "desc", "espelh", "inverta", "invert", "escal", "redimension",
        "simplif", "centraliz", "paint", "color", "scale", "rotate", "turn", "move", "remove", "delete", "smooth", "mirror", "duplicat", "resiz", "dobr",
    )

    private val numberWords = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7,
        "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "quatorze" to 14, "catorze" to 14,
        "quinze" to 15, "dezesseis" to 16, "vinte" to 20, "trinta" to 30, "cem" to 100,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "twelve" to 12,
        "par" to 2,
    )

    private val sizeWords = mapOf(
        "minusculo" to 0.35f, "minuscula" to 0.35f, "mini" to 0.45f, "miniatura" to 0.4f, "pequeno" to 0.65f, "pequena" to 0.65f, "pequenos" to 0.65f, "pequenas" to 0.65f,
        "small" to 0.65f, "tiny" to 0.35f, "medio" to 1f, "media" to 1f, "grande" to 1.5f, "grandes" to 1.5f, "big" to 1.5f, "large" to 1.5f,
        "enorme" to 2.2f, "enormes" to 2.2f, "gigante" to 3f, "gigantes" to 3f, "huge" to 2.2f, "giant" to 3f, "imenso" to 2.5f, "imensa" to 2.5f,
    )

    // ------------------------------------------------------------ estruturas
    private data class Tok(val w: String, val raw: String)

    private data class Mention(
        val def: ObjectDef?, val start: Int, val end: Int, var count: Int = 1, val text: String? = null,
    )

    private data class Attr(
        var color: Int? = null,
        var material: MaterialLibrary.Preset? = null,
        var size: Float = 1f,
        var sx: Float = 1f, var sy: Float = 1f, var sz: Float = 1f,
        var height: Float? = null, var width: Float? = null, var depth: Float? = null, var overall: Float? = null,
        var round: Boolean? = null,
        var lowPoly: Boolean = false, var smooth: Boolean = false,
        val counts: MutableMap<String, Int> = mutableMapOf(),
        val partMats: MutableMap<String, Pair<Int?, MaterialLibrary.Preset?>> = mutableMapOf(),
        val notes: MutableList<String> = mutableListOf(),
    )

    private enum class Rel { ROW, ON_TOP, BESIDE, AROUND, BELOW, INSIDE }

    // ------------------------------------------------------------ API
    fun interpret(input: String, scene: Scene): MindResult {
        val raw = input.trim()
        if (raw.isEmpty()) return MindResult(scene, "Diga o que você quer criar, por exemplo: \"uma mesa redonda de madeira com 4 cadeiras\".", changed = false)
        val quoted = Regex("[\"“”'‘’]([^\"“”'‘’]+)[\"“”'‘’]").find(raw)?.groupValues?.get(1)
        val norm = normalize(raw)
        val toks = tokenize(norm)
        val words = toks.map { it.w }
        val text = " ${words.joinToString(" ")} "

        // meta-comandos
        if (has(text, " desfaz", " desfazer", " volta ", " voltar", " undo ", " ctrl z")) return MindResult(scene, "Desfeito.", special = Special.UNDO, changed = false)
        if (has(text, " refaz", " redo ")) return MindResult(scene, "Refeito.", special = Special.REDO, changed = false)
        if (has(text, " ajuda", " help ", " o que voce faz", " o que voce sabe", " comandos", " como usar", " exemplos")) return MindResult(scene, help(), special = Special.HELP, changed = false)
        if (has(text, " exporta", " export ", " salva ", " salvar ", " salve ", " baixar", " download", " compartilh")) return MindResult(scene, "Abrindo as opções de exportação.", special = Special.EXPORT, changed = false)
        if (has(text, " limpa", " limpar", " apaga tudo", " apague tudo", " remova tudo", " remove tudo", " novo projeto", " comecar de novo", " recomec", " reset", " clear ", " zerar", " zere ")) {
            return MindResult(Scene(nextGroup = scene.nextGroup), "Cena limpa. O que vamos criar agora?")
        }

        // comandos sobre a foto
        if (has(text, " foto", " imagem", " photo", " picture", " retrato")) {
            val mode = when {
                has(text, " vaso", " revolu", " torne", " garrafa", " girar", " gira ", " copo", " lathe") -> PhotoMode.LATHE
                has(text, " relevo", " litofan", " placa", " quadro", " baixo relevo", " alto relevo", " relief") -> PhotoMode.RELIEF
                has(text, " extru", " chapa", " logo", " recorte", " chaveiro", " plano", " flat", " adesivo") -> PhotoMode.EXTRUDE
                has(text, " volume", " infl", " pelucia", " boneco", " gordinh", " arredond", " fofo") -> PhotoMode.VOLUME
                has(text, " automatic", " auto ") -> PhotoMode.AUTO
                else -> null
            }
            val thick = when {
                has(text, " mais gross", " mais espess", " mais volume", " mais gordo") -> 1.4f
                has(text, " mais fin", " menos gross", " menos volume", " mais achat") -> 0.7f
                else -> null
            }
            if (mode != null || thick != null) {
                return MindResult(scene, "Reconstruindo o modelo da foto" + (mode?.let { " no modo ${it.label}" } ?: "") + "…", photoMode = mode ?: PhotoMode.AUTO, photoThickness = thick, changed = false)
            }
            if (has(text, " tir", " tira", " captur", " camera", " fotograf")) return MindResult(scene, "Abrindo a câmera…", special = Special.PHOTO_REQUEST, changed = false)
        }

        // texto 3D
        val textContent = quoted ?: extractTextCommand(toks)
        val mentions = findMentions(toks).toMutableList()
        if (textContent != null) mentions.add(0, Mention(null, 0, 0, 1, textContent))

        val hasCreate = words.any { w -> createVerbs.any { w.startsWith(it) } }
        val hasAdd = words.any { w -> addVerbs.any { w.startsWith(it) } } || has(text, " tambem ", " mais um ", " mais uma ", " outro ", " outra ")
        val hasEdit = words.any { w -> editVerbs.any { w.startsWith(it) } } ||
            has(text, " maior", " menor", " mais alt", " mais baix", " mais larg", " mais fin", " mais gross", " low poly", " lowpoly", " facetad", " suave", " metade", " dobro", " graus", " para cima", " para baixo", " pra cima", " pra baixo")

        val editMode = !scene.isEmpty && hasEdit && !hasAdd && !(hasCreate && mentions.any { it.def != null && scene.nodes.none { n -> n.kind == it.def.id } })

        if (editMode || (mentions.isEmpty() && !scene.isEmpty)) {
            return edit(toks, text, scene, mentions)
        }

        if (mentions.isEmpty()) {
            val unknown = guessUnknownNoun(toks)
            val msg = if (unknown != null)
                "Ainda não sei modelar \"$unknown\" a partir de texto. Tire uma foto de um(a) $unknown e eu transformo em 3D, ou descreva com formas: \"uma esfera sobre um cilindro\"."
            else "Não entendi. ${shortHelp()}"
            return MindResult(scene, msg, changed = false)
        }

        // --------- criação
        val replace = !hasAdd || scene.isEmpty
        var sc = if (replace) Scene(nextGroup = scene.nextGroup) else scene
        val described = ArrayList<String>()
        val segments = segmentRanges(toks, mentions)
        var prevGroup: Int? = if (replace) null else scene.lastGroup
        var prevKind: String? = if (replace) null else scene.nodes.lastOrNull()?.kind

        // plano: atributos e relações na ordem do texto; relações invertidas trocam a ordem
        class Plan(val mention: Mention, val attr: Attr, var rel: Rel)
        val plans = ArrayList<Plan>()
        for ((mi, mention) in mentions.withIndex()) {
            val (segStart, segEnd) = segments[mi]
            val attr = parseAttrs(toks, segStart, segEnd, mention)
            val pk = if (mi == 0) prevKind else (mentions[mi - 1].def?.id ?: "texto")
            val (rel, reversed) = relationFor(toks, mentions, mi, pk, mention)
            if (reversed && plans.isNotEmpty()) {
                val prev = plans.removeAt(plans.size - 1)
                plans += Plan(mention, attr, prev.rel)
                prev.rel = rel
                plans += prev
            } else plans += Plan(mention, attr, rel)
        }
        // "adicione um vaso em cima" sem dizer onde: usa o objeto com maior área de apoio (ex.: a mesa)
        if (!replace && plans.isNotEmpty() && plans[0].rel in setOf(Rel.ON_TOP, Rel.INSIDE)) {
            prevGroup = scene.groups().maxByOrNull { g -> val b = scene.groupBounds(g); b.sizeX * b.sizeZ } ?: prevGroup
        }
        for (plan in plans) {
            val mention = plan.mention; val attr = plan.attr; val rel = plan.rel
            val created = ArrayList<Int>()
            val n = mention.count.coerceIn(1, 50)
            for (c in 0 until n) {
                val gid = sc.nextGroup
                var nodes = buildObject(mention, attr, gid) ?: continue
                val r = if (c > 0 && (rel == Rel.BESIDE || rel == Rel.ROW)) Rel.ROW else rel
                nodes = place(nodes, sc, r, prevGroup, c, n)
                sc = sc.copy(nodes = sc.nodes + nodes, nextGroup = gid + 1)
                created += gid
            }
            if (created.isEmpty()) continue
            described += describe(mention, attr, rel, n)
            if (rel != Rel.AROUND || prevGroup == null) prevGroup = created.last()
            prevKind = mention.def?.id ?: "texto"
        }
        sc = settleOnGround(sc)
        val verb = if (replace) "Criei" else "Adicionei"
        val tip = tipFor(mentions)
        return MindResult(sc, "$verb: ${described.joinToString("; ")}.$tip")
    }

    // ------------------------------------------------------------ análise de texto
    fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.replace(Regex("(\\d),(\\d)"), "$1.$2")
            .replace(Regex("(\\d)\\s*(x|vezes)\\b"), "$1 vezes")
            .replace(Regex("(\\d)(cm|mm|m|metros?|centimetros?|milimetros?)\\b"), "$1 $2")
            .replace(Regex("(\\d)\\s*%"), "$1 porcento")
            .replace(Regex("[^a-z0-9#.\\- ]"), " ")
            .replace(Regex("(?<![0-9])\\.|\\.(?![0-9])"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun tokenize(n: String): List<Tok> = n.split(' ').filter { it.isNotBlank() }.map { Tok(it, it) }

    private fun has(text: String, vararg keys: String) = keys.any { text.contains(it) }

    private fun numberAt(toks: List<Tok>, i: Int): Float? {
        if (i !in toks.indices) return null
        val w = toks[i].w
        w.toFloatOrNull()?.let { return it }
        numberWords[w]?.let { return it.toFloat() }
        return null
    }

    private fun findMentions(toks: List<Tok>): List<Mention> {
        val out = ArrayList<Mention>()
        var i = 0
        var lastDef: ObjectDef? = null
        while (i < toks.size) {
            var matched: Mention? = null
            for (len in ObjectLibrary.maxWords downTo 1) {
                if (i + len > toks.size) continue
                val phrase = toks.subList(i, i + len).joinToString(" ") { it.w }
                val def = ObjectLibrary.lookup(phrase) ?: continue
                // palavra que é parte/parâmetro do objeto anterior não cria objeto novo
                val prev = lastDef
                if (prev != null && len == 1) {
                    val isPart = prev.parts.values.any { phrase in it }
                    val isParam = ObjectLibrary.paramWords[phrase]?.let { ObjectLibrary.countParams[prev.id]?.contains(it) } == true
                    if (isPart || isParam) break
                }
                // "copa" de árvore, "base" etc. já tratados; "taca" só vira troféu se não vier depois de "copo"
                matched = Mention(def, i, i + len)
                break
            }
            if (matched != null) {
                // quantidade imediatamente antes (ex.: "4 cadeiras", "duas mesas")
                var count = 1
                var explicit = false
                numberAt(toks, matched.start - 1)?.let { count = it.toInt(); explicit = true }
                // adjetivo entre número e substantivo: "3 grandes esferas"
                if (!explicit && matched.start >= 2 && sizeWords.containsKey(toks[matched.start - 1].w)) {
                    numberAt(toks, matched.start - 2)?.let { count = it.toInt(); explicit = true }
                }
                val word = toks[matched.start].w
                val def = matched.def!!
                val plural = word.endsWith("s") && (def.words.contains(word.dropLast(1)) || def.words.contains(word.dropLast(2)))
                if (!explicit && plural) {
                    // plural sem número: "cadeiras" -> 4 ao redor de mesa, senão 3
                    count = if (lastDef?.id == "mesa") 4 else 3
                }
                matched.count = count
                out += matched
                lastDef = matched.def
                i = matched.end
            } else i++
        }
        return out
    }

    private fun extractTextCommand(toks: List<Tok>): String? {
        val keys = setOf("texto", "escreva", "escrever", "escrito", "letreiro", "palavra", "nome", "text", "write", "letras", "frase")
        val idx = toks.indexOfFirst { it.w in keys }
        if (idx < 0) return null
        val stop = setOf("em", "de", "com", "na", "no", "cor", "azul", "vermelho", "dourado")
        val after = toks.drop(idx + 1).dropWhile { it.w in setOf("o", "a", "um", "uma", "3d", "escrito", "com", "dizendo", "que", "diz") }
        val words = after.takeWhile { it.w !in stop && ColorLibrary.find(it.w) == null && MaterialLibrary.find(it.w) == null }
        return words.joinToString(" ") { it.raw }.uppercase().ifBlank { null }
    }

    private fun guessUnknownNoun(toks: List<Tok>): String? {
        val articles = setOf("um", "uma", "o", "a", "uns", "umas", "an")
        val i = toks.indexOfFirst { it.w in articles }
        return if (i >= 0 && i + 1 < toks.size) toks[i + 1].w else toks.lastOrNull()?.w?.takeIf { it.length > 3 }
    }

    /** Divide o texto em trechos: cada objeto recebe as palavras que o descrevem. */
    private fun segmentRanges(toks: List<Tok>, mentions: List<Mention>): List<Pair<Int, Int>> {
        val connectors = setOf("e", "com", "ao", "sobre", "em", "mais", "tambem", "junto", "perto", "do", "da", "no", "na", "ate", "and", "with", "on", "next")
        val res = ArrayList<Pair<Int, Int>>()
        val starts = IntArray(mentions.size)
        for (m in mentions.indices) {
            if (mentions[m].def == null) { starts[m] = 0; continue }
            if (m == 0) { starts[m] = 0; continue }
            // começa após o último conector entre o objeto anterior e este
            var s = mentions[m].start
            var k = mentions[m].start - 1
            while (k >= mentions[m - 1].end) {
                if (toks[k].w in connectors) break
                s = k; k--
            }
            starts[m] = s
        }
        for (m in mentions.indices) {
            val end = if (m + 1 < mentions.size && mentions[m + 1].def != null) starts[m + 1] else toks.size
            res += Pair(starts[m], end)
        }
        return res
    }

    private fun parseAttrs(toks: List<Tok>, s: Int, e: Int, mention: Mention): Attr {
        val a = Attr()
        val def = mention.def
        var currentPart: String? = null
        var i = s
        while (i < e) {
            val w = toks[i].w
            val next = toks.getOrNull(i + 1)?.w
            // partes
            if (def != null) {
                val part = def.parts.entries.firstOrNull { w in it.value }?.key
                val isCountParam = numberAt(toks, i - 1) != null && ObjectLibrary.paramWords[w] != null
                if (part != null && !isCountParam) { currentPart = part; i++; continue }
            }
            // parâmetros numéricos: "4 pernas", "3 andares"
            val num = numberAt(toks, i)
            if (num != null && next != null) {
                val param = ObjectLibrary.paramWords[next]
                if (param != null) { a.counts[param] = num.toInt(); if (def != null && def.parts.values.any { next in it }) currentPart = null; i += 2; continue }
                // medidas
                val unit = unitFactor(next)
                if (unit != null) {
                    val value = num * unit
                    val ctx = (maxOf(s, i - 3) until minOf(e, i + 5)).map { toks[it].w }
                    when {
                        ctx.any { it.startsWith("altur") || it == "alto" || it == "alta" || it == "height" || it == "tall" } -> a.height = value
                        ctx.any { it.startsWith("largur") || it == "width" || it == "wide" } -> a.width = value
                        ctx.any { it.startsWith("profundidade") || it.startsWith("comprimento") || it == "depth" || it == "long" } -> a.depth = value
                        ctx.any { it == "raio" || it == "radius" } -> a.overall = value * 2
                        else -> a.overall = value
                    }
                    i += 2; continue
                }
            }
            // cores
            val hex = ColorLibrary.parseHex(w)
            val color = hex ?: ColorLibrary.find(w)
            val preset = MaterialLibrary.find(w)
            if (color != null || preset != null) {
                var c = color
                if (c != null && (next == "escuro" || next == "escura")) { c = ColorLibrary.adjust(c, 0.55f); i++ }
                else if (c != null && (next == "claro" || next == "clara")) { c = ColorLibrary.lighten(c); i++ }
                // "dourado"/"prateado" -> material metálico
                val asMaterial = preset != null && (color == null || w in setOf("dourado", "dourada", "prateado", "prateada", "ouro", "prata"))
                if (currentPart != null) {
                    val prev = a.partMats[currentPart]
                    a.partMats[currentPart] = Pair(if (asMaterial) prev?.first else c ?: prev?.first, if (asMaterial) preset else prev?.second)
                } else {
                    if (asMaterial) a.material = preset else a.color = c
                }
                i++; continue
            }
            when {
                sizeWords.containsKey(w) -> a.size *= sizeWords.getValue(w)
                w in setOf("redondo", "redonda", "redondos", "redondas", "circular", "round") -> a.round = true
                w in setOf("quadrado", "quadrada", "retangular", "square") -> a.round = false
                w in setOf("alto", "alta", "altos", "altas", "tall") && numberAt(toks, i + 1) == null -> a.sy *= 1.5f
                w in setOf("baixo", "baixa", "baixinho", "short") -> a.sy *= 0.65f
                w in setOf("largo", "larga", "gordo", "gorda", "wide") -> { a.sx *= 1.4f; a.sz *= 1.4f }
                w in setOf("fino", "fina", "estreito", "estreita", "magro", "magra", "thin") -> { a.sx *= 0.7f; a.sz *= 0.7f }
                w in setOf("comprido", "comprida", "longo", "longa") -> a.sx *= 1.6f
                w in setOf("achatado", "achatada", "flat", "plano") -> a.sy *= 0.4f
                w == "low" && next == "poly" || w == "lowpoly" || w.startsWith("facetad") || w.startsWith("poligona") || w == "geometrico" -> a.lowPoly = true
                w in setOf("suave", "liso", "lisa", "arredondado", "arredondada", "smooth", "organico", "organica") -> a.smooth = true
            }
            i++
        }
        return a
    }

    private fun unitFactor(w: String): Float? = when (w) {
        "m", "metro", "metros", "meter", "meters" -> 1f
        "cm", "centimetro", "centimetros" -> 0.01f
        "mm", "milimetro", "milimetros" -> 0.001f
        "km" -> 1000f
        else -> null
    }

    /** Relação espacial e se ela é "invertida" ("A em cima de B" => B primeiro, A em cima). */
    private fun relationFor(toks: List<Tok>, mentions: List<Mention>, mi: Int, prevKind: String?, mention: Mention): Pair<Rel, Boolean> {
        val from = if (mi == 0) 0 else mentions[mi - 1].end
        val between = " " + (from until mention.start).joinToString(" ") { toks[it].w } + " "
        val after = " " + (mention.end until minOf(toks.size, mention.end + 4)).joinToString(" ") { toks[it].w } + " "
        if (mi > 0) {
            if (has(between, " em cima d", " encima d", " acima d", " no topo d", " por cima d", " sobre ", " on top of ", " on ")) return Rel.ON_TOP to true
            if (has(between, " dentro d", " inside ", " in ")) return Rel.INSIDE to true
            if (has(between, " embaixo d", " debaixo d", " abaixo d", " sob ", " under ", " below ")) return Rel.ON_TOP to false
        }
        if (prevKind == null) return Rel.ROW to false
        val ctx = between + after
        val rel = when {
            has(ctx, " em cima", " no topo", " acima", " por cima", " on top", " above", " encima") -> Rel.ON_TOP
            has(ctx, " embaixo", " debaixo", " abaixo", " below", " under") -> Rel.BELOW
            has(ctx, " dentro", " inside") -> Rel.INSIDE
            has(ctx, " em volta", " ao redor", " em torno", " rodeando", " around", " circulo") -> Rel.AROUND
            has(ctx, " ao lado", " do lado", " perto", " junto", " beside", " next to", " lado a lado") -> Rel.BESIDE
            has(between, " com ") && mention.count > 1 && prevKind in setOf("mesa", "fogueira", "arvore", "castelo", "torre", "planeta", "esfera", "cilindro") -> Rel.AROUND
            has(between, " com ") -> Rel.BESIDE
            else -> Rel.ROW
        }
        return rel to false
    }

    // ------------------------------------------------------------ construção
    private fun buildObject(mention: Mention, attr: Attr, gid: Int): List<Node>? {
        val kind: String
        val label: String
        val parts: List<com.vorticis.studio3d.core.gen.Part>
        val def = mention.def
        if (def == null) {
            val t = mention.text ?: return null
            val ras = textRasterizer ?: return null
            kind = "texto"; label = "texto \"$t\""
            parts = listOf(com.vorticis.studio3d.core.gen.Part("letras", Text3D.build(ras.rasterize(t)), Material("Texto", 0xFF2F6FD8.toInt(), 0.1f, 0.35f)))
        } else {
            kind = def.id; label = def.label
            parts = def.build(GenParams(attr.counts, attr.round))
        }
        val main = def?.mainParts ?: emptySet()
        var nodes = parts.map { p ->
            var mat = p.material
            val pm = attr.partMats[p.part]
            if (pm != null) mat = applyLook(mat, pm.first, pm.second)
            else if (main.isEmpty() || p.part in main) mat = applyLook(mat, attr.color, attr.material)
            var mesh = p.mesh
            if (attr.lowPoly) mesh = MeshOps.lowPoly(mesh)
            if (attr.smooth) mesh = MeshOps.smooth(mesh, 4)
            Node("${label}_${p.part}".replace(' ', '_'), mesh, mat, gid, kind, p.part)
        }
        // escala: tamanho geral, proporções e medidas explícitas
        val b = Bounds.empty().apply { nodes.forEach { add(it.mesh.bounds()) } }
        var sx = attr.sx * attr.size; var sy = attr.sy * attr.size; var sz = attr.sz * attr.size
        attr.overall?.let { val f = it / b.maxSize.coerceAtLeast(1e-4f); sx = f * attr.sx; sy = f * attr.sy; sz = f * attr.sz }
        attr.height?.let { sy = it / b.sizeY.coerceAtLeast(1e-4f); if (attr.width == null && attr.depth == null && attr.overall == null && def?.id in setOf("esfera", "cubo", "estrela", "coracao", "planeta", "moeda", "dado")) { sx = sy; sz = sy } }
        attr.width?.let { sx = it / b.sizeX.coerceAtLeast(1e-4f) }
        attr.depth?.let { sz = it / b.sizeZ.coerceAtLeast(1e-4f) }
        if (sx != 1f || sy != 1f || sz != 1f) {
            nodes = nodes.map { it.copy(mesh = MeshOps.scale(it.mesh, sx, sy, sz, b.centerX, b.minY, b.centerZ)) }
        }
        return nodes
    }

    private fun applyLook(base: Material, color: Int?, preset: MaterialLibrary.Preset?): Material {
        if (preset != null) return preset.build(color)
        if (color == null) return base
        if (base.name == "Foto") return base.copy(color = color)
        // material procedural (madeira, pedra, tecido...) é regenerado com a nova cor
        val p = if (base.texture != null) MaterialLibrary.presets.firstOrNull { it.key == base.name.lowercase() } else null
        return p?.build(color) ?: base.copy(color = color, name = "Cor")
    }

    /** Posiciona o novo objeto em relação ao anterior. */
    private fun place(nodes: List<Node>, scene: Scene, rel: Rel, prevGroup: Int?, index: Int, count: Int): List<Node> {
        val b = Bounds.empty().apply { nodes.forEach { add(it.mesh.bounds()) } }
        val prev = prevGroup?.let { scene.groupBounds(it) }?.takeIf { !it.isEmpty }
        val all = scene.bounds()
        fun moved(dx: Float, dy: Float, dz: Float) = nodes.map { it.copy(mesh = MeshOps.translate(it.mesh, dx, dy, dz)) }
        val gap = 0.15f
        return when {
            rel == Rel.AROUND && prev != null -> {
                val radius = max(prev.sizeX, prev.sizeZ) / 2 + max(b.sizeX, b.sizeZ) / 2 + 0.05f
                val a = index * 2 * PI.toFloat() / count + PI.toFloat() / 4 * (if (count == 4) 1 else 0)
                val tx = prev.centerX + radius * sin(a); val tz = prev.centerZ + radius * cos(a)
                // gira para "olhar" para o centro (frente do objeto = +Z)
                val ang = Math.toDegrees(atan2((prev.centerX - tx).toDouble(), (prev.centerZ - tz).toDouble())).toFloat()
                nodes.map {
                    val r = MeshOps.rotate(it.mesh, 'y', ang, b.centerX, 0f, b.centerZ)
                    it.copy(mesh = MeshOps.translate(r, tx - b.centerX, prev.minY - b.minY, tz - b.centerZ))
                }
            }
            rel == Rel.ON_TOP && prev != null -> {
                val spread = (index - (count - 1) / 2f) * (b.sizeX + 0.05f)
                moved(prev.centerX - b.centerX + spread, prev.maxY - b.minY, prev.centerZ - b.centerZ)
            }
            rel == Rel.INSIDE && prev != null -> moved(prev.centerX - b.centerX, prev.minY + prev.sizeY * 0.1f - b.minY, prev.centerZ - b.centerZ)
            rel == Rel.BELOW && prev != null -> moved(prev.centerX - b.centerX, prev.minY - b.maxY, prev.centerZ - b.centerZ)
            rel == Rel.BESIDE && prev != null -> moved(prev.maxX + gap - b.minX, prev.minY - b.minY, prev.centerZ - b.centerZ)
            scene.isEmpty -> moved(-b.centerX, -b.minY, -b.centerZ)
            else -> moved(all.maxX + gap * 2 - b.minX, -b.minY, -b.centerZ)
        }
    }

    /** Garante que nada fique abaixo do chão e centraliza a cena. */
    private fun settleOnGround(scene: Scene): Scene {
        if (scene.isEmpty) return scene
        val b = scene.bounds()
        val dy = if (b.minY < 0f) -b.minY else 0f
        val dx = -b.centerX; val dz = -b.centerZ
        if (kotlin.math.abs(dx) < 1e-4f && kotlin.math.abs(dz) < 1e-4f && dy == 0f) return scene
        return scene.copy(nodes = scene.nodes.map { it.copy(mesh = MeshOps.translate(it.mesh, dx, dy, dz)) })
    }

    private fun describe(m: Mention, a: Attr, rel: Rel, n: Int): String {
        val name = m.def?.label ?: "texto 3D \"${m.text}\""
        val det = ArrayList<String>()
        a.material?.let { det += it.key }
        a.color?.let { c -> det += ColorLibrary.colors.entries.firstOrNull { it.value == c }?.key ?: String.format("#%06X", c and 0xFFFFFF) }
        if (a.round == true) det += "redondo"
        a.counts.forEach { (k, v) -> det += "$v $k" }
        a.partMats.forEach { (p, v) ->
            val d = listOfNotNull(v.second?.key, v.first?.let { c -> ColorLibrary.colors.entries.firstOrNull { it.value == c }?.key ?: "cor personalizada" })
            det += "$p ${d.joinToString(" ")}"
        }
        if (a.size != 1f) det += if (a.size > 1f) "grande" else "pequeno"
        a.height?.let { det += "altura ${fmtLen(it)}" }
        a.width?.let { det += "largura ${fmtLen(it)}" }
        a.depth?.let { det += "profundidade ${fmtLen(it)}" }
        a.overall?.let { det += "tamanho ${fmtLen(it)}" }
        if (a.lowPoly) det += "low poly"
        if (a.smooth) det += "suavizado"
        val relTxt = when (rel) {
            Rel.ON_TOP -> " em cima"; Rel.AROUND -> " ao redor"; Rel.BESIDE -> " ao lado"; Rel.BELOW -> " embaixo"; Rel.INSIDE -> " dentro"; Rel.ROW -> ""
        }
        val qty = if (n > 1) "$n× " else ""
        return qty + name + (if (det.isNotEmpty()) " (${det.joinToString(", ")})" else "") + relTxt
    }

    private fun fmtLen(m: Float) = if (m < 1f) "${(m * 100).toInt()} cm" else "${"%.2f".format(m).trimEnd('0').trimEnd('.', ',')} m"

    private fun tipFor(mentions: List<Mention>): String {
        val d = mentions.firstNotNullOfOrNull { it.def } ?: return " Dica: diga \"pinte de azul\" ou \"deixe maior\"."
        val part = d.parts.keys.firstOrNull()
        return if (part != null) " Dica: experimente \"pinte $part de preto\" ou \"deixe low poly\"." else " Dica: experimente \"de metal\", \"gire 45 graus\" ou \"adicione uma esfera em cima\"."
    }

    // ------------------------------------------------------------ edição
    private fun edit(toks: List<Tok>, text: String, scene: Scene, mentions: List<Mention>): MindResult {
        val words = toks.map { it.w }
        // alvos: objetos mencionados que existem na cena; "tudo" = todos; senão o último grupo
        val all = has(text, " tudo ", " todos ", " todas ", " cena ", " everything ", " all ")
        val kinds = mentions.mapNotNull { it.def?.id }.toSet()
        var targets: List<Int> = when {
            all -> scene.groups()
            kinds.isNotEmpty() -> scene.nodes.filter { it.kind in kinds }.map { it.group }.distinct()
            else -> listOfNotNull(scene.lastGroup)
        }
        if (targets.isEmpty()) targets = listOfNotNull(scene.lastGroup)
        // parte específica? ("pinte as pernas de preto")
        val partWords = scene.nodes.filter { it.group in targets }.map { it.part }.distinct()
        val defs = scene.nodes.filter { it.group in targets }.mapNotNull { ObjectLibrary.byId(it.kind) }.distinct()
        val part = words.firstNotNullOfOrNull { w -> defs.firstNotNullOfOrNull { d -> d.parts.entries.firstOrNull { w in it.value }?.key }?.takeIf { it in partWords } }
        val pick: (Node) -> Boolean = { n -> n.group in targets && (part == null || n.part == part) }
        val targetName = when {
            all -> "a cena toda"
            part != null -> part
            else -> scene.nodes.firstOrNull { it.group == targets.firstOrNull() }?.kind?.let { k -> ObjectLibrary.byId(k)?.label ?: k } ?: "o objeto"
        }

        var sc = scene
        val did = ArrayList<String>()
        fun mapNodes(f: (Node) -> Node) { sc = sc.copy(nodes = sc.nodes.map { if (pick(it)) f(it) else it }) }
        fun bounds(): Bounds = Bounds.empty().apply { sc.nodes.filter(pick).forEach { add(it.mesh.bounds()) } }

        // remover
        if (words.any { w -> listOf("remov", "apag", "delet", "exclu", "tira", "tire", "elimin").any { w.startsWith(it) } }) {
            sc = sc.copy(nodes = sc.nodes.filterNot(pick))
            return MindResult(sc, "Removi $targetName.")
        }
        // duplicar
        if (words.any { w -> listOf("dupliq", "copi", "clon", "duplicat").any { w.startsWith(it) } }) {
            val times = toks.indices.firstNotNullOfOrNull { i -> if (toks.getOrNull(i + 1)?.w == "vezes") numberAt(toks, i)?.toInt() else null } ?: 1
            for (t in 1..times.coerceIn(1, 20)) {
                val b = sc.bounds()
                val src = scene.nodes.filter(pick)
                val sb = Bounds.empty().apply { src.forEach { add(it.mesh.bounds()) } }
                val groupMap = HashMap<Int, Int>()
                var next = sc.nextGroup
                val copies = src.map { n ->
                    val g = groupMap.getOrPut(n.group) { next++ }
                    n.copy(group = g, mesh = MeshOps.translate(n.mesh, b.maxX + 0.2f - sb.minX, 0f, 0f))
                }
                sc = sc.copy(nodes = sc.nodes + copies, nextGroup = next)
            }
            return MindResult(settleOnGround(sc), "Dupliquei $targetName${if (times > 1) " $times vezes" else ""}.")
        }

        // cor / material
        val attr = parseAttrs(toks, 0, toks.size, Mention(null, 0, 0))
        val paintPart = attr.partMats.values.firstOrNull()
        val color = attr.color ?: paintPart?.first
        val preset = attr.material ?: paintPart?.second
        if (color != null || preset != null) {
            mapNodes { n ->
                n.copy(material = applyLook(n.material, color, preset))
            }
            did += "pintei $targetName" + (preset?.let { " de ${it.key}" } ?: "")
        }

        // escala
        val factorNum = toks.indices.firstNotNullOfOrNull { i ->
            val nx = toks.getOrNull(i + 1)?.w
            when {
                nx == "vezes" -> numberAt(toks, i)
                nx == "porcento" -> numberAt(toks, i)?.div(100f)
                else -> null
            }
        }
        val bigger = has(text, " maior", " aument", " amplie", " ampli", " cresc", " bigger", " larger", " dobr", " dobro")
        val smaller = has(text, " menor", " diminu", " reduz", " encolh", " smaller", " metade")
        val axisH = has(text, " altur", " mais alt", " estic", " along", " taller", " vertical")
        val axisW = has(text, " largur", " mais larg", " alarg", " wider")
        if (bigger || smaller || factorNum != null) {
            var f = factorNum ?: when {
                has(text, " dobro", " dobr") -> 2f
                has(text, " metade") -> 0.5f
                bigger -> 1.5f
                else -> 0.66f
            }
            if (smaller && f > 1f) f = 1f / f
            val b = bounds()
            val (fx, fy, fz) = when {
                axisH -> Triple(1f, f, 1f)
                axisW -> Triple(f, 1f, 1f)
                else -> Triple(f, f, f)
            }
            mapNodes { it.copy(mesh = MeshOps.scale(it.mesh, fx, fy, fz, b.centerX, b.minY, b.centerZ)) }
            did += (if (f >= 1f) "aumentei" else "diminuí") + " $targetName ${"%.2f".format(f).trimEnd('0').trimEnd('.', ',')}×" + if (axisH) " na altura" else if (axisW) " na largura" else ""
        } else {
            val (fx, fy, fz, what) = when {
                has(text, " mais alt", " estic", " along") -> Quad(1f, 1.4f, 1f, "estiquei na altura")
                has(text, " mais baix", " achat") -> Quad(1f, 0.65f, 1f, "achatei")
                has(text, " mais larg", " alarg", " mais gord", " engord") -> Quad(1.35f, 1f, 1.35f, "alarguei")
                has(text, " mais fin", " afin", " emagre") -> Quad(0.7f, 1f, 0.7f, "afinei")
                has(text, " mais gross", " engross", " mais espess") -> Quad(1f, 1f, 1.4f, "engrossei")
                has(text, " mais comprid", " mais long", " alongu") -> Quad(1.4f, 1f, 1f, "alonguei")
                else -> Quad(1f, 1f, 1f, "")
            }
            if (what.isNotEmpty()) {
                val b = bounds()
                mapNodes { it.copy(mesh = MeshOps.scale(it.mesh, fx, fy, fz, b.centerX, b.minY, b.centerZ)) }
                did += what
            }
        }

        // rotação
        if (has(text, " gir", " rotacion", " vire", " virar", " rotate", " turn", " graus", " de cabeca para baixo", " deitad")) {
            val deg = toks.indices.firstNotNullOfOrNull { i -> if (toks.getOrNull(i + 1)?.w?.startsWith("grau") == true || toks.getOrNull(i + 1)?.w == "degrees") numberAt(toks, i) else null }
                ?: if (has(text, " cabeca para baixo", " ponta cabeca")) 180f else 90f
            val axis = when {
                has(text, " eixo x", " x ", " de lado", " deitad", " para frente", " pra frente", " cabeca para baixo") -> 'x'
                has(text, " eixo z", " z ", " inclin") -> 'z'
                else -> 'y'
            }
            val dir = if (has(text, " anti", " esquerda", " contra")) 1f else -1f
            val b = bounds()
            mapNodes { it.copy(mesh = MeshOps.rotate(it.mesh, axis, dir * deg, b.centerX, b.centerY, b.centerZ)) }
            did += "girei $targetName ${deg.toInt()}° no eixo ${axis.uppercaseChar()}"
        }

        // mover
        val moveAmount = toks.indices.firstNotNullOfOrNull { i -> unitFactor(toks.getOrNull(i + 1)?.w ?: "")?.let { u -> numberAt(toks, i)?.times(u) } } ?: 0.5f
        val move = when {
            has(text, " para cima", " pra cima", " suba", " subir", " levant", " up ") -> Triple(0f, moveAmount, 0f)
            has(text, " para baixo", " pra baixo", " desca", " descer", " abaix", " down ") -> Triple(0f, -moveAmount, 0f)
            has(text, " para esquerda", " pra esquerda", " a esquerda", " left") -> Triple(-moveAmount, 0f, 0f)
            has(text, " para direita", " pra direita", " a direita", " right") -> Triple(moveAmount, 0f, 0f)
            has(text, " para frente", " pra frente", " forward") -> Triple(0f, 0f, moveAmount)
            has(text, " para tras", " pra tras", " back ") -> Triple(0f, 0f, -moveAmount)
            else -> null
        }
        if (move != null && words.any { w -> listOf("mov", "desloc", "empurr", "suba", "subi", "desc", "lev", "abaix", "coloq", "pux", "pass", "move", "leve").any { w.startsWith(it) } }) {
            mapNodes { it.copy(mesh = MeshOps.translate(it.mesh, move.first, move.second, move.third)) }
            did += "movi $targetName"
        }

        // espelhar
        if (words.any { it.startsWith("espelh") || it.startsWith("mirror") || it.startsWith("invert") }) {
            val b = bounds()
            mapNodes { it.copy(mesh = MeshOps.mirrorX(it.mesh, b.centerX)) }
            did += "espelhei $targetName"
        }
        // estilo
        if (has(text, " low poly", " lowpoly", " facetad", " poligonal", " simplifi", " geometric")) {
            mapNodes { it.copy(mesh = MeshOps.lowPoly(it.mesh)) }
            did += "deixei $targetName low poly"
        } else if (has(text, " suaviz", " suave", " arredond", " alis", " smooth")) {
            mapNodes { it.copy(mesh = MeshOps.smooth(it.mesh, 5)) }
            did += "suavizei $targetName"
        }
        if (has(text, " centraliz", " center")) {
            val b = bounds()
            mapNodes { it.copy(mesh = MeshOps.translate(it.mesh, -b.centerX, 0f, -b.centerZ)) }
            did += "centralizei $targetName"
        }

        if (did.isEmpty()) {
            return MindResult(scene, "Não entendi a alteração. ${shortHelp()}", changed = false)
        }
        val onGround = if (move != null) sc else settleOnGround(sc)
        return MindResult(onGround, did.joinToString(", ").replaceFirstChar { it.uppercase() } + ".")
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: String)

    // ------------------------------------------------------------ ajuda
    fun shortHelp() = "Exemplos: \"crie uma casa azul com telhado vermelho\", \"mesa redonda de madeira com 4 cadeiras\", " +
        "\"pinte as pernas de preto\", \"deixe 2 vezes maior\", \"gire 45 graus\", \"texto 'VORTICIS' dourado\"."

    fun help(): String = buildString {
        appendLine("Eu sou a Vorticis Mind e funciono 100% no seu celular, sem internet.")
        appendLine()
        appendLine("CRIAR: descreva objetos com cores, materiais, quantidades e medidas.")
        appendLine("• \"uma casa de 2 andares com telhado azul\"")
        appendLine("• \"mesa redonda de madeira com 4 cadeiras pretas\"")
        appendLine("• \"foguete vermelho com 3 aletas\" • \"árvore low poly\"")
        appendLine("• \"esfera dourada em cima de um cubo de mármore\"")
        appendLine("• \"texto 'OLÁ' prateado\"")
        appendLine()
        appendLine("EDITAR: \"pinte de verde\", \"pinte o telhado de preto\", \"deixe 2 vezes maior\", \"mais alto\", \"gire 90 graus\", ")
        appendLine("\"mova para a direita\", \"duplique 3 vezes\", \"espelhe\", \"suavize\", \"remova a cadeira\", \"desfazer\".")
        appendLine()
        appendLine("FOTO: toque na câmera e fotografe um objeto. Depois diga \"transforme a foto em vaso\", \"foto em relevo\", \"extrusão da foto\" ou \"foto com mais volume\".")
        appendLine()
        append("Objetos que conheço: ")
        append(ObjectLibrary.objects.joinToString(", ") { it.label })
        appendLine(", texto 3D.")
        append("Materiais: ").append(MaterialLibrary.presets.joinToString(", ") { it.key }).append('.')
    }
}

package com.vorticis.studio3d.core.gen

import com.vorticis.studio3d.core.geom.MeshOps
import com.vorticis.studio3d.core.geom.Primitives as P
import com.vorticis.studio3d.core.model.Material
import com.vorticis.studio3d.core.model.Mesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Peça gerada por um objeto da biblioteca. */
data class Part(val part: String, val mesh: Mesh, val material: Material)

/** Parâmetros entendidos pela IA para um objeto. */
data class GenParams(
    val counts: Map<String, Int> = emptyMap(),
    val round: Boolean? = null,
) {
    fun count(key: String, def: Int, min: Int = 1, max: Int = 64): Int = (counts[key] ?: def).coerceIn(min, max)
}

class ObjectDef(
    val id: String,
    val label: String,
    /** palavras (sem acento) que identificam o objeto; inclua plurais. */
    val words: List<String>,
    /** partes: nome canônico -> sinônimos */
    val parts: Map<String, List<String>> = emptyMap(),
    /** partes que recebem a cor/material geral do objeto (vazio = todas). */
    val mainParts: Set<String> = emptySet(),
    val build: (GenParams) -> List<Part>,
)

private fun Mesh.at(x: Float, y: Float, z: Float) = MeshOps.translate(this, x, y, z)
private fun Mesh.rot(axis: Char, deg: Float) = MeshOps.rotate(this, axis, deg)
private fun Mesh.sc(x: Float, y: Float, z: Float) = MeshOps.scale(this, x, y, z)
private fun col(hex: Long) = hex.toInt()
private fun solid(hex: Long, name: String = "Cor", rough: Float = 0.55f, metal: Float = 0f) = Material(name, col(hex), metal, rough)
private fun mat(key: String) = MaterialLibrary.presets.first { it.key == key }.build(null)
private const val TAU = (PI * 2).toFloat()

private class Parts {
    val list = ArrayList<Part>()
    fun add(part: String, mesh: Mesh, m: Material) { list.add(Part(part, mesh, m)) }
    fun addAll(part: String, meshes: List<Mesh>, m: Material) { if (meshes.isNotEmpty()) add(part, MeshOps.merge(meshes), m) }
}

private fun parts(block: Parts.() -> Unit): List<Part> = Parts().apply(block).list

/** Pontos distribuídos num círculo. */
private fun ring(n: Int, r: Float, phase: Float = 0f) = (0 until n).map { i ->
    val a = phase + i * TAU / n
    Pair(r * sin(a), r * cos(a))
}

object ObjectLibrary {

    val wood get() = mat("madeira")

    val objects: List<ObjectDef> = listOf(
        ObjectDef("cubo", "cubo", listOf("cubo", "cubos", "caixa", "caixas", "bloco", "blocos", "cube", "box")) {
            parts { add("corpo", P.box(1f, 1f, 1f).at(0f, 0.5f, 0f), solid(0xFF3D7DD8)) }
        },
        ObjectDef("esfera", "esfera", listOf("esfera", "esferas", "bola", "bolas", "sphere", "ball", "globo")) {
            parts { add("corpo", P.sphere(0.5f).at(0f, 0.5f, 0f), solid(0xFFE0533C)) }
        },
        ObjectDef("cilindro", "cilindro", listOf("cilindro", "cilindros", "tubo", "cylinder")) { p ->
            parts { add("corpo", P.cylinder(0.4f, 0.4f, 1f, p.count("lados", 40, 3)).at(0f, 0.5f, 0f), solid(0xFF3BA55C)) }
        },
        ObjectDef("cone", "cone", listOf("cone", "cones")) { p ->
            parts { add("corpo", P.cone(0.45f, 1f, p.count("lados", 40, 3)).at(0f, 0.5f, 0f), solid(0xFFF08A24)) }
        },
        ObjectDef("piramide", "pirâmide", listOf("piramide", "piramides", "pyramid")) { p ->
            val sides = p.count("lados", 4, 3, 12)
            val m = if (sides == 4) P.pyramid(1.1f, 0.9f) else P.cone(0.6f, 0.9f, sides)
            parts { add("corpo", m.at(0f, 0.45f, 0f), solid(0xFFE3C16F)) }
        },
        ObjectDef("prisma", "prisma", listOf("prisma", "prismas", "hexagono", "prism")) { p ->
            parts { add("corpo", P.cylinder(0.5f, 0.5f, 0.8f, p.count("lados", 6, 3, 24)).at(0f, 0.4f, 0f), solid(0xFF8A4FD6)) }
        },
        ObjectDef("toro", "rosca", listOf("toro", "rosca", "roscas", "donut", "donuts", "rosquinha", "anel", "aneis", "torus", "ring", "boia")) {
            parts { add("corpo", P.torus(0.4f, 0.16f).at(0f, 0.16f, 0f), solid(0xFFEC6FA6)) }
        },
        ObjectDef("capsula", "cápsula", listOf("capsula", "capsulas", "pilula", "capsule")) {
            parts { add("corpo", P.capsule(0.3f, 1.2f).at(0f, 0.6f, 0f), solid(0xFF2EC4B6)) }
        },
        ObjectDef("estrela", "estrela", listOf("estrela", "estrelas", "star")) { p ->
            val n = p.count("pontas", 5, 3, 16)
            parts { add("corpo", P.extrude(P.starPolygon(n, 0.5f, 0.22f), 0.22f).at(0f, 0.5f, 0f), solid(0xFFF2C230, rough = 0.3f)) }
        },
        ObjectDef("coracao", "coração", listOf("coracao", "coracoes", "heart")) {
            parts { add("corpo", P.extrude(P.heartPolygon(1f), 0.28f).at(0f, 0.5f, 0f), solid(0xFFD8322F, rough = 0.3f)) }
        },
        ObjectDef("chao", "chão", listOf("chao", "piso", "plano", "plataforma", "floor", "terreno", "gramado")) {
            parts { add("corpo", P.box(3f, 0.05f, 3f).at(0f, 0.025f, 0f), mat("grama")) }
        },
        ObjectDef("moeda", "moeda", listOf("moeda", "moedas", "coin")) {
            parts { add("corpo", P.cylinder(0.3f, 0.3f, 0.05f, 48).rot('x', 90f).at(0f, 0.3f, 0f), mat("ouro")) }
        },
        ObjectDef("mesa", "mesa", listOf("mesa", "mesas", "table", "escrivaninha"),
            parts = mapOf("tampo" to listOf("tampo", "topo", "superficie", "tampa"), "pernas" to listOf("perna", "pernas", "pes", "legs")),
        ) { p ->
            val round = p.round == true
            val legs = p.count("pernas", if (round) 3 else 4, 1, 12)
            parts {
                val topY = 0.75f
                if (round) add("tampo", P.cylinder(0.6f, 0.6f, 0.05f, 48).at(0f, topY - 0.025f, 0f), wood)
                else add("tampo", P.box(1.3f, 0.05f, 0.8f).at(0f, topY - 0.025f, 0f), wood)
                val legH = topY - 0.05f
                val pos: List<Pair<Float, Float>> = when {
                    legs == 1 -> listOf(Pair(0f, 0f))
                    legs == 4 && !round -> listOf(Pair(-0.58f, -0.33f), Pair(0.58f, -0.33f), Pair(-0.58f, 0.33f), Pair(0.58f, 0.33f))
                    else -> ring(legs, if (round) 0.42f else 0.45f, TAU / legs / 2)
                }
                val legW = if (legs == 1) 0.12f else 0.06f
                addAll("pernas", pos.map { (x, z) -> P.box(legW, legH, legW).at(x, legH / 2, z) }, wood)
                if (legs == 1) add("pernas", P.cylinder(0.3f, 0.3f, 0.04f).at(0f, 0.02f, 0f), wood)
            }
        },
        ObjectDef("cadeira", "cadeira", listOf("cadeira", "cadeiras", "chair", "chairs"),
            parts = mapOf("assento" to listOf("assento", "seat"), "encosto" to listOf("encosto", "costas"), "pernas" to listOf("perna", "pernas", "pes")),
        ) { p ->
            val legs = p.count("pernas", 4, 3, 4)
            parts {
                add("assento", P.box(0.45f, 0.05f, 0.45f).at(0f, 0.45f, 0f), wood)
                add("encosto", P.box(0.45f, 0.5f, 0.05f).at(0f, 0.72f, -0.2f), wood)
                val pos = if (legs == 4) listOf(Pair(-0.19f, -0.19f), Pair(0.19f, -0.19f), Pair(-0.19f, 0.19f), Pair(0.19f, 0.19f))
                else ring(3, 0.2f)
                addAll("pernas", pos.map { (x, z) -> P.box(0.045f, 0.43f, 0.045f).at(x, 0.215f, z) }, wood)
            }
        },
        ObjectDef("banco", "banquinho", listOf("banco", "bancos", "banquinho", "banqueta", "stool"),
            parts = mapOf("assento" to listOf("assento"), "pernas" to listOf("perna", "pernas")),
        ) { p ->
            val legs = p.count("pernas", 3, 3, 6)
            parts {
                add("assento", P.cylinder(0.2f, 0.2f, 0.05f, 40).at(0f, 0.6f, 0f), wood)
                addAll("pernas", ring(legs, 0.13f).map { (x, z) -> P.cylinder(0.022f, 0.022f, 0.58f, 12).at(x, 0.29f, z) }, wood)
            }
        },
        ObjectDef("sofa", "sofá", listOf("sofa", "sofas", "couch", "poltrona"),
            parts = mapOf("assento" to listOf("assento", "almofada", "almofadas"), "encosto" to listOf("encosto"), "bracos" to listOf("braco", "bracos")),
        ) {
            val t = mat("tecido")
            parts {
                add("assento", P.box(1.8f, 0.4f, 0.8f).at(0f, 0.25f, 0f), t)
                add("encosto", P.box(1.8f, 0.5f, 0.2f).at(0f, 0.7f, -0.3f), t)
                addAll("bracos", listOf(P.box(0.2f, 0.6f, 0.8f).at(-1f, 0.3f, 0f), P.box(0.2f, 0.6f, 0.8f).at(1f, 0.3f, 0f)), t)
                addAll("pes", listOf(-0.9f, 0.9f).flatMap { x -> listOf(-0.3f, 0.3f).map { z -> P.cylinder(0.04f, 0.03f, 0.05f, 12).at(x, 0.025f, z) } }, solid(0xFF2B2B2B))
            }
        },
        ObjectDef("cama", "cama", listOf("cama", "camas", "bed"),
            parts = mapOf("estrutura" to listOf("estrutura", "base"), "colchao" to listOf("colchao"), "travesseiro" to listOf("travesseiro", "travesseiros"), "cabeceira" to listOf("cabeceira")),
            mainParts = setOf("estrutura", "cabeceira"),
        ) {
            parts {
                add("estrutura", P.box(1.4f, 0.3f, 2f).at(0f, 0.2f, 0f), wood)
                add("cabeceira", P.box(1.4f, 0.8f, 0.08f).at(0f, 0.45f, -1.02f), wood)
                add("colchao", P.box(1.32f, 0.2f, 1.9f).at(0f, 0.45f, 0.02f), solid(0xFFF2F2F2, rough = 0.9f))
                add("travesseiro", P.ellipsoid(0.25f, 0.07f, 0.15f, 20, 10).at(0f, 0.62f, -0.72f), solid(0xFFDDE6F5, rough = 0.9f))
            }
        },
        ObjectDef("estante", "estante", listOf("estante", "estantes", "prateleira", "prateleiras", "armario", "shelf", "bookshelf")) { p ->
            val shelves = p.count("prateleiras", 4, 2, 10)
            parts {
                val h = 0.4f * shelves + 0.1f
                val meshes = ArrayList<Mesh>()
                meshes += P.box(0.04f, h, 0.35f).at(-0.5f, h / 2, 0f)
                meshes += P.box(0.04f, h, 0.35f).at(0.5f, h / 2, 0f)
                meshes += P.box(1.04f, h, 0.02f).at(0f, h / 2, -0.17f)
                for (i in 0..shelves) meshes += P.box(0.96f, 0.03f, 0.33f).at(0f, 0.05f + i * 0.4f, 0f)
                add("estrutura", MeshOps.merge(meshes), wood)
            }
        },
        ObjectDef("casa", "casa", listOf("casa", "casas", "casinha", "house", "home", "chale"),
            parts = mapOf(
                "paredes" to listOf("parede", "paredes", "fachada"), "telhado" to listOf("telhado", "teto", "telha", "telhas", "roof"),
                "porta" to listOf("porta", "portas", "door"), "janelas" to listOf("janela", "janelas", "window", "windows"),
                "chamine" to listOf("chamine"),
            ),
            mainParts = setOf("paredes"),
        ) { p ->
            val floors = p.count("andares", 1, 1, 5)
            val wins = p.count("janelas", 2, 0, 8)
            val wallH = 1.3f * floors
            parts {
                add("paredes", P.box(2f, wallH, 1.6f).at(0f, wallH / 2, 0f), solid(0xFFEFE3C8, rough = 0.85f))
                add("telhado", P.roof(1.9f, 0.9f, 2.3f).rot('y', 90f).at(0f, wallH, 0f), solid(0xFFB4442E, rough = 0.7f))
                add("porta", P.box(0.42f, 0.82f, 0.05f).at(0f, 0.41f, 0.81f), solid(0xFF6B4226, rough = 0.6f))
                val wm = ArrayList<Mesh>()
                for (f in 0 until floors) {
                    val y = 0.85f + f * 1.3f
                    val perRow = if (f == 0) wins else maxOf(wins, 1) + 1
                    val xs = if (f == 0) {
                        (0 until perRow).map { i -> val side = if (i % 2 == 0) -1 else 1; side * (0.55f + (i / 2) * 0.35f) }
                    } else (0 until perRow).map { i -> -0.7f + 1.4f * i / maxOf(perRow - 1, 1) }
                    xs.filter { it in -0.9f..0.9f }.forEach { x -> wm += P.box(0.32f, 0.32f, 0.04f).at(x, y, 0.81f) }
                }
                addAll("janelas", wm, solid(0xFF9FD3F0, rough = 0.1f))
                add("chamine", P.box(0.22f, 0.6f, 0.22f).at(0.5f, wallH + 0.55f, -0.35f), mat("tijolo"))
            }
        },
        ObjectDef("predio", "prédio", listOf("predio", "predios", "edificio", "edificios", "arranha", "arranha-ceu", "building", "skyscraper"),
            parts = mapOf("paredes" to listOf("parede", "paredes", "fachada"), "janelas" to listOf("janela", "janelas", "vidros")),
            mainParts = setOf("paredes"),
        ) { p ->
            val floors = p.count("andares", 8, 1, 60)
            val fh = 0.32f
            val h = floors * fh + 0.1f
            parts {
                add("paredes", P.box(1f, h, 1f).at(0f, h / 2, 0f), mat("concreto"))
                val wm = ArrayList<Mesh>()
                for (f in 0 until floors) for (i in 0 until 3) {
                    val y = 0.2f + f * fh; val x = -0.3f + i * 0.3f
                    wm += P.box(0.2f, 0.18f, 0.02f).at(x, y, 0.505f)
                    wm += P.box(0.2f, 0.18f, 0.02f).at(x, y, -0.505f)
                    wm += P.box(0.02f, 0.18f, 0.2f).at(0.505f, y, x)
                    wm += P.box(0.02f, 0.18f, 0.2f).at(-0.505f, y, x)
                }
                addAll("janelas", wm, solid(0xFF7FB6E0, rough = 0.1f, metal = 0.3f))
                add("topo", P.box(1.06f, 0.06f, 1.06f).at(0f, h + 0.03f, 0f), solid(0xFF555555))
            }
        },
        ObjectDef("torre", "torre", listOf("torre", "torres", "tower", "farol"),
            parts = mapOf("corpo" to listOf("corpo", "parede", "paredes"), "telhado" to listOf("telhado", "teto", "ponta")),
            mainParts = setOf("corpo"),
        ) {
            parts {
                add("corpo", P.cylinder(0.45f, 0.4f, 2f, 40).at(0f, 1f, 0f), mat("pedra"))
                addAll("corpo", ring(8, 0.42f).map { (x, z) -> P.box(0.14f, 0.16f, 0.14f).at(x, 2.08f, z) }, mat("pedra"))
                add("telhado", P.cone(0.36f, 0.8f, 40).at(0f, 2.4f, 0f), solid(0xFF3E5BA8))
            }
        },
        ObjectDef("castelo", "castelo", listOf("castelo", "castelos", "castle", "fortaleza"),
            parts = mapOf("muralhas" to listOf("muralha", "muralhas", "parede", "paredes"), "torres" to listOf("torre", "torres"), "telhados" to listOf("telhado", "telhados", "teto"), "portao" to listOf("portao", "porta")),
            mainParts = setOf("muralhas", "torres"),
        ) { p ->
            val n = p.count("torres", 4, 3, 8)
            val stone = mat("pedra")
            parts {
                val corners = ring(n, 1.3f, TAU / n / 2)
                addAll("torres", corners.map { (x, z) -> P.cylinder(0.32f, 0.32f, 1.6f, 32).at(x, 0.8f, z) }, stone)
                addAll("telhados", corners.map { (x, z) -> P.cone(0.38f, 0.7f, 32).at(x, 1.95f, z) }, solid(0xFF3E5BA8))
                val walls = ArrayList<Mesh>()
                for (i in corners.indices) {
                    val (x0, z0) = corners[i]; val (x1, z1) = corners[(i + 1) % n]
                    val len = kotlin.math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0))
                    val ang = Math.toDegrees(kotlin.math.atan2((x1 - x0).toDouble(), (z1 - z0).toDouble())).toFloat()
                    walls += P.box(0.2f, 1f, len).rot('y', ang).at((x0 + x1) / 2, 0.5f, (z0 + z1) / 2)
                }
                addAll("muralhas", walls, stone)
                add("portao", P.box(0.5f, 0.6f, 0.1f).at(0f, 0.3f, corners.maxOf { it.second } * cos(PI.toFloat() / n) + 0.02f), solid(0xFF5A3A22))
            }
        },
        ObjectDef("arvore", "árvore", listOf("arvore", "arvores", "tree", "trees"),
            parts = mapOf("tronco" to listOf("tronco", "galho", "caule"), "copa" to listOf("copa", "folhas", "folhagem", "folha")),
            mainParts = setOf("copa"),
        ) {
            parts {
                add("tronco", P.cylinder(0.11f, 0.08f, 0.9f, 16).at(0f, 0.45f, 0f), mat("madeira").copy(color = col(0xFF6B4423)))
                val copa = MeshOps.merge(listOf(
                    P.sphere(0.5f, 24, 12).at(0f, 1.25f, 0f),
                    P.sphere(0.35f, 20, 10).at(0.3f, 1.05f, 0.1f),
                    P.sphere(0.35f, 20, 10).at(-0.28f, 1.1f, -0.1f),
                ))
                add("copa", copa, solid(0xFF3E9B45, rough = 0.85f))
            }
        },
        ObjectDef("pinheiro", "pinheiro", listOf("pinheiro", "pinheiros", "arvore de natal", "pine"),
            parts = mapOf("tronco" to listOf("tronco"), "copa" to listOf("copa", "folhas")),
            mainParts = setOf("copa"),
        ) {
            parts {
                add("tronco", P.cylinder(0.1f, 0.1f, 0.4f, 16).at(0f, 0.2f, 0f), solid(0xFF6B4423, rough = 0.9f))
                add("copa", MeshOps.merge(listOf(
                    P.cone(0.6f, 0.8f, 24).at(0f, 0.7f, 0f),
                    P.cone(0.47f, 0.7f, 24).at(0f, 1.1f, 0f),
                    P.cone(0.33f, 0.6f, 24).at(0f, 1.45f, 0f),
                )), solid(0xFF2E7D45, rough = 0.85f))
            }
        },
        ObjectDef("flor", "flor", listOf("flor", "flores", "flower", "margarida", "girassol"),
            parts = mapOf("petalas" to listOf("petala", "petalas"), "miolo" to listOf("miolo", "centro"), "caule" to listOf("caule", "talo", "haste")),
            mainParts = setOf("petalas"),
        ) { p ->
            val n = p.count("petalas", 8, 3, 24)
            parts {
                add("caule", P.cylinder(0.02f, 0.02f, 0.8f, 10).at(0f, 0.4f, 0f), solid(0xFF3E9B45))
                add("folha", P.ellipsoid(0.15f, 0.02f, 0.06f).rot('z', 30f).at(0.12f, 0.35f, 0f), solid(0xFF3E9B45))
                add("miolo", P.sphere(0.08f, 20, 10).sc(1f, 1f, 0.6f).at(0f, 0.85f, 0.02f), solid(0xFFF2C230))
                addAll("petalas", (0 until n).map { i ->
                    val a = i * 360f / n
                    P.ellipsoid(0.11f, 0.045f, 0.02f).at(0.15f, 0f, 0f).rot('z', a).at(0f, 0.85f, 0f)
                }, solid(0xFFF2F2F2, rough = 0.6f))
            }
        },
        ObjectDef("cogumelo", "cogumelo", listOf("cogumelo", "cogumelos", "mushroom"),
            parts = mapOf("chapeu" to listOf("chapeu", "topo", "cabeca"), "talo" to listOf("talo", "caule", "pe")),
            mainParts = setOf("chapeu"),
        ) {
            parts {
                add("talo", P.lathe(listOf(Pair(0.14f, 0f), Pair(0.11f, 0.2f), Pair(0.1f, 0.45f)), 24).at(0f, 0f, 0f), solid(0xFFF3EFE6, rough = 0.8f))
                val dome = (0..10).map { i -> val t = PI.toFloat() / 2 * i / 10; Pair(0.4f * cos(t), 0.4f + 0.3f * sin(t)) }
                add("chapeu", P.lathe(listOf(Pair(0.1f, 0.4f)) + dome, 32), solid(0xFFD8322F, rough = 0.4f))
                addAll("pintas", listOf(0f, 72f, 144f, 216f, 288f).map { a ->
                    P.sphere(0.05f, 12, 6).sc(1f, 0.4f, 1f).at(0f, 0.6f, 0.25f).rot('x', -12f).rot('y', a)
                } + listOf(P.sphere(0.06f, 12, 6).sc(1f, 0.4f, 1f).at(0f, 0.7f, 0f)), solid(0xFFF2F2F2))
            }
        },
        ObjectDef("vaso", "vaso", listOf("vaso", "vasos", "jarro", "jarra", "vase", "pot"),
            parts = mapOf("corpo" to listOf("corpo"), "planta" to listOf("planta", "plantas", "folhas")),
            mainParts = setOf("corpo"),
        ) {
            parts {
                val outer = listOf(Pair(0.18f, 0f), Pair(0.28f, 0.2f), Pair(0.3f, 0.35f), Pair(0.2f, 0.6f), Pair(0.17f, 0.72f), Pair(0.22f, 0.8f))
                val inner = listOf(Pair(0.19f, 0.8f), Pair(0.14f, 0.72f), Pair(0.0f, 0.7f))
                add("corpo", P.lathe(outer + inner, 40, capBottom = true, capTop = false, creaseDeg = 50f), mat("ceramica").copy(color = col(0xFFC86B45)))
            }
        },
        ObjectDef("copo", "copo", listOf("copo", "copos", "taca de vinho", "cup", "glass")) {
            parts {
                val prof = listOf(Pair(0.16f, 0f), Pair(0.2f, 0.45f), Pair(0.185f, 0.45f), Pair(0.145f, 0.03f), Pair(0f, 0.03f))
                add("corpo", P.lathe(prof, 40, capBottom = true, capTop = false, creaseDeg = 50f), mat("vidro"))
            }
        },
        ObjectDef("caneca", "caneca", listOf("caneca", "canecas", "xicara", "xicaras", "mug", "chavena"),
            parts = mapOf("corpo" to listOf("corpo"), "alca" to listOf("alca", "asa", "cabo")),
        ) {
            val m = mat("ceramica")
            parts {
                val prof = listOf(Pair(0.18f, 0f), Pair(0.18f, 0.4f), Pair(0.16f, 0.4f), Pair(0.16f, 0.03f), Pair(0f, 0.03f))
                add("corpo", P.lathe(prof, 40, capBottom = true, capTop = false, creaseDeg = 50f), m)
                add("alca", P.torus(0.11f, 0.025f, 24, 10).rot('x', 90f).rot('y', 90f).at(0.2f, 0.22f, 0f), m)
            }
        },
        ObjectDef("garrafa", "garrafa", listOf("garrafa", "garrafas", "bottle"),
            parts = mapOf("corpo" to listOf("corpo"), "tampa" to listOf("tampa", "rolha")),
            mainParts = setOf("corpo"),
        ) {
            parts {
                val prof = listOf(Pair(0f, 0f), Pair(0.15f, 0f), Pair(0.16f, 0.05f), Pair(0.16f, 0.55f), Pair(0.12f, 0.7f), Pair(0.06f, 0.8f), Pair(0.055f, 0.95f))
                add("corpo", P.lathe(prof, 40, capTop = true), mat("vidro").copy(color = col(0xFF5FAE6E)))
                add("tampa", P.cylinder(0.065f, 0.065f, 0.06f, 24).at(0f, 0.98f, 0f), solid(0xFFD8322F))
            }
        },
        ObjectDef("luminaria", "luminária", listOf("luminaria", "luminarias", "abajur", "lampada", "lamp", "lampiao", "poste"),
            parts = mapOf("base" to listOf("base", "pe"), "haste" to listOf("haste"), "cupula" to listOf("cupula", "lampada", "cobertura")),
            mainParts = setOf("cupula"),
        ) {
            val metal = mat("metal")
            parts {
                add("base", P.cylinder(0.2f, 0.22f, 0.05f, 40).at(0f, 0.025f, 0f), metal)
                add("haste", P.cylinder(0.02f, 0.02f, 0.9f, 12).at(0f, 0.5f, 0f), metal)
                add("cupula", P.lathe(listOf(Pair(0.32f, 0.8f), Pair(0.17f, 1.15f)), 40, capBottom = false, capTop = false), mat("tecido").copy(color = col(0xFFF1E6C8)))
                add("bulbo", P.sphere(0.07f, 16, 8).at(0f, 0.95f, 0f), solid(0xFFFFF5C0, rough = 0.1f))
            }
        },
        ObjectDef("carro", "carro", listOf("carro", "carros", "carrinho", "automovel", "car", "veiculo"),
            parts = mapOf(
                "carroceria" to listOf("carroceria", "lataria", "corpo"), "rodas" to listOf("roda", "rodas", "pneu", "pneus", "wheels"),
                "janelas" to listOf("janela", "janelas", "vidro", "vidros", "parabrisa"), "farois" to listOf("farol", "farois"),
            ),
            mainParts = setOf("carroceria"),
        ) { p ->
            val wheels = p.count("rodas", 4, 3, 8)
            val body = solid(0xFFD8322F, rough = 0.3f, metal = 0.4f)
            parts {
                add("carroceria", MeshOps.merge(listOf(
                    P.box(1.8f, 0.4f, 0.85f).at(0f, 0.38f, 0f),
                    P.box(1f, 0.36f, 0.75f).at(-0.1f, 0.74f, 0f),
                )), body)
                addAll("janelas", listOf(
                    P.box(0.02f, 0.26f, 0.62f).at(0.41f, 0.74f, 0f),
                    P.box(0.02f, 0.26f, 0.62f).at(-0.61f, 0.74f, 0f),
                    P.box(0.8f, 0.24f, 0.02f).at(-0.1f, 0.74f, 0.38f),
                    P.box(0.8f, 0.24f, 0.02f).at(-0.1f, 0.74f, -0.38f),
                ), solid(0xFF1F2E3D, rough = 0.1f, metal = 0.2f))
                val perSide = (wheels + 1) / 2
                val xs = (0 until perSide).map { i -> -0.6f + 1.2f * i / maxOf(perSide - 1, 1) }
                addAll("rodas", xs.flatMap { x -> listOf(0.42f, -0.42f).map { z -> P.cylinder(0.19f, 0.19f, 0.14f, 28).rot('x', 90f).at(x, 0.19f, z) } }.take(wheels), mat("borracha"))
                addAll("farois", listOf(0.25f, -0.25f).map { z -> P.sphere(0.06f, 12, 6).at(0.9f, 0.42f, z) }, solid(0xFFFFF5C0, rough = 0.1f))
            }
        },
        ObjectDef("foguete", "foguete", listOf("foguete", "foguetes", "rocket", "missil", "nave"),
            parts = mapOf("corpo" to listOf("corpo", "fuselagem"), "ponta" to listOf("ponta", "nariz", "bico"), "aletas" to listOf("aleta", "aletas", "asas"), "janela" to listOf("janela", "escotilha")),
            mainParts = setOf("corpo"),
        ) { p ->
            val fins = p.count("aletas", 4, 2, 8)
            val red = solid(0xFFD8322F, rough = 0.35f)
            parts {
                add("corpo", P.lathe(listOf(Pair(0.22f, 0.2f), Pair(0.26f, 0.6f), Pair(0.26f, 1.3f), Pair(0.22f, 1.5f)), 40), solid(0xFFF2F2F2, rough = 0.3f, metal = 0.2f))
                add("ponta", P.lathe((0..8).map { i -> val t = i / 8f; Pair(0.22f * kotlin.math.sqrt(1 - t), 1.5f + 0.5f * t) }, 40), red)
                addAll("aletas", (0 until fins).map { i ->
                    P.extrude(listOf(Pair(0f, 0f), Pair(0.28f, -0.1f), Pair(0.28f, 0.15f), Pair(0f, 0.55f)), 0.04f)
                        .at(0.2f, 0f, 0f).rot('y', 45f + i * 360f / fins).at(0f, 0.2f, 0f)
                }, red)
                add("janela", P.torus(0.09f, 0.025f, 24, 8).rot('x', 90f).at(0f, 1.15f, 0.26f), solid(0xFF888888, metal = 1f, rough = 0.3f))
                add("vidro", P.cylinder(0.09f, 0.09f, 0.02f, 24).rot('x', 90f).at(0f, 1.15f, 0.255f), solid(0xFF7FC8F0, rough = 0.05f))
                add("motor", P.cylinder(0.12f, 0.18f, 0.2f, 24).at(0f, 0.1f, 0f), solid(0xFF444444, metal = 1f, rough = 0.4f))
            }
        },
        ObjectDef("aviao", "avião", listOf("aviao", "avioes", "aeronave", "jato", "airplane", "plane"),
            parts = mapOf("fuselagem" to listOf("fuselagem", "corpo"), "asas" to listOf("asa", "asas"), "cauda" to listOf("cauda", "leme")),
            mainParts = setOf("fuselagem"),
        ) {
            val white = solid(0xFFF2F2F2, rough = 0.3f, metal = 0.3f)
            parts {
                add("fuselagem", P.capsule(0.18f, 2f, 32).rot('z', 90f).at(0f, 0.5f, 0f), white)
                add("asas", P.extrude(listOf(Pair(-0.25f, 0f), Pair(0.15f, 0f), Pair(-0.05f, 1.1f), Pair(-0.3f, 1.1f)), 0.04f)
                    .rot('x', 90f).let { MeshOps.merge(listOf(it, MeshOps.scale(it, 1f, 1f, -1f))) }.at(0.1f, 0.45f, 0f), solid(0xFF2F6FD8, rough = 0.35f))
                add("cauda", MeshOps.merge(listOf(
                    P.extrude(listOf(Pair(-0.95f, 0f), Pair(-0.7f, 0f), Pair(-0.9f, 0.4f), Pair(-1.0f, 0.4f)), 0.03f).at(0f, 0.6f, 0f),
                    P.extrude(listOf(Pair(-0.1f, 0f), Pair(0.08f, 0f), Pair(-0.02f, 0.4f), Pair(-0.12f, 0.4f)), 0.03f)
                        .rot('x', 90f).let { MeshOps.merge(listOf(it, MeshOps.scale(it, 1f, 1f, -1f))) }.at(-0.85f, 0.55f, 0f),
                )), solid(0xFF2F6FD8, rough = 0.35f))
            }
        },
        ObjectDef("barco", "barco", listOf("barco", "barcos", "navio", "veleiro", "boat", "ship", "canoa"),
            parts = mapOf("casco" to listOf("casco"), "vela" to listOf("vela", "velas"), "mastro" to listOf("mastro")),
            mainParts = setOf("casco"),
        ) {
            parts {
                val hullTop = listOf(Pair(-0.9f, -0.3f), Pair(0.5f, -0.3f), Pair(1f, 0f), Pair(0.5f, 0.3f), Pair(-0.9f, 0.3f))
                val hull = P.extrude(hullTop, 0.3f).rot('x', -90f)
                add("casco", MeshOps.merge(listOf(hull.at(0f, 0.15f, 0f), MeshOps.scale(hull, 0.8f, 1f, 0.75f).at(0f, -0.0f, 0f))), mat("madeira"))
                add("mastro", P.cylinder(0.03f, 0.03f, 1.4f, 12).at(0f, 0.95f, 0f), mat("madeira"))
                add("vela", P.extrude(listOf(Pair(0.04f, 0.4f), Pair(0.75f, 0.45f), Pair(0.04f, 1.55f)), 0.015f), mat("tecido").copy(color = col(0xFFF2F2F2)))
            }
        },
        ObjectDef("robo", "robô", listOf("robo", "robos", "robot", "androide", "mecha"),
            parts = mapOf("corpo" to listOf("corpo", "tronco", "peito"), "cabeca" to listOf("cabeca"), "bracos" to listOf("braco", "bracos"), "pernas" to listOf("perna", "pernas"), "olhos" to listOf("olho", "olhos")),
            mainParts = setOf("corpo", "cabeca", "bracos", "pernas"),
        ) {
            val m = mat("metal")
            parts {
                add("pernas", MeshOps.merge(listOf(P.box(0.18f, 0.55f, 0.2f).at(-0.15f, 0.275f, 0f), P.box(0.18f, 0.55f, 0.2f).at(0.15f, 0.275f, 0f))), m)
                add("corpo", P.box(0.62f, 0.7f, 0.38f).at(0f, 0.9f, 0f), m)
                add("bracos", MeshOps.merge(listOf(P.box(0.14f, 0.6f, 0.14f).at(-0.42f, 0.9f, 0f), P.box(0.14f, 0.6f, 0.14f).at(0.42f, 0.9f, 0f))), m)
                add("cabeca", P.box(0.4f, 0.32f, 0.34f).at(0f, 1.45f, 0f), m)
                add("olhos", MeshOps.merge(listOf(P.sphere(0.05f, 12, 6).at(-0.09f, 1.48f, 0.17f), P.sphere(0.05f, 12, 6).at(0.09f, 1.48f, 0.17f))), solid(0xFF39C8FF, rough = 0.1f))
                add("antena", MeshOps.merge(listOf(P.cylinder(0.012f, 0.012f, 0.2f, 8).at(0f, 1.7f, 0f), P.sphere(0.04f, 12, 6).at(0f, 1.82f, 0f))), solid(0xFFD8322F))
                add("painel", P.box(0.3f, 0.2f, 0.02f).at(0f, 1f, 0.2f), solid(0xFF2B2B2B, rough = 0.2f))
            }
        },
        ObjectDef("boneco", "boneco", listOf("boneco", "bonecos", "pessoa", "pessoas", "humano", "personagem", "homem", "mulher", "menino", "menina", "person", "character", "figura"),
            parts = mapOf("corpo" to listOf("corpo", "camisa", "roupa", "tronco"), "cabeca" to listOf("cabeca", "rosto", "pele"), "bracos" to listOf("braco", "bracos"), "pernas" to listOf("perna", "pernas", "calca"), "olhos" to listOf("olho", "olhos")),
            mainParts = setOf("corpo"),
        ) {
            val skin = solid(0xFFE8B98F, rough = 0.6f)
            parts {
                add("pernas", MeshOps.merge(listOf(P.capsule(0.09f, 0.75f, 16).at(-0.11f, 0.375f, 0f), P.capsule(0.09f, 0.75f, 16).at(0.11f, 0.375f, 0f))), solid(0xFF2F4A7A, rough = 0.8f))
                add("corpo", P.capsule(0.22f, 0.7f, 24).sc(1f, 1f, 0.7f).at(0f, 1.05f, 0f), solid(0xFFD8322F, rough = 0.8f))
                add("bracos", MeshOps.merge(listOf(
                    P.capsule(0.07f, 0.65f, 16).rot('z', -12f).at(-0.32f, 1.02f, 0f),
                    P.capsule(0.07f, 0.65f, 16).rot('z', 12f).at(0.32f, 1.02f, 0f),
                )), skin)
                add("cabeca", P.sphere(0.17f, 24, 12).at(0f, 1.6f, 0f), skin)
                add("olhos", MeshOps.merge(listOf(P.sphere(0.025f, 10, 5).at(-0.06f, 1.63f, 0.15f), P.sphere(0.025f, 10, 5).at(0.06f, 1.63f, 0.15f))), solid(0xFF1E1E1E))
            }
        },
        ObjectDef("espada", "espada", listOf("espada", "espadas", "sword", "katana", "adaga"),
            parts = mapOf("lamina" to listOf("lamina", "blade"), "cabo" to listOf("cabo", "empunhadura", "punho"), "guarda" to listOf("guarda")),
            mainParts = setOf("lamina"),
        ) {
            parts {
                add("lamina", P.extrude(listOf(Pair(-0.05f, 0f), Pair(0.05f, 0f), Pair(0.05f, 0.9f), Pair(0f, 1.02f), Pair(-0.05f, 0.9f)), 0.02f).at(0f, 0.38f, 0f), mat("metal").copy(roughness = 0.15f))
                add("guarda", P.box(0.36f, 0.05f, 0.06f).at(0f, 0.36f, 0f), mat("ouro"))
                add("cabo", P.cylinder(0.03f, 0.03f, 0.26f, 16).at(0f, 0.21f, 0f), mat("tecido").copy(color = col(0xFF5A3A22)))
                add("pomo", P.sphere(0.05f, 16, 8).at(0f, 0.06f, 0f), mat("ouro"))
            }
        },
        ObjectDef("trofeu", "troféu", listOf("trofeu", "trofeus", "taca", "tacas", "trophy", "copa do mundo"),
            parts = mapOf("taca" to listOf("taca", "copa"), "base" to listOf("base", "pedestal"), "alcas" to listOf("alca", "alcas", "asas")),
            mainParts = setOf("taca", "alcas"),
        ) {
            val gold = mat("ouro")
            parts {
                add("base", MeshOps.merge(listOf(P.box(0.4f, 0.12f, 0.4f).at(0f, 0.06f, 0f), P.box(0.3f, 0.08f, 0.3f).at(0f, 0.16f, 0f))), mat("marmore").copy(color = col(0xFF2B2B2B)))
                add("haste", P.lathe(listOf(Pair(0.1f, 0.2f), Pair(0.04f, 0.3f), Pair(0.04f, 0.5f), Pair(0.06f, 0.55f)), 32), gold)
                add("taca", P.lathe(listOf(Pair(0.06f, 0.55f), Pair(0.2f, 0.65f), Pair(0.26f, 0.85f), Pair(0.27f, 1f), Pair(0.25f, 1f), Pair(0.24f, 0.86f), Pair(0f, 0.68f)), 40, capTop = false), gold)
                add("alcas", MeshOps.merge(listOf(
                    P.torus(0.09f, 0.02f, 24, 8).rot('x', 90f).at(0.28f, 0.85f, 0f),
                    P.torus(0.09f, 0.02f, 24, 8).rot('x', 90f).at(-0.28f, 0.85f, 0f),
                )), gold)
            }
        },
        ObjectDef("escada", "escada", listOf("escada", "escadas", "degrau", "stairs", "staircase"),
            parts = mapOf("degraus" to listOf("degrau", "degraus")),
        ) { p ->
            val steps = p.count("degraus", 8, 2, 40)
            parts {
                addAll("degraus", (0 until steps).map { i ->
                    val h = 0.18f * (i + 1)
                    P.box(1f, h, 0.28f).at(0f, h / 2, -i * 0.28f)
                }, mat("concreto"))
            }
        },
        ObjectDef("coluna", "coluna", listOf("coluna", "colunas", "pilar", "pilares", "column", "pillar"),
            parts = mapOf("fuste" to listOf("fuste", "corpo"), "capitel" to listOf("capitel", "topo")),
        ) {
            val m = mat("marmore")
            parts {
                add("base", P.box(0.6f, 0.15f, 0.6f).at(0f, 0.075f, 0f), m)
                add("fuste", P.cylinder(0.22f, 0.2f, 1.8f, 24).at(0f, 1.05f, 0f), m)
                add("capitel", P.box(0.6f, 0.15f, 0.6f).at(0f, 2.025f, 0f), m)
            }
        },
        ObjectDef("planeta", "planeta", listOf("planeta", "planetas", "saturno", "planet", "lua", "terra", "mundo"),
            parts = mapOf("corpo" to listOf("corpo", "planeta"), "anel" to listOf("anel", "aneis", "ring")),
            mainParts = setOf("corpo"),
        ) {
            parts {
                add("corpo", P.sphere(0.5f, 40, 20).at(0f, 0.75f, 0f), Material("Planeta", col(0xFFE0A85A), 0f, 0.8f, ProceduralTextures.marble(col(0xFFE0A85A))))
                add("anel", P.torus(0.75f, 0.12f, 64, 12).sc(1f, 0.08f, 1f).rot('x', 20f).at(0f, 0.75f, 0f), solid(0xFFD9C3A0, rough = 0.6f))
            }
        },
        ObjectDef("ovni", "disco voador", listOf("ovni", "ufo", "disco", "discos", "disco voador"),
            parts = mapOf("corpo" to listOf("corpo", "disco"), "cupula" to listOf("cupula", "vidro"), "luzes" to listOf("luz", "luzes")),
            mainParts = setOf("corpo"),
        ) {
            parts {
                add("corpo", P.ellipsoid(0.8f, 0.16f, 0.8f, 40, 16).at(0f, 0.5f, 0f), mat("metal"))
                add("cupula", P.sphere(0.3f, 32, 16).sc(1f, 0.8f, 1f).at(0f, 0.62f, 0f), solid(0xFF7FE0C8, rough = 0.05f))
                addAll("luzes", ring(10, 0.62f).map { (x, z) -> P.sphere(0.04f, 10, 5).at(x, 0.48f, z) }, solid(0xFFFFE15A, rough = 0.1f))
            }
        },
        ObjectDef("dado", "dado", listOf("dado", "dados", "dice", "die")) {
            parts {
                add("corpo", P.box(0.6f, 0.6f, 0.6f).at(0f, 0.3f, 0f), solid(0xFFF2F2F2, rough = 0.3f))
                val s = 0.3f; val o = 0.15f
                val pip = { x: Float, y: Float, z: Float -> P.sphere(0.045f, 10, 6).at(x, y + 0.3f, z) }
                val pips = ArrayList<Mesh>()
                pips += pip(0f, 0f, s)                                                          // 1 (frente)
                pips += listOf(pip(-o, o, -s), pip(o, -o, -s))                                   // 2 (trás)
                pips += listOf(pip(s, o, -o), pip(s, 0f, 0f), pip(s, -o, o))                     // 3 (direita)
                pips += listOf(pip(-s, o, o), pip(-s, o, -o), pip(-s, -o, o), pip(-s, -o, -o))   // 4 (esquerda)
                pips += listOf(pip(o, s, o), pip(-o, s, o), pip(0f, s, 0f), pip(o, s, -o), pip(-o, s, -o)) // 5 (topo)
                pips += listOf(-o, 0f, o).flatMap { x -> listOf(pip(x, -s, o), pip(x, -s, -o)) } // 6 (base)
                add("pontos", MeshOps.merge(pips), solid(0xFF1E1E1E))
            }
        },
    )

    private val byWord: Map<String, ObjectDef> = buildMap {
        objects.forEach { def -> def.words.forEach { w -> putIfAbsent(w, def) } }
    }

    fun byId(id: String): ObjectDef? = objects.firstOrNull { it.id == id }

    /** Procura por expressão de 1..3 palavras. */
    fun lookup(phrase: String): ObjectDef? = byWord[phrase]

    val maxWords = 3

    /** Parâmetros numéricos aceitos por objeto (ex.: "mesa com 6 pernas"). */
    val countParams: Map<String, Set<String>> = mapOf(
        "mesa" to setOf("pernas"), "cadeira" to setOf("pernas"), "banco" to setOf("pernas"),
        "cilindro" to setOf("lados"), "cone" to setOf("lados"), "piramide" to setOf("lados"), "prisma" to setOf("lados"),
        "estrela" to setOf("pontas"), "estante" to setOf("prateleiras"), "casa" to setOf("andares", "janelas"),
        "predio" to setOf("andares"), "castelo" to setOf("torres"), "flor" to setOf("petalas"), "carro" to setOf("rodas"),
        "foguete" to setOf("aletas"), "escada" to setOf("degraus"),
    )

    /** Sinônimos dos parâmetros numéricos. */
    val paramWords: Map<String, String> = mapOf(
        "perna" to "pernas", "pernas" to "pernas", "pes" to "pernas", "pe" to "pernas", "legs" to "pernas",
        "lado" to "lados", "lados" to "lados", "faces" to "lados", "sides" to "lados",
        "ponta" to "pontas", "pontas" to "pontas", "bicos" to "pontas", "points" to "pontas",
        "andar" to "andares", "andares" to "andares", "pisos" to "andares", "pavimentos" to "andares", "floors" to "andares",
        "janela" to "janelas", "janelas" to "janelas",
        "prateleira" to "prateleiras", "prateleiras" to "prateleiras", "shelves" to "prateleiras",
        "torre" to "torres", "torres" to "torres",
        "petala" to "petalas", "petalas" to "petalas",
        "roda" to "rodas", "rodas" to "rodas", "wheels" to "rodas",
        "aleta" to "aletas", "aletas" to "aletas",
        "degrau" to "degraus", "degraus" to "degraus", "steps" to "degraus",
    )
}

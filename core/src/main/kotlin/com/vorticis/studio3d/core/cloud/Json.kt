package com.vorticis.studio3d.core.cloud

/** JSON mínimo (sem dependências): objetos viram Map, listas viram List, números viram Double. */
object Json {
    fun parse(s: String): Any? = Parser(s).run { val v = value(); ws(); v }

    fun stringify(v: Any?): String = buildString { write(this, v) }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> { sb.append('"'); for (c in v) when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }; sb.append('"') }
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> { val d = v.toDouble(); if (d == Math.rint(d) && !d.isInfinite()) sb.append(d.toLong()) else sb.append(d) }
            is Map<*, *> -> { sb.append('{'); var first = true; for ((k, x) in v) { if (!first) sb.append(','); first = false; write(sb, k.toString()); sb.append(':'); write(sb, x) }; sb.append('}') }
            is List<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }; sb.append(']') }
            is Array<*> -> write(sb, v.toList())
            else -> write(sb, v.toString())
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) error("JSON vazio")
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("JSON inválido em $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) { ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws(); if (s[i] == ',') { i++; continue }; i++; return m }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l += value(); ws(); if (s[i] == ',') { i++; continue }; i++; return l }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> { val e = s[i++]; when (e) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    } }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()

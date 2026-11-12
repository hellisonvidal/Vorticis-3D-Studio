package com.vorticis.studio3d.core.export

import com.vorticis.studio3d.core.model.Texture
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Codificador PNG puro (RGBA 8 bits), funciona no Android e na JVM. */
object PngEncoder {
    fun encode(t: Texture): ByteArray {
        val raw = ByteArray((t.width * 4 + 1) * t.height)
        var o = 0
        for (y in 0 until t.height) {
            raw[o++] = 0
            for (x in 0 until t.width) {
                val c = t.pixels[y * t.width + x]
                raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte(); raw[o++] = (c ushr 24).toByte()
            }
        }
        val def = Deflater(6)
        def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!def.finished()) { val n = def.deflate(buf); z.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(t.width).putInt(t.height).put(8).put(6).put(0).put(0).put(0).array()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val tb = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(tb); out.write(data)
        val crc = CRC32(); crc.update(tb); crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}

/**
 * Escritor ZIP mínimo. Suporta entradas armazenadas (sem compressão) com
 * alinhamento de dados (exigido pelo USDZ: 64 bytes) e entradas comprimidas.
 */
class ZipWriter(private val align: Int = 1) {
    private class Entry(val name: ByteArray, val crc: Long, val csize: Int, val usize: Int, val method: Int, val offset: Int)
    private val out = ByteArrayOutputStream()
    private val entries = ArrayList<Entry>()

    fun add(name: String, data: ByteArray, compress: Boolean = false) {
        val nameB = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val payload = if (compress) deflate(data) else data
        val method = if (compress) 8 else 0
        val offset = out.size()
        val headerLen = 30 + nameB.size
        var extra = 0
        if (align > 1 && !compress) {
            val dataStart = offset + headerLen
            extra = (align - dataStart % align) % align
            if (extra in 1..3) extra += align // campo extra precisa de 4 bytes de cabeçalho
        }
        val h = ByteBuffer.allocate(headerLen + extra).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(0x04034b50).putShort(20).putShort(0x0800).putShort(method.toShort())
            .putShort(0).putShort(0x21) // hora/data fixas (1980-01-01)
            .putInt(crc.toInt()).putInt(payload.size).putInt(data.size)
            .putShort(nameB.size.toShort()).putShort(extra.toShort()).put(nameB)
        if (extra > 0) {
            h.putShort(0x1986.toShort()).putShort((extra - 4).toShort())
            repeat(extra - 4) { h.put(0) }
        }
        out.write(h.array()); out.write(payload)
        entries += Entry(nameB, crc, payload.size, data.size, method, offset)
    }

    fun finish(): ByteArray {
        val cdStart = out.size()
        for (e in entries) {
            val c = ByteBuffer.allocate(46 + e.name.size).order(ByteOrder.LITTLE_ENDIAN)
            c.putInt(0x02014b50).putShort(20).putShort(20).putShort(0x0800).putShort(e.method.toShort())
                .putShort(0).putShort(0x21).putInt(e.crc.toInt()).putInt(e.csize).putInt(e.usize)
                .putShort(e.name.size.toShort()).putShort(0).putShort(0).putShort(0).putShort(0)
                .putInt(0).putInt(e.offset).put(e.name)
            out.write(c.array())
        }
        val cdSize = out.size() - cdStart
        val end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        end.putInt(0x06054b50).putShort(0).putShort(0).putShort(entries.size.toShort()).putShort(entries.size.toShort())
            .putInt(cdSize).putInt(cdStart).putShort(0)
        out.write(end.array())
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(6, true)
        d.setInput(data); d.finish()
        val o = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!d.finished()) { val n = d.deflate(buf); o.write(buf, 0, n) }
        d.end()
        return o.toByteArray()
    }
}

/** Buffer binário little-endian crescente. */
class LeBuffer(initial: Int = 1024) {
    private var buf: ByteBuffer = ByteBuffer.allocate(initial).order(ByteOrder.LITTLE_ENDIAN)
    val size: Int get() = buf.position()

    private fun ensure(n: Int) {
        if (buf.remaining() < n) {
            val nb = ByteBuffer.allocate(maxOf(buf.capacity() * 2, buf.position() + n + 1024)).order(ByteOrder.LITTLE_ENDIAN)
            buf.flip(); nb.put(buf); buf = nb
        }
    }

    fun u8(v: Int) = apply { ensure(1); buf.put(v.toByte()) }
    fun i16(v: Int) = apply { ensure(2); buf.putShort(v.toShort()) }
    fun i32(v: Int) = apply { ensure(4); buf.putInt(v) }
    fun i64(v: Long) = apply { ensure(8); buf.putLong(v) }
    fun f32(v: Float) = apply { ensure(4); buf.putFloat(v) }
    fun f64(v: Double) = apply { ensure(8); buf.putDouble(v) }
    fun bytes(b: ByteArray) = apply { ensure(b.size); buf.put(b) }
    fun setI32(pos: Int, v: Int) { buf.putInt(pos, v) }
    fun pad(align: Int, value: Int = 0) = apply { while (size % align != 0) u8(value) }
    fun toByteArray(): ByteArray = buf.array().copyOf(buf.position())
}

/** Formata número para arquivos texto (ponto decimal, sem notação científica longa). */
internal fun f(v: Float): String {
    if (v.isNaN() || v.isInfinite()) return "0"
    val r = Math.round(v * 1_000_000.0) / 1_000_000.0
    val s = if (r == Math.rint(r)) r.toLong().toString() else r.toBigDecimal().stripTrailingZeros().toPlainString()
    return if (s == "-0") "0" else s
}

internal fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun safeName(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[^A-Za-z0-9_]"), "_").let { if (it.isEmpty() || it[0].isDigit()) "_$it" else it }

package com.vorticis.studio3d.core.photo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mapa de profundidade relativa (maior = mais perto da câmera), como sai dos
 * modelos Depth Anything / MiDaS. Pode ter resolução diferente da foto:
 * a amostragem usa coordenadas normalizadas.
 */
class DepthMap(val width: Int, val height: Int, val data: FloatArray) {
    /** Amostra bilinear em coordenadas normalizadas (0..1). */
    fun sample(nx: Float, ny: Float): Float {
        val x = (nx * width - 0.5f).coerceIn(0f, width - 1f)
        val y = (ny * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val fx = x - x0; val fy = y - y0
        val a = data[y0 * width + x0]; val b = data[y0 * width + x1]
        val c = data[y1 * width + x0]; val d = data[y1 * width + x1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}

/**
 * Pré e pós-processamento do Depth Anything V2 (ONNX), compartilhado pelo app
 * Android e pelos testes: imagem RGB normalizada (ImageNet) em NCHW, com lados
 * múltiplos de 14 (tamanho do "patch" do ViT).
 */
object DepthPrep {
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Tamanho de entrada (largura, altura) preservando o formato da foto. */
    fun inputSize(w: Int, h: Int, longSide: Int = 392): Pair<Int, Int> {
        val s = longSide.toFloat() / max(w, h)
        val iw = max(14, (w * s / 14f).roundToInt() * 14)
        val ih = max(14, (h * s / 14f).roundToInt() * 14)
        return iw to ih
    }

    /** Converte pixels ARGB em tensor NCHW float (redimensiona por amostragem bilinear simples). */
    fun toTensor(pixels: IntArray, w: Int, h: Int, iw: Int, ih: Int): FloatArray {
        val out = FloatArray(3 * iw * ih)
        val plane = iw * ih
        for (y in 0 until ih) for (x in 0 until iw) {
            val sx = ((x + 0.5f) * w / iw).toInt().coerceIn(0, w - 1)
            val sy = ((y + 0.5f) * h / ih).toInt().coerceIn(0, h - 1)
            val c = pixels[sy * w + sx]
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val i = y * iw + x
            out[i] = (r - MEAN[0]) / STD[0]
            out[plane + i] = (g - MEAN[1]) / STD[1]
            out[2 * plane + i] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    /** Saída [1,H,W] ou [1,1,H,W] -> DepthMap (descarta dimensões unitárias). */
    fun fromOutput(values: FloatArray, shape: LongArray): DepthMap {
        val dims = shape.filter { it > 1 }.map { it.toInt() }
        require(dims.size == 2) { "Formato inesperado da saída de profundidade: ${shape.toList()}" }
        return DepthMap(dims[1], dims[0], values)
    }
}

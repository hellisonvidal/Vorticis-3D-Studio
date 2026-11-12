package com.vorticis.studio3d.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.vorticis.studio3d.core.photo.DepthMap
import com.vorticis.studio3d.core.photo.DepthPrep
import java.nio.FloatBuffer

/**
 * IA de profundidade monocular (Depth Anything V2 Small, quantizada, licença Apache-2.0)
 * rodando no aparelho com ONNX Runtime. O modelo vem embutido no APK pelo GitHub Actions;
 * se não estiver presente, o app segue sem ele.
 */
object DepthEstimator {
    const val ASSET = "models/depth_anything_v2_small_q.onnx"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun isAvailable(context: Context): Boolean =
        try { context.assets.list("models")?.contains("depth_anything_v2_small_q.onnx") == true } catch (_: Exception) { false }

    @Synchronized
    private fun session(context: Context): OrtSession {
        session?.let { return it }
        val e = OrtEnvironment.getEnvironment().also { env = it }
        val bytes = context.assets.open(ASSET).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        }
        return e.createSession(bytes, opts).also { session = it }
    }

    /** Estima a profundidade relativa (maior = mais perto). Retorna null se o modelo não existir ou falhar. */
    @Synchronized
    fun estimate(context: Context, pixels: IntArray, w: Int, h: Int): DepthMap? {
        if (!isAvailable(context)) return null
        return try {
            val s = session(context)
            val e = env!!
            val (iw, ih) = DepthPrep.inputSize(w, h)
            val tensor = DepthPrep.toTensor(pixels, w, h, iw, ih)
            OnnxTensor.createTensor(e, FloatBuffer.wrap(tensor), longArrayOf(1, 3, ih.toLong(), iw.toLong())).use { input ->
                s.run(mapOf(s.inputNames.first() to input)).use { res ->
                    val out = res.get(0) as OnnxTensor
                    val fb = out.floatBuffer
                    val arr = FloatArray(fb.remaining()); fb.get(arr)
                    DepthPrep.fromOutput(arr, out.info.shape)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}

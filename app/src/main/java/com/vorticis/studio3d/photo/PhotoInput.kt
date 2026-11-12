package com.vorticis.studio3d.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.vorticis.studio3d.core.mind.TextRasterizer
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.Segmentation
import android.media.MediaMetadataRetriever
import android.os.Build
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/** Foto carregada + máscara do objeto. */
class PhotoInput(val pixels: IntArray, val width: Int, val height: Int, val mask: Mask, val segmentedBy: String)

object PhotoLoader {

    /** Decodifica a imagem respeitando a rotação EXIF e limitando o maior lado. */
    fun load(context: Context, uri: Uri, maxSide: Int = 720): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Não foi possível ler a imagem")
        val rotation = try {
            context.contentResolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        } catch (_: Exception) { 0f }
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun segmenterClient() = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
    )

    /** O modelo de recorte do ML Kit já está no aparelho? */
    suspend fun isModelReady(context: Context): Boolean = try {
        val c = segmenterClient()
        try { ModuleInstall.getClient(context).areModulesAvailable(c).await().areModulesAvailable() } finally { c.close() }
    } catch (_: Throwable) { false }

    /**
     * Pede ao Google Play Services para baixar o modelo de recorte (uma vez só).
     * Espera até [waitMs]; retorna true se o modelo ficou pronto.
     */
    suspend fun ensureModel(context: Context, waitMs: Long = 0): Boolean {
        if (isModelReady(context)) return true
        try {
            val c = segmenterClient()
            try {
                val req = ModuleInstallRequest.newBuilder().addApi(c).build()
                val resp = ModuleInstall.getClient(context).installModules(req).await()
                if (resp.areModulesAlreadyInstalled()) return true
            } finally { c.close() }
        } catch (_: Throwable) { return false }
        if (waitMs <= 0) return false
        return withTimeoutOrNull(waitMs) {
            while (!isModelReady(context)) delay(1500)
            true
        } ?: false
    }

    /**
     * Extrai quadros igualmente espaçados de um vídeo (corrigindo a rotação),
     * limitando o maior lado em [maxSide].
     */
    fun videoFrames(context: Context, uri: Uri, count: Int = 30, maxSide: Int = 560, progress: (Int, Int) -> Unit = { _, _ -> }): List<Bitmap> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Vídeo sem duração")
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val n = count.coerceIn(8, 48)
            val out = ArrayList<Bitmap>()
            for (i in 0 until n) {
                progress(i + 1, n)
                val tUs = (durMs * 1000L * i) / n
                var bmp = (if (Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide)
                else r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)) ?: continue
                // alguns aparelhos devolvem o quadro sem aplicar a rotação do vídeo
                val landscapeSrc = vw >= vh
                val frameLandscape = bmp.width >= bmp.height
                if ((rot == 90 || rot == 270) && landscapeSrc == frameLandscape) {
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                } else if (rot == 180) {
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(180f) }, true)
                }
                val sc = maxSide.toFloat() / max(bmp.width, bmp.height)
                if (sc < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
                out += if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (out.size < 4) error("Não consegui ler quadros suficientes do vídeo")
            return out
        } finally {
            r.release()
        }
    }

    /**
     * Separa o objeto do fundo: primeiro com o ML Kit (rede neural do Google, no aparelho);
     * se indisponível, usa o algoritmo próprio do Vorticis.
     */
    suspend fun segment(bitmap: Bitmap): PhotoInput {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val ml: Mask? = try {
            withTimeout(20_000) {
                val client = segmenterClient()
                try {
                    val res = client.process(InputImage.fromBitmap(bitmap, 0)).await()
                    val buf = res.foregroundConfidenceMask ?: return@withTimeout null
                    buf.rewind()
                    val conf = FloatArray(w * h)
                    buf.get(conf)
                    Mask.fromAlpha(w, h, conf, 0.5f)
                } finally {
                    client.close()
                }
            }
        } catch (_: Throwable) { null }
        return if (ml != null && ml.area > w * h / 200) {
            PhotoInput(px, w, h, Segmentation.largestComponent(Segmentation.fillHoles(ml)), "IA de segmentação (ML Kit)")
        } else {
            PhotoInput(px, w, h, Segmentation.auto(px, w, h), "o recorte próprio do Vorticis (a IA do Google ainda está sendo baixada pelo Play Services)")
        }
    }
}

/** Texto 3D: desenha as letras com o Canvas do Android e gera a máscara. */
class AndroidTextRasterizer : TextRasterizer {
    override fun rasterize(text: String): Mask {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 160f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val fm = paint.fontMetrics
        val w = (paint.measureText(text) + 60).toInt().coerceIn(32, 4000)
        val h = (fm.descent - fm.ascent + 60).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        c.drawText(text, 30f, 30f - fm.ascent, paint)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val m = Mask(w, h)
        for (i in px.indices) m.data[i] = (px[i] and 0xFF) > 127
        return m
    }
}

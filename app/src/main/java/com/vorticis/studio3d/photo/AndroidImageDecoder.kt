package com.vorticis.studio3d.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vorticis.studio3d.core.cloud.ImageDecoder
import com.vorticis.studio3d.core.model.Texture
import kotlin.math.max

/** PNG/JPEG -> Texture (limita o maior lado para caber na memória e na GPU). */
object AndroidImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): Texture? {
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val s = 2048f / max(bmp.width, bmp.height)
        if (s < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Texture(bmp.width, bmp.height, IntArray(px.size) { px[it] or (0xFF shl 24) })
    }
}

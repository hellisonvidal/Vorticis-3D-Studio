package com.vorticis.studio3d.core.mind

import com.vorticis.studio3d.core.model.Mesh
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.PhotoMode
import com.vorticis.studio3d.core.photo.PhotoOptions
import com.vorticis.studio3d.core.photo.PhotoTo3D

/** Texto 3D: extrusão chanfrada da máscara das letras. */
object Text3D {
    fun build(mask: Mask): Mesh {
        val px = IntArray(mask.w * mask.h) { -1 }
        val longest = maxOf(mask.w, mask.h)
        val size = (0.35f * longest / mask.h).coerceIn(0.6f, 4f)
        val r = PhotoTo3D.build(px, mask.w, mask.h, mask, PhotoOptions(mode = PhotoMode.EXTRUDE, resolution = 260, thickness = 0.5f, targetSize = size))
        return r.mesh
    }
}

package com.vorticis.studio3d.core

import com.vorticis.studio3d.core.photo.GrabCut
import com.vorticis.studio3d.core.photo.Mask
import com.vorticis.studio3d.core.photo.Segmentation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SegmentationTest {
    /** Cena "de shopping": piso de ladrilhos, loja escura com vitrine clara, planta; criança de camiseta azul sobre um cubo de granito. */
    private fun busyScene(w: Int = 360, h: Int = 480): Pair<IntArray, Mask> {
        val px = IntArray(w * h); val gt = Mask(w, h)
        val rnd = java.util.Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            val n = rnd.nextInt(17) - 8
            var c = when {
                y < h * 0.38 && x in (w * 0.3).toInt()..(w * 0.6).toInt() && y > h * 0.1 -> rgb(225 + n, 205 + n, 170 + n) // vitrine iluminada
                y < h * 0.38 -> rgb(70 + n, 50 + n, 40 + n)                                                                     // fachada escura
                ((x + y * 2) / 40) % 2 == 0 -> rgb(222 + n, 212 + n, 196 + n)                                                  // ladrilho claro
                else -> rgb(196 + n, 180 + n, 160 + n)                                                                         // ladrilho escuro
            }
            if (x < w * 0.18 && y in (h * 0.15).toInt()..(h * 0.5).toInt() && (x * 7 + y * 3) % 5 != 0) c = rgb(60 + n, 120 + n, 50 + n) // planta
            fun sub(v: Int) { c = v; gt[x, y] = true }
            val cx = w * 0.5f
            val head = (x - cx) * (x - cx) / (32f * 32f) + (y - h * 0.24f) * (y - h * 0.24f) / (38f * 38f) <= 1f
            val shirt = abs(x - cx) < 60 && y in (h * 0.31).toInt()..(h * 0.52).toInt()
            val arm = x in (cx + 40).toInt()..(cx + 140).toInt() && abs(y - (h * 0.30f + (x - cx - 40) * -0.4f)) < 12
            val pants = abs(x - cx) < 70 && y in (h * 0.52).toInt()..(h * 0.64).toInt()
            val cube = abs(x - cx) < 90 && y >= (h * 0.64).toInt()
            when {
                head -> sub(rgb(230 + n, 190 + n, 160 + n))
                arm -> sub(rgb(150 + n, 150 + n, 155 + n))
                shirt -> sub(rgb(30 + n, 60 + n, 170 + n))
                pants -> sub(rgb(25 + n, 30 + n, 60 + n))
                cube -> sub(rgb(120 + n + rnd.nextInt(40), 110 + n + rnd.nextInt(40), 100 + n + rnd.nextInt(40)))
            }
            px[y * w + x] = c
        }
        return px to gt
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun iou(a: Mask, b: Mask): Float {
        var i = 0; var u = 0
        for (k in a.data.indices) { if (a.data[k] && b.data[k]) i++; if (a.data[k] || b.data[k]) u++ }
        return i.toFloat() / u
    }

    @Test fun grabCutBeatsBorderModelOnBusyBackground() {
        val (px, gt) = busyScene()
        val old = Segmentation.borderModel(px, 360, 480)
        val t0 = System.currentTimeMillis()
        val new = GrabCut.segment(px, 360, 480)
        val ms = System.currentTimeMillis() - t0
        val a = iou(old, gt); val b = iou(new, gt)
        println("IoU recorte antigo=%.2f  novo(GrabCut)=%.2f  (%d ms)".format(a, b, ms))
        assertTrue(b > 0.75f, "GrabCut IoU $b")
        assertTrue(b >= a, "novo não pode ser pior")
    }
}

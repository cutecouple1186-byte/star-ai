package com.noor.assistant

import android.graphics.Bitmap

/** HUD ki saanjhi halat: bana hua hologram, "bana raha hai" nishan. */
object Hud {
    @Volatile var holo: Bitmap? = null
    @Volatile var busy = false
    var listener: (() -> Unit)? = null

    fun put(b0: Bitmap?) {
        var b: Bitmap? = null
        if (b0 != null) {
            val w = 480
            val src = Bitmap.createScaledBitmap(b0, w, w, true)
            val px = IntArray(w * w)
            src.getPixels(px, 0, w, 0, 0, w, w)
            for (i in px.indices) {
                val y = i / w
                if (y > w * 0.93f) { px[i] = 0; continue }
                val r = (px[i] shr 16) and 255; val g = (px[i] shr 8) and 255; val bl = px[i] and 255
                val m = maxOf(r, g, bl)
                val a = ((m - 25) * 2.2f).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (minOf(255, r * 255 / maxOf(m, 1)) shl 16) or (minOf(255, g * 255 / maxOf(m, 1)) shl 8) or minOf(255, bl * 255 / maxOf(m, 1))
            }
            b = Bitmap.createBitmap(px, w, w, Bitmap.Config.ARGB_8888)
        }
        holo = b
        ping()
    }

    fun ping() {
        listener?.invoke()
    }
}

package com.noor.assistant

import android.content.Context
import android.graphics.*
import kotlin.math.*

/** Noor aur Rox ke poore-badan hologram, aur asli Earth globe. */
object Chars {
    var noor: Bitmap? = null
    var rox: Bitmap? = null
    var earthTex: IntArray = IntArray(0)
    var tw = 0
    var th = 0
    private val holoPaintN = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val holoPaintR = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint()

    fun load(ctx: Context) {
        if (noor != null) return
        val o = BitmapFactory.Options()
        noor = BitmapFactory.decodeResource(ctx.resources, R.drawable.full_noor, o)
        rox = BitmapFactory.decodeResource(ctx.resources, R.drawable.full_rox, o)
        val e = BitmapFactory.decodeResource(ctx.resources, R.drawable.earth_map)
        tw = e.width; th = e.height
        earthTex = IntArray(tw * th)
        e.getPixels(earthTex, 0, tw, 0, 0, tw, th)
        // Noor ko neela hologram rang
        val cmN = ColorMatrix(floatArrayOf(
            0.10f, 0.20f, 0.05f, 0f, 0f,
            0.20f, 0.55f, 0.15f, 0f, 25f,
            0.35f, 0.85f, 0.35f, 0f, 70f,
            0f, 0f, 0f, 0.92f, 0f))
        holoPaintN.colorFilter = ColorMatrixColorFilter(cmN)
        val cmR = ColorMatrix(floatArrayOf(
            0.95f, 0f, 0f, 0f, 0f,
            0f, 1.05f, 0f, 0f, 8f,
            0f, 0f, 1.15f, 0f, 14f,
            0f, 0f, 0f, 0.95f, 0f))
        holoPaintR.colorFilter = ColorMatrixColorFilter(cmR)
    }

    fun bust(isRox: Boolean): Pair<Bitmap, Rect>? {
        val b = (if (isRox) rox else noor) ?: return null
        val hh = if (isRox) (b.height * 0.36f).toInt() else (b.height * 0.2f).toInt()
        return Pair(b, Rect(0, 0, b.width, hh))
    }

    /** footY = paon ki line; hgt = oonchai; t = waqt (halki jhilmil aur scan line ke liye). */
    fun draw(c: Canvas, isRox: Boolean, cx: Float, footY: Float, hgt: Float, alpha: Float, t: Float, dens: Float) {
        val b = (if (isRox) rox else noor) ?: return
        val w = hgt * b.width / b.height
        val bob = sin(t * 1.6f) * 3f * dens
        val glitch = if ((t * 7f).toInt() % 23 == 0) (sin(t * 90f) * 3f * dens) else 0f
        val dst = RectF(cx - w / 2f + glitch, footY - hgt + bob, cx + w / 2f + glitch, footY + bob)
        val pt = if (isRox) holoPaintR else holoPaintN
        val flick = 0.9f + 0.1f * sin(t * 13f) * sin(t * 3.1f)
        val sc = c.saveLayer(dst, null)
        pt.alpha = (255 * alpha * flick).toInt().coerceIn(0, 255)
        c.drawBitmap(b, null, dst, pt)
        // scan lines (sirf badan par)
        line.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        line.color = Color.parseColor("#022B4A")
        line.alpha = (90 * alpha).toInt()
        var y = dst.top + (t * 30f * dens) % (4f * dens)
        while (y < dst.bottom) { c.drawRect(dst.left, y, dst.right, y + 1.4f * dens, line); y += 4f * dens }
        // chalti hui roshni ki patti
        val sy = dst.top + ((t * 0.35f) % 1f) * dst.height()
        line.color = Color.parseColor("#7DD3FC"); line.alpha = (70 * alpha).toInt()
        c.drawRect(dst.left, sy, dst.right, sy + 10f * dens, line)
        line.xfermode = null
        c.restoreToCount(sc)
    }

    // ------------------------------------------------------------ Earth
    private var gSize = 0
    private var gLat = FloatArray(0)
    private var gLon = FloatArray(0)
    private var gShade = FloatArray(0)
    private var gBmp: Bitmap? = null
    private var gPx = IntArray(0)

    fun earth(size: Int, rot: Float): Bitmap? {
        if (earthTex.isEmpty()) return null
        if (gSize != size) {
            gSize = size
            gLat = FloatArray(size * size); gLon = FloatArray(size * size); gShade = FloatArray(size * size)
            val tilt = Math.toRadians(18.0)
            val ct = cos(tilt).toFloat(); val st = sin(tilt).toFloat()
            for (y in 0 until size) for (x in 0 until size) {
                val nx = (x + 0.5f) / size * 2f - 1f
                val ny = (y + 0.5f) / size * 2f - 1f
                val r2 = nx * nx + ny * ny
                val i = y * size + x
                if (r2 > 1f) { gShade[i] = -1f; continue }
                val nz = sqrt(1f - r2)
                val yy = -ny * ct + nz * st
                val zz = ny * st + nz * ct
                gLat[i] = asin(yy.coerceIn(-1f, 1f))
                gLon[i] = atan2(nx, zz)
                gShade[i] = nz
            }
            gBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            gPx = IntArray(size * size)
        }
        val tau = (2 * PI).toFloat()
        for (i in gPx.indices) {
            val sh = gShade[i]
            if (sh < 0f) { gPx[i] = 0; continue }
            var u = (gLon[i] + rot) / tau; u -= floor(u)
            val v = (0.5f - gLat[i] / PI.toFloat()).coerceIn(0f, 0.999f)
            val tx = (u * tw).toInt().coerceIn(0, tw - 1); val ty = (v * th).toInt().coerceIn(0, th - 1)
            val col = earthTex[ty * tw + tx]
            val k = 0.35f + 0.75f * sh.pow(0.7f)
            val rim = (1f - sh).pow(2.5f)
            val r = (((col shr 16) and 255) * k + 40f * rim).coerceIn(0f, 255f).toInt()
            val g = (((col shr 8) and 255) * k + 150f * rim).coerceIn(0f, 255f).toInt()
            val b = ((col and 255) * k + 220f * rim).coerceIn(0f, 255f).toInt()
            gPx[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        gBmp!!.setPixels(gPx, 0, size, 0, 0, size, size)
        return gBmp
    }
}

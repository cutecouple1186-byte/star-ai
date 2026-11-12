package com.noor.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Chamakte dots se bana hologram chehra (video jaisa). Bolte waqt hont awaaz ke saath hilte hain. */
class HoloView(private val ctx: Context) : View(ctx) {
    var state = "off"
    private var rox = false
    private var built = false
    private var real = false
    private var art = false
    private var artBmp: Bitmap? = null
    private val clip = android.graphics.Path()
    private val rainbow = intArrayOf(
        Color.parseColor("#22D3EE"), Color.parseColor("#2563EB"), Color.parseColor("#7DD3FC"), Color.parseColor("#1D4ED8"), Color.parseColor("#22D3EE")
    )

    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var ph = FloatArray(0)
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var bucket = IntArray(0)
    private var xs = arrayOf(FloatArray(0), FloatArray(0), FloatArray(0))
    private var counts = IntArray(3)
    private var form = 0f

    private val stars = FloatArray(80) { Random(it.toLong() * 31).nextFloat() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var mouth = 0f
    private var mouthY = 0.654f
    private var mouthX = 0.5f
    private var mouthW = 0.095f

    fun setPersona(r: Boolean) {
        if (!built || r != rox) {
            rox = r
            build()
        }
    }

    /** Naya chehra (Settings se) ya mouth position badalne ke baad dobara banao. */
    fun reload() = build()

    private fun faceFile() = File(ctx.filesDir, if (rox) "face_rox.png" else "face_noor.png")

    private fun build() {
        val f = faceFile()
        var bmp: Bitmap? = null
        real = false
        if (f.exists()) {
            bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
            if (bmp != null) real = true
        }
        art = false
        if (bmp == null) {
            bmp = BitmapFactory.decodeResource(resources, if (rox) R.drawable.holo_rox else R.drawable.holo_noor)
            art = true
            artBmp = bmp
        }
        val n = 176
        // square center-crop
        val side = min(bmp!!.width, bmp.height)
        val crop = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val sc = Bitmap.createScaledBitmap(crop, n, n, true)
        val prefs = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE)
        val key = "mouthY2_" + (if (rox) "rox" else "noor")
        if (real) {
            mouthY = prefs.getFloat(key, 0.68f)
            mouthX = 0.5f
            mouthW = 0.075f
        } else {
            mouthY = prefs.getFloat(key, if (rox) 0.482f else 0.814f)
            mouthX = if (rox) 0.417f else 0.562f
            mouthW = if (rox) 0.055f else 0.07f
        }
        val lum = FloatArray(n * n)
        val al = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val c = sc.getPixel(x, y)
            al[y * n + x] = Color.alpha(c) / 255f
            lum[y * n + x] = (Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f) / 255f
        }
        val rnd = Random(7)
        val lx = ArrayList<Float>()
        val ly = ArrayList<Float>()
        val lb = ArrayList<Int>()
        for (y in 1 until n - 1) for (x in 1 until n - 1) {
            val i = y * n + x
            if (al[i] < 0.45f) continue
            val gx = lum[i + 1] - lum[i - 1]
            val gy = lum[i + n] - lum[i - n]
            val edge = sqrt(gx * gx + gy * gy)
            var w = 0.2f + 0.8f * ((lum[i] * lum[i] * 1.4f).coerceAtMost(1f))
            if (edge > 0.12f) w = 1f
            val fx = (x.toFloat() / n - 0.5f) / 0.47f
            val fy = (y.toFloat() / n - 0.48f) / 0.52f
            if (real) w *= (1f - (fx * fx + fy * fy).coerceIn(0f, 1f).let { it * it })
            val yy = y.toFloat() / n
            if (!art && yy > 0.8f) w *= (1f - (yy - 0.8f) * 4f).coerceAtLeast(0f)
            if (rnd.nextFloat() > w) continue
            lx.add((x + rnd.nextFloat()) / n)
            ly.add((y + rnd.nextFloat()) / n)
            lb.add(if (edge > 0.12f || lum[i] > 0.75f) 2 else if (lum[i] > 0.35f) 1 else 0)
        }
        // zyada ho to patla karo (halke phone ke liye)
        val keep = if (lx.size > 6500) 6500f / lx.size else 1f
        val ix = ArrayList<Int>()
        for (i in lx.indices) if (rnd.nextFloat() <= keep) ix.add(i)
        px = FloatArray(ix.size) { lx[ix[it]] }
        py = FloatArray(ix.size) { ly[ix[it]] }
        bucket = IntArray(ix.size) { lb[ix[it]] }
        ph = FloatArray(ix.size) { rnd.nextFloat() * 6.28f }
        sx = FloatArray(ix.size) { (rnd.nextFloat() - 0.5f) * 1.6f }
        sy = FloatArray(ix.size) { (rnd.nextFloat() - 0.5f) * 1.6f }
        xs = Array(3) { FloatArray(ix.size * 2) }
        form = 0f
        built = true
    }

    private fun prefsDots() = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE).getBoolean("dots", false)

    /** Rangeen tasveer: gol frame, chalti rainbow roshni, scan lines aur bolte waqt hont. */
    private fun drawArt(c: Canvas) {
        val bm = artBmp ?: return
        val speaking = state == "speaking"
        val s = min(width, height) * 0.92f
        val cx = width / 2f
        val cy = height / 2f
        val bob = if (speaking) sin(t * 4f) * 2.5f else sin(t) * 1.2f
        val r = s / 2f
        val side = min(bm.width, bm.height)
        val src = android.graphics.Rect((bm.width - side) / 2, (bm.height - side) / 2, (bm.width + side) / 2, (bm.height + side) / 2)
        c.save()
        clip.reset()
        clip.addCircle(cx, cy + bob, r, android.graphics.Path.Direction.CW)
        c.clipPath(clip)
        val dst = RectF(cx - r, cy - r + bob, cx + r, cy + r + bob)
        p.style = Paint.Style.FILL
        p.shader = null
        p.alpha = 255
        c.drawBitmap(bm, src, dst, p)
        p.color = Color.WHITE
        p.alpha = 16
        var y = dst.top + ((t * 14f) % 8f)
        while (y < dst.bottom) {
            c.drawRect(dst.left, y, dst.right, y + 1.5f, p)
            y += 8f
        }
        val target = if (speaking) (abs(sin(t * 7.3f)) * 0.6f + abs(sin(t * 3.1f + 1.3f)) * 0.4f) else 0f
        mouth += (target - mouth) * 0.4f
        if (mouth > 0.1f) {
            val mx = dst.left + s * mouthX
            val my = dst.top + s * mouthY
            val w = s * mouthW * (0.8f + 0.25f * mouth)
            val h = s * (0.006f + 0.045f * mouth)
            val o = RectF(mx - w, my - h, mx + w, my + h)
            p.alpha = 255
            p.color = Color.parseColor("#C0566D")
            c.drawOval(RectF(o.left - 2f, o.top - 2f, o.right + 2f, o.bottom + 2f), p)
            p.color = Color.parseColor("#4A1220")
            c.drawOval(o, p)
            if (mouth > 0.45f) {
                p.color = Color.parseColor("#F5E6E6")
                c.drawRect(mx - w * 0.55f, o.top + h * 0.15f, mx + w * 0.55f, o.top + h * 0.5f, p)
            }
        }
        c.restore()
        val sh = android.graphics.SweepGradient(cx, cy, rainbow, null)
        val m = android.graphics.Matrix()
        m.postRotate(t * 40f, cx, cy)
        sh.setLocalMatrix(m)
        p.shader = sh
        p.style = Paint.Style.STROKE
        for ((wd, al) in listOf(Pair(16f, 40), Pair(9f, 90), Pair(if (speaking) 6f else 4f, 255))) {
            p.strokeWidth = wd
            p.alpha = al
            c.drawCircle(cx, cy + bob, r + 2f, p)
        }
        p.shader = null
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (!built) build()
        t += 0.04f
        if (art && artBmp != null && !prefsDots()) {
            drawArt(c)
            postInvalidateOnAnimation()
            return
        }
        if (form < 1f) form = (form + 0.018f).coerceAtMost(1f)
        val e = 1f - (1f - form) * (1f - form) * (1f - form)
        val s = min(width, height) * 0.94f * (1f + 0.008f * sin(t))
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        val col = when (state) {
            "speaking" -> Color.parseColor("#E879F9")
            "thinking" -> Color.parseColor("#FBBF24")
            "listening" -> Color.parseColor("#22D3EE")
            "idle" -> Color.parseColor("#38BDF8")
            else -> Color.parseColor("#3B5B7A")
        }
        val cx = width / 2f
        val cy = height / 2f

        // taare
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        for (i in stars.indices step 2) {
            p.alpha = (40 + 90 * abs(sin(t * 0.6f + i))).toInt()
            c.drawCircle(stars[i] * width, stars[(i + 1) % stars.size] * height, 1.3f, p)
        }

        // peeche halka neela dhuan
        p.shader = android.graphics.RadialGradient(cx, cy, s * 0.55f, intArrayOf(Color.argb(70, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.55f, p)
        p.shader = null

        counts[0] = 0; counts[1] = 0; counts[2] = 0
        val amp = 1.0f + (if (state == "speaking") 0.8f else 0f)
        for (i in px.indices) {
            val b = bucket[i]
            val k = counts[b]
            xs[b][k] = ox + (px[i] + sx[i] * (1f - e)) * s + cos(t * 1.7f + ph[i]) * amp
            xs[b][k + 1] = oy + (py[i] + sy[i] * (1f - e)) * s + sin(t * 1.3f + ph[i]) * amp
            counts[b] = k + 2
        }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        val dim = if (rox) "#1D4ED8" else "#0E7490"
        val mid = if (rox) "#38BDF8" else "#22D3EE"
        val hi = if (rox) "#FDE68A" else "#E0FBFF"
        p.strokeWidth = 2.2f; p.color = Color.parseColor(dim); p.alpha = 200
        c.drawPoints(xs[0], 0, counts[0], p)
        p.strokeWidth = 2.5f; p.color = Color.parseColor(mid); p.alpha = 235
        c.drawPoints(xs[1], 0, counts[1], p)
        // chamak (glow) + tez dots
        p.strokeWidth = 8f; p.color = Color.parseColor(mid); p.alpha = 38
        c.drawPoints(xs[2], 0, counts[2], p)
        p.strokeWidth = 3f; p.color = Color.parseColor(hi); p.alpha = 255
        c.drawPoints(xs[2], 0, counts[2], p)

        // scan line
        val sy2 = oy + ((t * 0.3f) % 1f) * s
        p.strokeWidth = 2f
        p.color = col
        p.alpha = 60
        c.drawLine(ox + s * 0.1f, sy2, ox + s * 0.9f, sy2, p)

        // hont (dots ki tarah, halka chamakta)
        val target = if (state == "speaking") (abs(sin(t * 7.3f)) * 0.6f + abs(sin(t * 3.1f + 1.3f)) * 0.4f) else 0f
        mouth += (target - mouth) * 0.35f
        val mx = ox + s * mouthX
        val my = oy + s * mouthY
        val w = s * mouthW
        val h = s * (0.006f + 0.05f * mouth)
        val r = RectF(mx - w, my - h, mx + w, my + h)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#06111F")
        p.alpha = if (real) 200 else 235
        c.drawOval(r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.parseColor(if (rox) "#FDE68A" else "#FFB4D0")
        p.alpha = 255
        c.drawOval(r, p)

        postInvalidateOnAnimation()
    }
}

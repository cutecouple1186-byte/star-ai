package com.noor.assistant

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

/** Hologram page: andhera kamra, neeli kirnein; jo bhi hologram bane wo yahan platform par khada hota hai. */
class HoloPageView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cy = Color.parseColor("#22D3EE")
    private var t = 0f
    private var last = 0L
    private val stars = Array(90) { floatArrayOf(Math.random().toFloat(), Math.random().toFloat(), Math.random().toFloat()) }
    private var seen: Bitmap? = null
    private var revealAt = 0L
    private var angle = 0f
    private var zoom = 1f
    private var lastX = 0f
    private var dragging = false
    private var moved = false
    var onAction: (String) -> Unit = {}
    private val sg = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoom = (zoom * d.scaleFactor).coerceIn(0.5f, 2.2f); return true }
    })
    var state = "off"

    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        if (last != 0L) t += min(0.05f, (now - last) / 1000f)
        last = now
        val w = width.toFloat(); val h = height.toFloat()
        val hb = Hud.holo
        if (hb !== seen) { seen = hb; revealAt = now; angle = 0f; zoom = 1f }
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(w / 2, h * 0.5f, h * 0.7f, intArrayOf(Color.parseColor("#05274A"), Color.parseColor("#01050F")), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // deewaron ki patti
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1f); p.color = Color.parseColor("#0B3A5C"); p.alpha = 160
        for (i in 0..5) c.drawLine(w * 0.08f, h * (0.12f + i * 0.14f), w * 0.2f, h * (0.12f + i * 0.14f), p)
        c.drawLine(w * 0.1f, 0f, w * 0.1f, h, p)
        // kirnein
        val cx = w * 0.5f; val foot = h * 0.74f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, h * 0.15f, 0f, foot, Color.TRANSPARENT, Color.parseColor("#4422D3EE"), Shader.TileMode.CLAMP)
        val bm = Path(); bm.moveTo(cx - w * 0.36f, foot); bm.lineTo(cx - w * 0.2f, h * 0.15f); bm.lineTo(cx + w * 0.2f, h * 0.15f); bm.lineTo(cx + w * 0.36f, foot); bm.close()
        c.drawPath(bm, p); p.shader = null
        for (i in 0 until 7) {
            val x = cx - w * 0.3f + i * w * 0.1f + sin(t + i) * d(4f)
            p.shader = LinearGradient(0f, h * 0.3f, 0f, foot, Color.TRANSPARENT, Color.parseColor("#6622D3EE"), Shader.TileMode.CLAMP)
            c.drawRect(x, h * 0.3f, x + d(3f), foot, p); p.shader = null
        }
        p.color = Color.parseColor("#7DD3FC")
        for (s in stars) {
            val y = ((s[1] - t * 0.02f * (0.3f + s[2])) % 1f + 1f) % 1f
            p.alpha = (50 + 150 * abs(sin(t * 1.5f + s[0] * 11f))).toInt()
            c.drawCircle(s[0] * w, y * h, d(0.7f + s[2] * 1.3f), p)
        }
        p.alpha = 255
        // platform (hamesha chalta hua)
        for (i in 0..4) {
            val k = 1f + i * 0.2f; val rr = w * 0.26f * k
            p.style = Paint.Style.STROKE; p.strokeWidth = d(2f - i * 0.3f); p.color = if (i % 2 == 0) cy else Color.parseColor("#3B82F6"); p.alpha = 245 - i * 40
            c.drawOval(RectF(cx - rr, foot - rr * 0.14f, cx + rr, foot + rr * 0.14f), p)
        }
        p.strokeWidth = d(3f); p.color = Color.WHITE; p.alpha = 255
        c.drawArc(RectF(cx - w * 0.3f, foot - w * 0.042f, cx + w * 0.3f, foot + w * 0.042f), t * 140f, 45f, false, p)
        c.drawArc(RectF(cx - w * 0.3f, foot - w * 0.042f, cx + w * 0.3f, foot + w * 0.042f), t * 140f + 180f, 45f, false, p)
        // Processing panel (aakhri picture jaisa)
        val rp = RectF(w * 0.74f, h * 0.18f, w * 0.97f, h * 0.33f)
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.2f); p.color = cy; p.alpha = 150; c.drawRect(rp, p)
        p.style = Paint.Style.FILL; p.alpha = 90
        for (i in 0..5) c.drawRect(rp.left + d(6f), rp.top + d(8f) + i * d(7f), rp.left + d(6f) + d(30f + ((i * 41 + (t * 4).toInt() * 9) % 34)), rp.top + d(11f) + i * d(7f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.6f); p.alpha = 255
        for (i in 0..2) {
            val yy = rp.top + d(66f) + i * d(0f); val cxx = rp.left + d(12f) + i * d(26f)
            c.save(); c.rotate(t * 220f + i * 50f, cxx, rp.bottom - d(10f))
            c.drawArc(RectF(cxx - d(8f), rp.bottom - d(18f), cxx + d(8f), rp.bottom - d(2f)), 0f, 250f, false, p); c.restore()
        }
        // hologram
        if (hb != null) {
            val side = min(w * 0.8f, h * 0.55f) * zoom
            val prog = ((now - revealAt) / 1800f).coerceIn(0f, 1f)
            val sx = max(0.12f, abs(cos(angle))) * (0.94f + 0.06f * sin(t * 1.3f))
            val bw = side * sx
            val bob = sin(t * 1.4f) * d(4f)
            val dst = RectF(cx - bw / 2, foot - side * 1.02f + bob, cx + bw / 2, foot - side * 0.02f + bob)
            val sc = c.save()
            val top = dst.bottom - dst.height() * prog
            c.clipRect(dst.left, top, dst.right, dst.bottom + d(40f))
            p.style = Paint.Style.FILL; p.alpha = (225 + 30 * sin(t * 10f)).toInt()
            if (angle != 0f && cos(angle) < 0) { c.scale(-1f, 1f, cx, 0f) }
            c.drawBitmap(hb, null, dst, p)
            p.alpha = 255
            c.restoreToCount(sc)
            // scan line
            if (prog < 1f) { p.color = Color.WHITE; p.alpha = 220; c.drawRect(dst.left - d(10f), top, dst.right + d(10f), top + d(2.5f), p); p.alpha = 255 }
            p.color = cy; p.alpha = 26
            var y = dst.top + (t * 30f) % d(5f)
            while (y < dst.bottom) { c.drawRect(dst.left, y, dst.right, y + d(1f), p); y += d(5f) }
            p.alpha = 255
            // band karne ka nishan
            p.style = Paint.Style.STROKE; p.strokeWidth = d(2f); p.color = cy
            val xr = RectF(w - d(46f), d(14f), w - d(14f), d(46f)); c.drawCircle(xr.centerX(), xr.centerY(), d(15f), p)
            c.drawLine(xr.centerX() - d(6f), xr.centerY() - d(6f), xr.centerX() + d(6f), xr.centerY() + d(6f), p)
            c.drawLine(xr.centerX() + d(6f), xr.centerY() - d(6f), xr.centerX() - d(6f), xr.centerY() + d(6f), p)
        }
        if (Hud.busy) {
            c.save(); c.rotate(t * 220f, cx, h * 0.45f)
            p.style = Paint.Style.STROKE; p.strokeWidth = d(3.5f); p.color = cy
            c.drawArc(RectF(cx - d(30f), h * 0.45f - d(30f), cx + d(30f), h * 0.45f + d(30f)), 0f, 250f, false, p)
            c.restore()
        }
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; dragging = true; moved = false }
            MotionEvent.ACTION_MOVE -> if (!sg.isInProgress && e.pointerCount == 1 && dragging) {
                val dx = e.x - lastX; if (abs(dx) > 2f) moved = true
                angle += dx / width * 5f; lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && Hud.holo != null && e.x > width - d(60f) && e.y < d(60f)) onAction("holo_clear")
                dragging = false
            }
        }
        return true
    }
}

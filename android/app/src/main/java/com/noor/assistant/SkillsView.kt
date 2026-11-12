package com.noor.assistant

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** SKILLS panel: hologram jaisa neural jaal + 3 asli bars (server, yaadein, system control). */
class SkillsView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val cy = Color.parseColor("#22D3EE")
    private var t = 0f
    private var last = 0L
    var bars = listOf("AI (SERVER)" to 0, "YAADEIN (DATA)" to 0, "SYSTEM CONTROL" to 0)

    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        if (last != 0L) t += min(0.05f, (now - last) / 1000f)
        last = now
        val w = width.toFloat()
        // do hologram boxes
        for (k in 0..1) {
            val r = RectF(0.04f * w + k * 0.48f * w, d(6f), 0.48f * w + k * 0.48f * w, d(130f))
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#0A2A4A"); p.alpha = 110
            c.drawRoundRect(r, d(10f), d(10f), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1.3f); p.color = cy; p.alpha = 200
            c.drawRoundRect(r, d(10f), d(10f), p)
            val cx = r.centerX(); val cyy = r.centerY() - d(6f)
            if (k == 0) {
                val pts = ArrayList<PointF>()
                for (i in 0 until 16) {
                    val a = i * 2.4f + t * 0.6f
                    pts.add(PointF(cx + cos(a) * r.width() * 0.30f * (0.6f + 0.4f * sin(i * 1.7f)), cyy + sin(a * 1.3f) * d(38f) * (0.5f + 0.5f * cos(i * 0.9f))))
                }
                p.strokeWidth = d(0.8f); p.alpha = 120
                for (i in pts.indices) for (j in i + 1 until pts.size) if (hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y) < d(55f)) c.drawLine(pts[i].x, pts[i].y, pts[j].x, pts[j].y, p)
                p.style = Paint.Style.FILL; p.alpha = 255
                for ((i, q) in pts.withIndex()) c.drawCircle(q.x, q.y, d(1.8f + 1.2f * abs(sin(t * 2f + i))), p)
            } else {
                val path = Path()
                for (i in 0..9) {
                    val rr = if (i % 2 == 0) d(40f) else d(17f)
                    val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5 + t * 0.5f
                    val x = cx + cos(a) * rr; val y = cyy + sin(a) * rr
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                p.style = Paint.Style.FILL; p.alpha = 70; c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d(1.6f); p.alpha = 255; c.drawPath(path, p)
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1.5f); p.alpha = 230
            c.drawOval(RectF(cx - d(42f), r.bottom - d(22f), cx + d(42f), r.bottom - d(10f)), p)
        }
        // bars
        var y = d(150f)
        for ((name, v) in bars) {
            tp.textSize = d(11f); tp.color = Color.WHITE; tp.textAlign = Paint.Align.LEFT
            c.drawText(name, 0.05f * w, y, tp)
            val bar = RectF(0.05f * w, y + d(6f), 0.74f * w, y + d(22f))
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1.2f); p.color = cy; p.alpha = 200
            c.drawRoundRect(bar, d(8f), d(8f), p)
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#2563EB"); p.alpha = 255
            var x = bar.left + d(3f)
            val fillEnd = bar.left + (bar.width() - d(6f)) * v / 100f
            while (x + d(6f) <= fillEnd) { c.drawRect(x, bar.top + d(3f), x + d(6f), bar.bottom - d(3f), p); x += d(9f) }
            val rc = PointF(0.88f * w, y + d(4f)); val rr = d(20f)
            p.style = Paint.Style.STROKE; p.strokeWidth = d(3f); p.color = Color.parseColor("#0E4A6B")
            c.drawCircle(rc.x, rc.y, rr, p)
            p.color = cy; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(rc.x - rr, rc.y - rr, rc.x + rr, rc.y + rr), -90f, 360f * v / 100f, false, p)
            p.strokeCap = Paint.Cap.BUTT
            tp.textAlign = Paint.Align.CENTER; tp.textSize = d(10f)
            c.drawText("$v%", rc.x, rc.y + d(4f), tp)
            y += d(54f)
        }
        postInvalidateOnAnimation()
    }

    override fun onMeasure(wm: Int, hm: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wm), d(320f).toInt())
    }
}

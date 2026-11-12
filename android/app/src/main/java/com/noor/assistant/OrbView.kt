package com.noor.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Noor ka chamakta hua gola. Halat ke hisaab se rang aur dhadkan badalti hai. */
class OrbView(ctx: Context) : View(ctx) {

    var state = "off"

    /** false ho to beech ka gola nahi banta (wahan avatar tasveer hoti hai). */
    var core = true

    /** Saans lene jaisa halka hilne wala avatar. */
    var avatar: View? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        t += 0.045f
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.27f
        val amp = when (state) {
            "speaking" -> 0.16f
            "thinking" -> 0.10f
            "listening" -> 0.07f
            "idle" -> 0.04f
            else -> 0.01f
        }
        val speed = if (state == "speaking") 7f else 2.4f
        val r = base * (1f + amp * sin(t * speed))
        val col = when (state) {
            "speaking" -> Color.parseColor("#E879F9")
            "thinking" -> Color.parseColor("#FBBF24")
            "listening" -> Color.parseColor("#22D3EE")
            "idle" -> Color.parseColor("#38BDF8")
            else -> Color.parseColor("#475569")
        }

        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        for (i in 1..3) {
            p.color = col
            p.alpha = 110 / i
            c.drawCircle(cx, cy, r * (1f + 0.24f * i + 0.03f * sin(t + i)), p)
        }

        for (i in 0 until 3) {
            val a = t * (0.8f + i * 0.25f) + i * 2.1f
            val rr = r * (1.75f + 0.12f * i)
            p.style = Paint.Style.FILL
            p.color = col
            p.alpha = 200
            c.drawCircle(cx + rr * cos(a), cy + rr * sin(a), 5f, p)
        }

        avatar?.let {
            val k = 1f + amp * 0.45f * sin(t * speed)
            it.scaleX = k
            it.scaleY = k
        }
        if (!core) {
            postInvalidateOnAnimation()
            return
        }

        p.style = Paint.Style.FILL
        p.alpha = 255
        p.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
            intArrayOf(mix(col, Color.WHITE, 0.55f), col, mix(col, Color.BLACK, 0.65f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, p)
        p.shader = null

        txt.color = Color.parseColor("#06111F")
        txt.textAlign = Paint.Align.CENTER
        txt.textSize = r * 0.95f
        c.drawText("✦", cx, cy + txt.textSize * 0.33f, txt)

        postInvalidateOnAnimation()
    }
}

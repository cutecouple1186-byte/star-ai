package com.noor.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/** Upar ki chhoti "NOOR LISTENING" pill. Har app ke upar dikhti hai, dabane par Noor khulti hai. */
class OverlayPill(private val ctx: Context) {

    private class Bars(c: Context) : View(c) {
        var color = Color.parseColor("#22D3EE")
        var active = true
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            t += 0.18f
            p.color = color
            val n = 5
            val gap = width / (n * 2f)
            for (i in 0 until n) {
                val f = if (active) 0.3f + 0.7f * abs(sin(t + i * 0.9f)) else 0.25f
                val h = height * f
                val x = gap * (i * 2 + 0.5f)
                c.drawRoundRect(RectF(x, (height - h) / 2f, x + gap, (height + h) / 2f), gap / 2f, gap / 2f, p)
            }
            postInvalidateOnAnimation()
        }
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var bars: Bars? = null
    private var glow: GlowView? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (root != null) return
        if (!Settings.canDrawOverlays(ctx)) return
        try {
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER_VERTICAL
            box.setPadding(dp(14), dp(7), dp(16), dp(7))
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#E6050A12"))
            bg.cornerRadius = dp(22).toFloat()
            bg.setStroke(dp(1), Color.parseColor("#22D3EE"))
            box.background = bg

            val b = Bars(ctx)
            box.addView(b, LinearLayout.LayoutParams(dp(22), dp(16)))
            val t = TextView(ctx)
            t.text = "NOOR  LISTENING"
            t.textSize = 11f
            t.letterSpacing = 0.12f
            t.setTextColor(Color.parseColor("#E6F1FF"))
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            val tp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tp.leftMargin = dp(8)
            box.addView(t, tp)

            box.setOnClickListener {
                val i = Intent(ctx, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                try {
                    ctx.startActivity(i)
                } catch (e: Exception) {
                }
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = dp(10)
            wm.addView(box, lp)
            root = box
            label = t
            bars = b
            if (ctx.getSharedPreferences("noor", Context.MODE_PRIVATE).getBoolean("glow", false)) {
                try {
                    val g = GlowView(ctx, true)
                    val gp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )
                    wm.addView(g, gp)
                    glow = g
                } catch (e: Exception) {
                    glow = null
                }
            }
        } catch (e: Exception) {
            root = null
        }
    }

    /** state: off, idle, listening, thinking, speaking */
    fun update(state: String) {
        val r = root ?: return
        val (text, color) = when (state) {
            "listening" -> Pair(Persona.name + "  LISTENING", "#22D3EE")
            "thinking" -> Pair(Persona.name + "  THINKING", "#FBBF24")
            "speaking" -> Pair(Persona.name + "  SPEAKING", "#E879F9")
            else -> Pair(Persona.name + "  READY", "#38BDF8")
        }
        label?.text = text
        bars?.color = Color.parseColor(color)
        bars?.active = state != "idle"
        (r.background as? GradientDrawable)?.setStroke(dp(1), Color.parseColor(color))
    }

    fun hide() {
        try {
            glow?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        glow = null
        try {
            root?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        root = null
        label = null
        bars = null
    }
}

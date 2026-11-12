package com.noor.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View

/** Phone ke kinaaron par chalti rangeen (RGB) roshni. overlay=true ho to Noor ki app band hone par har app ke upar dikhti hai. */
class GlowView(c: Context, private val overlay: Boolean = false) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val cols = intArrayOf(
        Color.parseColor("#22D3EE"), Color.parseColor("#818CF8"), Color.parseColor("#E879F9"),
        Color.parseColor("#FB7185"), Color.parseColor("#FBBF24"), Color.parseColor("#34D399"), Color.parseColor("#22D3EE")
    )

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(cv: Canvas) {
        if (overlay && MainActivity.resumed) {
            postDelayed({ invalidate() }, 500)
            return
        }
        t += if (overlay) 3.2f else 1.6f
        val d = resources.displayMetrics.density
        val inset = 2f * d
        val rr = RectF(inset, inset, width - inset, height - inset)
        val sh = SweepGradient(width / 2f, height / 2f, cols, null)
        val m = Matrix()
        m.postRotate(t, width / 2f, height / 2f)
        sh.setLocalMatrix(m)
        p.shader = sh
        p.style = Paint.Style.STROKE
        val boost = if (Chat.state == "speaking") 1.6f else 1f
        for ((wd, al) in listOf(Pair(14f, 28), Pair(8f, 70), Pair(3.5f, 235))) {
            p.strokeWidth = wd * d / 2f * boost
            p.alpha = al
            cv.drawRoundRect(rr, 26f * d, 26f * d, p)
        }
        if (overlay) postDelayed({ invalidate() }, 33) else postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = false
}

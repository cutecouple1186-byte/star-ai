package com.noor.assistant

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/** Zinda 3D hologram HUD: sab kuch chalta rehta hai. Noor/Rox poore badan ke hologram. */
class HudView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val cy = Color.parseColor("#22D3EE")
    private val bl = Color.parseColor("#3B82F6")
    private val dim = Color.parseColor("#0E4A6B")
    private var t = 0f
    private var last = 0L

    var state = "off"
    var weather = "..."
    var ram = 0
    var batt = 0
    var disk = 0
    var news = ""
    var rox = true
    var torchOn = false
    var bubble = ""
    var liveOn = false
    var quality = 1
    var netGuard = false
    private val rNet = RectF()
    var focus = 1f
    var onAction: (String) -> Unit = {}
    var onRects: (RectF, RectF) -> Unit = { _, _ -> }
    val mainRect = RectF()
    val sideRect = RectF()
    private var pressed = ""
    private var pressAt = 0L
    private var shownRox = true
    private var prevRox = true
    private var swapAt = 0L
    private val stars = Array(60) { floatArrayOf(Math.random().toFloat(), Math.random().toFloat(), Math.random().toFloat()) }
    private val rLogo = RectF(); private val rTitle = RectF(); private val rWx = RectF()
    private val rChk = RectF(); private val rBub = RectF(); private val rProc = RectF(); private val rMini = RectF()
    private val rGauge = RectF(); private val rSmart = RectF(); private val rNews = RectF()
    private val rMic = RectF(); private val rKey = RectF(); private val rGlobe = RectF()
    private val hexC = Array(4) { PointF() }
    private var hexR = 0f
    private val hexKey = listOf("wifi", "bluetooth", "torch", "airplane")
    private val hexName = listOf("WiFi", "Bluetooth", "Flashlight", "Airplane")
    private val steps = listOf("Analysis", "Processing", "Searching", "Connecting", "Ready")
    private var stageFoot = 0f
    private var stageH = 0f

    init { Chars.load(ctx) }

    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val lr = 0.085f * w
        rLogo.set(0.12f * w - lr, 0.012f * h + 0.005f * h, 0.12f * w + lr, 0.012f * h + 0.005f * h + 2 * lr)
        sideRect.set(rLogo.left + lr * 0.15f, rLogo.top + lr * 0.15f, rLogo.right - lr * 0.15f, rLogo.bottom - lr * 0.15f)
        rTitle.set(0.26f * w, 0.012f * h, 0.70f * w, 0.095f * h)
        rWx.set(0.72f * w, 0.012f * h, 0.98f * w, 0.15f * h)
        rChk.set(0.02f * w, 0.125f * h, 0.27f * w, 0.285f * h)
        rBub.set(0.29f * w, 0.108f * h, 0.70f * w, 0.178f * h)
        stageFoot = 0.575f * h; stageH = 0.37f * h
        mainRect.set(0.5f * w - 0.2f * w, stageFoot - stageH, 0.5f * w + 0.2f * w, stageFoot)
        rProc.set(0.73f * w, 0.20f * h, 0.98f * w, 0.36f * h)
        rMini.set(0.76f * w, 0.375f * h, 0.98f * w, 0.47f * h)
        rGlobe.set(0.02f * w, 0.33f * h, 0.02f * w + 0.27f * w, 0.33f * h + 0.27f * w)
        rGauge.set(0.02f * w, 0.615f * h, 0.52f * w, 0.725f * h)
        rSmart.set(0.54f * w, 0.615f * h, 0.98f * w, 0.725f * h)
        rNews.set(0.02f * w, 0.74f * h, 0.98f * w, 0.815f * h)
        rMic.set(0.5f * w - 0.085f * w, 0.9f * h - 0.085f * w, 0.5f * w + 0.085f * w, 0.9f * h + 0.085f * w)
        rNet.set(0.87f * w, 0.9f * h - 0.045f * w, 0.97f * w, 0.9f * h + 0.045f * w)
        rKey.set(0.03f * w, 0.9f * h - 0.045f * w, 0.03f * w + 0.1f * w, 0.9f * h + 0.045f * w)
        hexR = 0.04f * w
        val x0 = rSmart.left + 0.062f * w
        for (i in 0..3) hexC[i].set(x0 + i * (hexR * 2.2f), rSmart.top + 0.05f * h)
        onRects(mainRect, sideRect)
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, a: Paint.Align = Paint.Align.LEFT) {
        tp.textSize = d(size); tp.color = col; tp.textAlign = a; tp.alpha = 255
        c.drawText(s, x, y, tp)
    }

    private fun panel(c: Canvas, r: RectF, title: String) {
        val k = d(9f)
        val path = Path()
        path.moveTo(r.left + k, r.top); path.lineTo(r.right - k, r.top); path.lineTo(r.right, r.top + k)
        path.lineTo(r.right, r.bottom - k); path.lineTo(r.right - k, r.bottom); path.lineTo(r.left + k, r.bottom)
        path.lineTo(r.left, r.bottom - k); path.lineTo(r.left, r.top + k); path.close()
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.parseColor("#14335A"), Color.parseColor("#071A36"), Shader.TileMode.CLAMP); p.alpha = 200
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.3f); p.color = cy; p.alpha = (170 + 60 * sin(t * 2f)).toInt()
        c.drawPath(path, p); p.alpha = 255
        if (title.isNotEmpty()) txt(c, title, r.left + d(10f), r.top + d(15f), 9.5f, Color.parseColor("#BAE6FD"))
    }

    private fun hex(c: Canvas, x: Float, y: Float, r: Float) {
        val path = Path()
        for (i in 0..5) {
            val a = Math.toRadians((60 * i - 30).toDouble())
            val px = x + r * cos(a).toFloat(); val py = y + r * sin(a).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); c.drawPath(path, p)
    }

    private fun gauge(c: Canvas, cx: Float, cyy: Float, r: Float, pct: Int, label: String) {
        p.style = Paint.Style.STROKE; p.strokeWidth = d(4f); p.strokeCap = Paint.Cap.ROUND
        p.color = dim; p.alpha = 255
        val o = RectF(cx - r, cyy - r, cx + r, cyy + r)
        c.drawArc(o, 135f, 270f, false, p)
        p.color = if (pct > 85) Color.parseColor("#F97316") else cy
        c.drawArc(o, 135f, 270f * pct.coerceIn(0, 100) / 100f, false, p)
        p.strokeCap = Paint.Cap.BUTT
        txt(c, "$pct%", cx, cyy + d(4f), 10f, Color.WHITE, Paint.Align.CENTER)
        txt(c, label, cx, cyy + r + d(13f), 8.5f, Color.WHITE, Paint.Align.CENTER)
    }

    private fun drawIcon(c: Canvas, key: String, x: Float, y: Float, r: Float, col: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.8f); p.color = col; p.alpha = 255
        when (key) {
            "wifi" -> for (i in 1..3) c.drawArc(RectF(x - r * i / 3f, y - r * i / 3f + r * 0.4f, x + r * i / 3f, y + r * i / 3f + r * 0.4f), 225f, 90f, false, p)
            "bluetooth" -> { val b = Path(); b.moveTo(x - r * 0.6f, y - r * 0.5f); b.lineTo(x + r * 0.6f, y + r * 0.5f); b.lineTo(x, y + r); b.lineTo(x, y - r); b.lineTo(x + r * 0.6f, y - r * 0.5f); b.lineTo(x - r * 0.6f, y + r * 0.5f); c.drawPath(b, p) }
            "torch" -> { c.drawRect(x - r * 0.4f, y - r * 0.9f, x + r * 0.4f, y + r * 0.1f, p); c.drawLine(x, y + r * 0.1f, x, y + r * 0.9f, p) }
            else -> { c.drawLine(x - r, y + r * 0.2f, x + r, y - r * 0.2f, p); c.drawLine(x - r * 0.2f, y, x - r * 0.7f, y - r * 0.7f, p); c.drawLine(x - r * 0.2f, y, x - r * 0.7f, y + r * 0.8f, p) }
        }
    }

    private fun bustInRing(c: Canvas, isRox: Boolean, cx: Float, cyy: Float, r: Float, alpha: Float) {
        val pr = Chars.bust(isRox) ?: return
        val clip = Path(); clip.addCircle(cx, cyy, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#06224A"); p.alpha = (255 * alpha).toInt(); c.drawCircle(cx, cyy, r, p)
        val src = pr.second
        val sw = 2f * r; val sh = sw * src.height() / src.width()
        val dst = RectF(cx - sw / 2, cyy - r, cx + sw / 2, cyy - r + sh)
        p.alpha = (255 * alpha).toInt(); c.drawBitmap(pr.first, src, dst, p)
        c.restore(); p.alpha = 255
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        if (last != 0L) t += min(0.05f, (now - last) / 1000f)
        last = now
        val w = width.toFloat(); val h = height.toFloat()
        if (rox != shownRox) { prevRox = shownRox; shownRox = rox; swapAt = now }
        // peeche ka manzar
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(w / 2, h * 0.35f, h * 0.75f, intArrayOf(Color.parseColor("#06305E"), Color.parseColor("#020B1A")), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // zameen par grid
        p.style = Paint.Style.STROKE; p.strokeWidth = d(0.8f); p.color = cy; p.alpha = 40
        val hor = h * 0.6f
        for (i in 0..8) { val yy = hor + (i * i) * h * 0.0065f + (t * 6f) % 3f; if (yy < h) c.drawLine(0f, yy, w, yy, p) }
        for (i in -8..8) c.drawLine(w / 2 + i * w * 0.02f, hor, w / 2 + i * w * 0.18f, h, p)
        // roshni ki kirnein
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, h * 0.2f, 0f, stageFoot, Color.TRANSPARENT, Color.parseColor("#3322D3EE"), Shader.TileMode.CLAMP)
        val bm = Path(); bm.moveTo(w * 0.5f - w * 0.34f, stageFoot); bm.lineTo(w * 0.5f - w * 0.14f, h * 0.2f); bm.lineTo(w * 0.5f + w * 0.14f, h * 0.2f); bm.lineTo(w * 0.5f + w * 0.34f, stageFoot); bm.close()
        c.drawPath(bm, p); p.shader = null
        // taare
        p.color = Color.parseColor("#7DD3FC")
        for (s in stars) {
            val y = ((s[1] - t * 0.015f * (0.4f + s[2])) % 1f + 1f) % 1f
            p.alpha = (60 + 140 * abs(sin(t * 1.3f + s[0] * 9f))).toInt()
            c.drawCircle(s[0] * w, y * h, d(0.7f + s[2] * 1.2f), p)
        }
        p.alpha = 255

        // ---- header
        val lc = PointF(rLogo.centerX(), rLogo.centerY()); val lr = rLogo.width() / 2f
        bustInRing(c, !shownRox, lc.x, lc.y, lr * 0.84f, 1f)
        p.style = Paint.Style.STROKE; p.strokeWidth = d(2.2f); p.color = cy
        c.drawCircle(lc.x, lc.y, lr, p)
        p.color = bl; c.save(); c.rotate(t * 70f, lc.x, lc.y)
        c.drawArc(RectF(lc.x - lr * 0.93f, lc.y - lr * 0.93f, lc.x + lr * 0.93f, lc.y + lr * 0.93f), 0f, 110f, false, p); c.restore()
        c.save(); c.rotate(-t * 110f, lc.x, lc.y); p.color = cy; p.strokeWidth = d(1.2f)
        c.drawArc(RectF(lc.x - lr * 1.12f, lc.y - lr * 1.12f, lc.x + lr * 1.12f, lc.y + lr * 1.12f), 40f, 60f, false, p); c.restore()
        panel(c, rTitle, "")
        txt(c, "NOOR AI", rTitle.centerX(), rTitle.top + d(29f), 23f, Color.WHITE, Paint.Align.CENTER)
        txt(c, "Your Personal AI Assistant", rTitle.centerX(), rTitle.top + d(45f), 9.5f, Color.parseColor("#BAE6FD"), Paint.Align.CENTER)
        panel(c, rWx, "")
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#FBBF24"); p.alpha = 255
        c.drawCircle(rWx.centerX() - d(8f), rWx.top + d(18f), d(8f), p)
        p.color = Color.WHITE; c.drawCircle(rWx.centerX() + d(5f), rWx.top + d(23f), d(9f), p)
        txt(c, weather, rWx.centerX(), rWx.top + d(46f), 10f, Color.WHITE, Paint.Align.CENTER)
        txt(c, SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date()), rWx.centerX(), rWx.bottom - d(24f), 19f, Color.WHITE, Paint.Align.CENTER)
        txt(c, SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH).format(Date()), rWx.centerX(), rWx.bottom - d(8f), 8.5f, Color.parseColor("#BAE6FD"), Paint.Align.CENTER)

        // ---- checklist
        panel(c, rChk, "")
        val act = if (state == "thinking") ((t * 2.5f).toInt() % 5) else 5
        for (i in steps.indices) {
            val y = rChk.top + d(22f) + i * (rChk.height() - d(26f)) / 5f
            val on = state != "off" && i <= act
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1.6f); p.color = if (on) Color.parseColor("#2DD4BF") else dim; p.alpha = 255
            c.drawCircle(rChk.left + d(14f), y - d(3f), d(7f), p)
            if (on) { val ck = Path(); ck.moveTo(rChk.left + d(10.5f), y - d(3f)); ck.lineTo(rChk.left + d(13f), y); ck.lineTo(rChk.left + d(18f), y - d(7f)); c.drawPath(ck, p) }
            txt(c, steps[i] + if (i < 4) "..." else "!", rChk.left + d(27f), y, 9.5f, if (on) Color.WHITE else Color.parseColor("#5B7C99"))
        }
        // ---- bubble
        panel(c, rBub, "")
        val bt = bubble.ifBlank { "Hello Boss, How can I help you today?" }
        tp.textSize = d(10.5f)
        var rest = bt.replace("\n", " ").trim(); var ly = rBub.top + d(20f)
        for (ln in 0..1) {
            if (rest.isBlank()) break
            val n = tp.breakText(rest, true, rBub.width() - d(18f), null)
            var cut = n; if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) cut = sp }
            var seg = rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
            if (ln == 1 && rest.isNotEmpty()) seg = seg.dropLast(1).trimEnd() + "…"
            txt(c, seg, rBub.left + d(10f), ly, 10.5f, Color.WHITE); ly += d(16f)
        }

        // ---- globe (asli Earth)
        val gs = rGlobe.width()
        val eb = Chars.earth(max(64, (gs / resources.displayMetrics.density * 1.1f).toInt().coerceAtMost(150)), t * 0.35f)
        p.style = Paint.Style.FILL; p.shader = RadialGradient(rGlobe.centerX(), rGlobe.centerY(), gs * 0.62f, Color.parseColor("#4422D3EE"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(rGlobe.centerX(), rGlobe.centerY(), gs * 0.62f, p); p.shader = null
        if (eb != null) c.drawBitmap(eb, null, rGlobe, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.2f); p.color = cy; p.alpha = 160
        c.drawOval(RectF(rGlobe.left - d(6f), rGlobe.bottom - d(8f), rGlobe.right + d(6f), rGlobe.bottom + d(8f)), p)
        c.save(); c.rotate(t * 40f, rGlobe.centerX(), rGlobe.centerY())
        c.drawArc(RectF(rGlobe.left - d(5f), rGlobe.top - d(5f), rGlobe.right + d(5f), rGlobe.bottom + d(5f)), 0f, 70f, false, p); c.restore(); p.alpha = 255

        // ---- stage: platform + character
        val mx = w / 2f
        for (i in 0..3) {
            val k = 1f + i * 0.22f; val rr = w * 0.27f * k
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1.8f - i * 0.25f); p.color = if (i % 2 == 0) cy else bl; p.alpha = 240 - i * 55
            c.drawOval(RectF(mx - rr, stageFoot - rr * 0.13f, mx + rr, stageFoot + rr * 0.13f), p)
        }
        c.save(); c.rotate(0f)
        p.alpha = 255; p.strokeWidth = d(2.5f); p.color = Color.WHITE
        val sa = t * 120f
        c.drawArc(RectF(mx - w * 0.3f, stageFoot - w * 0.04f, mx + w * 0.3f, stageFoot + w * 0.04f), sa, 40f, false, p)
        c.restore()
        val ap = ((now - swapAt) / 850f).coerceIn(0f, 1f)
        val ease = ap * ap * (3 - 2 * ap)
        fun place(isRox: Boolean, k: Float, alpha: Float) {
            // k: 0 = ring mein, 1 = stage par
            val cxk = lc.x + (mx - lc.x) * k
            val footk = (rLogo.centerY() + lr * 0.6f) + (stageFoot - (rLogo.centerY() + lr * 0.6f)) * k
            val hk = (lr * 1.4f) + ((if (isRox) stageH * 0.92f else stageH) - lr * 1.4f) * k
            Chars.draw(c, isRox, cxk, footk, hk, alpha, t, resources.displayMetrics.density)
        }
        if (ap < 1f) { place(prevRox, 1f - ease, 1f - ease); place(shownRox, ease, ease) } else place(shownRox, 1f, 1f)
        // hologram jo bola gaya hai (agar hai to Hologram page par, yahan nahi)

        // ---- processing + mini
        panel(c, rProc, "")
        p.style = Paint.Style.FILL; p.color = cy
        for (i in 0..5) { p.alpha = 90; c.drawRect(rProc.left + d(6f), rProc.top + d(8f) + i * d(7f), rProc.left + d(6f) + d(28f + ((i * 37 + (t * 4).toInt() * 7) % 30)), rProc.top + d(11f) + i * d(7f), p) }
        for (i in 0..2) {
            val yy = rProc.top + d(62f) + i * d(21f)
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1.6f); p.color = cy; p.alpha = 255
            c.save(); c.rotate(t * 220f + i * 40f, rProc.left + d(12f), yy - d(3f))
            c.drawArc(RectF(rProc.left + d(5f), yy - d(10f), rProc.left + d(19f), yy + d(4f)), 0f, 250f, false, p); c.restore()
            txt(c, "Processing...", rProc.left + d(26f), yy, 9f, Color.WHITE)
        }
        panel(c, rMini, "")
        bustInRing(c, shownRox, rMini.centerX(), rMini.centerY() - d(3f), rMini.height() * 0.36f, 0.9f)
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.4f); p.color = cy; c.drawCircle(rMini.centerX(), rMini.centerY() - d(3f), rMini.height() * 0.4f, p)

        // ---- gauges
        panel(c, rGauge, "System Status")
        val gr = 0.04f * w
        gauge(c, rGauge.left + rGauge.width() * 0.17f, rGauge.centerY() + d(7f), gr, ram, "RAM")
        gauge(c, rGauge.left + rGauge.width() * 0.5f, rGauge.centerY() + d(7f), gr, disk, "Storage")
        gauge(c, rGauge.left + rGauge.width() * 0.83f, rGauge.centerY() + d(7f), gr, batt, "Battery")
        // ---- smart control
        panel(c, rSmart, "Smart Control")
        val now2 = System.currentTimeMillis()
        for (i in 0..3) {
            val key = hexKey[i]
            val hot = (pressed == key && now2 - pressAt < 400) || (key == "torch" && torchOn)
            p.style = Paint.Style.FILL; p.color = if (hot) cy else Color.parseColor("#0B3A5C"); p.alpha = if (hot) 200 else 255
            hex(c, hexC[i].x, hexC[i].y, hexR)
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1.5f); p.color = cy; hex(c, hexC[i].x, hexC[i].y, hexR)
            txt(c, hexName[i], hexC[i].x, hexC[i].y + hexR + d(12f), 6.5f, Color.WHITE, Paint.Align.CENTER)
            drawIcon(c, key, hexC[i].x, hexC[i].y, hexR * 0.5f, if (hot) Color.parseColor("#04121C") else cy)
        }
        // ---- news
        panel(c, rNews, "News Feed")
        var line = news.ifBlank { "Khabrein load ho rahi hain..." }
        tp.textSize = d(10f)
        val maxW = rNews.width() - d(110f)
        if (tp.measureText(line) > maxW) { val n = tp.breakText(line, true, maxW - d(12f), null); line = line.take(n) + "…" }
        txt(c, line, rNews.left + d(10f), rNews.centerY() + d(8f), 10f, Color.WHITE)
        txt(c, "Read More →", rNews.right - d(10f), rNews.centerY() + d(8f), 9.5f, cy, Paint.Align.RIGHT)

        // ---- waveform + mic ON/OFF
        drawWave(c, 0.16f * w, 0.9f * h, 0.28f * w)
        drawWave(c, 0.56f * w, 0.9f * h, 0.40f * w)
        drawMic(c)
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.4f); p.color = cy; p.alpha = 255
        c.drawRoundRect(rKey, d(6f), d(6f), p)
        for (r in 0..1) for (k in 0..3) c.drawCircle(rKey.left + rKey.width() * (0.2f + 0.2f * k), rKey.top + rKey.height() * (0.35f + 0.3f * r), d(0.9f), p)
        // net-guard switch (net na ho to Noor band)
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.4f); p.color = if (netGuard) Color.parseColor("#34D399") else cy
        c.drawRoundRect(rNet, d(6f), d(6f), p)
        for (i in 1..3) c.drawArc(RectF(rNet.centerX() - rNet.width() * 0.13f * i, rNet.centerY() - rNet.width() * 0.13f * i + d(4f), rNet.centerX() + rNet.width() * 0.13f * i, rNet.centerY() + rNet.width() * 0.13f * i + d(4f)), 225f, 90f, false, p)
        p.style = Paint.Style.FILL; p.color = if (netGuard) Color.parseColor("#34D399") else Color.parseColor("#EF4444")
        c.drawCircle(rNet.right - d(7f), rNet.top + d(7f), d(3.5f), p)
        postInvalidateOnAnimation()
    }

    private fun drawMic(c: Canvas) {
        val cx = rMic.centerX(); val cyy = rMic.centerY(); val r = rMic.width() / 2f
        val onC = if (liveOn) Color.parseColor("#34D399") else Color.parseColor("#EF4444")
        val pulse = if (liveOn) 1f + 0.07f * sin(t * 6f) else 1f
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cyy, r * 1.7f * pulse, if (liveOn) Color.parseColor("#5534D399") else Color.parseColor("#4422D3EE"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cyy, r * 1.7f * pulse, p); p.shader = null
        p.color = Color.parseColor("#031A33"); p.alpha = 255; c.drawCircle(cx, cyy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(6f); p.color = onC; p.alpha = 80; c.drawCircle(cx, cyy, r * pulse, p)
        p.strokeWidth = d(2.5f); p.alpha = 255; c.drawCircle(cx, cyy, r * pulse, p)
        p.strokeWidth = d(2f); p.color = Color.WHITE
        val ir = r * 0.5f
        c.drawRoundRect(RectF(cx - ir * 0.22f, cyy - ir * 1.25f, cx + ir * 0.22f, cyy - ir * 0.25f), ir * 0.22f, ir * 0.22f, p)
        c.drawArc(RectF(cx - ir * 0.45f, cyy - ir * 0.95f, cx + ir * 0.45f, cyy + ir * 0.3f), 0f, 180f, false, p)
        c.drawLine(cx, cyy + ir * 0.3f, cx, cyy + ir * 0.5f, p)
        txt(c, if (liveOn) "ON" else "OFF", cx, cyy + r * 0.8f, 12f, onC, Paint.Align.CENTER)
    }

    private fun drawWave(c: Canvas, x0: Float, y: Float, wd: Float) {
        val amp = when (state) { "speaking" -> 1f; "listening" -> 0.6f; "thinking" -> 0.35f; else -> 0.18f }
        p.style = Paint.Style.STROKE; p.strokeWidth = d(1.6f); p.color = cy; p.alpha = 255
        var i = 0
        while (i <= 60) {
            val f = i / 60f
            val env = sin(f * PI.toFloat())
            val hh = abs(sin(f * 40f + t * 9f) * (0.5f + 0.5f * sin(t * 3f + f * 7f))) * d(13f) * amp * env + d(1.5f)
            val x = x0 + wd * f
            c.drawLine(x, y - hh, x, y + hh, p)
            i++
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x; val y = e.y
        if (hypot(x - rMic.centerX(), y - rMic.centerY()) < rMic.width() * 0.7f) { onAction("live"); return true }
        for (i in 0..3) if (hypot(x - hexC[i].x, y - hexC[i].y) < hexR * 1.3f) { pressed = hexKey[i]; pressAt = System.currentTimeMillis(); onAction(pressed); return true }
        if (rKey.contains(x, y)) { onAction("keyboard"); return true }
        if (rNet.contains(x, y)) { onAction("netguard"); return true }
        if (rNews.contains(x, y)) { onAction("news"); return true }
        if (rLogo.contains(x, y)) { onAction("swap"); return true }
        if (y > rChk.top && y < stageFoot && x > w3(0.3f) && x < w3(0.7f)) { onAction("main"); return true }
        return true
    }

    private fun w3(f: Float) = width * f
}

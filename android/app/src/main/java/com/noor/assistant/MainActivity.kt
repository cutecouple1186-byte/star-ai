package com.noor.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Noor ki screen: Home (baat-cheet), Vision (camera), Memory, Settings. */
class MainActivity : Activity() {

    companion object {
        const val DEFAULT_URL = "https://STAR-URL-YAHAN.vercel.app"
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_VISION = "vision"

        @Volatile
        var instance: MainActivity? = null

        @Volatile
        var resumed = false

        /** Camera tab khula ho to uska ek frame (data-URL) do, warna "". */
        fun requestFrame(cb: (String) -> Unit) {
            val a = instance
            if (a == null || !resumed || a.current != 1 || a.web == null) {
                cb("")
                return
            }
            a.runOnUiThread {
                try {
                    a.web!!.evaluateJavascript("(function(){try{return grabFrame()}catch(e){return ''}})()") { v ->
                        cb(try { org.json.JSONArray("[" + (v ?: "null") + "]").optString(0, "").let { if (it == "null") "" else it } } catch (e: Exception) { "" })
                    }
                } catch (e: Exception) {
                    cb("")
                }
            }
        }

        val BG = Color.parseColor("#070B14")
        val CARD = Color.parseColor("#0E1726")
        val LINE = Color.parseColor("#1E3A52")
        val CYAN = Color.parseColor("#22D3EE")
        val PINK = Color.parseColor("#E879F9")
        val TEXT = Color.parseColor("#E6F1FF")
        val MUTED = Color.parseColor("#7F93AD")
        val RED = Color.parseColor("#EF4444")
    }

    private val ui = Handler(Looper.getMainLooper())
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var pages: FrameLayout
    private lateinit var homePage: View
    private lateinit var visionPage: FrameLayout
    private lateinit var memoryPage: View
    private lateinit var chatPage: View
    private lateinit var toolsPage: View
    private val navIdx = listOf(0, 9, 6, 3, -1)
    private lateinit var navBar: View
    private lateinit var chatScroll: ScrollView
    private lateinit var settingsPage: View
    private val navItems = ArrayList<TextView>()
    private lateinit var micBtn: TextView

    // Home
    private lateinit var holoRox: HoloView
    private lateinit var holoNoor: HoloView
    private lateinit var hud: HudView
    private val holo: HoloView get() = if (Persona.isRox) holoRox else holoNoor
    private var lastRox: Boolean? = null
    private lateinit var termBtn: TextView
    private val boxVals = HashMap<String, TextView>()
    private lateinit var statePill: TextView
    private lateinit var greeting: TextView
    private lateinit var titleTv: TextView
    private lateinit var detailTv: TextView
    private lateinit var moodTv: TextView
    private lateinit var logBox: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var input: EditText
    private var rendered = 0

    // Memory
    private lateinit var memTv: TextView

    // Settings
    private lateinit var statusTv: TextView
    private lateinit var autoBtn: Button
    private lateinit var proBtn: Button
    private lateinit var pttBtn: Button
    private lateinit var gateBtn: Button
    private lateinit var netBtn: Button
    private lateinit var liveBtn: android.widget.TextView
    private lateinit var urlEt: EditText
    private lateinit var tokEt: EditText

    private var web: WebView? = null
    private var current = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("noor", Context.MODE_PRIVATE)

    private fun shape(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    private fun tv(text: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun pill(label: String, onClick: () -> Unit): TextView {
        val t = tv(label, 14f, TEXT)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(10), dp(8), dp(10))
        t.background = shape(CARD, 18, LINE, 1)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(TEXT)
        b.background = shape(CARD, 14, LINE, 1)
        b.setOnClickListener { onClick() }
        val p = lp(MATCH, WRAP)
        p.topMargin = dp(8)
        b.layoutParams = p
        return b
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        Persona.load(this)
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        pages = FrameLayout(this)
        buildOldHome()
        homePage = buildHud()
        visionPage = FrameLayout(this)
        memoryPage = buildMemory()
        chatPage = buildChat()
        skillsPage = buildSkills()
        holoPage = HoloPageView(this)
        holoPage.onAction = { if (it == "holo_clear") Hud.put(null) }
        settingsPage = buildSettings()
        pages.addView(homePage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(visionPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(memoryPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(chatPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(toolsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(settingsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(skillsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(holoPage, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(pages, lp(MATCH, 0, 1f))
        navBar = buildNav()
        root.addView(navBar, lp(MATCH, WRAP))
        val top = FrameLayout(this)
        top.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(top)
        show(0)

        // Phone on hone par BootReceiver is screen ko kholta hai; yahan se Noor shuru karke screen peeche kar dete hain.
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            if (!NoorService.isRunning && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    startForegroundService(Intent(this, NoorService::class.java))
                } catch (e: Exception) {
                }
            }
            root.post { moveTaskToBack(true) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent?.getStringExtra(EXTRA_VISION)) {
            "on" -> show(1)
            "holo" -> show(9)
            "off" -> {
                show(0)
                web?.destroy()
                web = null
                visionPage.removeAllViews()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        resumed = true
        hud.removeCallbacks(tick); hud.post(tick)
        Hud.listener = { runOnUiThread { refreshHome() } }
        Chat.listener = { refreshHome(); refreshLiveBtn() }
        refreshHome()
        refreshSettings()
        refreshMemory()
    }

    override fun onPause() {
        resumed = false
        Chat.listener = null
        saveSettings()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            web?.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ navigation
    private fun buildNav(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#020B1A"))
        bar.setPadding(0, dp(6), 0, dp(8))

        fun item(label: String, idx: Int): TextView {
            val t = tv(label, 12f, MUTED)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(6), 0, dp(6))
            t.setOnClickListener { show(idx) }
            navItems.add(t)
            return t
        }

        bar.addView(item("⌂\nHome", 0), lp(0, WRAP, 1f))
        bar.addView(item("🔷\nHologram", 9), lp(0, WRAP, 1f))
        bar.addView(item("▦\nTools", 6), lp(0, WRAP, 1f))

        micBtn = tv("🎙", 26f, Color.WHITE)
        micBtn.gravity = Gravity.CENTER
        micBtn.setOnClickListener { toggleService() }
        val mp = lp(dp(64), dp(64))
        mp.setMargins(dp(6), 0, dp(6), 0)

        bar.addView(item("⚙\nSettings", 3), lp(0, WRAP, 1f))
        val more = item("⋯\nMore", -1)
        more.setOnClickListener { showMore() }
        bar.addView(more, lp(0, WRAP, 1f))
        return bar
    }

    private fun show(idx: Int) {
        current = idx
        homePage.visibility = if (idx == 0) View.VISIBLE else View.GONE
        visionPage.visibility = if (idx == 1) View.VISIBLE else View.GONE
        memoryPage.visibility = if (idx == 4) View.VISIBLE else View.GONE
        chatPage.visibility = if (idx == 2) View.VISIBLE else View.GONE
        toolsPage.visibility = if (idx == 5) View.VISIBLE else View.GONE
        settingsPage.visibility = if (idx == 3) View.VISIBLE else View.GONE
        skillsPage.visibility = if (idx == 6) View.VISIBLE else View.GONE
        if (idx == 6) refreshSkills()
        holoPage.visibility = if (idx == 9) View.VISIBLE else View.GONE
        navBar.visibility = View.VISIBLE
        for (i in navItems.indices) navItems[i].setTextColor(if (navIdx[i] == idx) CYAN else MUTED)
        if (idx == 1) openVision()
        if (idx == 4) refreshMemory()
        if (idx == 2 || idx == 5) refreshHome()
        if (idx == 3) refreshSettings()
    }

    // ------------------------------------------------------------------ Home
    private fun buildOldHome(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        col.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#031A33"), Color.parseColor("#0A1630"), BG))
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(14), dp(16), dp(4))
        titleTv = tv("NOOR", 22f, CYAN, true)
        titleTv.setOnClickListener {
            Persona.set(this, if (Persona.isRox) "noor" else "rox")
            refreshHome()
        }
        titleTv.letterSpacing = 0.25f
        header.addView(titleTv, lp(0, WRAP, 1f))
        statePill = tv("OFF", 11f, TEXT, true)
        statePill.setPadding(dp(12), dp(5), dp(12), dp(5))
        header.addView(statePill, lp(WRAP, WRAP))
        col.addView(header, lp(MATCH, WRAP))
        col.addView(RainbowLine(this), lp(MATCH, dp(3)))

        homeScroll = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), 0, dp(16), dp(8))

        greeting = tv("", 20f, TEXT, true)
        content.addView(greeting, lp(MATCH, WRAP))
        detailTv = tv("", 12f, MUTED)
        content.addView(detailTv, lp(MATCH, WRAP))

        val holoTmp = HoloView(this)
        val stage = FrameLayout(this)
        stage.addView(holoTmp, FrameLayout.LayoutParams(MATCH, MATCH))

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        fun box(key: String, icon: String, title: String, accent: Int, value: String, onClick: () -> Unit): LinearLayout {
            val b = LinearLayout(this)
            b.orientation = LinearLayout.VERTICAL
            b.setPadding(dp(12), dp(10), dp(12), dp(10))
            b.background = shape(Color.parseColor("#0B2A4A"), 14, Color.parseColor("#22D3EE"), 1)
            b.addView(tv("$icon  $title", 11f, Color.parseColor("#7DD3FC"), true))
            val v = tv(value, 14f, TEXT, true)
            v.maxLines = 2
            boxVals[key] = v
            b.addView(v)
            b.setOnClickListener { onClick() }
            return b
        }
        fun rowOf(a: LinearLayout, c: LinearLayout, tgt: LinearLayout = grid) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            val p1 = lp(0, WRAP, 1f); p1.setMargins(dp(3), dp(6), dp(3), 0)
            r.addView(a, p1)
            r.addView(c, p1.let { lp(0, WRAP, 1f).also { q -> q.setMargins(dp(3), dp(6), dp(3), 0) } })
            tgt.addView(r, lp(MATCH, WRAP))
        }
        val green = Color.parseColor("#34D399"); val amber = Color.parseColor("#FBBF24"); val blue = Color.parseColor("#60A5FA")
        val orange = Color.parseColor("#F97316")
        rowOf(box("music", "🎵", "MUSIC", PINK, "Gaane lagao") { sendTyped("YouTube par mere liye achhe gaane lagao") },
            box("weather", "☁", "MAUSAM", blue, "...") { sendTyped("Aaj ka mausam batao") })
        rowOf(box("namaz", "🕌", "NAMAZ", green, "...") { sendTyped("Namaz ke waqt batao") },
            box("sleep", "🌙", "GOOD NIGHT", blue, "Focus + screen off") { sendTyped("Good night") })
        rowOf(box("camera", "📷", "CAMERA", CYAN, "Dekhna shuru") { show(1) },
            box("notif", "🔔", "NOTIFICATION", amber, "Kis ka msg?") { sendTyped("Kis ka msg aaya? Notifications parho") })
        rowOf(box("photo", "🖼", "PHOTO", PINK, "Gallery se bhejo") { pickPhoto() },
            box("face", "🎭", "CHEHRA", orange, "Noor ⇄ Rox") { Persona.set(this, if (Persona.isRox) "noor" else "rox"); refreshHome() })
        rowOf(box("battery", "🔋", "BATTERY", green, "-") { sendTyped("Mera battery aur phone ka haal batao") },
            box("study", "📖", "STUDY", blue, "Padhai mein madad") { sendTyped("Mujhe padhai mein madad chahiye, mujhse sawal poochho") })
        rowOf(box("pass", "🔑", "PASSWORDS", amber, "Sirf phone mein") { show(3) },
            box("vent", "💬", "DIL KI BAAT", orange, "Dil halka karo") { sendTyped("Mera dil halka karna hai, meri baat suno") })
        // ---- Home par sirf chehra + likhne ki jagah; baaki sab "Tools" tab mein ----
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        fun nb(key: String, icon: String, title: String, accent: Int, onClick: () -> Unit): LinearLayout {
            val b = box(key, icon, title, accent, "", onClick)
            b.setPadding(dp(10), dp(9), dp(10), dp(9))
            return b
        }
        rowOf(nb("memory", "🧠", "MEMORY", CYAN) { show(4) },
            nb("skills", "⚡", "SKILLS", Color.parseColor("#34D399")) { show(6) }, top)
        rowOf(nb("char", "🎭", "CHARACTER", Color.parseColor("#EF4444")) { Persona.set(this, if (Persona.isRox) "noor" else "rox"); refreshHome() },
            nb("settings", "⚙", "SETTING", Color.parseColor("#94A3B8")) { show(3) }, top)
        content.addView(top, lp(MATCH, WRAP))
        content.addView(grid, lp(MATCH, WRAP))

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = shape(Color.parseColor("#05121A"), 20, Color.parseColor("#0F6B78"), 2)
        panel.setPadding(dp(6), dp(6), dp(6), dp(8))
        panel.addView(stage, lp(MATCH, 0, 1f))
        termBtn = tv("TERMINATE", 11f, Color.parseColor("#EF4444"), true)
        termBtn.gravity = Gravity.CENTER
        termBtn.letterSpacing = 0.2f
        termBtn.setPadding(dp(8), dp(7), dp(8), dp(7))
        termBtn.background = shape(Color.parseColor("#1A0A0F"), 8, Color.parseColor("#EF4444"), 1)
        termBtn.setOnClickListener { toggleService() }
        panel.addView(termBtn, lp(MATCH, WRAP))
        val pm = lp(MATCH, 0, 1f)
        pm.setMargins(dp(14), dp(10), dp(14), dp(6))
        col.addView(panel, pm)
        moodTv = tv("-", 15f, PINK, true)

        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logBox.setPadding(0, dp(12), 0, 0)

        homeScroll.addView(content)
        homeScroll.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#031A33"), Color.parseColor("#0A1630"), BG))
        toolsPage = homeScroll

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(6), dp(12), dp(8))
        input = EditText(this)
        input.hint = "Noor ko likhkar poochho..."
        input.setHintTextColor(MUTED)
        input.setTextColor(TEXT)
        input.textSize = 15f
        input.maxLines = 3
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.background = shape(CARD, 24, LINE, 1)
        input.setPadding(dp(16), dp(10), dp(16), dp(10))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                doSend()
                true
            } else {
                false
            }
        }
        liveBtn = tv("LIVE", 12f, Color.parseColor("#04121C"), true)
        liveBtn.gravity = Gravity.CENTER
        liveBtn.background = shape(Color.parseColor("#FBBF24"), 24)
        liveBtn.setOnClickListener { toggleLive() }
        val lp2 = lp(dp(52), dp(48))
        lp2.rightMargin = dp(8)
        row.addView(liveBtn, lp2)
        val mic = tv("🎤", 20f, Color.parseColor("#04121C"), true)
        mic.gravity = Gravity.CENTER
        mic.background = shape(PINK, 24)
        mic.setOnClickListener { talkNow() }
        val mp = lp(dp(48), dp(48))
        mp.rightMargin = dp(8)
        row.addView(mic, mp)
        row.addView(input, lp(0, WRAP, 1f))
        val send = tv("➤", 20f, Color.parseColor("#04121C"), true)
        send.gravity = Gravity.CENTER
        send.background = shape(CYAN, 24)
        send.setOnClickListener { doSend() }
        val sp = lp(dp(48), dp(48))
        sp.leftMargin = dp(8)
        row.addView(send, sp)
        col.addView(row, lp(MATCH, WRAP))
        return col
    }

    private fun showMore() {
        android.app.AlertDialog.Builder(this).setItems(arrayOf("💬  Baat-cheet (Chat)", "👁  Vision (Camera)", "🧠  Memory", "📦  Purane boxes")) { _, i -> show(intArrayOf(2, 1, 4, 5)[i]) }.show()
    }

    private fun placeChars(animate: Boolean) {}

    private var newsLink = ""
    private var newsList = ArrayList<Pair<String, String>>()
    private var newsI = 0

    private fun loadNews() {
        Thread {
            try {
                val c = java.net.URL("https://news.google.com/rss?hl=en-PK&gl=PK&ceid=PK:en").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val x = c.inputStream.bufferedReader().use { it.readText() }
                val out = ArrayList<Pair<String, String>>()
                for (m in Regex("<item>.*?<title>(.*?)</title>.*?<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).findAll(x)) {
                    out.add(Pair(m.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\""), m.groupValues[2].trim()))
                    if (out.size >= 12) break
                }
                runOnUiThread { newsList = out; newsI = 0 }
            } catch (e: Exception) {
            }
        }.start()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
                hud.ram = (100 - mi.availMem * 100 / mi.totalMem).toInt()
                hud.batt = (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val sf = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                hud.disk = (100 - sf.availableBytes * 100 / sf.totalBytes).toInt()
                if (newsList.isNotEmpty()) {
                    val n = newsList[newsI % newsList.size]; hud.news = n.first; newsLink = n.second; newsI++
                }
            } catch (e: Exception) {
            }
            hud.postDelayed(this, 6000)
        }
    }

    private fun typeDialog() {
        val et = EditText(this)
        et.hint = "Yahan likhein..."
        android.app.AlertDialog.Builder(this).setView(et).setPositiveButton("Bhejo") { _, _ ->
            val m = et.text.toString().trim()
            if (m.isNotEmpty()) sendTyped(m)
        }.setNegativeButton("Band", null).show()
    }

    private fun hudAction(a: String) {
        when (a) {
            "wifi", "bluetooth", "airplane" -> Thread { val f = Actions.execute(this, "quick_setting", org.json.JSONObject().put("name", a)); if (f != null) runOnUiThread { Toast.makeText(this, f, Toast.LENGTH_SHORT).show() } }.start()
            "torch" -> { hud.torchOn = !hud.torchOn; Actions.execute(this, "torch", org.json.JSONObject().put("on", hud.torchOn)) }
            "keyboard" -> typeDialog()
            "news" -> if (newsLink.isNotBlank()) try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(newsLink))) } catch (e: Exception) {}
            "swap" -> { Persona.set(this, if (Persona.isRox) "noor" else "rox"); refreshHome() }
            "main" -> talkNow()
            "live" -> toggleService()
            "netguard" -> { val v = !prefs().getBoolean("netguard", false); prefs().edit().putBoolean("netguard", v).apply(); hud.netGuard = v; Toast.makeText(this, if (v) "Net na ho to Noor band: ON" else "Net na ho to Noor band: OFF", Toast.LENGTH_SHORT).show() }
            "nav0" -> show(0)
            "holo_clear" -> Hud.put(null)
        }
    }

    private fun buildHud(): View {
        val f = FrameLayout(this)
        hud = HudView(this)
        f.addView(hud, FrameLayout.LayoutParams(MATCH, MATCH))
        holoRox = HoloView(this); holoRox.setPersona(true)
        holoNoor = HoloView(this); holoNoor.setPersona(false)
        hud.onAction = { hudAction(it) }
        hud.netGuard = prefs().getBoolean("netguard", false)
        Thread { val w = Jarvis.weather(this); if (w != null) runOnUiThread { hud.weather = w } }.start()
        loadNews()
        return f
    }

    private lateinit var holoPage: HoloPageView

    private lateinit var skillsPage: View
    private lateinit var skillsView: SkillsView

    private fun buildSkills(): View {
        val sv = ScrollView(this)
        sv.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#020B1A"), Color.parseColor("#04204A")))
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(16), dp(24), dp(16), dp(24))
        val h = tv("SKILLS", 24f, Color.WHITE, true); h.gravity = Gravity.CENTER; h.letterSpacing = 0.2f
        h.setPadding(dp(8), dp(10), dp(8), dp(10)); h.background = shape(Color.parseColor("#0A2A4A"), 12, Color.parseColor("#22D3EE"), 2)
        col.addView(h, lp(MATCH, WRAP))
        skillsView = SkillsView(this)
        col.addView(skillsView, lp(MATCH, WRAP))
        // hologram jaldi banane ke chip
        val chips = LinearLayout(this); chips.orientation = LinearLayout.HORIZONTAL
        for ((nm, q) in listOf("🧠 Brain" to "brain", "🌍 Earth" to "earth planet", "❤ Dil" to "human heart", "🚗 Gaadi" to "sports car", "🛰 Satellite" to "satellite")) {
            val c = tv(nm, 12f, Color.WHITE); c.gravity = Gravity.CENTER; c.setPadding(dp(8), dp(8), dp(8), dp(8))
            c.background = shape(Color.parseColor("#0B3A5C"), 16, Color.parseColor("#22D3EE"), 1)
            c.setOnClickListener { sendTyped("$q ka 3D hologram banao") }
            val q2 = lp(0, WRAP, 1f); q2.setMargins(dp(2), dp(8), dp(2), 0); chips.addView(c, q2)
        }
        col.addView(chips, lp(MATCH, WRAP))
        val list = listOf(
            Triple("📞 Call, SMS, WhatsApp bhejna", "Bolo: 'Ammi ko call karo'", {}),
            Triple("🎵 YouTube / gaane", "Bolo: 'Gaana lagao'", { sendTyped("Koi acha gaana lagao") }),
            Triple("📷 Camera se dekhna (Vision)", "Camera kholta hai", { show(1) }),
            Triple("🌐 Net se search, khabrein", "Aaj ki khabrein poochta hai", { sendTyped("Aaj ki badi khabrein batao") }),
            Triple("🖼 Tasveer banana aur edit", "Bolo: 'Billi ki tasveer banao'", {}),
            Triple("🔷 3D hologram banana", "Hologram page kholta hai", { show(9) }),
            Triple("📱 App kholna, screen par tap/likhna", "Bolo: 'WhatsApp kholo'", {}),
            Triple("⏰ Alarm, timer, yaad-dahani", "Bolo: '10 minute ka timer'", {}),
            Triple("🕌 Namaz ke waqt", "Namaz ke waqt poochta hai", { sendTyped("Namaz ke waqt batao") }),
            Triple("🔦 Torch, WiFi, Bluetooth, Flight", "Bolo: 'Torch jalao'", { Actions.execute(this, "torch", org.json.JSONObject().put("on", true)) }),
            Triple("🔔 Notifications parhna", "Notifications sunata hai", { sendTyped("Meri notifications batao") }),
            Triple("🌙 Good night, focus mode", "Good night chalata hai", { sendTyped("good night") }),
            Triple("🎙 Live baat-cheet (Hello Noor)", "Live shuru/band", { toggleLive() })
        )
        for ((s, hint, act) in list) {
            val r = LinearLayout(this); r.orientation = LinearLayout.VERTICAL
            r.setPadding(dp(12), dp(9), dp(12), dp(9)); r.background = shape(Color.parseColor("#0A2A4A"), 10, Color.parseColor("#0E4A6B"), 1)
            r.addView(tv(s, 14f, Color.WHITE)); r.addView(tv(hint, 11f, Color.parseColor("#7DD3FC")))
            r.setOnClickListener { act(); Toast.makeText(this, hint, Toast.LENGTH_SHORT).show() }
            val q = lp(MATCH, WRAP); q.topMargin = dp(6); col.addView(r, q)
        }
        sv.addView(col)
        return sv
    }

    private fun refreshSkills() {
        val perms = listOf(
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED,
            Settings.canDrawOverlays(this),
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        )
        val mem = (prefs().getString("memories", "") ?: "").split("\n").count { it.isNotBlank() }
        skillsView.bars = listOf(
            "AI (SERVER)" to (if (prefs().getString("token", "").isNullOrBlank()) 0 else 100),
            "YAADEIN (DATA)" to Math.min(100, mem * 10),
            "SYSTEM CONTROL" to perms.count { it } * 100 / perms.size
        )
    }

    private fun buildChat(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#031A33"), Color.parseColor("#0A1630"), BG))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(16), dp(14), dp(16), dp(8))
        head.addView(tv("💬  BAAT-CHEET", 18f, CYAN, true), lp(0, WRAP, 1f))
        val clr = pill("Saaf karo") { Chat.clear(); refreshHome() }
        head.addView(clr, lp(WRAP, WRAP))
        col.addView(head, lp(MATCH, WRAP))
        col.addView(RainbowLine(this), lp(MATCH, dp(3)))
        chatScroll = ScrollView(this)
        logBox.setPadding(dp(14), dp(8), dp(14), dp(8))
        chatScroll.addView(logBox)
        col.addView(chatScroll, lp(MATCH, 0, 1f))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(6), dp(12), dp(8))
        val et = EditText(this)
        et.hint = "Yahan likhein..."
        et.setHintTextColor(MUTED)
        et.setTextColor(TEXT)
        et.textSize = 15f
        et.maxLines = 3
        et.background = shape(CARD, 24, LINE, 1)
        et.setPadding(dp(16), dp(10), dp(16), dp(10))
        val go = tv("➤", 20f, Color.parseColor("#04121C"), true)
        go.gravity = Gravity.CENTER
        go.background = shape(CYAN, 24)
        go.setOnClickListener {
            val m = et.text.toString().trim()
            if (m.isNotEmpty()) {
                et.setText("")
                sendTyped(m)
            }
        }
        row.addView(et, lp(0, WRAP, 1f))
        val gp = lp(dp(48), dp(48))
        gp.leftMargin = dp(8)
        row.addView(go, gp)
        col.addView(row, lp(MATCH, WRAP))
        return col
    }

    private fun showChatDialog() {
        val all = Chat.snapshot()
        val sv = ScrollView(this)
        val tvv = tv(if (all.isEmpty()) "Abhi koi baat nahi." else all.joinToString("\n\n") { (if (it.first == "me") "Aap: " else Persona.name + ": ") + it.second }, 14f, TEXT)
        tvv.setPadding(dp(16), dp(12), dp(16), dp(12))
        sv.addView(tvv)
        android.app.AlertDialog.Builder(this).setTitle("Baat-cheet").setView(sv)
            .setPositiveButton("Theek hai", null)
            .setNeutralButton("Saaf karo") { _, _ -> Chat.clear(); refreshHome() }
            .show()
    }

    private fun refreshBoxes() {
        boxVals["memory"]?.text = Chat.snapshot().size.toString() + " baatein, " + prefs().getString("memories", "").orEmpty().lines().filter { it.isNotBlank() }.size + " yaadein"
        boxVals["skills"]?.text = "40+ kaam"
        boxVals["settings"]?.text = "Ijazaten"
        boxVals["char"]?.text = if (Persona.isRox) "Rox (ladka) ⇄ Noor" else "Noor (ladki) ⇄ Rox"
        try {
            val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) boxVals["battery"]?.text = b.getIntExtra("level", 0).toString() + "%" + (if (b.getIntExtra("plugged", 0) > 0) " charging" else "")
        } catch (e: Exception) {
        }
        boxVals["notif"]?.text = NoorNotif.recent.size.toString() + " nayi"
        if (System.currentTimeMillis() - lastInfo > 300000) {
            lastInfo = System.currentTimeMillis()
            Thread {
                val w = Jarvis.weather(this) ?: "Net nahi"
                val n = Jarvis.nextPrayer(this) ?: "Shehar check karein"
                runOnUiThread {
                    boxVals["weather"]?.text = Jarvis.city(this) + ": " + w
                    boxVals["namaz"]?.text = n
                }
            }.start()
        }
    }

    private var lastInfo = 0L

    /** Header ke neeche chalti rainbow patti. */
    private class RainbowLine(c: Context) : View(c) {
        override fun onDraw(cv: Canvas) {
            cv.drawColor(Color.parseColor("#0E7490"))
        }
    }

    /** Left boxes se hologram panel tak rang-birangi lines (video jaisa). */
    private class NodePanel(c: Context) : LinearLayout(c) {
        var leftCol: LinearLayout? = null
        var panel: View? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cols = intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#34D399"), Color.parseColor("#EF4444"), Color.parseColor("#94A3B8"))
        override fun dispatchDraw(cv: Canvas) {
            super.dispatchDraw(cv)
            val l = leftCol ?: return
            val pn = panel ?: return
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            val n = l.childCount
            for (i in 0 until n) {
                val b = l.getChildAt(i)
                val x0 = (l.left + b.right).toFloat()
                val y0 = (l.top + b.top + b.bottom) / 2f
                val x1 = pn.left.toFloat()
                val y1 = pn.top + pn.height * (i + 1f) / (n + 1f)
                val xm = x0 + (x1 - x0) * 0.5f
                p.color = cols[i % cols.size]
                p.alpha = 200
                val path = android.graphics.Path()
                path.moveTo(x0, y0)
                path.lineTo(xm, y0)
                path.lineTo(xm, y1)
                path.lineTo(x1, y1)
                cv.drawPath(path, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(x1, y1, 5f, p)
                p.style = Paint.Style.STROKE
            }
        }
    }

    /** Chehre se boxes tak jaati lines. */
    private class BusView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.parseColor("#22D3EE")
            p.alpha = 160
            cv.drawLine(w / 2, 0f, w / 2, h * 0.45f, p)
            p.color = Color.parseColor("#E879F9")
            cv.drawLine(w * 0.25f, h * 0.45f, w * 0.75f, h * 0.45f, p)
            p.color = Color.parseColor("#34D399")
            cv.drawLine(w * 0.25f, h * 0.45f, w * 0.25f, h, p)
            cv.drawLine(w * 0.75f, h * 0.45f, w * 0.75f, h, p)
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#22D3EE")
            cv.drawCircle(w / 2, h * 0.45f, 6f, p)
        }
    }

    /** Mic dabakar bolo: Noor ab sirf tumhe sunegi (bina Hey Noor ke). */
    private fun talkNow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions()
            return
        }
        saveSettings()
        if (!NoorService.isRunning) {
            try {
                startForegroundService(Intent(this, NoorService::class.java))
            } catch (e: Exception) {
            }
            pages.postDelayed({ NoorService.talkNow() }, 1200)
        } else {
            NoorService.talkNow()
        }
    }

    private fun toggleLive() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions()
            return
        }
        saveSettings()
        if (!NoorService.isRunning) {
            try {
                startForegroundService(Intent(this, NoorService::class.java))
            } catch (e: Exception) {
            }
            pages.postDelayed({ NoorService.toggleLive() }, 1200)
        } else {
            NoorService.toggleLive()
        }
        pages.postDelayed({ refreshLiveBtn() }, 1500)
    }

    private fun refreshLiveBtn() {
        if (::liveBtn.isInitialized) liveBtn.text = if (NoorService.liveActive) "STOP" else "LIVE"
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try {
            startActivityForResult(Intent.createChooser(i, "Tasveer chunein"), 77)
        } catch (e: Exception) {
            Toast.makeText(this, "Gallery nahi khuli", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 77 || resultCode != RESULT_OK || uri == null) return
        Thread {
            try {
                val opts = android.graphics.BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / sample > 1600) sample *= 2
                val o2 = android.graphics.BitmapFactory.Options()
                o2.inSampleSize = sample
                val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
                    ?: return@Thread
                val k = 768f / maxOf(bmp.width, bmp.height)
                val small = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
                val bo = java.io.ByteArrayOutputStream()
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bo)
                val b64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP)
                runOnUiThread {
                    val q = input.text.toString().trim().ifEmpty { "Is tasveer mein kya hai? Batao." }
                    input.setText("")
                    if (!NoorService.isRunning) {
                        try {
                            startForegroundService(Intent(this, NoorService::class.java))
                        } catch (e: Exception) {
                        }
                        pages.postDelayed({ NoorService.submitImage(q, b64) }, 1200)
                    } else {
                        NoorService.submitImage(q, b64)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Tasveer nahi khuli", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun doSend() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        sendTyped(t)
    }

    /** Likha hua sawaal Noor ko bhejo. Service band ho to pehle shuru karo. */
    private fun sendTyped(text: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Pehle Settings mein button 1 dabakar ijazat dein", Toast.LENGTH_LONG).show()
            askPermissions()
            return
        }
        saveSettings()
        if (NoorService.isRunning) {
            NoorService.submit(text)
            return
        }
        try {
            startForegroundService(Intent(this, NoorService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Noor shuru nahi ho saki", Toast.LENGTH_LONG).show()
            return
        }
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                if (NoorService.instanceReady()) {
                    NoorService.submit(text)
                } else if (tries++ < 8) {
                    ui.postDelayed(this, 400)
                }
            }
        }
        ui.postDelayed(r, 500)
    }

    private fun refreshHome() {
        val st = Chat.state
        holo.state = st
        val (label, color) = when (st) {
            "listening" -> Pair("LISTENING", CYAN)
            "thinking" -> Pair("THINKING", Color.parseColor("#FBBF24"))
            "speaking" -> Pair("SPEAKING", PINK)
            "idle" -> Pair("READY", Color.parseColor("#38BDF8"))
            else -> Pair("OFF", MUTED)
        }
        statePill.text = label
        hud.state = st
        hud.liveOn = NoorService.isRunning
        val lastN = Chat.snapshot().lastOrNull { it.first != "me" }?.second
        hud.bubble = if ((st == "speaking" || st == "thinking") && !lastN.isNullOrBlank()) lastN else "Hello ${if (Persona.isRox) "Boss" else Persona.you},\nHow can I help you today?"
        holoRox.visibility = if (!Persona.isRox) View.VISIBLE else View.INVISIBLE
        holoNoor.visibility = if (!Persona.isRox && Hud.holo == null) View.VISIBLE else View.INVISIBLE
        hud.rox = Persona.isRox
        holoRox.state = if (Persona.isRox) st else "off"
        holoNoor.state = if (!Persona.isRox) st else "off"
        if (lastRox != Persona.isRox) { lastRox = Persona.isRox; placeChars(true) }
        statePill.setTextColor(color)
        statePill.background = shape(Color.parseColor("#0B1626"), 14, color, 1)
        moodTv.text = when (st) {
            "listening" -> "Sun rahi"
            "thinking" -> "Soch rahi"
            "speaking" -> "Bol rahi"
            "idle" -> "Taiyaar"
            else -> "Soyi hui"
        }
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = if (h < 12) "Subah bakhair" else if (h < 17) "Assalam o Alaikum" else "Shaam bakhair"
        greeting.text = "$hello, ${Persona.you}"
        titleTv.text = Persona.name + " ⇄"
        titleTv.setTextColor(if (Persona.isRox) Color.parseColor("#F97316") else CYAN)
        holo.setPersona(Persona.isRox)
        termBtn.text = if (NoorService.isRunning) "TERMINATE" else "START"
        refreshBoxes()
        detailTv.text = if (Chat.detail.isNotEmpty()) Chat.detail
        else if (NoorService.isRunning) (if (Persona.isRox) "Rox sun raha hai. \"Hey Rox\" ya \"Hey Noor\" bolein, ya likhein." else "Noor sun rahi hai. \"Hey Noor\" ya \"Hey Rox\" bolein, ya likhein.")
        else "Noor band hai. Neeche mic dabakar shuru karein."

        micBtn.text = if (NoorService.isRunning) "■" else "🎙"
        micBtn.background = shape(if (NoorService.isRunning) RED else CYAN, 32)

        val all = Chat.snapshot()
        if (all.size < rendered) {
            logBox.removeAllViews()
            rendered = 0
        }
        var added = false
        for (i in rendered until all.size) {
            addBubble(all[i].first, all[i].second)
            added = true
        }
        rendered = all.size
        if (added) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(who: String, text: String) {
        val me = who == "me"
        val t = tv(text, 15f, if (me) Color.parseColor("#04121C") else TEXT)
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.background = shape(if (me) CYAN else CARD, 18, if (me) CYAN else LINE, 1)
        t.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val p = lp(WRAP, WRAP)
        p.gravity = if (me) Gravity.END else Gravity.START
        p.topMargin = dp(6)
        logBox.addView(t, p)
    }

    // ------------------------------------------------------------------ Vision (camera)
    private fun openVision() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (visionPage.childCount == 0) {
                val m = tv("Camera ki ijazat chahiye. Allow karein, phir ye tab dobara kholein.", 15f, TEXT)
                m.gravity = Gravity.CENTER
                m.setPadding(dp(24), dp(24), dp(24), dp(24))
                visionPage.addView(m, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        if (web != null) return
        visionPage.removeAllViews()
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    val wantsCam = request?.resources?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true
                    val ok = wantsCam && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (ok) request?.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request?.deny()
                }
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                result?.confirm(prefs().getString("token", "") ?: "")
                return true
            }
        }
        val base = (prefs().getString("url", DEFAULT_URL) ?: DEFAULT_URL).trim().trimEnd('/')
        w.loadUrl(base + "/?app=1")
        visionPage.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        web = w
    }

    // ------------------------------------------------------------------ Memory
    private fun buildMemory(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(24), dp(18), dp(18))
        val t = tv("Yaad rakha hua", 22f, CYAN, true)
        col.addView(t, lp(MATCH, WRAP))
        col.addView(tv("Noor ko bolo: \"ye yaad rakho ...\"", 12f, MUTED), lp(MATCH, WRAP))
        memTv = tv("", 16f, TEXT)
        memTv.setPadding(0, dp(14), 0, dp(10))
        col.addView(memTv, lp(MATCH, WRAP))
        col.addView(button("Sab mita do") {
            prefs().edit().putString("memories", "").apply()
            refreshMemory()
        })
        sv.addView(col)
        return sv
    }

    private fun refreshMemory() {
        val list = (prefs().getString("memories", "") ?: "").split("\n").filter { it.isNotBlank() }
        memTv.text = if (list.isEmpty()) "Abhi kuch yaad nahi hai." else list.joinToString("\n\n") { "• $it" }
    }

    // ------------------------------------------------------------------ Settings
    private fun buildSettings(): View {
        val sv = ScrollView(this)
        sv.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#020B1A"), Color.parseColor("#04204A")))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(24), dp(18), dp(24))

        val sh = tv("SETTINGS", 24f, Color.WHITE, true); sh.gravity = Gravity.CENTER; sh.letterSpacing = 0.2f
        sh.setPadding(dp(8), dp(10), dp(8), dp(10)); sh.background = shape(Color.parseColor("#0A2A4A"), 12, Color.parseColor("#22D3EE"), 2)
        col.addView(sh, lp(MATCH, WRAP))
        statusTv = tv("", 14f, TEXT)
        statusTv.setPadding(0, dp(8), 0, dp(4))
        col.addView(statusTv, lp(MATCH, WRAP))

        var cur: LinearLayout = col
        fun sec(title: String, accent: Int) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.background = shape(Color.parseColor("#0A2A4A"), 16, Color.parseColor("#22D3EE"), 1)
            val hd = tv(title, 12f, Color.parseColor("#7DD3FC"), true)
            hd.letterSpacing = 0.12f
            card.addView(hd)
            val p = lp(MATCH, WRAP)
            p.setMargins(0, dp(14), 0, 0)
            col.addView(card, p)
            cur = card
        }
        sec("🔊  SYSTEM SOUND", CYAN)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val vol = android.widget.SeekBar(this)
        vol.max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        vol.progress = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        vol.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22D3EE"))
        vol.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        vol.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, u: Boolean) { if (u) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        cur.addView(vol, lp(MATCH, WRAP))
        sec("✨  GRAPHICS QUALITY", CYAN)
        val gq = LinearLayout(this); gq.orientation = LinearLayout.HORIZONTAL
        val gqs = ArrayList<TextView>()
        fun paintGq() { val q = prefs().getInt("gfx", 1); for ((i, v) in gqs.withIndex()) { v.setTextColor(if (i == q) Color.parseColor("#04121C") else TEXT); v.background = shape(if (i == q) Color.parseColor("#22D3EE") else Color.parseColor("#0B3A5C"), 16, Color.parseColor("#22D3EE"), 1) } }
        for ((i, nm) in listOf("LOW", "MEDIUM", "ULTRA").withIndex()) {
            val v = tv(nm, 13f, TEXT, true); v.gravity = Gravity.CENTER; v.setPadding(dp(6), dp(10), dp(6), dp(10))
            v.setOnClickListener { prefs().edit().putInt("gfx", i).apply(); hud.quality = i; paintGq() }
            gqs.add(v); val q = lp(0, WRAP, 1f); q.setMargins(dp(3), dp(6), dp(3), 0); gq.addView(v, q)
        }
        paintGq()
        cur.addView(gq, lp(MATCH, WRAP))
        sec("🔷  HOLOGRAPHIC FOCUS", CYAN)
        val fo = android.widget.SeekBar(this); fo.max = 60; fo.progress = prefs().getInt("focus", 40)
        fo.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22D3EE"))
        fo.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        fo.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, u: Boolean) { if (u) { prefs().edit().putInt("focus", v).apply(); hud.focus = 0.4f + v / 100f } }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        cur.addView(fo, lp(MATCH, WRAP))
        sec("🔌  CONNECTION", CYAN)
        cur.addView(tv("Server (Vercel ka link)", 13f, MUTED), lp(MATCH, WRAP))
        urlEt = EditText(this)
        urlEt.setText(prefs().getString("url", DEFAULT_URL))
        urlEt.setTextColor(TEXT)
        urlEt.inputType = InputType.TYPE_TEXT_VARIATION_URI
        cur.addView(urlEt, lp(MATCH, WRAP))

        cur.addView(tv("Password (jo Vercel mein APP_TOKEN hai)", 13f, MUTED), lp(MATCH, WRAP))
        tokEt = EditText(this)
        tokEt.setText(prefs().getString("token", ""))
        tokEt.setTextColor(TEXT)
        tokEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        cur.addView(tokEt, lp(MATCH, WRAP))

        sec("🔐  IJAZATEN (pehli baar zaroor)", Color.parseColor("#34D399"))
        cur.addView(button("1) Mic, camera, contacts aur notification allow karo") { askPermissions() })
        cur.addView(button("2) Dusri app ke upar dikhne do (apps kholne ke liye)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        cur.addView(button("3) Battery: background mein chalne do") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Toast.makeText(this, "Settings se Battery > Noor > No restrictions chunein", Toast.LENGTH_LONG).show()
            }
        })
        cur.addView(button("4) App info (Autostart / Background allow karein)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        cur.addView(button("5) WhatsApp message khud bhejne ke liye: Accessibility mein Noor ON karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        sec("⚙  BEHAVIOUR", PINK)
        proBtn = button("") {
            prefs().edit().putBoolean("proactive", !prefs().getBoolean("proactive", true)).apply()
            refreshSettings()
        }
        cur.addView(proBtn)
        pttBtn = button("") {
            prefs().edit().putBoolean("ptt", !prefs().getBoolean("ptt", false)).apply()
            refreshSettings()
        }
        cur.addView(pttBtn)
        gateBtn = button("") {
            val cur = prefs().getInt("gate", 900)
            val nxt = when (cur) { 0 -> 600; 600 -> 900; 900 -> 1500; else -> 0 }
            prefs().edit().putInt("gate", nxt).apply()
            refreshSettings()
        }
        cur.addView(gateBtn)
        netBtn = button("") {
            prefs().edit().putBoolean("netguard", !prefs().getBoolean("netguard", false)).apply()
            hud.netGuard = prefs().getBoolean("netguard", false)
            refreshSettings()
        }
        cur.addView(netBtn)
        autoBtn = button("") {
            prefs().edit().putBoolean("autostart", !prefs().getBoolean("autostart", true)).apply()
            refreshSettings()
        }
        cur.addView(autoBtn)
        sec("🔑  PASSWORDS (sirf is phone mein, encrypted)", Color.parseColor("#FBBF24"))
        val pl = EditText(this).apply { hint = "Naam (jaise instagram)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true) }
        val pv = EditText(this).apply { hint = "Password"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        cur.addView(pl)
        cur.addView(pv)
        cur.addView(button("Password save karo") {
            if (Secrets.put(this, pl.text.toString(), pv.text.toString())) {
                Toast.makeText(this, "Save: " + pl.text.toString().trim(), Toast.LENGTH_SHORT).show()
                pl.setText(""); pv.setText("")
            } else Toast.makeText(this, "Naam aur password dono likhein", Toast.LENGTH_SHORT).show()
        })
        cur.addView(button("Saved naam dekho / hatao") {
            val ls = Secrets.labels(this)
            if (ls.isEmpty()) Toast.makeText(this, "Koi password nahi", Toast.LENGTH_SHORT).show()
            else android.app.AlertDialog.Builder(this).setTitle("Hatane ke liye dabayein")
                .setItems(ls.toTypedArray()) { _, i -> Secrets.remove(this, ls[i]); Toast.makeText(this, "Hata diya", Toast.LENGTH_SHORT).show() }
                .show()
        })
        sec("🕌  SHEHAR (namaz aur mausam ke liye)", Color.parseColor("#60A5FA"))
        val cityEt = EditText(this).apply { setText(prefs().getString("city", "Karachi")); setTextColor(Color.WHITE); setSingleLine(true) }
        cur.addView(cityEt)
        cur.addView(button("Shehar save karo") {
            prefs().edit().putString("city", cityEt.text.toString().trim().ifBlank { "Karachi" }).apply()
            Toast.makeText(this, "Shehar save", Toast.LENGTH_SHORT).show()
        })
        sec("🎭  CHEHRA (asli jaisa banao)", Color.parseColor("#E879F9"))
        fun makeFace(who: String, prompt: String) {
            Toast.makeText(this, who + " ka chehra ban raha hai (1 minute)...", Toast.LENGTH_LONG).show()
            val base = prefs().getString("url", DEFAULT_URL)!!.trim().trimEnd('/')
            val token = prefs().getString("token", "")!!
            Thread {
                val bytes = Net.image(base, token, prompt)
                runOnUiThread {
                    if (bytes == null || bytes.size < 1000) {
                        Toast.makeText(this, "Chehra nahi ban saka, thodi der baad dobara dabayein.", Toast.LENGTH_LONG).show()
                    } else {
                        java.io.File(filesDir, "face_" + who.lowercase() + ".png").writeBytes(bytes)
                        holoRox.reload(); holoNoor.reload()
                        Toast.makeText(this, who + " ka naya chehra lag gaya. Home dekhein.", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
        cur.addView(button("Noor ka asli jaisa chehra banao") {
            makeFace("Noor", "Photorealistic centered front-facing head-and-shoulders portrait of an original fictional young woman with round glasses and long dark hair with bangs, relaxed neutral expression with lips gently closed, looking straight at the camera, soft even studio light, plain dark background. Not a real person.")
        })
        cur.addView(button("Rox ka asli jaisa chehra banao") {
            makeFace("Rox", "Photorealistic centered front-facing head-and-shoulders portrait of an original fictional young man with thin round glasses and short messy black hair, wearing a dark blazer over a white shirt, relaxed neutral expression with lips gently closed, looking straight at the camera, soft even studio light, plain dark background. Not a real person.")
        })
        cur.addView(button("Hont thora upar") {
            val k = "mouthY2_" + (if (Persona.isRox) "rox" else "noor")
            prefs().edit().putFloat(k, prefs().getFloat(k, if (Persona.isRox) 0.482f else 0.814f) - 0.012f).apply(); holoRox.reload(); holoNoor.reload()
        })
        cur.addView(button("Hont thora neeche") {
            val k = "mouthY2_" + (if (Persona.isRox) "rox" else "noor")
            prefs().edit().putFloat(k, prefs().getFloat(k, if (Persona.isRox) 0.482f else 0.814f) + 0.012f).apply(); holoRox.reload(); holoNoor.reload()
        })
        cur.addView(button("Rangeen tasveer ⇄ Dots hologram") {
            prefs().edit().putBoolean("dots", !prefs().getBoolean("dots", false)).apply()
        })
        cur.addView(button("Cartoon chehra wapas") {
            java.io.File(filesDir, "face_noor.png").delete(); java.io.File(filesDir, "face_rox.png").delete(); holoRox.reload(); holoNoor.reload()
        })
        sec("🔔  EXTRA IJAZATEN", Color.parseColor("#F97316"))
        cur.addView(button("Notification parhne ki ijazat (Noor)") {
            try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) {}
        })
        cur.addView(button("Brightness/Settings badalne ki ijazat") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        })
        cur.addView(button("Focus/Do Not Disturb ijazat") {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } catch (e: Exception) {}
        })
        col.addView(button("Save karo") {
            saveSettings()
            Toast.makeText(this, "Save ho gaya", Toast.LENGTH_SHORT).show()
        })
        sv.addView(col)
        return sv
    }

    private fun refreshSettings() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        fun m(ok: Boolean) = if (ok) "OK" else "baaki"
        statusTv.text = "Mic: ${m(mic)}   Camera: ${m(cam)}   Contacts: ${m(contacts)}" +
            "\nApps kholna: ${m(overlay)}   Background: ${m(battery)}"
        val auto = prefs().getBoolean("autostart", true)
        netBtn.text = "Net na ho to Noor band rahe: " + (if (prefs().getBoolean("netguard", false)) "ON" else "OFF") + "  (dabakar badlein)"
        gateBtn.text = "Live mein awaaz filter (door ki awaaz band): " + when (prefs().getInt("gate", 900)) { 0 -> "BAND"; 600 -> "HALKA"; 900 -> "DARMIYANA"; else -> "SAKHT" } + "  (dabakar badlein)"
        pttBtn.text = "Sirf mic dabane par sunna (Hey Noor ke bina): " + (if (prefs().getBoolean("ptt", false)) "ON" else "OFF")
        proBtn.text = "Noor khud baat kare (trending, gappein): " + (if (prefs().getBoolean("proactive", true)) "ON" else "OFF")
        autoBtn.text = "Phone on hote hi Noor khud shuru: " + (if (auto) "ON" else "OFF")
    }

    private fun saveSettings() {
        prefs().edit()
            .putString("url", urlEt.text.toString().trim().ifEmpty { DEFAULT_URL })
            .putString("token", tokEt.text.toString().trim())
            .apply()
    }

    // ------------------------------------------------------------------ permissions / service
    private fun askPermissions() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        list.add(Manifest.permission.CAMERA)
        list.add(Manifest.permission.READ_CONTACTS)
        list.add(Manifest.permission.CALL_PHONE)
        list.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        list.add(if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissions(list.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSettings()
        if (requestCode == 2 && current == 1) openVision()
    }

    private fun toggleService() {
        saveSettings()
        if (NoorService.isRunning) {
            startService(Intent(this, NoorService::class.java).setAction(NoorService.ACTION_STOP))
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Pehle Settings mein button 1 dabakar mic allow karein", Toast.LENGTH_LONG).show()
                askPermissions()
                return
            }
            startForegroundService(Intent(this, NoorService::class.java))
        }
        ui.postDelayed({ refreshHome() }, 700)
    }
}

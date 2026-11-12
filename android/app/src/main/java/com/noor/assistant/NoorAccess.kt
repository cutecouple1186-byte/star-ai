package com.noor.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Do kaam: (1) WhatsApp ka Send button dabana (sirf jab Noor ne message bhejne ko kaha ho),
 * (2) tumhare bolne par screen chalana: back, home, scroll, swipe, tap.
 */
class NoorAccess : AccessibilityService() {

    companion object {
        @Volatile
        var pendingUntil = 0L

        @Volatile
        var instance: NoorAccess? = null

        fun arm() {
            pendingUntil = System.currentTimeMillis() + 20000
        }

        /** Abhi screen par jo likha hai (buttons ke naam waghera), LLM ko dikhane ke liye. */
        fun readScreen(): String {
            val s = instance ?: return ""
            return s.dump()
        }

        /** Screen ka kaam. null = ho gaya, warna wajah (bolne ke liye). */
        fun screen(action: String, text: String, x: Int, y: Int): String? {
            val s = instance ?: return "Pehle Settings mein Accessibility mein Noor ko ON karein."
            return s.doAction(action, text, x, y)
        }
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > pendingUntil) return
        val root = rootInActiveWindow ?: return
        val btn = findSend(root) ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            pendingUntil = 0L
        }
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (!l.isNullOrEmpty()) return l[0]
        }
        for (d in listOf("Send", "send", "بھیجیں", "भेजें")) {
            val l = root.findAccessibilityNodeInfosByText(d)
            val hit = l?.firstOrNull { it.isClickable || it.contentDescription?.toString().equals(d, true) }
            if (hit != null) return hit
        }
        return null
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    private fun doAction(action: String, text: String, x: Int, y: Int): String? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val ok = when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "lock" -> if (android.os.Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "scroll_down" -> swipe(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f, 300)
            "scroll_up" -> swipe(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.72f, 300)
            "swipe_left" -> swipe(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f, 250)
            "swipe_right" -> swipe(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f, 250)
            "tap" -> swipe(w * x.coerceIn(0, 100) / 100f, h * y.coerceIn(0, 100) / 100f, w * x.coerceIn(0, 100) / 100f + 1f, h * y.coerceIn(0, 100) / 100f + 1f, 60)
            "tap_text" -> return tapText(text)
            "quick" -> return quickTile(text)
            "type" -> return typeText(text)
            "enter" -> return pressEnter()
            else -> return "Ye screen ka kaam mujhe samajh nahi aaya."
        }
        return if (ok) null else "Screen ka kaam nahi ho saka."
    }

    private val mainH = android.os.Handler(android.os.Looper.getMainLooper())

    /** text = "naam1|naam2|...#on" ya "#off" ya khali. Quick Settings panel khol kar tile dabata hai. */
    private fun quickTile(spec: String): String? {
        val names = spec.substringBefore("#").split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val want = when (spec.substringAfter("#", "")) { "on" -> true; "off" -> false; else -> null }
        if (names.isEmpty()) return "Kaun si setting?"
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        mainH.postDelayed({ tryTile(names, want, 0) }, 1000)
        return null
    }

    private fun tryTile(names: List<String>, want: Boolean?, attempt: Int) {
        val root = rootInActiveWindow
        var done = false
        if (root != null) {
            for (nm in names) {
                val l = root.findAccessibilityNodeInfosByText(nm) ?: continue
                var n: AccessibilityNodeInfo? = l.firstOrNull() ?: continue
                var hop = 0
                while (n != null && !n.isClickable && hop < 5) { n = n.parent; hop++ }
                if (n == null) continue
                var state: Boolean? = null
                try {
                    val sd = if (android.os.Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString()?.lowercase() ?: "" else ""
                    val cd = n.contentDescription?.toString()?.lowercase() ?: ""
                    val all = sd + " " + cd
                    state = if (n.isChecked) true else if (sd.contains("off") || cd.contains("off")) false
                    else if (all.contains(" on") || all.startsWith("on")) true else null
                } catch (e: Exception) {
                }
                if (want == null || state == null || state != want) n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                done = true
                break
            }
        }
        if (done) {
            mainH.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 700)
        } else if (attempt < 2) {
            // panel ke neeche wale tiles ke liye upar swipe
            val dm = resources.displayMetrics
            swipe(dm.widthPixels * 0.5f, dm.heightPixels * 0.75f, dm.widthPixels * 0.5f, dm.heightPixels * 0.35f, 250)
            mainH.postDelayed({ tryTile(names, want, attempt + 1) }, 900)
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun tapText(text: String): String? {
        if (text.isBlank()) return "Kis button par dabana hai?"
        val root = rootInActiveWindow ?: return "Screen padh nahi saki."
        val list = root.findAccessibilityNodeInfosByText(text)
        var n: AccessibilityNodeInfo? = list?.firstOrNull() ?: return "Screen par \"$text\" nahi mila."
        while (n != null && !n.isClickable) n = n.parent
        if (n == null) return "\"$text\" dabane layak nahi hai."
        return if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) null else "Dabane mein masla aaya."
    }

    private fun typeText(text: String): String? {
        val root = rootInActiveWindow ?: return "Screen padh nahi saki."
        var f: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f == null) f = firstEditable(root)
        if (f == null) return "Likhne ki jagah nahi mili."
        val b = android.os.Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) null else "Likh nahi saki."
    }

    private fun firstEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = firstEditable(c)
            if (r != null) return r
        }
        return null
    }

    private fun pressEnter(): String? {
        val root = rootInActiveWindow ?: return "Screen padh nahi saki."
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root)
        if (f != null && android.os.Build.VERSION.SDK_INT >= 30 &&
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) return null
        for (d in listOf("Search", "search", "Go", "Enter")) {
            val l = root.findAccessibilityNodeInfosByText(d)
            var n: AccessibilityNodeInfo? = l?.firstOrNull()
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return null
        }
        return "Enter nahi dab saka."
    }

    private fun dump(): String {
        val root = rootInActiveWindow ?: return ""
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || out.size > 60) return
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (t.isNotEmpty() && t.length < 60) out.add(if (n.isClickable) "[$t]" else t)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return (root.packageName?.toString() ?: "") + ": " + out.joinToString(" | ")
    }

    override fun onInterrupt() {}
}

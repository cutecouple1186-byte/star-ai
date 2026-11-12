package com.noor.assistant

import android.os.Handler
import android.os.Looper

/** Service aur screen ke beech ka chhota sa pul: baat-cheet ki list aur Noor ki halat. */
object Chat {
    private val items = ArrayList<Pair<String, String>>()

    /** off, idle, listening, thinking, speaking */
    @Volatile
    var state = "off"

    @Volatile
    var detail = ""

    @Volatile
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun add(who: String, text: String) {
        items.add(Pair(who, text))
        while (items.size > 100) items.removeAt(0)
        ping()
    }

    @Synchronized
    fun clear() {
        items.clear()
        ping()
    }

    @Synchronized
    fun snapshot(): List<Pair<String, String>> = ArrayList(items)

    fun set(newState: String, newDetail: String? = null) {
        state = newState
        if (newDetail != null) detail = newDetail
        ping()
    }

    private fun ping() {
        main.post { listener?.invoke() }
    }
}

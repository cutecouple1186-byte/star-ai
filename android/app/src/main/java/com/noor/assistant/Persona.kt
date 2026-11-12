package com.noor.assistant

import android.content.Context

/** Noor (ladki, "Rizwan") ya Rox (ladka, "Sir"). Dono ka apna andaz, par yaadein ek. */
object Persona {
    @Volatile
    var id = "rox"

    val isRox: Boolean get() = id == "rox"
    val name: String get() = if (isRox) "STAR" else "NOOR"
    val title: String get() = if (isRox) "Star" else "Noor"
    val you: String get() = if (isRox) "Sir" else "Rizwan"

    fun load(ctx: Context) {
        id = ctx.getSharedPreferences("noor", Context.MODE_PRIVATE).getString("persona", "rox") ?: "rox"
    }

    fun set(ctx: Context, v: String) {
        id = "rox"
        ctx.getSharedPreferences("noor", Context.MODE_PRIVATE).edit().putString("persona", id).apply()
    }
}

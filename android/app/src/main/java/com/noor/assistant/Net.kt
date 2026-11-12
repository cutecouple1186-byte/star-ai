package com.noor.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Noor ke server (Vercel) se baat: /chat (jawab) aur /tts (awaaz). */
object Net {

    private fun fail(reply: String): JSONObject =
        JSONObject().put("reply", reply).put("intent", "none").put("slots", JSONObject())

    private fun open(url: String, token: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("x-app-token", token)
        return c
    }

    fun chat(
        base: String,
        token: String,
        message: String,
        history: List<Pair<String, String>>,
        memories: List<String>,
        context: String,
        image: String = "",
        persona: String = Persona.id
    ): JSONObject {
        val body = JSONObject()
        body.put("message", message)
        val h = JSONArray()
        for ((role, content) in history.takeLast(12)) {
            h.put(JSONObject().put("role", role).put("content", content))
        }
        body.put("history", h)
        val m = JSONArray()
        for (x in memories.takeLast(20)) m.put(x)
        body.put("memories", m)
        body.put("context", context)
        body.put("persona", persona)
        if (image.isNotBlank()) body.put("image", image)

        var last = fail("Maaf kijiye, abhi mujhe jawab nahi mil raha.")
        for (attempt in 0..1) {
            try {
                val c = open("$base/chat", token)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 401) return fail("Password galat hai. App mein dobara daalein.")
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) return JSONObject(text)
                last = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    last
                }
            } catch (e: Exception) {
                last = fail("Internet check karein, server se connection nahi ho paya.")
            }
        }
        return last
    }

    /** Server ki Gemini awaaz (WAV). Na chale to null, tab phone ki apni awaaz use hoti hai. */
    fun tts(base: String, token: String, text: String, dir: File): File? {
        return try {
            val c = open("$base/tts", token)
            c.connectTimeout = 4000
            c.readTimeout = 7000
            c.outputStream.use {
                it.write(JSONObject().put("text", text).put("persona", Persona.id).toString().toByteArray(Charsets.UTF_8))
            }
            if (c.responseCode != 200) return null
            val f = File(dir, "noor_tts.wav")
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() < 100) null else f
        } catch (e: Exception) {
            null
        }
    }

    /** Aaj Pakistan mein kya trend/viral hai, Noor ke andaz mein 1-2 jumle. Khali ho to kuch nahi bolna. */
    fun trending(base: String, token: String): String {
        return try {
            val c = open("$base/trending", token)
            c.readTimeout = 20000
            c.outputStream.use { it.write("{}".toByteArray()) }
            if (c.responseCode != 200) return ""
            JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optString("reply")
        } catch (e: Exception) {
            ""
        }
    }

    /** Server se tasveer (bytes) lo. Na bane to null. */
    fun holo(base: String, token: String, subject: String): ByteArray? {
        return try {
            val c = open("$base/holo", token)
            c.connectTimeout = 8000
            c.readTimeout = 70000
            c.outputStream.use { it.write(JSONObject().put("subject", subject).toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) return null
            val j = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            android.util.Base64.decode(j.optString("image"), android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    fun image(base: String, token: String, prompt: String, src: String = ""): ByteArray? {
        return try {
            val c = open("$base/image", token)
            c.connectTimeout = 8000
            c.readTimeout = 60000
            c.outputStream.use { it.write(JSONObject().put("prompt", prompt).put("image", src).toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) return null
            val j = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            android.util.Base64.decode(j.optString("image"), android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    /** Yaadein internet par ek hain: phone ki yaadein bhejo, saari wapas lo. Na ho paye to null. */
    fun memorySync(base: String, token: String, local: List<String>): List<String>? {
        return try {
            val c = open("$base/memory", token)
            c.connectTimeout = 8000
            c.readTimeout = 12000
            val arr = JSONArray()
            for (x in local.takeLast(40)) arr.put(x)
            c.outputStream.use { it.write(JSONObject().put("fact", arr).toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) return null
            val g = URL("$base/memory").openConnection() as HttpURLConnection
            g.setRequestProperty("x-app-token", token)
            g.connectTimeout = 8000
            g.readTimeout = 12000
            if (g.responseCode != 200) return null
            val j = JSONObject(g.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (!j.optBoolean("cloud", false)) return null
            val f = j.optJSONArray("facts") ?: return null
            (0 until f.length()).map { f.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Internet se abhi ka jawab (Google search ke saath Gemini). Khali = nahi mila. */
    fun search(base: String, token: String, q: String): String {
        return try {
            val c = open("$base/search", token)
            c.connectTimeout = 8000
            c.readTimeout = 25000
            c.outputStream.use { it.write(JSONObject().put("q", q).toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) return ""
            JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optString("text")
        } catch (e: Exception) {
            ""
        }
    }
}

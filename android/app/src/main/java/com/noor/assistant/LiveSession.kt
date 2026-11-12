package com.noor.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gemini Live: real-time awaaz. Mic ki awaaz seedha bhejti hai, jawab ki awaaz seedha bajati hai.
 * Beech mein tok sakte ho. Phone ke kaam "do_action" function se hote hain.
 */
class LiveSession(
    private val base: String,
    private val token: String,
    private val memories: String,
    private val gateMin: Int,
    private val onState: (String) -> Unit,
    private val onText: (String, String) -> Unit,
    private val onTool: (String, JSONObject, (String) -> Unit) -> Unit,
    private val onEnd: (String) -> Unit
) {
    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var closed = false

    @Volatile
    private var ready = false

    @Volatile
    private var halfDuplex = false

    @Volatile
    private var playing = false

    private var key = ""
    private var system = ""
    private var voice = "Leda"
    private var combos: List<Pair<String, Int>> = emptyList()

    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private val q = LinkedBlockingQueue<ByteArray>()
    private var userBuf = StringBuilder()
    private var modelBuf = StringBuilder()
    private var lastAudioAt = 0L

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun start() {
        Thread {
            try {
                val c = URL("$base/live-config?persona=${Persona.id}").openConnection() as HttpURLConnection
                c.setRequestProperty("x-app-token", token)
                c.connectTimeout = 8000
                c.readTimeout = 10000
                if (c.responseCode != 200) {
                    end("Live ki settings nahi mil sakin. Password check karein.")
                    return@Thread
                }
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                key = j.optString("key")
                system = j.optString("system")
                voice = j.optString("voice", "Leda")
                val ms = j.optJSONArray("models") ?: JSONArray()
                val list = ArrayList<Pair<String, Int>>()
                for (i in 0 until ms.length()) {
                    list.add(Pair(ms.getString(i), 0))
                    list.add(Pair(ms.getString(i), 1))
                }
                combos = list
                if (key.isBlank() || combos.isEmpty()) {
                    end("Live ke liye key nahi mili.")
                    return@Thread
                }
                connect(0)
            } catch (e: Exception) {
                end("Live shuru nahi ho saka: internet check karein.")
            }
        }.start()
    }

    private fun setupJson(model: String, shape: Int): String {
        val speech = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
        )
        val fn = JSONObject()
            .put("name", "do_action")
            .put("description", "Phone par kaam karo (app kholna, call, alarm, screen tap waghera).")
            .put(
                "parameters",
                JSONObject().put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("intent", JSONObject().put("type", "STRING").put("description", "kaam ka naam"))
                            .put("args_json", JSONObject().put("type", "STRING").put("description", "JSON string mein tafseel"))
                    )
                    .put("required", JSONArray().put("intent"))
            )
        val fnSearch = JSONObject()
            .put("name", "web_search")
            .put("description", "Internet par abhi ki jaankari dhoondo (dollar rate, khabar, mausam, score, koi bhi taza cheez).")
            .put(
                "parameters",
                JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("query", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("query"))
            )
        val fnPersona = JSONObject()
            .put("name", "switch_persona")
            .put("description", "Dusre assistant ko bulao: name 'rox' ya 'noor'.")
            .put(
                "parameters",
                JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("name", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("name"))
            )
        val su = JSONObject()
            .put("model", "models/$model")
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system + "\nUser ke baare mein yaadein: " + memories))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(fn).put(fnSearch).put(fnPersona))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (shape == 0) {
            su.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put("speechConfig", speech))
        } else {
            su.put("responseModalities", JSONArray().put("AUDIO")).put("speechConfig", speech)
        }
        return JSONObject().put("setup", su).toString()
    }

    private fun connect(idx: Int) {
        if (closed) return
        if (idx >= combos.size) {
            end("Live abhi nahi chal raha (model ya limit ka masla). Thodi der baad try karein.")
            return
        }
        val (model, shape) = combos[idx]
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        val req = Request.Builder().url(url).build()
        var me: WebSocket? = null
        var failedOver = false
        fun failover() {
            if (failedOver) return
            failedOver = true
            if (!ready) connect(idx + 1) else end("Live band ho gaya.")
        }
        me = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                webSocket.send(setupJson(model, shape))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                handle(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== ws) return
                handle(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws && !closed) failover()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws && !closed) failover()
            }
        })
        ws = me
    }

    private fun handle(text: String) {
        try {
            val j = JSONObject(text)
            if (j.has("setupComplete")) {
                ready = true
                startAudio()
                onState("listening")
                return
            }
            val sc = j.optJSONObject("serverContent")
            if (sc != null) {
                if (sc.optBoolean("interrupted", false)) flushPlayback()
                sc.optJSONObject("inputTranscription")?.optString("text")?.let { userBuf.append(it) }
                sc.optJSONObject("outputTranscription")?.optString("text")?.let {
                    if (userBuf.isNotBlank()) {
                        onText("me", userBuf.toString().trim())
                        userBuf = StringBuilder()
                    }
                    modelBuf.append(it)
                }
                val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                        if (d.isNotEmpty()) {
                            q.add(Base64.decode(d, Base64.DEFAULT))
                            playing = true
                            lastAudioAt = System.currentTimeMillis()
                            onState("speaking")
                        }
                    }
                }
                if (sc.optBoolean("turnComplete", false)) {
                    if (userBuf.isNotBlank()) {
                        onText("me", userBuf.toString().trim())
                        userBuf = StringBuilder()
                    }
                    if (modelBuf.isNotBlank()) {
                        onText("noor", modelBuf.toString().trim())
                        modelBuf = StringBuilder()
                    }
                }
            }
            val tc = j.optJSONObject("toolCall")
            if (tc != null) {
                val fcs = tc.optJSONArray("functionCalls") ?: JSONArray()
                for (i in 0 until fcs.length()) {
                    val fc = fcs.getJSONObject(i)
                    val id = fc.optString("id")
                    val name = fc.optString("name")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    onTool(name, args) { result ->
                        val resp = JSONObject().put(
                            "toolResponse",
                            JSONObject().put(
                                "functionResponses",
                                JSONArray().put(
                                    JSONObject().put("id", id).put("name", name)
                                        .put("response", JSONObject().put("result", result))
                                )
                            )
                        )
                        ws?.send(resp.toString())
                    }
                }
            }
            if (j.has("goAway")) end("Live ka waqt khatam, dobara shuru karein.")
        } catch (e: Exception) {
        }
    }

    private fun startAudio() {
        val inRate = 16000
        val minIn = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 6400)
        )
        rec = r
        halfDuplex = !AcousticEchoCanceler.isAvailable()
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true
        } catch (e: Exception) {
        }
        val outRate = 24000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(outRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(minOut * 4, 24000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(1f)
        t.play()

        Thread {
            try {
                r.startRecording()
                val buf = ByteArray(3200)
                var floor = 250.0
                var openUntil = 0L
                while (!closed) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (halfDuplex && (playing || q.isNotEmpty())) continue
                    // Awaaz filter: sirf paas se, zor se bolne wali awaaz aage jaye. Door ka music/TV chup.
                    if (gateMin > 0) {
                        var sum = 0.0
                        var k = 0
                        while (k + 1 < n) {
                            val v = (buf[k].toInt() and 0xFF) or (buf[k + 1].toInt() shl 8)
                            sum += v.toDouble() * v.toDouble()
                            k += 2
                        }
                        val rms = Math.sqrt(sum / maxOf(1, n / 2))
                        val now = System.currentTimeMillis()
                        if (rms < floor * 2) floor = maxOf(80.0, floor * 0.95 + rms * 0.05)
                        if (rms > maxOf(floor * 3.0, gateMin.toDouble())) openUntil = now + 600
                        if (now > openUntil) java.util.Arrays.fill(buf, 0, n, 0.toByte())
                    }
                    val msg = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject().put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                                .put("mimeType", "audio/pcm;rate=16000")
                        )
                    )
                    ws?.send(msg.toString())
                }
            } catch (e: Exception) {
            }
        }.start()

        Thread {
            try {
                while (!closed) {
                    val b = q.poll(200, TimeUnit.MILLISECONDS)
                    if (b != null) {
                        t.write(b, 0, b.size)
                    } else if (playing && System.currentTimeMillis() - lastAudioAt > 600) {
                        playing = false
                        onState("listening")
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun flushPlayback() {
        q.clear()
        playing = false
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) {
        }
        onState("listening")
    }

    fun end(msg: String) {
        if (closed) return
        closed = true
        try {
            ws?.close(1000, null)
        } catch (e: Exception) {
        }
        try {
            rec?.stop()
            rec?.release()
        } catch (e: Exception) {
        }
        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
        }
        rec = null
        track = null
        onEnd(msg)
    }
}

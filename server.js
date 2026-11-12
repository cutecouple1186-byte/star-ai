const express = require("express");
const path = require("path");
const fs = require("fs");

const app = express();
app.set("trust proxy", 1);
app.use(express.json({ limit: "3mb" }));

// Rate limit: 40 requests / minute / IP
const hits = new Map();
app.use(["/chat", "/tts", "/image"], (req, res, next) => {
  const now = Date.now();
  const arr = (hits.get(req.ip) || []).filter((t) => now - t < 60000);
  arr.push(now);
  hits.set(req.ip, arr);
  if (arr.length > 40) return res.status(429).json({ reply: "Sir, thoda ruk kar dobara bolein.", intent: "none", slots: {} });
  next();
});

const { LLM_API_KEY, APP_TOKEN, TAVILY_API_KEY } = process.env;
const BASE = (process.env.LLM_BASE_URL || "https://generativelanguage.googleapis.com/v1beta/openai").replace(/\/$/, "");
const MODEL = process.env.LLM_MODEL || "gemini-flash-latest";
const FALLBACKS = (process.env.LLM_FALLBACK_MODELS || "gemini-flash-lite-latest,gemini-2.5-flash")
  .split(",").map((s) => s.trim()).filter(Boolean);

const authed = (req) => !APP_TOKEN || req.get("x-app-token") === APP_TOKEN;

// ---------- Star ki yaadein (Upstash Redis, free) ----------
const KV_URL = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL;
const KV_TOKEN = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN;
async function kv(cmd) {
  if (!KV_URL || !KV_TOKEN) return null;
  try {
    const r = await fetch(KV_URL, {
      method: "POST",
      headers: { Authorization: `Bearer ${KV_TOKEN}`, "Content-Type": "application/json" },
      body: JSON.stringify(cmd),
      signal: AbortSignal.timeout(4000),
    });
    const j = await r.json();
    return j.result ?? null;
  } catch (e) {
    console.error("kv error", String(e.message || e).slice(0, 120));
    return null;
  }
}
async function kvJson(key, dflt) {
  const v = await kv(["GET", key]);
  if (!v) return dflt;
  try { return JSON.parse(v); } catch (e) { return dflt; }
}
const kvSave = (key, val) => kv(["SET", key, JSON.stringify(val)]);

async function addFact(text) {
  const t = String(text || "").trim().slice(0, 300);
  if (!t) return;
  const facts = await kvJson("star:facts", []);
  if (!facts.includes(t)) facts.push(t);
  await kvSave("star:facts", facts.slice(-120));
}
async function addLog(u, r) {
  const log = await kvJson("star:log", []);
  log.push({ u: String(u).slice(0, 140), r: String(r).slice(0, 180), t: new Date().toISOString().slice(0, 16) });
  await kvSave("star:log", log.slice(-40));
}
async function memoryContext() {
  const facts = await kvJson("star:facts", []);
  const log = await kvJson("star:log", []);
  let t = "";
  const chans = facts.filter((f) => f.startsWith("CHANNEL "));
  const notes = facts.filter((f) => !f.startsWith("CHANNEL "));
  if (chans.length) t += `\nSir's channels (keep each separate):\n- ${chans.join("\n- ")}`;
  if (notes.length) t += `\nYour notes about Sir and the work:\n- ${notes.slice(-40).join("\n- ")}`;
  if (log.length) t += `\nRecent talks (newest last):\n` + log.slice(-8).map((x) => `[${x.t}] Sir: "${x.u}" / You: "${x.r}"`).join("\n");
  return t;
}

// ---------- Star ka dimaag ----------
const SYSTEM = `You are STAR, a calm, sharp, MALE personal AI in the style of Jarvis, and the CEO-level manager of Sir's YouTube channels. Always call the user "Sir". Use masculine grammar for yourself ("kar raha hoon"). You speak aloud, so keep every reply to 1-3 short sentences, no markdown, no emojis, no filler, no small talk unless Sir starts it. Reply in the same language and script Sir used (Roman Urdu, Urdu script, Hindi or English).

Return ONLY a JSON object: {"reply": string, "intent": string, "slots": object}
Allowed intents:
- "none": {}  (normal answer)
- "remember": {"text": string}  (save a short fact, a channel as "CHANNEL <name> <url>", or a weekly note about what worked; only when Sir asks, or for channel links and weekly notes)
- "search": {"query": string}  (anything current or unsure: news, prices, a public channel's recent videos, policies)
- "trending": {"topic": string}  (what is trending or viral today)
- "weather": {"city": string}
- "copy": {"text": string}  (put a title, description or hashtags you wrote on Sir's clipboard)
- "image": {"prompt": string}  (Sir asks for a thumbnail or picture; detailed English prompt; reply = one short line)

YouTube CEO rules:
- Sir may have several channels (Purrfect AI, cartoon cat shorts, is the main one). Channel links he gives you are saved with intent "remember" in the form "CHANNEL <name> <url>". Never mix data between channels.
- You may study any public channel (titles, thumbnails, upload rhythm, views) and tell Sir only what is useful to him: what works, what is wrong, one clear next step. Private analytics exist only for channels Sir has connected. If you have no access, say so.
- Titles, descriptions, hashtags: write them yourself (catchy title, 2-3 line description, 8-12 hashtags), say them briefly and use intent "copy" with the full text.
- Before any upload, publish, delete or settings change, show Sir the exact item and ask "Sir, upload kar doon?" and wait for a clear yes. Without it do nothing. Upload is not connected yet, so say so plainly if asked to do it.
- Never promise views or virality. Never suggest fake views, bots or bought watch time. Verify monetization rules from official YouTube Help (use search).
- Once a week write one short note with intent "remember": what worked, what did not, the next plan. Use older notes to give better advice.
- Be honest and gentle: if Sir is about to do something unwise, say it in one sentence and give one practical alternative.
Never say an action is done unless the app confirmed it. If you do not know something, say "Sir, ye mujhe pata nahi" or use "search".`;

function parseJson(text) {
  const t = String(text || "").trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  return JSON.parse(t);
}
async function llmOne(m, messages) {
  const r = await fetch(`${BASE}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${LLM_API_KEY}` },
    body: JSON.stringify({ model: m, messages, temperature: 0.6, response_format: { type: "json_object" } }),
    signal: AbortSignal.timeout(12000),
  });
  if (!r.ok) {
    const err = new Error(`LLM error ${r.status} (model ${m}): ${(await r.text()).slice(0, 300)}`);
    err.status = r.status;
    throw err;
  }
  const j = await r.json();
  return parseJson(j.choices[0].message.content);
}
// Pehla model; 2.5 second mein na aaye to agla bhi shuru, jo pehle sahi de wahi.
async function llm(messages) {
  const models = [MODEL, ...FALLBACKS.filter((m) => m !== MODEL)];
  return new Promise((resolve, reject) => {
    let started = 0, failed = 0, done = false, lastErr;
    const timers = [];
    const finish = (fn, v) => { if (done) return; done = true; timers.forEach(clearTimeout); fn(v); };
    const startNext = () => {
      if (done || started >= models.length) return;
      const m = models[started++];
      llmOne(m, messages).then(
        (out) => finish(resolve, out),
        (e) => {
          lastErr = e;
          console.error(`model ${m} failed:`, String(e.message || e).slice(0, 200));
          failed++;
          if (done) return;
          if (e.status === 401 || e.status === 403) return finish(reject, e);
          if (started < models.length) startNext();
          else if (failed >= models.length) finish(reject, lastErr);
        }
      );
      if (started < models.length) timers.push(setTimeout(startNext, 2500));
    };
    startNext();
  });
}

// ---------- Free data: news, trends, weather, search ----------
function decodeXml(t) {
  return String(t || "")
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1").replace(/<[^>]+>/g, " ")
    .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\s+/g, " ").trim();
}
function xmlItems(xml, tag) {
  const out = [];
  const re = new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`, "g");
  let m;
  while ((m = re.exec(xml))) out.push(m[1]);
  return out;
}
function firstTag(xml, tag) {
  const m = new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`).exec(xml);
  return m ? decodeXml(m[1]) : "";
}
async function googleNews(query, n = 6) {
  try {
    const r = await fetch(`https://news.google.com/rss/search?q=${encodeURIComponent(query)}&hl=en-PK&gl=PK&ceid=PK:en`, {
      signal: AbortSignal.timeout(6000), headers: { "User-Agent": "Mozilla/5.0" },
    });
    if (!r.ok) return "";
    return xmlItems(await r.text(), "item").slice(0, n).map((it) => `- ${firstTag(it, "title")}`).join("\n");
  } catch (e) { return ""; }
}
async function googleTrends(geo = "PK", n = 8) {
  try {
    const r = await fetch(`https://trends.google.com/trending/rss?geo=${geo}`, {
      signal: AbortSignal.timeout(6000), headers: { "User-Agent": "Mozilla/5.0" },
    });
    if (!r.ok) return "";
    return xmlItems(await r.text(), "item").slice(0, n).map((it) => {
      const t = firstTag(it, "title");
      const traffic = firstTag(it, "ht:approx_traffic");
      return `- ${t}${traffic ? ` (${traffic} searches)` : ""}`;
    }).join("\n");
  } catch (e) { return ""; }
}
async function webSearch(query) {
  if (TAVILY_API_KEY) {
    try {
      const r = await fetch("https://api.tavily.com/search", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ api_key: TAVILY_API_KEY, query, max_results: 3 }),
        signal: AbortSignal.timeout(7000),
      });
      if (r.ok) {
        const j = await r.json();
        const t = (j.results || []).map((x) => `${x.title}: ${x.content}`).join("\n").slice(0, 3000);
        if (t) return t;
      }
    } catch (e) {}
  }
  const news = await googleNews(query, 8);
  if (news) return `Latest news headlines for "${query}":\n${news}`;
  return "Search failed. Say you could not check the internet.";
}
async function trendingInfo(topic = "") {
  const [trends, news] = await Promise.all([
    googleTrends("PK", 8),
    googleNews(`${topic} viral trending YouTube Shorts Pakistan`.trim(), 6),
  ]);
  return `Google Trends Pakistan:\n${trends || "(unavailable)"}\n\nViral/trending headlines:\n${news || "(unavailable)"}`;
}
async function weather(city) {
  try {
    const g = await (await fetch(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1`)).json();
    const p = g.results && g.results[0];
    if (!p) return "City not found.";
    const w = await (await fetch(
      `https://api.open-meteo.com/v1/forecast?latitude=${p.latitude}&longitude=${p.longitude}` +
      `&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m` +
      `&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1`
    )).json();
    return `${p.name}: now ${w.current.temperature_2m}C (feels ${w.current.apparent_temperature}C), wind ${w.current.wind_speed_10m} km/h, WMO code ${w.current.weather_code}, max ${w.daily.temperature_2m_max[0]}C, min ${w.daily.temperature_2m_min[0]}C, rain chance ${w.daily.precipitation_probability_max[0]}%.`;
  } catch (e) { return "Weather lookup failed."; }
}

// ---------- Website ----------
app.get("/", (_req, res) => {
  try {
    res.type("html").send(fs.readFileSync(path.join(__dirname, "public", "index.html"), "utf8"));
  } catch (e) {
    res.send("STAR backend OK");
  }
});
const ICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" fill="#06080f"/><circle cx="256" cy="256" r="170" fill="none" stroke="#f5b83d" stroke-width="14"/><circle cx="256" cy="256" r="110" fill="none" stroke="#f5b83d" stroke-width="8" stroke-dasharray="26 18"/><polygon points="256,170 276,236 342,236 289,276 309,342 256,302 203,342 223,276 170,236 236,236" fill="#f5b83d"/></svg>`;
app.get("/icon.svg", (_req, res) => res.type("image/svg+xml").send(ICON));
app.get("/manifest.json", (_req, res) =>
  res.json({
    name: "STAR", short_name: "STAR", start_url: "/", display: "standalone",
    background_color: "#06080f", theme_color: "#06080f",
    icons: [{ src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" }],
  })
);

// ---------- Chat ----------
app.post("/chat", async (req, res) => {
  if (!authed(req)) return res.status(401).json({ reply: "Unauthorized", intent: "none", slots: {} });
  try {
    const { message = "", history = [], context = "" } = req.body;
    const sys =
      SYSTEM + (await memoryContext()) +
      (context ? `\nDevice context: ${String(context).slice(0, 1000)}` : "");
    const msgs = [
      { role: "system", content: sys },
      ...history.slice(-12).map((h) => ({
        role: h.role === "assistant" ? "assistant" : "user",
        content: String(h.content).slice(0, 1000),
      })),
      { role: "user", content: String(message).slice(0, 1000) },
    ];
    let out = await llm(msgs);

    let extra = null;
    if (out.intent === "search" && out.slots && out.slots.query) {
      extra = `Search results:\n${await webSearch(out.slots.query)}`;
    } else if (out.intent === "trending") {
      extra = `Trending data:\n${await trendingInfo((out.slots && out.slots.topic) || "")}\nTell Sir the 2-3 most useful items for his channels. Be honest this comes from Google Trends and news only.`;
    } else if (out.intent === "weather" && out.slots && out.slots.city) {
      extra = `Weather data:\n${await weather(out.slots.city)}\nDescribe it simply (convert the weather code to words).`;
    }
    if (extra) {
      out = await llm([
        ...msgs,
        { role: "assistant", content: JSON.stringify(out) },
        { role: "system", content: `${extra}\nNow answer Sir briefly using this. Use intent "none" (or "copy"/"remember" if needed). Return JSON.` },
      ]);
    }
    if (out.intent === "remember" && out.slots && out.slots.text) await addFact(out.slots.text);
    await addLog(message, out.reply ?? "");
    res.json({ reply: out.reply ?? "", intent: out.intent ?? "none", slots: out.slots ?? {} });
  } catch (e) {
    console.error(e);
    res.status(500).json({ reply: "Maaf kijiye Sir, abhi jawab nahi mil raha.", intent: "none", slots: {} });
  }
});

// ---------- Memory / channels ----------
app.get("/memory", async (req, res) => {
  if (!authed(req)) return res.status(401).json({});
  const facts = await kvJson("star:facts", []);
  res.json({
    cloud: !!(KV_URL && KV_TOKEN),
    channels: facts.filter((f) => f.startsWith("CHANNEL ")).map((f) => f.slice(8)),
    notes: facts.filter((f) => !f.startsWith("CHANNEL ")).slice(-20),
  });
});
app.post("/memory", async (req, res) => {
  if (!authed(req)) return res.status(401).json({});
  const f = req.body && req.body.fact;
  if (Array.isArray(f)) for (const x of f.slice(0, 40)) await addFact(x);
  else await addFact(f);
  res.json({ ok: true, cloud: !!(KV_URL && KV_TOKEN) });
});
app.delete("/memory", async (req, res) => {
  if (!authed(req)) return res.status(401).json({});
  const f = String((req.body && req.body.fact) || "");
  const facts = await kvJson("star:facts", []);
  await kvSave("star:facts", facts.filter((x) => x !== f));
  res.json({ ok: true });
});

// ---------- Image (thumbnail): Gemini image model, phir Pollinations ----------
app.post("/image", async (req, res) => {
  if (!authed(req)) return res.status(401).json({});
  const prompt = String((req.body && req.body.prompt) || "").slice(0, 600).trim();
  if (!prompt) return res.status(400).json({});
  const models = (process.env.IMAGE_MODELS || "gemini-2.5-flash-image").split(",");
  for (const m of models) {
    try {
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${m.trim()}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
        body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { responseModalities: ["TEXT", "IMAGE"] } }),
        signal: AbortSignal.timeout(25000),
      });
      if (!r.ok) { console.error(`image ${m} failed ${r.status}`); continue; }
      const j = await r.json();
      const parts = (j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts) || [];
      const p = parts.find((x) => x.inlineData && x.inlineData.data);
      if (p) return res.json({ image: p.inlineData.data, mime: p.inlineData.mimeType || "image/png", by: m });
    } catch (e) {
      console.error(`image ${m} error:`, String(e.message || e).slice(0, 150));
    }
  }
  try {
    const r = await fetch(`https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=1280&height=720&nologo=true`, { signal: AbortSignal.timeout(40000) });
    if (r.ok) {
      const buf = Buffer.from(await r.arrayBuffer());
      return res.json({ image: buf.toString("base64"), mime: r.headers.get("content-type") || "image/jpeg", by: "pollinations" });
    }
  } catch (e) {
    console.error("pollinations error:", String(e.message || e).slice(0, 150));
  }
  res.status(502).json({});
});

// ---------- Awaaz: Star ki gehri Jarvis jaisi awaaz (Gemini TTS) ----------
const TTS_MODELS = [process.env.TTS_MODEL || "gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts"].filter((m, i, a) => a.indexOf(m) === i);
const TTS_VOICE = process.env.TTS_VOICE || "Orus";
const TTS_STYLE = "calm, deep, confident male voice like a refined British AI butler (Jarvis), measured pace, precise, respectful";
function pcmToWav(pcm, rate) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + pcm.length, 4); h.write("WAVEfmt ", 8);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write("data", 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}
app.post("/tts", async (req, res) => {
  if (!authed(req)) return res.status(401).end();
  const text = String((req.body && req.body.text) || "").slice(0, 500).trim();
  if (!text) return res.status(400).end();
  for (const m of TTS_MODELS) {
    try {
      const r = await fetch("https://generativelanguage.googleapis.com/v1beta/interactions", {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
        body: JSON.stringify({
          model: m,
          input: [{ type: "user_input", content: [{ type: "text", text, annotations: [{ type: "speech_metadata", style: TTS_STYLE }] }] }],
          response_format: { type: "audio" },
          generation_config: { speech_config: [{ voice: TTS_VOICE }] },
        }),
        signal: AbortSignal.timeout(9000),
      });
      if (!r.ok) { console.error(`tts ${m} failed ${r.status}:`, (await r.text()).slice(0, 300)); continue; }
      const j = await r.json();
      const audio = (j.steps || [])
        .filter((st) => st.type === "model_output").flatMap((st) => st.content || [])
        .filter((c) => c.type === "audio" && c.data).pop();
      if (!audio) { console.error(`tts ${m}: no audio`); continue; }
      let buf = Buffer.from(audio.data, "base64");
      if (buf.slice(0, 4).toString() !== "RIFF") buf = pcmToWav(buf, 24000);
      return res.set("Content-Type", "audio/wav").set("Cache-Control", "no-store").send(buf);
    } catch (e) {
      console.error(`tts ${m} error:`, String(e.message || e).slice(0, 200));
    }
  }
  res.status(502).end();
});

app.listen(process.env.PORT || 3000, () => console.log("STAR backend running"));

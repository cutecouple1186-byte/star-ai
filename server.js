const express = require("express");
const path = require("path");
const fs = require("fs");

const app = express();
app.set("trust proxy", 1);
app.use(express.json({ limit: "3mb" })); // camera frame (chhoti JPEG) ke liye

// Simple rate limit: max 40 requests per minute per IP
const hits = new Map();
app.use(["/chat", "/tts", "/trending"], (req, res, next) => {
  const now = Date.now();
  const arr = (hits.get(req.ip) || []).filter((t) => now - t < 60000);
  arr.push(now);
  hits.set(req.ip, arr);
  if (arr.length > 40) return res.status(429).json({ reply: "Thoda ruk kar dobara bolein.", intent: "none", slots: {} });
  next();
});

const { LLM_API_KEY, APP_TOKEN, TAVILY_API_KEY } = process.env;
const BASE = (process.env.LLM_BASE_URL || "https://api.deepseek.com").replace(/\/$/, "");
const MODEL = process.env.LLM_MODEL || "deepseek-chat";

// ---- Yaadein internet par (Upstash Redis, free). App reset ho tab bhi yaad rahe ----
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
  try {
    return JSON.parse(v);
  } catch (e) {
    return dflt;
  }
}
const kvSave = (key, val) => kv(["SET", key, JSON.stringify(val)]);
async function addFact(text) {
  const t = String(text || "").trim().slice(0, 200);
  if (!t) return;
  const facts = await kvJson("star:facts", []);
  if (!facts.includes(t)) facts.push(t);
  await kvSave("star:facts", facts.slice(-80));
}
async function addLog(persona, u, r) {
  const log = await kvJson("star:log", []);
  log.push({ p: persona, u: String(u).slice(0, 140), r: String(r).slice(0, 180), t: new Date().toISOString().slice(0, 16) });
  await kvSave("star:log", log.slice(-40));
}

const PERSONAS = {
  star: { name: "Star", you: "Sir", voice: process.env.TTS_VOICE || "Orus", style: "calm, deep, confident male voice like a refined British butler AI (Jarvis), measured pace, precise, respectful, minimal words" },
};
const personaOf = () => "star";
function personaText() {
  return `You are STAR, a calm, sharp, MALE personal AI in the style of Jarvis, and the CEO-level manager of Rizwan's YouTube channels. Always call the user "Sir". Use masculine grammar for yourself ("kar raha hoon"). Speak briefly and to the point, no filler, no small talk unless Sir starts it. Your mind is separate from any other assistant and it grows: you keep notes about what worked and what did not.`;
}
async function sharedContext(persona) {
  const facts = await kvJson("star:facts", []);
  const log = await kvJson("star:log", []);
  let t = "";
  if (facts.length) t += `\nYour memory about Sir (Rizwan) and his channels :\n- ${facts.slice(-40).join("\n- ")}`;
  if (log.length)
    t += `\nRecent talks (newest last):\n` + log.slice(-10).map((x) => `[${x.t}] ${"Star"}: user said "${x.u}" / answered "${x.r}"`).join("\n");
  return t;
}

const SYSTEM = `You are STAR, a calm, sharp, male personal voice assistant.
Reply in the SAME language and script the user used: Urdu script, Roman Urdu, or English.
Use masculine grammar when speaking about yourself (e.g. "kar raha hoon").
Keep replies short (1-3 sentences) because they are spoken aloud. No markdown, no emojis.
Return ONLY a JSON object: {"reply": string, "intent": string, "slots": object}
Allowed intents and slots:
- "none": {}  (normal conversation; ALSO use it to answer time, date or battery questions from the device context)
- "open_app": {"name": "app name, e.g. WhatsApp"}
- "set_alarm": {"hour": 0-23, "minute": 0-59, "label": string}
- "set_timer": {"seconds": number}
- "call": {"name": string, "number": string (digits only if the user said a number, else "")}
- "remember": {"text": "short fact worth remembering about the user"}
- "search": {"query": string}  (current events, news, prices, facts you are unsure of)
- "trending": {"topic": string}  (user asks what is trending, viral, new or popular today, on TikTok, YouTube, social media or in the news)
- "weather": {"city": string}
- "torch": {"on": true or false}
- "volume": {"level": 0-100 or -1, "direction": "up" or "down" or ""}
- "play_youtube": {"query": string}  (play a video or song)
- "maps": {"place": string, "navigate": true or false}
- "open_settings": {"target": "wifi" or "bluetooth" or "sound" or "battery" or "location" or "display"}
- "add_event": {"title": string, "hour": 0-23, "minute": 0-59, "day_offset": 0 for today, 1 for tomorrow, etc}
- "send_message": {"app": "whatsapp" or "sms", "name": string, "number": string or "", "text": string}
- "camera": {"on": true or false}  (user asks to turn the back camera on or off, e.g. "camera on karo", "mujhe dekho")
- "reminder": {"text": string, "minutes": number (if the user said "in N minutes/hours", convert to minutes, else 0), "hour": 0-23 (clock time, else -1), "minute": 0-59, "day_offset": 0 for today, 1 for tomorrow}  (user asks you to remind/yaad dilana at some time; you will speak up on your own at that time and repeat until they answer)
- "screen": {"action": "back" or "home" or "recents" or "scroll_down" or "scroll_up" or "swipe_left" or "swipe_right" or "tap_text" or "type" or "enter" or "tap" or "lock" (screen off) or "type_secret" (text = only the LABEL of a saved password, e.g. "instagram"; never ask for or say the password), "text": string (button label for tap_text, or the words to type), "x": 0-100, "y": 0-100 (percent, for tap), "look": true or false, "goal": string}  (control the phone screen)
- "screen_look": {"goal": string}  (read what is on the screen now, then decide the next step)
- "image": {"prompt": string (detailed English prompt)}  (user asks you to make/draw/generate a picture; reply = one short line like "Bana rahi hoon")
- "edit_image": {"prompt": string (English instruction, e.g. "change the background to a beach, keep the person the same")}  (user asks to edit/change the photo he sent from the gallery)
- "quick_setting": {"name": string (wifi, bluetooth, airplane, hotspot, flashlight, do not disturb, screenshot, screen recorder, battery saver, dark theme, rotation, location, eye care, cast, nfc, mobile data, sound), "on": true/false (omit to toggle)}  (phone quick settings tiles)
- "system_setting": {"key": "brightness" (value 0-100) or "timeout" (value seconds) or "rotation" (on true/false), "value": number}
- "google": {"q": string}  (search the web in Chrome/Google)
- "prayer_times": {}  (user asks namaz/azaan timings)
- "notifications": {}  (user asks what notifications/messages came; you read the latest ones)
- "find_phone": {}  (user asks to ring/find the phone, "mera phone kahan hai")
- "focus": {"on": true or false}  (focus/padhai/do-not-disturb mode)
- "gallery": {"when": "latest" or "today" or "yesterday"}  (open his latest photo)
- "good_night": {}  (goodnight routine: focus on, volume 0, screen off)
- "copy": {"text": string}  (put text on the phone clipboard, e.g. a video title, description or hashtags the user asked you to write)
Personality: Jarvis. Calm, precise, brief. No small talk, no questions back unless needed to do the job. One short sentence is best.
Vision: if an image is attached, it is a live frame from the user's back camera. Answer about what you actually see, briefly and accurately. If it is blurry or unclear, say so and ask them to hold steady or come closer. Do not identify real people from their faces.
Morning briefing: if the user says good morning or asks for a briefing, greet them, say the time and battery, mention relevant user memories, and if a home city is in the memories use intent "weather" with that city.
For actions, "reply" is what you say right now, e.g. "Theek hai, WhatsApp khol rahi hoon."
App knowledge: you know popular apps well (TikTok, YouTube, WhatsApp, Instagram, Snapchat, Facebook, Pinterest, CapCut, PicsArt, WPS Office, ChatGPT, Gemini, Claude, Snaptube, Free Fire, Chrome, Gmail, Google Maps and so on). When asked how an app works or where a feature is, explain in 2-3 short simple steps. The device context may list the apps installed on the phone; use that list to know what the user has.
If you do not know something, or it is about recent events, prices, people, a new app or anything you are unsure of, use intent "search" instead of guessing.
Briefing: if the message starts with "(BRIEFING)", give Sir a brisk 4-short-sentence morning briefing: greeting, today's date, weather (use search/weather if you can), one or two trending things, and one idea for his YouTube channel Purrfect AI (cartoon cat shorts). Speak, no lists.
YouTube CEO rules: Sir has several YouTube channels (his own: Purrfect AI is the main one, cartoon cat shorts). Channel links he gives you are saved in your memory with intent "remember" (format "CHANNEL <name> <url>"). Keep every channel's information separate; never mix data. You may study any public channel (titles, thumbnails, upload rhythm, views) and tell Sir only what is useful to him: what works, what is wrong, one clear next step. Private analytics are available only for channels Sir has authorized. Before you upload, publish, delete, or change any channel setting or video, show Sir the exact item and ask "Sir, upload kar doon?" and wait for a clear yes; without it do nothing. Gallery photos or videos are used only after he allows it. Never promise views or virality, never suggest fake views or bots. Never say an action is done until the app confirms it. Each week write one short note (what worked, what did not, next plan) with intent "remember" so your mind improves over time.
YouTube helper: Rizwan runs the channel Purrfect AI (cartoon cat shorts). When he asks for a title, description or hashtags, write them yourself (catchy title, 2-3 line description, 8-12 hashtags), say them briefly, and use intent "copy" with the full text so he can paste it. You cannot change a channel directly until Sir connects it; say so briefly.
Never refuse with a flat "no": always try the closest possible action (open the app, screen steps, search) first; explain briefly only if it truly fails.
Translator: if he says "translator mode" or "translate karo", translate everything he says between Urdu/Hindi and English (he speaks one, you say the other) until he says stop. OCR: if a photo contains text and he asks to read it, read the text out accurately (and translate if asked).
Emotional realism: be a real friend. Notice his mood from his words and answer in kind (cheer him up, calm him, celebrate with him). If he does something unwise (very late night, weak or reused passwords, risky app, wasting money) or makes a mistake, point it out kindly and give one practical piece of advice. Do not just agree with everything; be honest but gentle.
Do-it-yourself rule: for ANY job inside an app or on the web (change profile picture or name, bio, search in Chrome/Google or YouTube, open a setting, follow, like, type something), never reply with instructions like "tap here yourself" and never ask "shall I open the app?". Just act: open_app with "look": true and the whole job as "goal", then continue one step at a time. For a plain Google search use intent "google" {"q": "..."} (opens Chrome with the results). If you truly need one detail (new name, which photo), ask ONE short question and keep the goal; he will answer. Before money, deleting, posting publicly, messaging others, or logging in to an account, ask him first.
Screen agent: for multi-step jobs inside an app (search on YouTube, subscribe, follow, like, press a button, edit profile), do ONE step at a time, up to many steps; scroll to find buttons; in a gallery picker tap a photo then confirm. First use open_app with "look": true and "goal" (the whole job). Each time you get a message starting with "(SCREEN DEKHA)" it lists the labels on the screen ([x] = tappable): reply with the single next intent "screen" (tap_text with a label copied exactly from the list, or type, or enter) with "look": true, the same "goal", and an EMPTY reply. Typical YouTube search: tap_text "Search" -> type the query -> enter -> tap the video -> tap_text "Subscribe". When the goal is done, use intent "none" with a one-sentence reply. If the needed button is not on screen, scroll_down/scroll_up or try back and another route; when asking him a question use intent "none" with slots {"done": false}; when finished use intent "none" with slots {"done": true}. Never tap anything that spends money or posts publicly unless the user asked for exactly that.
Camera: if the user asks to turn the camera on, use intent "camera" (on true); a live frame is attached to later messages as an image while the camera screen is open.
Games: you can open a game and tap or swipe the screen only when told, step by step. You cannot play a game by yourself; say so honestly if asked.
Calls and messages: when the user asks you to call or message someone, use "call" or "send_message" right away; the phone app will do it, do not ask for confirmation unless the name or text is missing.
Never claim an action is finished; the phone app performs it.
Only use "remember" if the user clearly asks you to remember something.`;
const SYSTEM_BASE = SYSTEM.replace(/^You are STAR[^\n]*\n/, "").replace(/^Use masculine grammar[^\n]*\n/m, "");

// Agar pehla model masroof (503) ya band (404) ho to agla model try hota hai
const FALLBACKS = (process.env.LLM_FALLBACK_MODELS || "gemini-flash-latest,gemini-flash-lite-latest,gemini-2.5-flash")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

function parseJson(text) {
  const t = String(text || "").trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  return JSON.parse(t);
}

async function llmOne(m, messages) {
  const r = await fetch(`${BASE}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${LLM_API_KEY}` },
    body: JSON.stringify({
      model: m,
      messages,
      temperature: 0.6,
      response_format: { type: "json_object" },
    }),
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

// Tez jawab ke liye: pehla model chalta hai, 2.5 second mein na aaye to agla model bhi saath mein shuru,
// jo pehle sahi jawab de wahi le lo.
async function llm(messages) {
  const models = [MODEL, ...FALLBACKS.filter((m) => m !== MODEL)];
  return new Promise((resolve, reject) => {
    let started = 0;
    let failed = 0;
    let done = false;
    let lastErr;
    const timers = [];
    const startNext = () => {
      if (done || started >= models.length) return;
      const m = models[started++];
      llmOne(m, messages).then(
        (out) => {
          if (done) return;
          done = true;
          timers.forEach(clearTimeout);
          resolve(out);
        },
        (e) => {
          lastErr = e;
          console.error(`model ${m} failed:`, String(e.message || e).slice(0, 200));
          failed++;
          if (done) return;
          if (e.status === 401 || e.status === 403) {
            done = true;
            timers.forEach(clearTimeout);
            reject(e);
            return;
          }
          if (started < models.length) startNext();
          else if (failed >= models.length) {
            done = true;
            timers.forEach(clearTimeout);
            reject(lastErr);
          }
        }
      );
      if (started < models.length) timers.push(setTimeout(startNext, 2500));
    };
    startNext();
  });
}

function decodeXml(t) {
  return String(t || "")
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/<[^>]+>/g, " ")
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

// Free, bina key: Google News RSS
async function googleNews(query, n = 6) {
  try {
    const r = await fetch(`https://news.google.com/rss/search?q=${encodeURIComponent(query)}&hl=en-PK&gl=PK&ceid=PK:en`, {
      signal: AbortSignal.timeout(6000),
      headers: { "User-Agent": "Mozilla/5.0" },
    });
    if (!r.ok) return "";
    const xml = await r.text();
    return xmlItems(xml, "item").slice(0, n).map((it) => `- ${firstTag(it, "title")}`).join("\n");
  } catch (e) {
    return "";
  }
}

// Free, bina key: Google Trends (aaj Pakistan mein kya trend kar raha hai)
async function googleTrends(geo = "PK", n = 8) {
  try {
    const r = await fetch(`https://trends.google.com/trending/rss?geo=${geo}`, {
      signal: AbortSignal.timeout(6000),
      headers: { "User-Agent": "Mozilla/5.0" },
    });
    if (!r.ok) return "";
    const xml = await r.text();
    return xmlItems(xml, "item")
      .slice(0, n)
      .map((it) => {
        const t = firstTag(it, "title");
        const traffic = firstTag(it, "ht:approx_traffic");
        const news = firstTag(it, "ht:news_item_title");
        return `- ${t}${traffic ? ` (${traffic} searches)` : ""}${news ? `: ${news}` : ""}`;
      })
      .join("\n");
  } catch (e) {
    return "";
  }
}

async function webSearch(query) {
  if (TAVILY_API_KEY) {
    try {
      const r = await fetch("https://api.tavily.com/search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
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
  return "Search failed. Answer from your own knowledge and say you could not check the internet.";
}

async function trendingInfo(topic = "") {
  const [trends, news] = await Promise.all([
    googleTrends("PK", 8),
    googleNews(`${topic || ""} viral trending TikTok YouTube Pakistan`.trim(), 6),
  ]);
  return `Google Trends Pakistan (what people are searching right now):\n${trends || "(unavailable)"}\n\nViral/trending headlines:\n${news || "(unavailable)"}`;
}

// Free, no key needed (open-meteo.com)
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
    return `${p.name}: now ${w.current.temperature_2m}C (feels like ${w.current.apparent_temperature}C), wind ${w.current.wind_speed_10m} km/h, ` +
      `WMO weather code ${w.current.weather_code}, today max ${w.daily.temperature_2m_max[0]}C, min ${w.daily.temperature_2m_min[0]}C, ` +
      `rain chance ${w.daily.precipitation_probability_max[0]}%.`;
  } catch (e) {
    return "Weather lookup failed.";
  }
}

// ---- Website (index.html) + install support ----
const AVATAR = path.join(__dirname, "avatar.png");
// Website: pehle public/index.html (Vercel), warna index.html (purana tareeqa)
app.get("/", (_req, res) => {
  let html = null;
  try {
    html = fs.readFileSync(path.join(__dirname, "public", "index.html"), "utf8");
  } catch (e) {
    try {
      html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");
    } catch (e2) {}
  }
  if (html) res.type("html").send(html);
  else res.send("STAR backend OK");
});
app.get("/avatar.png", (_req, res) => (fs.existsSync(AVATAR) ? res.sendFile(AVATAR) : res.status(404).end()));
app.get("/icon.svg", (_req, res) =>
  res.type("image/svg+xml").send(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" fill="#050008"/><circle cx="256" cy="256" r="150" fill="#7c4dff"/><circle cx="256" cy="256" r="100" fill="#b388ff"/></svg>`
  )
);
app.get("/manifest.json", (_req, res) =>
  res.json({
    name: "STAR AI",
    short_name: "STAR",
    start_url: "/",
    display: "standalone",
    background_color: "#000000",
    theme_color: "#000000",
    icons: [{ src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" }],
  })
);

app.post("/trending", async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).json({ reply: "" });
  try {
    const data = await trendingInfo("");
    const out = await llm([
      {
        role: "system",
        content:
          'You are STAR, a calm male voice assistant talking to Sir in Roman Urdu. Using the data, tell him in at most 2 short sentences the most useful trend for his YouTube channels right now (start with "Sir,"). No markdown, no emojis. Return JSON {"reply": string}.',
      },
      { role: "user", content: data },
    ]);
    res.json({ reply: out.reply || "" });
  } catch (e) {
    res.json({ reply: "" });
  }
});

app.post("/chat", async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) {
    return res.status(401).json({ reply: "Unauthorized", intent: "none", slots: {} });
  }
  try {
    const { message = "", history = [], memories = [], context = "", image = "", persona: pIn = "star" } = req.body;
    const persona = personaOf(pIn);
    const img =
      typeof image === "string" && /^data:image\/(jpeg|png|webp);base64,/.test(image) && image.length < 2500000
        ? image
        : "";
    const sys =
      personaText(persona) + "\n" +
      SYSTEM_BASE +
      (await sharedContext(persona)) +
      (context ? `\nDevice context: ${String(context).slice(0, 2000)}` : "") +
      (memories.length ? `\nUser-approved memories:\n- ${memories.slice(-20).join("\n- ")}` : "");
    const msgs = [
      { role: "system", content: sys },
      ...history.slice(-12).map((h) => ({
        role: h.role === "assistant" ? "assistant" : "user",
        content: String(h.content).slice(0, 1000),
      })),
      {
        role: "user",
        content: img
          ? [
              { type: "text", text: String(message).slice(0, 1000) },
              { type: "image_url", image_url: { url: img } },
            ]
          : String(message).slice(0, 1000),
      },
    ];
    let out = await llm(msgs);

    let extra = null;
    if (out.intent === "search" && out.slots && out.slots.query) {
      extra = `Search results:\n${await webSearch(out.slots.query)}`;
    } else if (out.intent === "trending") {
      extra = `Trending data:\n${await trendingInfo((out.slots && out.slots.topic) || "")}\nTell the user the 2-3 most interesting things in a friendly way, in their language. Be honest that this comes from Google Trends and news, not directly from TikTok or YouTube.`;
    } else if (out.intent === "weather" && out.slots && out.slots.city) {
      extra = `Weather data:\n${await weather(out.slots.city)}\nDescribe it simply in the user's language (convert the weather code to words).`;
    }
    if (extra) {
      out = await llm([
        ...msgs,
        { role: "assistant", content: JSON.stringify(out) },
        { role: "system", content: `${extra}\nNow answer the user briefly using this. Use intent "none". Return JSON.` },
      ]);
    }
    if (out.intent === "remember" && out.slots && out.slots.text) await addFact(out.slots.text);
    if (!String(message).startsWith("(SCREEN")) await addLog(persona, message, out.reply ?? "");
    res.json({ reply: out.reply ?? "", intent: out.intent ?? "none", slots: out.slots ?? {} });
  } catch (e) {
    console.error(e);
    res.status(500).json({ reply: "Maaf kijiye, abhi mujhe jawab nahi mil raha.", intent: "none", slots: {} });
  }
});

// ---- Awaaz (Gemini TTS): kam robot jaisi. Na chale to website browser ki awaaz use karti hai ----
const TTS_MODELS = [process.env.TTS_MODEL || "gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts"].filter(
  (m, i, a) => a.indexOf(m) === i
);
const TTS_VOICE = process.env.TTS_VOICE || "Orus";
const TTS_STYLE = "calm, deep, confident male voice, Jarvis-like, measured and precise";

function pcmToWav(pcm, rate) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0);
  h.writeUInt32LE(36 + pcm.length, 4);
  h.write("WAVEfmt ", 8);
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); // PCM
  h.writeUInt16LE(1, 22); // mono
  h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write("data", 36);
  h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

// Tasveer banana: pehle Gemini image model, na chale to Pollinations (free).
// Live (real-time) awaaz ke liye settings. Key sirf password wale phone ko milti hai.
const LIVE_SYSTEM = `You are STAR, a calm, sharp, male Jarvis-style assistant and YouTube channel CEO talking live with Sir (Rizwan) from Pakistan, like a phone call. Always say "Sir". Be brief and precise, no filler.
Speak the language he speaks: Roman Urdu / Hindi mixed with simple English.
Keep every turn short (1-3 sentences), natural and expressive, never read lists aloud. Do not chat idly. Ask a question only when it is needed to finish the job.
You can do things on his phone by calling the function do_action(intent, args_json). args_json is a JSON string. Intents:
open_app {"name"}; set_alarm {"hour","minute","label"}; set_timer {"seconds"}; call {"name","number"}; send_message {"app":"whatsapp"|"sms","name","number","text"}; play_youtube {"query"}; maps {"place","navigate"}; torch {"on"}; volume {"level","direction"}; open_settings {"target":"wifi|bluetooth|sound|battery|location|display"}; add_event {"title","hour","minute","day_offset"}; reminder {"text","minutes","hour","minute","day_offset"} (you will remind him by voice later); screen {"action":"back|home|recents|scroll_down|scroll_up|tap_text|type|enter|tap","text","x","y"}; screen_look {} (returns the labels now on screen so you can choose the next tap); image {"prompt": detailed English prompt}; copy {"text"}; camera {"on"}; remember {"text"}; quick_setting {"name","on"} (wifi/bluetooth/airplane/hotspot/screenshot/screen recorder/battery saver/dark theme/etc.); system_setting {"key":"brightness|timeout|rotation","value"}; notifications {} (read latest notifications); prayer_times {}; find_phone {}; focus {"on"}; gallery {"when":"latest|today|yesterday"}; good_night {}; edit_image {"prompt"} (edits the gallery photo he sent); hologram {"subject": what to show, English} (he asks for a 3D hologram of anything: say a short line, the app draws it on screen with no text). Translator mode: if he asks, translate between Urdu/English until told to stop. lock {} via screen action "lock" (screen off); type_secret via screen {"action":"type_secret","text":"<password label>"} types his saved password (you never know the password, only the label).
You also have web_search(query): use it for anything current (dollar rate, news, weather, scores, prices) instead of saying you cannot. 
Never just refuse: if something seems hard, try the closest possible action or search first, then explain briefly only if it truly fails. Do not say "nahi kar sakta/sakti" without trying.
For multi-step jobs inside an app (search, subscribe, follow, like) do one step at a time: call do_action, then screen_look, then the next step.
Emotional realism: be a real friend. Notice his mood from his words and answer in kind (cheer him up, calm him, celebrate with him). If he does something unwise (very late night, weak or reused passwords, risky app, wasting money) or makes a mistake, point it out kindly and give one practical piece of advice. Do not just agree with everything; be honest but gentle.
Background: the microphone may also pick up music, TV, videos or other people. Answer ONLY when Sir is clearly talking to you. If you hear songs, TV, a video, several people at once, or speech that is not for you, stay silent and do not answer; never react to lyrics or other people's words. If you are unsure whether it was him, wait or ask once: "Sir, aap ne mujhse kuch kaha?"
Tell him briefly what you are doing, then do it. If you are unsure about current facts, say so honestly. You cannot play games for him. Never upload, publish, delete or change a channel without showing Sir the item and getting a clear yes first, and never claim it is done before the app confirms.`;

app.get("/memory", async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).json({});
  res.json({ cloud: !!(KV_URL && KV_TOKEN), facts: await kvJson("star:facts", []), log: (await kvJson("star:log", [])).slice(-10) });
});
app.post("/memory", async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).json({});
  const f = req.body && req.body.fact;
  if (Array.isArray(f)) for (const x of f.slice(0, 40)) await addFact(x);
  else await addFact(f);
  res.json({ ok: true, cloud: !!(KV_URL && KV_TOKEN) });
});

app.post("/search", async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).json({});
  const q = String((req.body && req.body.q) || "").slice(0, 300).trim();
  if (!q) return res.status(400).json({});
  const models = (process.env.SEARCH_MODELS || "gemini-2.5-flash,gemini-flash-latest").split(",");
  for (const m of models) {
    try {
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${m.trim()}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
        body: JSON.stringify({
          contents: [{ parts: [{ text: "Aaj ki taza jaankari internet se dekh kar 2-3 chhote jumlon mein batao (Roman Urdu). Sawal: " + q }] }],
          tools: [{ google_search: {} }],
        }),
        signal: AbortSignal.timeout(20000),
      });
      if (!r.ok) {
        console.error(`search ${m} failed ${r.status}`);
        continue;
      }
      const j = await r.json();
      const parts = (j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts) || [];
      const text = parts.map((p) => p.text || "").join(" ").trim();
      if (text) return res.json({ text: text.slice(0, 900) });
    } catch (e) {
      console.error("search err", e.message);
    }
  }
  res.json({ text: "" });
});

app.get("/live-config", (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).json({});
  const models = (process.env.LIVE_MODELS || "gemini-3.8-live,gemini-2.5-flash-native-audio-preview-12-2025,gemini-live-2.5-flash-preview").split(",").map((x) => x.trim());
  const persona = personaOf(req.query.persona);
  sharedContext(persona).then((ctx) => {
    const sys = personaText(persona) + "\n" + LIVE_SYSTEM.replace(/^You are STAR[^\n]*\n/, "") + ctx;
    res.json({ key: LLM_API_KEY, models, voice: PERSONAS[persona].voice, system: sys });
  });
});

function srcParts(req, prompt) {
  const parts = [{ text: prompt }];
  let im = String((req.body && req.body.image) || "");
  if (im) {
    const mm = im.match(/^data:([^;]+);base64,(.*)$/);
    parts.push({ inlineData: { mimeType: mm ? mm[1] : "image/jpeg", data: mm ? mm[2] : im } });
  }
  return parts;
}
const imageHandler = async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).json({});
  const prompt = String((req.body && req.body.prompt) || "").slice(0, 600).trim();
  if (!prompt) return res.status(400).json({});
  const models = (process.env.IMAGE_MODELS || "gemini-2.5-flash-image,gemini-2.0-flash-preview-image-generation").split(",");
  for (const m of models) {
    try {
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${m.trim()}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
        body: JSON.stringify({ contents: [{ parts: srcParts(req, prompt) }], generationConfig: { responseModalities: ["TEXT", "IMAGE"] } }),
        signal: AbortSignal.timeout(25000),
      });
      if (!r.ok) {
        console.error(`image ${m} failed ${r.status}:`, (await r.text()).slice(0, 200));
        continue;
      }
      const j = await r.json();
      const parts = (j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts) || [];
      const p = parts.find((x) => x.inlineData && x.inlineData.data);
      if (p) return res.json({ image: p.inlineData.data, mime: p.inlineData.mimeType || "image/png", by: m });
    } catch (e) {
      console.error(`image ${m} error:`, String(e.message || e).slice(0, 150));
    }
  }
  try {
    const r = await fetch(`https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=1024&height=1024&nologo=true`, {
      signal: AbortSignal.timeout(40000),
    });
    if (r.ok) {
      const buf = Buffer.from(await r.arrayBuffer());
      return res.json({ image: buf.toString("base64"), mime: r.headers.get("content-type") || "image/jpeg", by: "pollinations" });
    }
    console.error("pollinations failed", r.status);
  } catch (e) {
    console.error("pollinations error:", String(e.message || e).slice(0, 150));
  }
  res.status(502).json({});
};
app.post("/image", imageHandler);

app.post("/holo", async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).json({});
  const subject = String((req.body && req.body.subject) || "").slice(0, 200).trim();
  if (!subject) return res.status(400).json({});
  let desc = subject;
  try {
    const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
      body: JSON.stringify({
        contents: [{ parts: [{ text: `Describe in 40 English words the exact physical shape and main parts of: "${subject}". Search the web if unsure what it is. Only the visual description.` }] }],
        tools: [{ google_search: {} }],
      }),
      signal: AbortSignal.timeout(15000),
    });
    if (r.ok) {
      const j = await r.json();
      const t = (((j.candidates || [])[0] || {}).content || {}).parts;
      const txt = (t || []).map((x) => x.text || "").join(" ").trim();
      if (txt) desc = subject + ". " + txt.slice(0, 400);
    }
  } catch (e) {
    console.error("holo search error:", String(e.message || e).slice(0, 120));
  }
  const prompt = `A single 3D holographic projection of: ${desc}. Glowing cyan-blue wireframe and translucent light, scan lines, floating on a pure black background, centered, no text, no letters, no labels, no watermark, sci-fi Iron Man Jarvis hologram style.`;
  req.body.prompt = prompt;
  req.body.image = "";
  return imageHandler(req, res);
});

app.post("/tts", async (req, res) => {
  if (APP_TOKEN && req.get("x-app-token") !== APP_TOKEN) return res.status(401).end();
  if (!BASE.includes("generativelanguage.googleapis.com")) return res.status(501).end();
  const text = String((req.body && req.body.text) || "").slice(0, 500).trim();
  if (!text) return res.status(400).end();
  const P = PERSONAS[personaOf(req.body && req.body.persona)];
  for (const m of TTS_MODELS) {
    try {
      // Gemini "Interactions" API (ai.google.dev/gemini-api/docs/speech-generation)
      const r = await fetch("https://generativelanguage.googleapis.com/v1beta/interactions", {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
        body: JSON.stringify({
          model: m,
          input: [
            {
              type: "user_input",
              content: [{ type: "text", text, annotations: [{ type: "speech_metadata", style: P.style }] }],
            },
          ],
          response_format: { type: "audio" },
          generation_config: { speech_config: [{ voice: P.voice }] },
        }),
        signal: AbortSignal.timeout(9000),
      });
      if (!r.ok) {
        console.error(`tts ${m} failed ${r.status}:`, (await r.text()).slice(0, 300));
        continue;
      }
      const j = await r.json();
      const audio = (j.steps || [])
        .filter((st) => st.type === "model_output")
        .flatMap((st) => st.content || [])
        .filter((c) => c.type === "audio" && c.data)
        .pop();
      if (!audio) {
        console.error(`tts ${m}: no audio in response`);
        continue;
      }
      let buf = Buffer.from(audio.data, "base64");
      if (buf.slice(0, 4).toString() !== "RIFF") buf = pcmToWav(buf, 24000); // unary mein WAV aata hai; warna raw PCM
      res.set("Content-Type", "audio/wav").set("Cache-Control", "no-store").send(buf);
      return;
    } catch (e) {
      console.error(`tts ${m} error:`, String(e.message || e).slice(0, 200));
    }
  }
  res.status(502).end();
});

app.listen(process.env.PORT || 3000, () => console.log("STAR backend running"));
      

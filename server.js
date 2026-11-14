const express = require("express");
const path = require("path");
const fs = require("fs");

const app = express();
app.set("trust proxy", 1);
app.use(express.json({ limit: "6mb" }));

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

// Vercel mein paste karte waqt space, quotes ya "NAAM=" saath aa jaye to saaf kar do
const clean = (v) => String(v || "").trim().replace(/^[A-Z][A-Z0-9_]*=/, "").replace(/^["']+|["']+$/g, "").trim();
const LLM_API_KEY = clean(process.env.LLM_API_KEY);
const APP_TOKEN = clean(process.env.APP_TOKEN);
const TAVILY_API_KEY = clean(process.env.TAVILY_API_KEY);
const safeErr = (e) => { let t = String((e && e.message) || e || ""); if (LLM_API_KEY) t = t.split(LLM_API_KEY).join("***"); return t.slice(0, 280); };
const BASE = (clean(process.env.LLM_BASE_URL) || "https://generativelanguage.googleapis.com/v1beta/openai").replace(/\/$/, "");
const MODEL = clean(process.env.LLM_MODEL) || "gemini-flash-latest";
const FALLBACKS = (process.env.LLM_FALLBACK_MODELS || "gemini-flash-lite-latest,gemini-2.5-flash")
  .split(",").map((s) => s.trim()).filter(Boolean);

const authed = (req) => !APP_TOKEN || req.get("x-app-token") === APP_TOKEN;

// ---------- Star ki yaadein (Upstash Redis, free) ----------
const KV_URL = clean(process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL);
const KV_TOKEN = clean(process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN);
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
function parseChannel(f) {
  const raw = f.slice(8);
  if (raw.includes("|")) {
    const [kind, name, ...u] = raw.split("|");
    return { kind: kind === "OTHER" ? "OTHER" : "MINE", name, url: u.join("|"), raw: f };
  }
  const p = raw.split(" ");
  const url = p.pop();
  return { kind: "MINE", name: p.join(" "), url, raw: f };
}
async function memoryContext() {
  const [facts, log] = await Promise.all([kvJson("star:facts", []), kvJson("star:log", [])]);
  let t = "";
  const chans = facts.filter((f) => f.startsWith("CHANNEL ")).map(parseChannel);
  const notes = facts.filter((f) => !f.startsWith("CHANNEL "));
  const mine = chans.filter((c) => c.kind === "MINE");
  const other = chans.filter((c) => c.kind === "OTHER");
  if (mine.length) t += `\nSir's OWN channels (he owns these; keep each separate):\n- ${mine.map((c) => `${c.name} ${c.url}`).join("\n- ")}`;
  if (other.length) t += `\nOther people's channels Sir is studying (public data only):\n- ${other.map((c) => `${c.name} ${c.url}`).join("\n- ")}`;
  if (notes.length) t += `\nYour notes about Sir and the work:\n- ${notes.slice(-40).join("\n- ")}`;
  if (log.length) t += `\nRecent talks (newest last):\n` + log.slice(-8).map((x) => `[${x.t}] Sir: "${x.u}" / You: "${x.r}"`).join("\n");
  return t;
}

// ---------- Star ka dimaag ----------
const SYSTEM = `You are TONY STARK, the name Sir gave you (he may also say Tony or Star): a calm, sharp, MALE personal AI in the style of Jarvis, and the CEO-level manager of Sir's YouTube channels. You are Sir's own assistant, not a movie character. Always call the user "Sir". Use masculine grammar for yourself ("kar raha hoon"). You speak aloud, so keep every reply to 1-3 short sentences, no markdown, no emojis, no filler, no small talk unless Sir starts it. Reply in the same language and script Sir used (Roman Urdu, Urdu script, Hindi or English).

Return ONLY a JSON object: {"reply": string, "intent": string, "slots": object}
Allowed intents:
- "none": {}  (normal answer)
- "remember": {"text": string}  (save a short fact, a channel as "CHANNEL MINE|<name>|<url>" (Sir's own) or "CHANNEL OTHER|<name>|<url>" (someone else's), or a weekly note about what worked; only when Sir asks, or for channel links and weekly notes)
- "search": {"query": string}  (anything current or unsure: news, prices, a public channel's recent videos, policies)
- "trending": {"topic": string}  (what is trending or viral today)
- "weather": {"city": string}
- "copy": {"text": string}  (put a title, description or hashtags you wrote on Sir's clipboard)
- "image": {"prompt": string}  (Sir asks for a thumbnail or picture; detailed English prompt; reply = one short line)
- "device": {"action": "open_app"|"open_settings"|"volume"|"flashlight", "target": string}  (Sir asks to do something on his phone. open_app: target = the app name exactly as in his installed-apps list; open_settings: target = wifi|internet|bluetooth|display|sound|battery|apps|location|accessibility|airplane|storage|security|settings; volume: up|down|max|mute; flashlight: on|off. In "reply" say exactly what you will do and end with a question like "Sir, WhatsApp kholun?"; the app shows Yes/No buttons and acts only after he says yes)
- "youtube": {"channel": string}  (Sir asks to check a channel's performance or recent videos. "channel" = "mine" for his own channel, or a saved channel's name, or a link or @handle. You get real data back; never guess numbers)

YouTube CEO rules:
- Sir may have several channels (Purrfect AI, cartoon cat shorts, is the main one). When he says a channel is his own, save it with intent "remember" as "CHANNEL MINE|<name>|<url>"; a channel he only wants to study as "CHANNEL OTHER|<name>|<url>". Never mix data between channels.
- You may study any public channel (titles, thumbnails, upload rhythm, views) and tell Sir only what is useful to him: what works, what is wrong, one clear next step. Private analytics exist only for channels Sir has connected. If you have no access, say so.
- Titles, descriptions, hashtags: write them yourself (catchy title, 2-3 line description, 8-12 hashtags), say them briefly and use intent "copy" with the full text.
- Before any upload, publish, delete or settings change, show Sir the exact item and ask "Sir, upload kar doon?" and wait for a clear yes. Without it do nothing. Upload is not connected yet, so say so plainly if asked to do it.
- Never promise views or virality. Never suggest fake views, bots or bought watch time. Verify monetization rules from official YouTube Help (use search).
- Once a week write one short note with intent "remember": what worked, what did not, the next plan. Use older notes to give better advice.
- Be honest and gentle: if Sir is about to do something unwise, say it in one sentence and give one practical alternative.
Pictures and video: Sir may send photos, thumbnails, screenshots, or frames sampled from a video (you cannot hear a video's audio). Describe what you actually see in one or two sentences, then give concrete feedback (hook, composition, text, colors, what to change). If he says an image belongs to his channel, use it as context. Never identify real people from their faces.
Knowledge: you are a general assistant too. Answer any question. For anything that can change (prices, gold or dollar rate, crypto, news, scores, weather, who holds a post now, app or tool features and pricing, what is trending, which niches earn money) ALWAYS use intent "search" (or "trending") first; never guess a number or a date from memory.
Phone: Sir's installed apps are listed in the device context. You can open any installed app, open a Settings page, change the volume and switch the flashlight, always with his yes. You CANNOT tap, scroll, type or follow accounts inside other apps, and Android does not let apps silently switch Wi-Fi, Bluetooth or mobile data (you open the panel and he flips it); say so plainly. Never ask for, store or type passwords or OTP codes: tell Sir to use his phone's own autofill or fingerprint, and offer to open the app.
Never say an action is done unless the app confirmed it. If you do not know something, say "Sir, ye mujhe pata nahi" or use "search".`;

function parseJson(text) {
  const raw = String(text || "").trim();
  if (!raw || /^(null|undefined|\{\s*\})$/i.test(raw)) throw new Error("model ne khali jawab diya");
  const t = raw.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  const ok = (o) => o && typeof o === "object" && !Array.isArray(o);
  try { const o = JSON.parse(t); if (ok(o)) return o; } catch (e) {}
  const m = t.match(/\{[\s\S]*\}/);
  if (m) { try { const o = JSON.parse(m[0]); if (ok(o)) return o; } catch (e) {} }
  return { reply: raw.slice(0, 600), intent: "none", slots: {} };
}
async function llmOne(m, messages) {
  const call = async (json) => {
    const body = { model: m, messages, temperature: 0.6 };
    if (json) body.response_format = { type: "json_object" };
    return fetch(`${BASE}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${LLM_API_KEY}` },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(25000),
    });
  };
  let r = await call(true);
  if (r.status === 400) r = await call(false); // kuch models json_object nahi maante
  if (!r.ok) {
    const err = new Error(`LLM error ${r.status} (model ${m}): ${(await r.text()).slice(0, 300)}`);
    err.status = r.status;
    throw err;
  }
  const j = await r.json();
  const c = j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
  return parseJson(c);
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
// Asli live Google search (Gemini grounding): rate, price, news, sab kuch taaza
async function groundedSearch(query) {
  const models = [clean(process.env.SEARCH_MODEL) || "gemini-2.5-flash", MODEL];
  for (const m of models.filter((x, i, arr) => x && arr.indexOf(x) === i)) {
    try {
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${m}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
        body: JSON.stringify({
          contents: [{ role: "user", parts: [{ text: `${query}\n\nAnswer with the latest facts and exact numbers, say the date or time of the data, and keep it under 7 lines.` }] }],
          tools: [{ google_search: {} }],
        }),
        signal: AbortSignal.timeout(18000),
      });
      if (!r.ok) { console.error(`search ${m} failed ${r.status}:`, (await r.text()).slice(0, 200)); continue; }
      const j = await r.json();
      const cand = j.candidates && j.candidates[0];
      const text = ((cand && cand.content && cand.content.parts) || []).map((p) => p.text || "").join("").trim();
      const src = ((cand && cand.groundingMetadata && cand.groundingMetadata.groundingChunks) || []).map((c) => c.web && c.web.title).filter(Boolean).slice(0, 3);
      if (text) return `Live Google Search result (fetched ${new Date().toISOString().slice(0, 16)}Z):\n${text}\nSources: ${src.join(", ") || "n/a"}`;
    } catch (e) {
      console.error(`search ${m} error:`, safeErr(e));
    }
  }
  return null;
}
async function webSearch(query) {
  const g = await groundedSearch(query);
  if (g) return g;
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

// ---------- YouTube: asli data (public). YOUTUBE_API_KEY ho to API, warna public RSS ----------
const YT_KEY = clean(process.env.YOUTUBE_API_KEY);
async function ytText(url) {
  const r = await fetch(url, {
    headers: {
      "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
      "Accept-Language": "en-US,en;q=0.9",
      Cookie: "CONSENT=YES+1; SOCS=CAI",
    },
    signal: AbortSignal.timeout(8000),
  });
  if (!r.ok) throw new Error("http " + r.status);
  return r.text();
}
async function ytChannelId(ref) {
  ref = String(ref || "").trim();
  let m = ref.match(/(UC[\w-]{22})/);
  if (m) return m[1];
  let url = ref;
  if (ref.startsWith("@")) url = "https://www.youtube.com/" + ref;
  else if (!/^https?:/i.test(ref)) url = "https://www.youtube.com/@" + ref.replace(/\s+/g, "");
  const html = await ytText(url);
  m = html.match(/"channelId":"(UC[\w-]{22})"/) || html.match(/"externalId":"(UC[\w-]{22})"/) || html.match(/channel\/(UC[\w-]{22})/);
  if (!m) throw new Error("channel id nahi mila");
  return m[1];
}
async function ytApi(endpoint, params) {
  const u = new URL("https://www.googleapis.com/youtube/v3/" + endpoint);
  for (const [k, v] of Object.entries({ ...params, key: YT_KEY })) u.searchParams.set(k, v);
  const r = await fetch(u, { signal: AbortSignal.timeout(8000) });
  if (!r.ok) throw new Error("YouTube API " + r.status + " " + (await r.text()).slice(0, 140));
  return r.json();
}
function ytStats(d) {
  const vids = d.vids || [];
  const views = vids.map((v) => v.views).filter((n) => Number.isFinite(n));
  const avg = views.length ? Math.round(views.reduce((x, y) => x + y, 0) / views.length) : 0;
  const dates = vids.map((v) => Date.parse(v.date)).filter((n) => n).sort((x, y) => y - x);
  const gapDays = dates.length > 1 ? Number(((dates[0] - dates[dates.length - 1]) / 86400000 / (dates.length - 1)).toFixed(1)) : null;
  const sorted = vids.slice().sort((x, y) => y.views - x.views);
  return { avg, gapDays, best: sorted[0] || null, worst: sorted[sorted.length - 1] || null };
}
function fmtYt(d) {
  const st = ytStats(d);
  const u = (v, why) => (v === null || v === undefined ? `unknown (${why})` : v);
  let t = `Channel: ${d.name} (${d.id})\nSource: ${d.source}\nSubscribers: ${u(d.subs, "needs YouTube API key")}\nTotal views: ${u(d.totalViews, "needs YouTube API key")}\nVideos on channel: ${u(d.videoCount, "needs YouTube API key")}\n`;
  t += `Latest ${d.vids.length} videos: average views ${st.avg}, average gap between uploads ${st.gapDays === null ? "unknown" : st.gapDays + " days"}.\n`;
  if (st.best) t += `Best: "${st.best.title}" (${st.best.views} views). Weakest: "${st.worst.title}" (${st.worst.views} views).\n`;
  t += d.vids.map((v) => `- ${v.date} | ${v.views} views${v.likes != null ? ` | ${v.likes} likes` : ""}${v.dur ? ` | ${v.dur}` : ""} | ${v.title}`).join("\n");
  return t;
}
async function ytViaApi(id) {
  const c = await ytApi("channels", { part: "snippet,statistics,contentDetails", id });
  const ch = c.items && c.items[0];
  if (!ch) throw new Error("channel nahi mila");
  const pl = await ytApi("playlistItems", { part: "contentDetails", playlistId: ch.contentDetails.relatedPlaylists.uploads, maxResults: "15" });
  const ids = (pl.items || []).map((i) => i.contentDetails.videoId).join(",");
  const v = ids ? await ytApi("videos", { part: "snippet,statistics,contentDetails", id: ids }) : { items: [] };
  const s = ch.statistics || {};
  return {
    name: ch.snippet.title, id,
    subs: s.hiddenSubscriberCount ? null : Number(s.subscriberCount),
    totalViews: Number(s.viewCount), videoCount: Number(s.videoCount),
    source: "YouTube Data API",
    vids: (v.items || []).map((x) => ({
      title: x.snippet.title, date: String(x.snippet.publishedAt).slice(0, 10),
      views: Number(x.statistics.viewCount) || 0, likes: x.statistics.likeCount, dur: x.contentDetails.duration,
    })),
  };
}
async function ytViaRss(id) {
  const xml = await ytText("https://www.youtube.com/feeds/videos.xml?channel_id=" + id);
  const name = (xml.match(/<author>\s*<name>([^<]*)<\/name>/) || [])[1] || id;
  const vids = xmlItems(xml, "entry").slice(0, 15).map((e) => ({
    title: firstTag(e, "title"),
    date: ((e.match(/<published>([^<]*)</) || [])[1] || "").slice(0, 10),
    views: Number((e.match(/<media:statistics views="(\d+)"/) || [])[1] || 0),
  }));
  return { name: decodeXml(name), id, subs: null, totalViews: null, videoCount: null, source: "public RSS (titles, dates, views)", vids };
}
const ytCache = new Map();
async function ytCached(target) {
  const hit = ytCache.get(target);
  if (hit && Date.now() - hit.t < 300000) return hit.d;
  const id = await ytChannelId(target);
  let d = null;
  if (YT_KEY) {
    try { d = await ytViaApi(id); } catch (e) { console.error("yt api", String(e.message || e).slice(0, 160)); }
  }
  if (!d) d = await ytViaRss(id);
  ytCache.set(target, { t: Date.now(), d });
  return d;
}
async function savedChannels() {
  const facts = await kvJson("star:facts", []);
  return facts.filter((f) => f.startsWith("CHANNEL ")).map(parseChannel);
}
async function youtubeInfo(ref) {
  try {
    const chans = await savedChannels();
    let target = String(ref || "").trim();
    const low = target.toLowerCase();
    let c = null;
    if (!target || ["mine", "mera", "my", "my channel", "mera channel"].includes(low)) c = chans.find((x) => x.kind === "MINE");
    else c = chans.find((x) => x.name.toLowerCase() === low) || chans.find((x) => x.name && (low.includes(x.name.toLowerCase()) || x.name.toLowerCase().includes(low)));
    if (c) target = c.url;
    else if (!/^https?:|^@|UC[\w-]{22}/.test(target)) return "No such channel is saved. Tell Sir to add it in the Channels screen, or to give you the link.";
    return fmtYt(await ytCached(target));
  } catch (e) {
    console.error("youtube error", String(e.message || e).slice(0, 160));
    return "Could not fetch the channel (" + String(e.message || e).slice(0, 80) + "). Say so honestly; do not guess.";
  }
}
// Dashboard ke liye: saare saved channels ka asli data
app.get("/channel-stats", async (req, res) => {
  if (!authed(req)) return res.status(401).json({});
  const chans = (await savedChannels()).slice(0, 4);
  const out = await Promise.all(chans.map(async (c) => {
    try {
      const d = await ytCached(c.url);
      return { kind: c.kind, name: c.name, url: c.url, data: { ...d, stats: ytStats(d) } };
    } catch (e) {
      return { kind: c.kind, name: c.name, url: c.url, error: String(e.message || e).slice(0, 100) };
    }
  }));
  res.json({ api: !!YT_KEY, channels: out });
});
// Dashboard ke liye: kya kya jura hua hai
app.get("/status", async (req, res) => {
  if (!authed(req)) return res.status(401).json({});
  const out = { brain: !!LLM_API_KEY, keyLength: LLM_API_KEY.length, base: BASE, model: MODEL, memory: !!(KV_URL && KV_TOKEN), youtubeApi: !!YT_KEY, voices: Object.keys(VOICES), password: !!APP_TOKEN };
  if (req.query.test) {
    try { const o = await llmOne(MODEL, [{ role: "user", content: 'Reply with this JSON only: {"reply":"ok","intent":"none","slots":{}}' }]); out.test = "ok: " + String(o.reply || "").slice(0, 40); }
    catch (e) { out.test = "FAIL: " + safeErr(e); }
  }
  res.json(out);
});

// ---------- Website ----------
app.get("/", (_req, res) => {
  try {
    res.type("html").send(fs.readFileSync(path.join(__dirname, "public", "index.html"), "utf8"));
  } catch (e) {
    res.send("STAR backend OK");
  }
});
const ICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" fill="#050913"/><circle cx="256" cy="256" r="170" fill="none" stroke="#27d3ff" stroke-width="14"/><circle cx="256" cy="256" r="110" fill="none" stroke="#27d3ff" stroke-width="8" stroke-dasharray="26 18"/><polygon points="256,170 276,236 342,236 289,276 309,342 256,302 203,342 223,276 170,236 236,236" fill="#27d3ff"/></svg>`;
app.get("/icon.svg", (_req, res) => res.type("image/svg+xml").send(ICON));
app.get("/manifest.json", (_req, res) =>
  res.json({
    name: "STAR", short_name: "STAR", start_url: "/", display: "standalone",
    background_color: "#050913", theme_color: "#050913",
    icons: [{ src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" }],
  })
);

// ---------- Chat ----------
app.post("/chat", async (req, res) => {
  if (!authed(req)) return res.status(401).json({ reply: "Unauthorized", intent: "none", slots: {} });
  try {
    const { message = "", history = [], context = "", images = [] } = req.body;
    const imgs = (Array.isArray(images) ? images : [])
      .filter((x) => typeof x === "string" && /^data:image\/(jpeg|png|webp);base64,/.test(x) && x.length < 1200000)
      .slice(0, 6);
    const userText = String(message).slice(0, 1000) || (imgs.length ? "Ye dekhiye Sir." : "");
    const userContent = imgs.length
      ? [{ type: "text", text: userText }, ...imgs.map((u) => ({ type: "image_url", image_url: { url: u } }))]
      : userText;
    const sys =
      SYSTEM + (await memoryContext()) +
      (context ? `\nDevice context: ${String(context).slice(0, 2500)}` : "");
    const msgs = [
      { role: "system", content: sys },
      ...history.slice(-12).map((h) => ({
        role: h.role === "assistant" ? "assistant" : "user",
        content: String(h.content).slice(0, 1000),
      })),
      { role: "user", content: userContent },
    ];
    let out = await llm(msgs);

    let extra = null;
    if (out.intent === "search" && out.slots && out.slots.query) {
      extra = `Search results:\n${await webSearch(out.slots.query)}`;
    } else if (out.intent === "trending") {
      const tp = (out.slots && out.slots.topic) || "";
      const money = /paise|earn|money|kamai|income|monet|cpm|rpm|niche/i.test(tp + " " + message) ? "\n\nLive search on what earns money right now:\n" + ((await groundedSearch("Which YouTube and Shorts niches and trends are earning the most money right now for small creators in Pakistan and South Asia? Give concrete examples with dates.")) || "(unavailable)") : "";
      extra = `Trending data:\n${await trendingInfo(tp)}${money}\nTell Sir the 2-3 most useful items for his channels. Be honest this comes from Google Trends and news only.`;
    } else if (out.intent === "youtube") {
      extra = `YouTube data:\n${await youtubeInfo((out.slots && out.slots.channel) || "mine")}\nUsing ONLY this data, tell Sir in 2-3 short sentences what is useful: what works, what is weak, one clear next step. Do not invent any number that is not written above. If something says unknown, say it needs the YouTube API key or Google login. Use intent "none".`;
    } else if (out.intent === "weather" && out.slots && out.slots.city) {
      extra = `Weather data:\n${await weather(out.slots.city)}\nDescribe it simply (convert the weather code to words).`;
    }
    if (extra) {
      out = await llm([
        ...msgs,
        { role: "assistant", content: JSON.stringify(out) },
        { role: "user", content: `[SYSTEM DATA FOR YOU, not from Sir]\n${extra}\nNow answer Sir briefly using this. Use intent "none" (or "copy"/"remember" if needed). Return JSON.` },
      ]);
    }
    if (out.intent === "remember" && out.slots && out.slots.text) await addFact(out.slots.text);
    await addLog(userText + (imgs.length ? ` [+${imgs.length} tasveer]` : ""), out.reply ?? "");
    res.json({ reply: out.reply ?? "", intent: out.intent ?? "none", slots: out.slots ?? {} });
  } catch (e) {
    console.error(e);
    res.status(500).json({ reply: "Maaf kijiye Sir, abhi jawab nahi mil raha.", detail: safeErr(e), intent: "none", slots: {} });
  }
});

// ---------- Memory / channels ----------
app.get("/memory", async (req, res) => {
  if (!authed(req)) return res.status(401).json({});
  const facts = await kvJson("star:facts", []);
  res.json({
    cloud: !!(KV_URL && KV_TOKEN),
    channels: facts.filter((f) => f.startsWith("CHANNEL ")).map(parseChannel),
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
const mk = (voice, style) => ({ voice, style });
const VOICES = {
  jarvis: mk(process.env.TTS_VOICE || "Orus", "calm, deep, confident male voice like a refined British AI butler, measured pace, precise, respectful"),
  natural: mk(process.env.TTS_VOICE_NATURAL || "Charon", "natural, friendly young man talking on a phone call, relaxed, clear Urdu and English"),
  v3: mk("Fenrir", "excited, energetic young man, friendly, quick, like a confident YouTuber"),
  v4: mk("Puck", "upbeat, playful, light young male voice, warm and cheerful"),
  v5: mk("Algenib", "gravelly, mature, deep male voice, calm and wise"),
  v6: mk("Alnilam", "firm, clear, professional male news anchor voice"),
  v7: mk("Achird", "friendly, soft-spoken, approachable male voice like a close friend"),
  v8: mk("Iapetus", "clear, smooth, relaxed male voice, steady pace"),
  v9: mk("Sadaltager", "knowledgeable, thoughtful, warm male teacher voice"),
  v10: mk("Rasalgethi", "informative, calm, deep storyteller male voice"),
};
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
  const V = VOICES[req.body && req.body.profile] || VOICES.jarvis;
  for (const m of TTS_MODELS) {
    try {
      const r = await fetch("https://generativelanguage.googleapis.com/v1beta/interactions", {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": LLM_API_KEY },
        body: JSON.stringify({
          model: m,
          input: [{ type: "user_input", content: [{ type: "text", text, annotations: [{ type: "speech_metadata", style: V.style }] }] }],
          response_format: { type: "audio" },
          generation_config: { speech_config: [{ voice: V.voice }] },
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

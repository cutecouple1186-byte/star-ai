# STAR AI

Jarvis jaisi awaaz wala assistant aur YouTube channels ka CEO.

## Kaam
- Orb dabayein aur bolein, ya type karein. Star aapko "Sir" bulata hai.
- Channels alag alag yaad rakhta hai (Channels screen).
- Upload, delete, publish se pehle poochta hai: "Sir, upload kar doon?" Aapki "haan" ke baghair kuch nahi.
- Thumbnail banata hai, titles/description likhta hai, trend aur news dekhta hai.
- Hafte ki yaad-dasht se behtar salah deta hai.

## Chalane ka tareeqa
```bash
npm install
LLM_API_KEY=your_key APP_TOKEN=your_password npm start
```

## Environment variables
| Naam | Kaam |
|---|---|
| `LLM_API_KEY` | Gemini API key (zaroori) |
| `LLM_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| `LLM_MODEL` | `gemini-flash-latest` |
| `APP_TOKEN` | Password (zaroori) |
| `KV_REST_API_URL`, `KV_REST_API_TOKEN` | Upstash Redis (free), Star ki yaadein |
| `TTS_VOICE` | JARVIS awaaz, default `Orus` |
| `TTS_VOICE_NATURAL` | Natural awaaz, default `Charon` |
| `YOUTUBE_API_KEY` | Optional. Channel ke subscribers aur zyada tafseel ke liye (YouTube Data API v3). Na ho to public RSS se videos aur views |

## Android app
`android/` mein chhoti native app hai: Star ka page kholti hai aur mic se sunti hai. GitHub Actions khud `Star.apk` banata hai (Releases mein).

## Abhi baqi
- "Hello Star" wake word (background mein sunna)
- YouTube ka asli control (Google login: analytics, upload)

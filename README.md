# STAR AI

Jarvis jaisa awaaz wala assistant aur YouTube channels ka CEO. Noor se alag dimaag (alag memory).

## Kaam
- Wake word: "Hello Star". Aapko "Sir" bulata hai.
- Channel links yaad rakhta hai, har channel alag.
- Upload, delete, publish se pehle aapko dikhata hai aur "Sir, upload kar doon?" poochta hai. Aapki "haan" ke baghair kuch nahi.
- Har hafte ki yaad-dasht se behtar hota jata hai.

## Chalane ka tareeqa
```bash
npm install
LLM_API_KEY=your_key APP_TOKEN=your_password npm start
```

## Environment variables
| Naam | Kaam |
|---|---|
| `LLM_API_KEY` | Gemini/LLM API key (zaroori) |
| `APP_TOKEN` | Password (zaroori rakhein) |
| `KV_REST_API_URL`, `KV_REST_API_TOKEN` | Upstash Redis (free), Star ki yaadein |
| `TTS_VOICE` | Awaaz, default `Orus` |

## Android app
GitHub Actions khud `Star.apk` banata hai. `MainActivity.kt` mein `DEFAULT_URL` apne Vercel URL se badlein.

## Hadood
YouTube channel ka asli control (upload, analytics) ke liye Google OAuth jodna baqi hai.

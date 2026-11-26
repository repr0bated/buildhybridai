# Call Guardian · web

The Chrome / web platform for [#BuildHybridAI](https://firebasehybridaihackathon.devpost.com/). Paste a text, a phone
number or a link; Call Guardian runs the same pipeline as the Android app:

1. **Rules** (instant, offline): a port of the Android `HeuristicScanner`, `SubscriptionDetector`, `Redactor`.
2. **Model**: Firebase AI Logic hybrid inference (`getGenerativeModel(ai, { mode: PREFER_ON_DEVICE })`, Gemini Developer API backend): **Gemini Nano in Chrome** (Prompt API) when it is ready, **cloud Gemini** otherwise. `inferenceSource` tells the UI which one actually ran. When the request will leave the browser the text is redacted first. The model must return JSON that validates against `scam_verdict.schema.json` (ajv), or the rules decide.
3. **Policy**: the Android `CapabilityPolicy`, ported. A model only proposes a registry method.
4. **Typed methods**: proposals come with a confirmation form **generated from the method's JSON Schema**; the receipt view is generated from the output schema; receipts are stored in IndexedDB.
5. **Sync** (optional): Google sign-in + Firestore keeps receipts, verdicts, block/trust lists, subscriptions, private flags and settings in step with the phone. Message content is **not** synced unless you switch on *Sync message content*. See [`../docs/SYNC.md`](../docs/SYNC.md).

Nothing is hand-typed per method: `npm run gen` reads the shared registry (`../app/src/main/assets/schemas`) and generates
TypeScript types (json-schema-to-typescript), the ajv schema bundle and `firestore.rules`.

## Run

```bash
cd web
npm install
npm run dev          # http://localhost:5173
npm test             # vitest (rules, policy, validation, forms, IndexedDB, sync)
npm run build        # -> dist/
```

On-device AI needs desktop Chrome 139+ with `chrome://flags/#prompt-api-for-gemini-nano-multimodal-input` enabled; use
the **Download Nano** button (it needs a click) to fetch the model. Without it the app falls back to cloud Gemini.

Local App Check: the debug provider is used on `localhost`. Open `http://localhost:5173/?appcheck=<debug token>` once
with a token registered in the Firebase console (App Check is not enforced).

## Deploy (Firebase Hosting, Spark plan)

```bash
npm run build && npx firebase-tools deploy --only hosting,firestore:rules --project callguardian-hybrid
```

## Notes

* Firebase web config (`src/firebase-config.json`) is public by design. App Check (reCAPTCHA Enterprise) is attached but not enforced.
* "Private" items are blurred until you press *Reveal private items*. On the web this is a simple reveal (no biometrics); the phone gates private items with the device lock.
* JetBrains Mono (SIL OFL 1.1) is self-hosted from `public/fonts` (license in `public/fonts/OFL.txt`).
* Personal project of Jeremy Hobson, Apache-2.0.

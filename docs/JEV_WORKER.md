# Jev second-opinion Worker (TypeSafe proxy)

Replaces the Firebase Cloud Function `jevCheck` (which needs the Blaze plan) with a Cloudflare
Worker on the **free** plan. The TypeSafe API key is a Worker secret; clients never see it.

**URL:** `https://callguardian-jev.reprobate.workers.dev`

On the web app, Jev is an **opt-in** Settings toggle (off by default). The default judge is Firebase AI Logic hybrid. The Worker is deployed and ready; the app only calls it when the toggle is on.

The decision table is unchanged and still lives in the clients (`JevTruthTable.kt` /
`web/src/jev-truth.ts`). The Worker only returns Jev's Noul probabilities, same as `jevCheck`.

## Auth

Every `POST` requires a Firebase App Check JWT:

```
X-Firebase-AppCheck: <jwt>
```

The Worker fetches `https://firebaseappcheck.googleapis.com/v1/jwks` and checks:

- `iss` = `https://firebaseappcheck.googleapis.com/662816906631`
- `aud` includes `projects/662816906631`
- `sub` is one of the allowed apps:
  - `1:662816906631:android:fc523a574d8ebc46c14722` (release)
  - `1:662816906631:android:91ee4f553d3fe35cc14722` (debug)
  - `1:662816906631:web:74134fe89fcf6d82c14722` (web)

Missing or invalid tokens return **401**. An unknown app returns **403**.

A basic per-app rate limit of **60 items / minute** is enforced (a batch of N items costs N). Over
the limit returns **429** with `Retry-After`.

## Single check

`POST /v1/check`

```json
{ "redactedText": "USPS: … https://usps-redelivery-help.top/[path]", "sender": "unknown_number" }
```

`sender` is one of `saved_contact | unknown_number | short_code | hidden`.
`redactedText` must be 1–2000 characters and must already be passed through `Redactor` (raw phone
numbers and emails are rejected).

**200:**

```json
{
  "model": "jev-1.14.0",
  "questionsVersion": "2026.11.1",
  "signals": {
    "asks_payment": 0.03,
    "deadline_pressure": 0.98,
    "claims_organization": 0.96,
    "asks_open_link": 0.98,
    "asks_share_code": 0.02,
    "asks_login_details": 0.05,
    "offers_money": 0.02,
    "moves_to_other_app": 0.03,
    "stranger_opener": 0.34
  },
  "inputTokens": 823
}
```

This is the same `result` object the Cloud Function returned. Feed `signals` to `JevTruthTable.decide`.
Errors: `{ "error": "<code>", "message": "…" }` with HTTP 400/401/403/429/503.

### Drop-in callable path (optional)

`POST /jevCheck` still accepts the Firebase callable envelope
`{ "data": { "redactedText", "sender" } }` and returns `{ "result": { … } }` or
`{ "error": { "status", "message" } }`, so the existing `JevClient` can switch by changing only the
URL to `https://callguardian-jev.reprobate.workers.dev/jevCheck` (keep the `X-Firebase-AppCheck`
header; no Firebase ID token is required).

## Batch (startup processing)

`POST /v1/check:batch`

Use this when the app opens and has a queue of unprocessed texts. Max **20** items per request.
Each item counts toward the per-app rate limit.

```json
{
  "items": [
    { "redactedText": "…", "sender": "unknown_number" },
    { "redactedText": "…", "sender": "short_code" }
  ]
}
```

**200:**

```json
{
  "results": [
    { "ok": true, "model": "jev-1.14.0", "questionsVersion": "2026.11.1", "signals": { "asks_payment": 0.02 }, "inputTokens": 816 },
    { "ok": false, "error": "redactedText must be 1-2000 characters" }
  ]
}
```

`results[i]` corresponds to `items[i]`. A TypeSafe failure on one item does not fail the whole batch.

## Android wiring (for the Android worker)

1. Point `JevClient.url()` at `https://callguardian-jev.reprobate.workers.dev/v1/check` (or `/jevCheck` if you keep the callable envelope).
2. Keep sending `X-Firebase-AppCheck`.
3. Add a batch call for process-all / startup: `POST /v1/check:batch` with the shape above.
4. Do not put `TYPESAFE_API_KEY` in the app. Do not call `api.typesafe.ai` from the device.
5. Continue to redact with `Redactor` before sending, and run `JevTruthTable` on `signals`.
6. Schema: `guardian.jev.check@v1` is unchanged (`questionsVersion` `2026.11.1`, signal ids match the output schema).

Source: `workers/jev/`. Deploy (from that directory, secret already set):

```bash
cd workers/jev && npm run deploy
```

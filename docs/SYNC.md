# Phone ⇄ web sync (spec for the Android side)

The web app (`web/`) is finished and deployed. This is the exact contract the Android app must follow so both
sides see the same receipts, verdicts, block/trust lists, subscriptions, private flags and settings.
Everything is **Firebase Auth (Google) + Cloud Firestore on the Spark plan** (no billing).

## 1. Backend (already provisioned, project `callguardian-hybrid`)

| Piece | State |
|---|---|
| Firestore `(default)` | Native mode, `us-east1` (the org policy blocks `nam5`/`us-central1`) |
| Security rules | Deployed, generated from the schemas: `web/firestore.rules` |
| Firebase Auth | Initialized. **Google provider must be switched on once in the console** (Authentication → Sign-in method → Google → Enable). That also creates the Web client ID Android needs. |
| Web app | `1:662816906631:web:74134fe89fcf6d82c14722` |

Rules summary: only `users/{uid}/…` is readable or writable, and only by that signed-in `uid`. Every write is checked
against its schema (`hasOnly` / required keys / types / enums / sizes). The `content` collection can be written only
while `users/{uid}/settings/main.syncMessageContent == true`.

## 2. Documents

Schemas live in `app/src/main/assets/schemas/sync/*.schema.json` (draft-07, same style as the method schemas).
Types are generated from them: TypeScript for the web, Kotlin in [`docs/sync/SyncTypes.kt`](sync/SyncTypes.kt)
(`web/scripts/gen-kotlin.sh`, kotlinx.serialization). Do not hand-write these classes.

| Collection → doc id | Schema | Room source |
|---|---|---|
| `users/{uid}/settings/main` | `sync_settings` | settings prefs (`syncMessageContent`, `autoBlockEnabled`, `autoBlockThreshold`, `onDeviceOnly`) |
| `users/{uid}/verdicts/{id}` | `sync_verdict` | `screening_events` (no `body`) |
| `users/{uid}/receipts/{id}` | `sync_receipt` | method receipts (typed input/output) |
| `users/{uid}/senders/{key}` | `sync_sender_rule` | block list / trusted / muted senders |
| `users/{uid}/subscriptions/{key}` | `sync_subscription` | `SubscriptionGroup` (no `lastBody`) |
| `users/{uid}/privateFlags/{kind}_{itemId}` | `sync_private_flag` | "Make private" flags |
| `users/{uid}/content/{itemId}` | `sync_content` | message text and model-written text (opt-in) |

Conventions:

* `deviceId`: a random id the app creates once (`"android" + 12 hex`). `id` for verdicts and receipts is `"<deviceId>-<localId>"` (receipts: `"<deviceId>-r<localId>"`). The document id equals the `id`/`key`/`itemId` field (rules enforce it).
* `updatedAt`: epoch millis. **Last writer wins**: apply a remote doc only if `remote.updatedAt > local.updatedAt`.
* Deletes are tombstones (`deleted: true`, bump `updatedAt`), never a Firestore delete (except `content`, see below).
* Sender key = `normalize(number)` as already done by `ScreeningEngine.normalize` (digits and `+`, or an e-mail-to-SMS address), lower-cased, any char outside `[a-z0-9+._@-]` replaced by `_`, max 80 chars. One doc per sender: `list` is `BLOCK | TRUST | MUTE`.
* Subscription key = `<source>_<senderKey>` where source is `sms | google_voice | whatsapp`.
* `origin` is `"android"` for documents the phone writes.
* `verdict.proposedMethod/finalMethod` are registry **aliases** (`block_number`, `none`, …).
* `source` uses the existing `MessageSource` wire values (+ `web_paste` for the web app).
* `eventId` inside typed inputs is the local numeric id; it is meaningful only on the device that wrote it.

### What must never be in a synced receipt/verdict/subscription

No message text. Specifically strip, before writing a `receipt`:
`input.reason`, `input.redactedText`, `input.onDeviceOpinion`, `input.warning` (move them to `content/{receiptId}.inputText`),
and write `ruleFlags` as the **fixed rule labels with the dynamic part removed** (`Look-alike brand domain (x.top)` →
`Look-alike brand domain`; `Uses the name "USPS"` → `Uses a well-known brand name`). Model `reasons`/`advice` go to `content`, not the verdict.

### "Sync message content" (opt-in, default OFF)

* Toggle in Settings (confirm dialog explaining where the text goes). Stored in `settings/main.syncMessageContent`.
* ON → also upload `content/{itemId}` for verdicts (`body`, `reasons`, `advice`), receipts (`inputText`, `summary`, `redactedText`) and calls/texts the user has in the log.
* OFF → **delete every document in `users/{uid}/content`** (hard delete is allowed by the rules) and stop writing it. Local copies stay on the device.
* The rules reject `content` writes while the flag is false, so a bug cannot leak text.

## 3. Android implementation plan

Dependencies (BoM already in the project for AI Logic): `firebase-auth-ktx`, `firebase-firestore-ktx`,
`androidx.credentials:credentials`, `credentials-play-services-auth`, `com.google.android.libraries.identity.googleid:googleid`.

1. **Sign in**: Credential Manager `GetGoogleIdOption` with the **Web client ID** (shown in Firebase console → Authentication → Google → Web SDK configuration, or `default_web_client_id` after re-downloading `google-services.json`) → `GoogleAuthProvider.getCredential(idToken)` → `FirebaseAuth.signInWithCredential`. Add a "Sync" row in Settings with sign-in/out and the content toggle. Nothing syncs while signed out.
2. **Module** `com.example.callguardian.sync` (new package, no edits to existing files except hooks):
   * `SyncRepository(firestore, auth, dao…)` with `start()/stop()`.
   * Upstream: observe Room (`Flow`) for each table; map to the generated `Sync*` classes, validate with the schema (`SchemaValidator` already exists: use `sync/<name>.schema.json`), `set()` the doc. Use Firestore's persistent cache (default on Android) for offline queueing.
   * Downstream: `addSnapshotListener` on each `users/{uid}/<collection>`; for each doc, validate, then upsert into Room only if `remote.updatedAt > local.updatedAt` (LWW). Tombstones remove from the active lists.
   * First attach: upload local docs the cloud lacks or has older (same as the web's `SyncCore`, `web/src/sync.ts`).
   * Mapping notes: block list ↔ `senders` (`list=BLOCK`, `scope`=CALLS|MESSAGES|ALL); trusted ↔ `TRUST`; subscription mutes ↔ `MUTE`; quarantine flag ↔ `verdict.quarantined`; "Make private" ↔ `privateFlags` (kind `call|sms|receipt|verdict`).
   * Remote senders with `list=BLOCK` must feed `PhoneProfile.shouldBlockCalls/Messages` so a block made on the web takes effect on the phone.
3. **Reference implementation**: `web/src/sync.ts` (`SyncCore`) is the algorithm; `web/tests/data.test.ts` has the behaviour as tests (content gating, LWW, tombstones, settings).
4. **Add** the sync schemas to `RegistryConsistencyTest`-style checks if desired. They are not part of `registry.json` (they are not methods).

## 4. Security notes

* Data is private to the signed-in user. Do not add any other `match` blocks without updating `web/scripts/gen-types.mjs` (rules are generated; edit the schemas, run `npm run gen`, redeploy).
* Redeploy rules: `cd web && npx firebase-tools deploy --only firestore:rules` (or the REST `firebaserules` API as done initially).
* App Check is attached on the web (reCAPTCHA Enterprise) but not enforced anywhere.

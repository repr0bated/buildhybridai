# Firebase console setup (jeremy@3tched.com)

These steps use the existing paid-tier Google/Firebase account. Nothing in the repo contains secrets, and `google-services.json` stays local (gitignored).

## 1. Project
1. Go to https://console.firebase.google.com and sign in as **jeremy@3tched.com**.
2. Create a project (e.g. `callguardian-hybrid`) or pick an existing one. Google Analytics is optional; it isn't used.

## 2. Register the Android apps
Project settings → *Your apps* → **Add app → Android**. Do this twice:

| | Package name | Nickname |
|---|---|---|
| Debug (the demo APK) | `io.github.repr0bated.callguardian.debug` | Call Guardian (debug) |
| Release | `io.github.repr0bated.callguardian` | Call Guardian |

Add the signing certificate fingerprints (Project settings → app → *Add fingerprint*):

| Build | SHA-1 | SHA-256 |
|---|---|---|
| Debug APK built on the box (`~/.android/debug.keystore`) | `73:92:E5:C6:AC:DE:74:36:FC:01:AA:82:46:29:E1:14:9B:8D:23:EA` | `89:DF:A3:86:82:99:31:85:E9:13:00:4B:46:3D:9E:BF:42:C0:4D:7A:65:95:A3:CB:2D:07:25:2A:F0:5D:0A:C4` |
| Release APK (`callguardian-release.jks`, kept outside the repo) | `E0:87:CC:81:9B:4D:DB:52:25:9D:02:E0:2A:51:3C:7D:8D:C9:C0:1F` | `F3:36:BC:06:BE:C7:40:18:46:5B:8E:12:90:E2:07:A7:03:49:85:40:74:C8:4C:DE:29:75:A8:49:96:7A:4F:7C` |

If you build on another machine, its debug keystore differs. Get its fingerprints with `./gradlew signingReport`.

Download **google-services.json** (it covers both apps once both are registered) and place it at **`app/google-services.json`**. Then rebuild with `./gradlew assembleDebug`.

## 3. Firebase AI Logic
1. In the console, go to **AI Services → AI Logic → Get started**.
2. Choose the **Gemini Developer API**, which works on the paid tier, and enable the required APIs when prompted.
3. Keep the cloud model at `gemini-3.5-flash-lite`, or set `-PCLOUD_MODEL=` at build time.
4. The on-device side (Gemma 4 / Nano 4 via AICore) needs no console setting. It needs the AICore Developer Preview enrollment on the phone (see README → Install).

## 4. App Check
1. Go to **Security → App Check → Apps**.
2. Release app: register with **Play Integrity**. Accept the terms and link the Google Cloud project if prompted. Play Integrity only passes for Play-distributed installs, so a sideloaded release APK will fail App Check for cloud calls.
3. Debug app (used for the demo):
   1. Register it with any provider (Play Integrity is fine).
   2. Install the debug APK, open it, and run **Explain with Gemini** once. This makes the debug provider generate a token.
   3. In the app, open **Settings → Cloud & App Check → Copy token**. The token also appears in logcat under the `DebugAppCheckProvider` tag.
   4. In the console, open the debug app → ⋮ → **Manage debug tokens** → Add → paste the token.
4. **APIs → Firebase AI Logic → Enforce.** AI Logic setup may already have turned on enforcement. Check the metrics tab for verified requests after the first cloud call.

## 5. Verify
* Home shows **Online** and the model card reads **Ready**.
* Open a Lab sample → **Explain with Gemini** → the confirm sheet shows the redacted text → **Send redacted text**. The deep analysis card fills in and Receipts shows `guardian.cloud.explain@v1 · OK · cloud`.
* If you get "App Check rejected the request", the debug token isn't registered yet.

## Project (provisioned)

- Firebase project: `callguardian-hybrid` (number `662816906631`), no billing linked.
- Android apps: `io.github.repr0bated.callguardian`, `io.github.repr0bated.callguardian.debug` (SHA-1 and SHA-256 registered for both).
- Enabled: Firebase AI Logic (`firebasevertexai`), Generative Language, App Check, Play Integrity.
- App Check: Play Integrity configured for the release app; not enforced. Register the debug token from the phone before enforcing.

## 6. Spark-plan products (Crashlytics, Performance, Analytics, A/B Testing, companion alerts)

These are enabled in the app and web companion. Do not upgrade the project to Blaze.

### Crashlytics (Android)
No extra console step beyond using this Firebase project. The first debug or release run uploads crashes automatically. Keep App Check debug tokens out of git.

### Performance Monitoring
Custom traces `inference_nano` and `inference_cloud` are recorded on Android and web. After a few inference runs they appear under **Performance**.

### Analytics
The app logs a `verdict` event with only `source` (`nano` / `cloud` / `local`), `category`, and `fallback_reason`. Message text and phone numbers are never attached. A/B Testing uses this event as the experiment metric.

### Remote Config A/B test of two triage prompts

The in-app / in-browser default is variant **A**. Variant **B** is a more conservative triage prompt (same required JSON fields). Both clients read `triage_prompt_variant`.

The Firebase CLI can list, get, and delete Remote Config experiments (`firebase remoteconfig:experiments:list`) on this Spark project, and it currently reports none. It has no create-experiment command, and there is no documented free create API for A/B Testing. Do not enable billing to unlock one. Create the experiment in the console:

1. Open [Firebase console](https://console.firebase.google.com/project/callguardian-hybrid) → **Engage → A/B Testing** (or **Remote Config → Experiments**).
2. **Create experiment** → **Remote Config**.
3. Target the Android apps (`io.github.repr0bated.callguardian` and `.debug`) and the web app.
4. Parameter: `triage_prompt_variant`. Control: `A`. Variant: `B`. 50% / 50% (or a smaller rollout first).
5. Goal: the Analytics event `verdict` (optional filter: `source` = `nano` or `cloud`). Do not add text or phone-number parameters.
6. Start the experiment. Clients pick up the variant on the next Remote Config fetch (debug builds fetch immediately; release uses a 1-hour interval; the web companion uses a 1-hour interval).

If the REST/CLI experiment-create call is ever available on this Spark project, use it instead of the console; do not enable billing to unlock it.

### Web companion alerts (Firestore, not FCM send)

When the phone blocks a scam and the user is signed in with the same Google account, Android writes `users/{uid}/companionAlerts/{id}` (category, source, timestamp — no message text, no numbers). The web app uses Firestore `onSnapshot` and may show a browser notification while the tab is open.

FCM *client* SDKs are free, but **sending** a push to the web companion from the server needs Cloud Functions or a similar backend, which requires the **Blaze** plan. This project stays on Spark and does not send FCM from the server.

Deploy the updated Firestore rules (from `web/`) after review:

```
npx -y firebase-tools@latest deploy --only firestore:rules --project callguardian-hybrid
```

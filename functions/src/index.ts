/**
 * jevCheck: the app's "Jev second opinion" (guardian.jev.check@v1). A callable function so the
 * TypeSafe API key stays on the server (Secret Manager), never in the APK. App Check is enforced,
 * and the app only sends text that the on-device Redactor has already masked.
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { buildRequest, QUESTIONS_VERSION, SIGNAL_IDS, type SenderKind } from "./questions";
import type { components } from "./typesafe.gen";

type SystemOneResponse = components["schemas"]["SystemOneResponse"];

const TYPESAFE_API_KEY = defineSecret("TYPESAFE_API_KEY");
const SENDERS: SenderKind[] = ["saved_contact", "unknown_number", "short_code", "hidden"];
// Defense in depth: refuse anything that still looks like an unredacted phone number or email.
const RAW_PII = /(\+?\d[\d\s().-]{8,}\d)|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})/;

export async function askJev(apiKey: string, redactedText: string, sender: SenderKind) {
  const body = buildRequest(redactedText, sender);
  const res = await fetch("https://api.typesafe.ai/v1/systemone", {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`TypeSafe HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const out = (await res.json()) as SystemOneResponse;
  const signals: Record<string, number> = {};
  for (const id of SIGNAL_IDS) {
    const a = out.answers[id];
    // A missing or mistyped answer is an error, never a silent "no".
    if (!a || a.type !== "noul") throw new Error(`TypeSafe returned no Noul answer for ${id}`);
    signals[id] = a.noul;
  }
  return { model: out.model, questionsVersion: QUESTIONS_VERSION, signals, inputTokens: out.usage.input_tokens };
}

export const jevCheck = onCall(
  { secrets: [TYPESAFE_API_KEY], enforceAppCheck: true, region: "us-central1", memory: "256MiB", maxInstances: 3, timeoutSeconds: 30 },
  async (req) => {
    const text = req.data?.redactedText;
    const sender = req.data?.sender as SenderKind;
    if (typeof text !== "string" || text.length < 1 || text.length > 2000) throw new HttpsError("invalid-argument", "redactedText must be 1-2000 characters");
    if (!SENDERS.includes(sender)) throw new HttpsError("invalid-argument", "unknown sender kind");
    if (RAW_PII.test(text)) throw new HttpsError("invalid-argument", "text does not look redacted");
    try {
      return await askJev(TYPESAFE_API_KEY.value(), text, sender);
    } catch (e) {
      throw new HttpsError("unavailable", (e as Error).message.slice(0, 200));
    }
  },
);

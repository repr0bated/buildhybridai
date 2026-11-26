/**
 * Live probe: sends ONLY the app's built-in demo samples (app/src/main/assets/lab_samples.json,
 * all invented texts) to TypeSafe Jev with the same request the Cloud Function builds, and writes
 * the per-question probabilities to fixtures/jev-samples.json. The app's JevTruthTable turns them
 * into verdicts in JevLiveFixtureTest. Reads TYPESAFE_API_KEY from the environment; never prints it.
 *
 *   set -a; . ~/keys/typesafe.env; set +a; npm run probe
 */
import { readFileSync, writeFileSync } from "node:fs";
import { askJev } from "../src/index";
import type { SenderKind } from "../src/questions";

const key = process.env.TYPESAFE_API_KEY?.trim();
if (!key) throw new Error("TYPESAFE_API_KEY not set");

// Same masking the app applies before anything leaves the phone (Redactor.kt), simplified for invented samples.
function redact(s: string): string {
  return s
    .replace(/(https?:\/\/[^\s/]+)\/\S*/g, "$1/[path]")
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[email]")
    .replace(/\+?\d[\d\s().-]{8,}\d/g, "[number]");
}
const senderKind = (s?: string): SenderKind =>
  !s ? "hidden" : /^\d{4,6}$/.test(s) ? "short_code" : "unknown_number";

const samples = JSON.parse(readFileSync("../app/src/main/assets/lab_samples.json", "utf8")).samples as { title: string; sender?: string; body: string }[];
async function main() {
const results: unknown[] = [];
let tokens = 0;
for (const s of samples) {
  const text = redact(s.body);
  const t0 = Date.now();
  const r = await askJev(key, text, senderKind(s.sender));
  tokens += r.inputTokens;
  results.push({ title: s.title, sender: senderKind(s.sender), text, latencyMs: Date.now() - t0, ...r });
  console.log(`${s.title.padEnd(34)} ${Object.entries(r.signals).map(([k, v]) => `${k}=${v.toFixed(2)}`).join(" ")}`);
}
writeFileSync("fixtures/jev-samples.json", JSON.stringify({ probedAt: new Date().toISOString(), totalInputTokens: tokens, results }, null, 2) + "\n");
console.log(`${results.length} samples, ${tokens} input tokens`);
}
main().catch((e) => { console.error(String(e?.message ?? e).replace(/Bearer\s+\S+/g, "Bearer [redacted]")); process.exit(1); });

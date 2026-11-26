import { buildRequest, QUESTIONS_VERSION, SIGNAL_IDS, type SenderKind } from './questions';
import type { components } from './typesafe.gen';

type SystemOneResponse = components['schemas']['SystemOneResponse'];
export type { SenderKind };
export const SENDERS: SenderKind[] = ['saved_contact', 'unknown_number', 'short_code', 'hidden'];
// Defense in depth: refuse anything that still looks like an unredacted phone number or email.
const RAW_PII = /(\+?\d[\d\s().-]{8,}\d)|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})/;

export interface CheckItem { redactedText: string; sender: SenderKind }
/** Same shape as functions/jevCheck `result`, plus `ok` on batch items. */
export interface CheckOk {
  ok?: true;
  model: string;
  questionsVersion: string;
  signals: Record<string, number>;
  inputTokens: number;
}

export function parseItem(raw: unknown): { ok: true; value: CheckItem } | { ok: false; error: string } {
  if (!raw || typeof raw !== 'object') return { ok: false, error: 'item must be an object' };
  const text = (raw as any).redactedText;
  const sender = (raw as any).sender as SenderKind;
  if (typeof text !== 'string' || text.length < 1 || text.length > 2000) return { ok: false, error: 'redactedText must be 1-2000 characters' };
  if (!SENDERS.includes(sender)) return { ok: false, error: 'unknown sender kind' };
  if (RAW_PII.test(text)) return { ok: false, error: 'text does not look redacted' };
  return { ok: true, value: { redactedText: text, sender } };
}

export async function askJev(apiKey: string, redactedText: string, sender: SenderKind, fetchImpl: typeof fetch = fetch): Promise<CheckOk> {
  const body = buildRequest(redactedText, sender);
  const res = await fetchImpl('https://api.typesafe.ai/v1/systemone', {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`TypeSafe HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const out = (await res.json()) as SystemOneResponse;
  const signals: Record<string, number> = {};
  for (const id of SIGNAL_IDS) {
    const a = (out.answers as any)?.[id];
    if (!a || a.type !== 'noul' || typeof a.noul !== 'number') throw new Error(`TypeSafe returned no Noul answer for ${id}`);
    signals[id] = a.noul;
  }
  return { model: out.model, questionsVersion: QUESTIONS_VERSION, signals, inputTokens: out.usage?.input_tokens ?? 0 };
}
